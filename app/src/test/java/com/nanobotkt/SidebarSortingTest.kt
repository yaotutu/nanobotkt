package com.nanobotkt

import com.nanobotkt.core.model.ChatSummary
import com.nanobotkt.core.model.SidebarSortMode
import com.nanobotkt.core.model.SidebarStatePayload
import com.nanobotkt.core.model.SidebarView
import com.nanobotkt.feature.sidebar.SidebarUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class SidebarSortingTest {
    @Test
    fun updatedDescendingUsesCreatedAtFallbackAndKeepsMissingTimesLast() {
        val sessions = listOf(
            chat("missing"),
            chat("created", createdAt = "2026-08-18T09:00:00Z"),
            chat("updated", createdAt = "2026-08-17T09:00:00Z", updatedAt = "2026-08-18T10:00:00Z"),
        )

        val result = sortSidebarSessions(sessions, state(SidebarSortMode.UPDATED_DESC))

        // Gateway 时间均为可字典比较的 ISO-8601；updated 缺失时回退 created，全部缺失则稳定排末尾。
        assertEquals(listOf("updated", "created", "missing"), result.map(ChatSummary::key))
    }

    @Test
    fun createdDescendingDoesNotLetUpdatedAtChangeCreationOrder() {
        val sessions = listOf(
            chat("older", createdAt = "2026-08-17T09:00:00Z", updatedAt = "2026-08-19T09:00:00Z"),
            chat("newer", createdAt = "2026-08-18T09:00:00Z"),
        )

        val result = sortSidebarSessions(sessions, state(SidebarSortMode.CREATED_DESC))

        assertEquals(listOf("newer", "older"), result.map(ChatSummary::key))
    }

    @Test
    fun titleAscendingUsesOverrideCaseInsensitivelyAndKeyAsStableTieBreaker() {
        val sessions = listOf(
            chat("key-b", title = "same"),
            chat("key-c", title = "Zulu"),
            chat("key-a", title = "SAME"),
        )
        val state = state(
            mode = SidebarSortMode.TITLE_ASC,
            titleOverrides = mapOf("key-c" to "alpha"),
        )

        val result = sortSidebarSessions(sessions, state)

        // 用户标题覆盖优先于服务端标题；大小写相同的标题再按 key 排序，刷新不会随机抖动。
        assertEquals(listOf("key-c", "key-a", "key-b"), result.map(ChatSummary::key))
    }

    private fun state(
        mode: SidebarSortMode,
        titleOverrides: Map<String, String> = emptyMap(),
    ): SidebarUiState = SidebarUiState(
        sidebar = SidebarStatePayload(
            titleOverrides = titleOverrides,
            view = SidebarView(sort = mode),
        ),
    )

    private fun chat(
        key: String,
        createdAt: String? = null,
        updatedAt: String? = null,
        title: String? = key,
    ): ChatSummary = ChatSummary(
        key = key,
        channel = "webui",
        chatId = key,
        createdAt = createdAt,
        updatedAt = updatedAt,
        title = title,
    )
}
