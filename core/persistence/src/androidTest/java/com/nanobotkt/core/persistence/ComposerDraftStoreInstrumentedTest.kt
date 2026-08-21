package com.nanobotkt.core.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 Room 内存数据库验证单草稿的 revision 保护与条件删除。 */
@RunWith(AndroidJUnit4::class)
class ComposerDraftStoreInstrumentedTest {
    private lateinit var database: NanobotDatabase
    private lateinit var dao: ComposerDraftDao
    private lateinit var store: ComposerDraftStore

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    NanobotDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        dao = database.composerDraftDao()
        store = RoomComposerDraftStore(dao, Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun olderRevisionCannotOverwriteNewerDraft() = runBlocking {
        store.save(SCOPE, revision = 8L, payload = payload("newer text"))
        store.save(SCOPE, revision = 7L, payload = payload("stale text"))

        val draft = requireNotNull(store.load(SCOPE))
        assertEquals(8L, draft.revision)
        assertEquals("newer text", draft.payload.text)
    }

    @Test
    fun acceptedSendDeletesOnlyMatchingRevision() = runBlocking {
        store.save(SCOPE, revision = 4L, payload = payload("sent text"))
        assertTrue(store.delete(SCOPE, expectedRevision = 4L))
        assertNull(store.load(SCOPE))

        store.save(SCOPE, revision = 5L, payload = payload("new input"))
        assertFalse(store.delete(SCOPE, expectedRevision = 4L))
        assertEquals("new input", store.load(SCOPE)?.payload?.text)
    }

    @Test
    fun corruptedPayloadIsRemovedSoNewDraftCanBePersisted() = runBlocking {
        // 模拟磁盘损坏且 revision 较高的记录。load 必须清除它，否则新 ViewModel 从较小 revision
        // 开始保存时会一直被 DAO 的防旧写保护拒绝，用户新输入会在下一次进程重建后再次丢失。
        dao.save(
            ComposerDraftEntity(
                scopeKey = SCOPE,
                revision = 99L,
                payloadJson = "not-valid-json",
            )
        )

        assertNull(store.load(SCOPE))
        store.save(SCOPE, revision = 1L, payload = payload("recoverable text"))

        assertEquals("recoverable text", store.load(SCOPE)?.payload?.text)
    }

    @Test
    fun deleteAllRemovesPrivateDrafts() = runBlocking {
        store.save(SCOPE, revision = 1L, payload = payload("private draft"))
        store.save("new-topic", revision = 2L, payload = payload("another draft"))

        store.deleteAll()

        assertNull(store.load(SCOPE))
        assertNull(store.load("new-topic"))
    }

    private fun payload(text: String) =
        ComposerDraftPayload(
            text = text,
            cursorPosition = text.length,
            quotedContext = "quoted context",
        )

    private companion object {
        const val SCOPE = "session:test:chat"
    }
}
