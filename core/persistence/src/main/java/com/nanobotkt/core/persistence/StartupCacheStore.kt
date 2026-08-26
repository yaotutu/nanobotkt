package com.nanobotkt.core.persistence

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nanobotkt.core.model.ChatSummary
import com.nanobotkt.core.model.SidebarStatePayload
import com.nanobotkt.core.model.WebUiThreadPayload
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Sidebar 的本地启动快照；会话列表与 Sidebar 设置必须作为同一个逻辑版本读取和替换。 */
@Serializable
data class SidebarStartupSnapshot(
    val sessions: List<ChatSummary>,
    val sidebar: SidebarStatePayload,
)

data class StoredSidebarStartupSnapshot(
    val snapshot: SidebarStartupSnapshot,
    val contentHash: String,
    val savedAtEpochMillis: Long,
)

data class StoredChatThreadSnapshot(
    val sessionKey: String,
    val chatId: String,
    val payload: WebUiThreadPayload,
    val contentHash: String,
    val savedAtEpochMillis: Long,
)

/**
 * 启动缓存的最小持久化边界。
 *
 * 所有读写都强制携带 profileId，防止同一服务器上的不同 Secret/账号共享 Sidebar、选择或消息正文。
 * 缓存是可丢弃的加速数据，解码损坏时实现会删除单条记录并返回 null，而不是阻断应用启动。
 */
interface StartupCacheStore {
    suspend fun loadSidebar(profileId: String): StoredSidebarStartupSnapshot?
    suspend fun saveSidebar(profileId: String, snapshot: SidebarStartupSnapshot, contentHash: String)
    suspend fun loadSelection(profileId: String): String?
    suspend fun saveSelection(profileId: String, selectedSessionKey: String?)
    suspend fun loadChatThread(profileId: String, sessionKey: String): StoredChatThreadSnapshot?
    suspend fun saveChatThread(
        profileId: String,
        sessionKey: String,
        chatId: String,
        payload: WebUiThreadPayload,
        contentHash: String,
    )
    /** 远端明确确认会话不存在时删除单条快照，避免下次冷启动复活已经失效的历史。 */
    suspend fun deleteChatThread(profileId: String, sessionKey: String)
    suspend fun deleteProfile(profileId: String)
}

@Entity(tableName = "sidebar_snapshots")
internal data class SidebarSnapshotEntity(
    @androidx.room.PrimaryKey val profileId: String,
    val payloadJson: String,
    val contentHash: String,
    val savedAtEpochMillis: Long,
)

@Entity(tableName = "chat_thread_snapshots", primaryKeys = ["profileId", "sessionKey"])
internal data class ChatThreadSnapshotEntity(
    val profileId: String,
    val sessionKey: String,
    val chatId: String,
    val payloadJson: String,
    val contentHash: String,
    val lastAccessedAtEpochMillis: Long,
    val savedAtEpochMillis: Long,
)

@Entity(tableName = "session_selections")
internal data class SessionSelectionEntity(
    @androidx.room.PrimaryKey val profileId: String,
    val selectedSessionKey: String?,
    val updatedAtEpochMillis: Long,
)

@Dao
internal interface StartupCacheDao {
    @Query("SELECT * FROM sidebar_snapshots WHERE profileId = :profileId LIMIT 1")
    suspend fun loadSidebar(profileId: String): SidebarSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSidebar(entity: SidebarSnapshotEntity)

    @Query("DELETE FROM sidebar_snapshots WHERE profileId = :profileId AND savedAtEpochMillis = :savedAt")
    suspend fun deleteSidebarIfUnchanged(profileId: String, savedAt: Long)

    @Query("SELECT * FROM session_selections WHERE profileId = :profileId LIMIT 1")
    suspend fun loadSelection(profileId: String): SessionSelectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSelection(entity: SessionSelectionEntity)

    @Query("SELECT * FROM chat_thread_snapshots WHERE profileId = :profileId AND sessionKey = :sessionKey LIMIT 1")
    suspend fun loadChatThread(profileId: String, sessionKey: String): ChatThreadSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChatThread(entity: ChatThreadSnapshotEntity)

    @Query(
        "UPDATE chat_thread_snapshots SET lastAccessedAtEpochMillis = :accessedAt " +
            "WHERE profileId = :profileId AND sessionKey = :sessionKey"
    )
    suspend fun touchChatThread(profileId: String, sessionKey: String, accessedAt: Long)

    @Query(
        "DELETE FROM chat_thread_snapshots WHERE profileId = :profileId AND sessionKey = :sessionKey " +
            "AND savedAtEpochMillis = :savedAt"
    )
    suspend fun deleteChatThreadIfUnchanged(profileId: String, sessionKey: String, savedAt: Long)

    @Query("DELETE FROM chat_thread_snapshots WHERE profileId = :profileId AND sessionKey = :sessionKey")
    suspend fun deleteChatThread(profileId: String, sessionKey: String)

    @Query(
        "DELETE FROM chat_thread_snapshots WHERE profileId = :profileId AND sessionKey NOT IN " +
            "(SELECT sessionKey FROM chat_thread_snapshots WHERE profileId = :profileId " +
            "ORDER BY lastAccessedAtEpochMillis DESC LIMIT :keepCount)"
    )
    suspend fun trimChatThreads(profileId: String, keepCount: Int)

    @Query("DELETE FROM sidebar_snapshots WHERE profileId = :profileId")
    suspend fun deleteSidebar(profileId: String)

    @Query("DELETE FROM session_selections WHERE profileId = :profileId")
    suspend fun deleteSelection(profileId: String)

