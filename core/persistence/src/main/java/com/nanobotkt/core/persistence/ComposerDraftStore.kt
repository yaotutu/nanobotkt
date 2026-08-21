package com.nanobotkt.core.persistence

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.nanobotkt.core.model.OutboundMedia
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 附件恢复所需的显示信息与完整发送载荷；恢复发送不再依赖可能已经失效的外部 Uri 权限。 */
@Serializable
data class ComposerDraftAttachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val bytes: Long,
    val outbound: OutboundMedia,
)

/**
 * 单个 Composer 草稿的完整可编辑内容。
 *
 * 持久化层只保存“用户正在编辑什么”，不保存待发送队列、自动重发记录或投递状态。服务端是否收到消息
 * 无法由客户端可靠推断，因此进程恢复后只恢复草稿、绝不自动发送，避免为了不可实现的 exactly-once
 * 语义引入复杂状态机。
 */
@Serializable
data class ComposerDraftPayload(
    val text: String,
    val cursorPosition: Int,
    val quotedContext: String? = null,
    val attachments: List<ComposerDraftAttachment> = emptyList(),
    val sessionKey: String? = null,
    val chatId: String? = null,
)

data class ComposerDraftRecord(
    val scopeKey: String,
    val revision: Long,
    val payload: ComposerDraftPayload,
)

/** 每个会话作用域最多一条草稿；revision 用来阻止较慢的旧保存覆盖较新的输入。 */
interface ComposerDraftStore {
    suspend fun load(scopeKey: String): ComposerDraftRecord?
    suspend fun save(scopeKey: String, revision: Long, payload: ComposerDraftPayload)
    suspend fun delete(scopeKey: String, expectedRevision: Long? = null): Boolean
    /** 注销时删除旧认证主体的正文、引用与附件 data URL，避免跨账号泄漏。 */
    suspend fun deleteAll()
}

@Entity(tableName = "composer_drafts")
data class ComposerDraftEntity(
    @PrimaryKey val scopeKey: String,
    val revision: Long,
    val payloadJson: String,
)

@Dao
abstract class ComposerDraftDao {
    @Query("SELECT * FROM composer_drafts WHERE scopeKey = :scopeKey LIMIT 1")
    abstract suspend fun load(scopeKey: String): ComposerDraftEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoringConflict(entity: ComposerDraftEntity): Long

    @Query(
        """
        UPDATE composer_drafts
        SET revision = :revision, payloadJson = :payloadJson
        WHERE scopeKey = :scopeKey AND revision <= :revision
        """
    )
    protected abstract suspend fun updateIfNotNewer(
        scopeKey: String,
        revision: Long,
        payloadJson: String,
    ): Int

    /** 新旧保存可能因 debounce 交错；插入后再按 revision 条件更新，旧协程永远不能回滚新草稿。 */
    @Transaction
    open suspend fun save(entity: ComposerDraftEntity) {
        insertIgnoringConflict(entity)
        updateIfNotNewer(entity.scopeKey, entity.revision, entity.payloadJson)
    }

    @Query("DELETE FROM composer_drafts WHERE scopeKey = :scopeKey")
    abstract suspend fun delete(scopeKey: String): Int

    @Query("DELETE FROM composer_drafts WHERE scopeKey = :scopeKey AND revision = :expectedRevision")
    abstract suspend fun delete(scopeKey: String, expectedRevision: Long): Int

    @Query("DELETE FROM composer_drafts")
    abstract suspend fun deleteAll()
}

@Database(entities = [ComposerDraftEntity::class], version = 1, exportSchema = false)
abstract class NanobotDatabase : RoomDatabase() {
    abstract fun composerDraftDao(): ComposerDraftDao
}

@Singleton
internal class RoomComposerDraftStore(
    private val dao: ComposerDraftDao,
    private val json: Json,
) : ComposerDraftStore {
    override suspend fun load(scopeKey: String): ComposerDraftRecord? {
        val entity = dao.load(scopeKey) ?: return null
        return runCatching {
                ComposerDraftRecord(
                    scopeKey = entity.scopeKey,
                    revision = entity.revision,
                    payload =
                        json.decodeFromString(
                            ComposerDraftPayload.serializer(),
                            entity.payloadJson,
                        ),
                )
            }
            .getOrElse {
                // 损坏记录必须按读取到的 revision 条件删除，而不能只返回 null。否则新 ViewModel
                // 会从较小 revision 重新计数，后续保存会持续被这条无法解码的高 revision 记录拒绝，
                // 用户看似可以输入，但进程重启后永远无法恢复新草稿。条件删除还能避免误删并发写入的新值。
                dao.delete(scopeKey, entity.revision)
                null
            }
    }

    override suspend fun save(
        scopeKey: String,
        revision: Long,
        payload: ComposerDraftPayload,
    ) {
        dao.save(
            ComposerDraftEntity(
                scopeKey = scopeKey,
                revision = revision,
                payloadJson = json.encodeToString(ComposerDraftPayload.serializer(), payload),
            )
        )
    }

    override suspend fun delete(scopeKey: String, expectedRevision: Long?): Boolean =
        if (expectedRevision == null) {
            dao.delete(scopeKey) > 0
        } else {
            dao.delete(scopeKey, expectedRevision) > 0
        }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ComposerDraftPersistenceModule {
    @Provides
    @Singleton
    internal fun provideNanobotDatabase(
        @ApplicationContext context: Context,
    ): NanobotDatabase =
        Room.databaseBuilder(
                context,
                NanobotDatabase::class.java,
                "nanobot-composer-drafts.db",
            )
            .build()

    @Provides
    fun provideComposerDraftDao(database: NanobotDatabase): ComposerDraftDao =
        database.composerDraftDao()

    @Provides
    @Singleton
    fun provideComposerDraftStore(
        dao: ComposerDraftDao,
        json: Json,
    ): ComposerDraftStore = RoomComposerDraftStore(dao, json)
}
