package com.nanobotkt.core.persistence

import com.nanobotkt.core.model.ChatSummary
import com.nanobotkt.core.model.SidebarStatePayload
import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.model.WebUiThreadPayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupCacheStoreTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    @Test
    fun profileDataIsIsolatedAndDeleteProfileOnlyRemovesItsOwnRows() = runTest {
        val dao = FakeStartupCacheDao()
        val store = RoomStartupCacheStore(dao, json)
        val sidebarA = sidebarSnapshot("a")
        val sidebarB = sidebarSnapshot("b")

        store.saveSidebar("profile-a", sidebarA, "hash-a")
        store.saveSidebar("profile-b", sidebarB, "hash-b")
        store.saveSelection("profile-a", "webui:a")
        store.saveSelection("profile-b", "webui:b")
        store.saveChatThread("profile-a", "webui:a", "chat-a", thread("webui:a", "a"), "thread-a")
        store.saveChatThread("profile-b", "webui:b", "chat-b", thread("webui:b", "b"), "thread-b")

        store.deleteProfile("profile-a")

        assertNull(store.loadSidebar("profile-a"))
        assertNull(store.loadSelection("profile-a"))
        assertNull(store.loadChatThread("profile-a", "webui:a"))
        assertEquals(sidebarB, store.loadSidebar("profile-b")?.snapshot)
        assertEquals("webui:b", store.loadSelection("profile-b"))
        assertEquals("b", store.loadChatThread("profile-b", "webui:b")?.payload?.messages?.single()?.id)
    }

    @Test
    fun corruptedRowsAreConditionallyRemovedWithoutBlockingStartup() = runTest {
        val dao = FakeStartupCacheDao().apply {
            sidebarRows["profile"] = SidebarSnapshotEntity("profile", "{broken", "sidebar", 10L)
            chatRows["profile" to "webui:broken"] = ChatThreadSnapshotEntity(
                profileId = "profile",
                sessionKey = "webui:broken",
                chatId = "chat-broken",
                payloadJson = "{broken",
                contentHash = "thread",
                lastAccessedAtEpochMillis = 10L,
                savedAtEpochMillis = 10L,
            )
        }
        val store = RoomStartupCacheStore(dao, json)

        assertNull(store.loadSidebar("profile"))
        assertNull(store.loadChatThread("profile", "webui:broken"))
        assertTrue(dao.sidebarRows.isEmpty())
        assertTrue(dao.chatRows.isEmpty())
    }

    @Test
    fun chatSnapshotsUseDeterministicLruAndSupportSingleThreadDeletion() = runTest {
        val dao = FakeStartupCacheDao()
        val store = RoomStartupCacheStore(dao, json)

        repeat(11) { index ->
            store.saveChatThread(
                profileId = "profile",
                sessionKey = "webui:$index",
                chatId = "chat-$index",
                payload = thread("webui:$index", "message-$index"),
                contentHash = "hash-$index",
            )
        }

        // 单进程快速写入也必须稳定保留最后十条，不能因毫秒时间戳相同随机淘汰。
        assertNull(store.loadChatThread("profile", "webui:0"))
        assertEquals(10, dao.chatRows.count { it.key.first == "profile" })
        assertEquals("message-10", store.loadChatThread("profile", "webui:10")?.payload?.messages?.single()?.id)

        store.deleteChatThread("profile", "webui:10")
        assertNull(store.loadChatThread("profile", "webui:10"))
    }

    @Test
    fun oversizedChatSnapshotIsSkippedWithoutReplacingExistingValue() = runTest {
        val dao = FakeStartupCacheDao()
        val store = RoomStartupCacheStore(dao, json)
        store.saveChatThread("profile", "webui:key", "chat", thread("webui:key", "small"), "small-hash")

        val oversized = thread("webui:key", "large", content = "x".repeat(4 * 1024 * 1024 + 1))
        store.saveChatThread("profile", "webui:key", "chat", oversized, "large-hash")

        val restored = store.loadChatThread("profile", "webui:key")
        assertEquals("small", restored?.payload?.messages?.single()?.id)
        assertEquals("small-hash", restored?.contentHash)
    }

    private fun sidebarSnapshot(suffix: String) = SidebarStartupSnapshot(
        sessions = listOf(ChatSummary("webui:$suffix", "webui", "chat-$suffix", title = suffix)),
        sidebar = SidebarStatePayload(pinnedKeys = listOf("webui:$suffix")),
    )

    private fun thread(sessionKey: String, messageId: String, content: String = messageId) = WebUiThreadPayload(
        schemaVersion = 1,
        sessionKey = sessionKey,
        messages = listOf(UiMessage(messageId, "user", content, createdAt = 1L)),
    )

    /**
     * Store 契约测试使用内存 DAO 精确观察条件删除和 LRU；Room SQL 的语法/映射继续由 Room 编译器验证。
     * 所有 key 都包含 profileId，便于测试直接断言跨账号清理边界。
     */
    private class FakeStartupCacheDao : StartupCacheDao {
        val sidebarRows = linkedMapOf<String, SidebarSnapshotEntity>()
        val selectionRows = linkedMapOf<String, SessionSelectionEntity>()
        val chatRows = linkedMapOf<Pair<String, String>, ChatThreadSnapshotEntity>()

        override suspend fun loadSidebar(profileId: String) = sidebarRows[profileId]
        override suspend fun saveSidebar(entity: SidebarSnapshotEntity) { sidebarRows[entity.profileId] = entity }
        override suspend fun deleteSidebarIfUnchanged(profileId: String, savedAt: Long) {
            if (sidebarRows[profileId]?.savedAtEpochMillis == savedAt) sidebarRows.remove(profileId)
        }
        override suspend fun loadSelection(profileId: String) = selectionRows[profileId]
        override suspend fun saveSelection(entity: SessionSelectionEntity) { selectionRows[entity.profileId] = entity }
        override suspend fun loadChatThread(profileId: String, sessionKey: String) = chatRows[profileId to sessionKey]
        override suspend fun saveChatThread(entity: ChatThreadSnapshotEntity) {
            chatRows[entity.profileId to entity.sessionKey] = entity
        }
        override suspend fun touchChatThread(profileId: String, sessionKey: String, accessedAt: Long) {
            val key = profileId to sessionKey
            chatRows[key]?.let { chatRows[key] = it.copy(lastAccessedAtEpochMillis = accessedAt) }
        }
        override suspend fun deleteChatThreadIfUnchanged(profileId: String, sessionKey: String, savedAt: Long) {
            val key = profileId to sessionKey
            if (chatRows[key]?.savedAtEpochMillis == savedAt) chatRows.remove(key)
        }
        override suspend fun deleteChatThread(profileId: String, sessionKey: String) {
            chatRows.remove(profileId to sessionKey)
        }
        override suspend fun trimChatThreads(profileId: String, keepCount: Int) {
            val keep = chatRows.values
                .filter { it.profileId == profileId }
                .sortedByDescending(ChatThreadSnapshotEntity::lastAccessedAtEpochMillis)
                .take(keepCount)
                .mapTo(mutableSetOf()) { it.profileId to it.sessionKey }
            chatRows.keys.removeAll { it.first == profileId && it !in keep }
        }
        override suspend fun deleteSidebar(profileId: String) { sidebarRows.remove(profileId) }
        override suspend fun deleteSelection(profileId: String) { selectionRows.remove(profileId) }
        override suspend fun deleteChatThreads(profileId: String) { chatRows.keys.removeAll { it.first == profileId } }
    }
}