    @Query("DELETE FROM chat_thread_snapshots WHERE profileId = :profileId")
    suspend fun deleteChatThreads(profileId: String)
}

@Database(
    entities = [SidebarSnapshotEntity::class, ChatThreadSnapshotEntity::class, SessionSelectionEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class StartupCacheDatabase : RoomDatabase() {
    abstract fun startupCacheDao(): StartupCacheDao
}

@Singleton
internal class RoomStartupCacheStore(
    private val dao: StartupCacheDao,
    private val json: Json,
) : StartupCacheStore {
    /**
     * 同一毫秒内连续保存多个线程时，单纯使用 wall clock 会产生相同 LRU 值，Room 的排序结果不稳定。
     * 这里生成进程内严格递增时间戳；它仍保留真实时间语义，又能保证批量恢复/保存时淘汰顺序确定。
     */
    private val lastCacheTimestamp = AtomicLong(0L)

    private fun nextCacheTimestamp(): Long = lastCacheTimestamp.updateAndGet { previous ->
        maxOf(System.currentTimeMillis(), previous + 1L)
    }
    override suspend fun loadSidebar(profileId: String): StoredSidebarStartupSnapshot? {
        val entity = dao.loadSidebar(profileId) ?: return null
        return runCatching {
            StoredSidebarStartupSnapshot(
                snapshot = json.decodeFromString(SidebarStartupSnapshot.serializer(), entity.payloadJson),
                contentHash = entity.contentHash,
                savedAtEpochMillis = entity.savedAtEpochMillis,
            )
        }.getOrElse {
            // 条件删除避免解码失败后误删恰好由并发刷新写入的新版本。
            dao.deleteSidebarIfUnchanged(profileId, entity.savedAtEpochMillis)
            null
        }
    }

    override suspend fun saveSidebar(profileId: String, snapshot: SidebarStartupSnapshot, contentHash: String) {
        dao.saveSidebar(
            SidebarSnapshotEntity(
                profileId = profileId,
                payloadJson = json.encodeToString(SidebarStartupSnapshot.serializer(), snapshot),
                contentHash = contentHash,
                savedAtEpochMillis = nextCacheTimestamp(),
            )
        )
    }

    override suspend fun loadSelection(profileId: String): String? =
        dao.loadSelection(profileId)?.selectedSessionKey

    override suspend fun saveSelection(profileId: String, selectedSessionKey: String?) {
        dao.saveSelection(SessionSelectionEntity(profileId, selectedSessionKey, nextCacheTimestamp()))
    }

    override suspend fun loadChatThread(profileId: String, sessionKey: String): StoredChatThreadSnapshot? {
        val entity = dao.loadChatThread(profileId, sessionKey) ?: return null
        val decoded = runCatching {
            StoredChatThreadSnapshot(
                sessionKey = entity.sessionKey,
                chatId = entity.chatId,
                payload = json.decodeFromString(WebUiThreadPayload.serializer(), entity.payloadJson),
                contentHash = entity.contentHash,
                savedAtEpochMillis = entity.savedAtEpochMillis,
            )
        }.getOrElse {
            dao.deleteChatThreadIfUnchanged(profileId, sessionKey, entity.savedAtEpochMillis)
            return null
        }
        dao.touchChatThread(profileId, sessionKey, nextCacheTimestamp())
        return decoded
    }

    override suspend fun saveChatThread(
        profileId: String,
        sessionKey: String,
        chatId: String,
        payload: WebUiThreadPayload,
        contentHash: String,
    ) {
        val payloadJson = json.encodeToString(WebUiThreadPayload.serializer(), payload)
        // Room 中的大字符串最终仍会占用内存；超过上限只放弃缓存，不把远端成功降级成聊天错误。
        if (payloadJson.toByteArray(Charsets.UTF_8).size > MAX_CHAT_SNAPSHOT_BYTES) return
        val now = nextCacheTimestamp()
        dao.saveChatThread(
            ChatThreadSnapshotEntity(profileId, sessionKey, chatId, payloadJson, contentHash, now, now)
        )
        dao.trimChatThreads(profileId, MAX_CHAT_SNAPSHOT_COUNT)
    }

    override suspend fun deleteChatThread(profileId: String, sessionKey: String) {
        dao.deleteChatThread(profileId, sessionKey)
    }

    override suspend fun deleteProfile(profileId: String) {
        // 每条 SQL 都带 profileId；即使清理与新登录交错，也不会误删新 profile 的缓存。
        dao.deleteSidebar(profileId)
        dao.deleteSelection(profileId)
        dao.deleteChatThreads(profileId)
    }

    private companion object {
        const val MAX_CHAT_SNAPSHOT_BYTES = 4 * 1024 * 1024
        const val MAX_CHAT_SNAPSHOT_COUNT = 10
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object StartupCachePersistenceModule {
    @Provides
    @Singleton
    fun provideStartupCacheDatabase(@ApplicationContext context: Context): StartupCacheDatabase =
        Room.databaseBuilder(context, StartupCacheDatabase::class.java, "nanobot_startup_cache.db")
            // 启动缓存没有兼容承诺；未来 schema 不兼容时可以安全重建，不能影响 Composer Draft 数据库。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideStartupCacheDao(database: StartupCacheDatabase): StartupCacheDao = database.startupCacheDao()

    @Provides
    @Singleton
    fun provideStartupCacheStore(dao: StartupCacheDao, json: Json): StartupCacheStore =
        RoomStartupCacheStore(dao, json)
}
