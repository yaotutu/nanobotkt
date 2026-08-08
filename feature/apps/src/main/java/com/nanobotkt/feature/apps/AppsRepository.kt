package com.nanobotkt.feature.apps

import com.nanobotkt.core.model.CliAppsPayload
import com.nanobotkt.core.model.McpPresetsPayload
import com.nanobotkt.core.model.SlashCommandsPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

interface AppsRepository {
    val state: StateFlow<AppsUiState>
    /** 清理当前登录会话，并使所有在途请求的结果失效。 */
    fun reset()
    suspend fun refresh()
    suspend fun cliAction(action: String, name: String)
    suspend fun mcpAction(action: String, name: String, values: Map<String, String> = emptyMap())
    suspend fun saveCustom(values: Map<String, String>)
    suspend fun importConfig(config: String)
    suspend fun importCursorConfig(config: String)
    suspend fun updateTools(name: String, tools: List<String>)
}

data class AppsUiState(
    val cli: CliAppsPayload? = null,
    val mcp: McpPresetsPayload? = null,
    val commands: SlashCommandsPayload? = null,
    val loading: Boolean = false,
    val pending: Set<String> = emptySet(),
    val error: String? = null,
)

@Singleton
class DefaultAppsRepository @Inject constructor(
    private val api: GatewayApiClient,
    private val json: Json,
) : AppsRepository {
    private val mutable = MutableStateFlow(AppsUiState())
    override val state = mutable.asStateFlow()
    private val mutex = Mutex()

    /**
     * refresh 可能由首次加载、手动刷新和 action 完成后的自动刷新并发触发。
     * 每次刷新都拿到一个递增代际，只有仍然属于最新代际的请求才能写入状态，
     * 从而避免较慢的旧响应把较新的列表覆盖掉。
     */
    private val refreshGuard = Any()
    private var latestRefreshGeneration = 0L
    private val sessionGeneration = AtomicLong(0L)

    override fun reset() {
        // 先使会话代次和刷新代次同时失效，再清空状态；旧请求返回时会被
        // updateIfCurrent 拦截，不能把旧账号的数据、错误或 pending 写回来。
        synchronized(refreshGuard) {
            sessionGeneration.incrementAndGet()
            latestRefreshGeneration += 1
            mutable.value = AppsUiState()
        }
    }

    override suspend fun refresh() {
        refreshForSession(sessionGeneration.get())
    }

    private suspend fun refreshForSession(expectedSession: Long) {
        if (!sessionGeneration.compareAndSet(expectedSession, expectedSession)) return
        val generation = synchronized(refreshGuard) {
            if (sessionGeneration.get() != expectedSession) return@synchronized null
            latestRefreshGeneration += 1
            latestRefreshGeneration
        } ?: return
        val old = mutable.value
        updateIfCurrent(expectedSession, generation) { it.copy(loading = true, error = null) }
        try {
            val result = coroutineScope {
                val cli = async { api.get<CliAppsPayload>("/api/settings/cli-apps") }
                val mcp = async { api.get<McpPresetsPayload>("/api/settings/mcp-presets") }
                val commands = async { api.get<SlashCommandsPayload>("/api/commands") }
                Triple(cli.await(), mcp.await(), commands.await())
            }
            updateIfCurrent(expectedSession, generation) { current ->
                AppsUiState(
                    cli = result.first,
                    mcp = result.second,
                    commands = result.third,
                    pending = current.pending,
                )
            }
        } catch (e: CancellationException) {
            // 只有最新刷新被取消时才恢复它开始前的状态；旧刷新不能影响新刷新。
            updateIfCurrent(expectedSession, generation) { old }
            throw e
        } catch (e: Exception) {
            updateIfCurrent(expectedSession, generation) {
                it.copy(loading = false, error = e.message ?: "apps_refresh_failed")
            }
        }
    }

    override suspend fun cliAction(action: String, name: String) =
        mutate("cli:$name") {
            val payload = api.get<CliAppsPayload>(
                "/api/settings/cli-apps/$action",
                mapOf("name" to name),
            )
            mutable.value.copy(cli = payload)
        }

    override suspend fun mcpAction(action: String, name: String, values: Map<String, String>) =
        mutate("mcp:$name") {
            val payload = api.request(
                "/api/settings/mcp-presets/$action",
                McpPresetsPayload.serializer(),
                query = mapOf("name" to name),
                headers = mcpHeader(values),
            )
            mutable.value.copy(mcp = payload)
        }

    override suspend fun saveCustom(values: Map<String, String>) =
        mutate("mcp:custom") {
            val payload = api.request(
                "/api/settings/mcp-presets/custom",
                McpPresetsPayload.serializer(),
                headers = mcpHeader(values),
            )
            mutable.value.copy(mcp = payload)
        }

    override suspend fun importConfig(config: String) =
        mutate("mcp:import") {
            val payload = api.request(
                "/api/settings/mcp-presets/import",
                McpPresetsPayload.serializer(),
                headers = mcpHeader(mapOf("config" to config)),
            )
            mutable.value.copy(mcp = payload)
        }

    override suspend fun importCursorConfig(config: String) =
        mutate("mcp:import-cursor") {
            // Cursor 配置与普通 MCP JSON 导入虽然共享 JSON 结构，但服务端提供了
            // 独立契约；保留独立路径可以让服务端按 Cursor 语义校验并便于审计。
            val payload = api.request(
                "/api/settings/mcp-presets/import-cursor",
                McpPresetsPayload.serializer(),
                headers = mcpHeader(mapOf("config" to config)),
            )
            mutable.value.copy(mcp = payload)
        }

    override suspend fun updateTools(name: String, tools: List<String>) =
        mutate("mcp:$name") {
            val body = buildJsonObject {
                put("name", name)
                putJsonArray("enabled_tools") {
                    tools.forEach { add(JsonPrimitive(it)) }
                }
            }
            val payload = api.request(
                "/api/settings/mcp-presets/tools",
                McpPresetsPayload.serializer(),
                headers = mapOf("X-Nanobot-MCP-Values" to json.encodeToString(body)),
            )
            mutable.value.copy(mcp = payload)
        }

    private fun mcpHeader(values: Map<String, String>): Map<String, String> =
        values
            .mapValues { (_, value) -> value.trim() }
            .filterValues { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { mapOf("X-Nanobot-MCP-Values" to json.encodeToString(it)) }
            .orEmpty()

    /**
     * 检查代际和写入状态必须在同一个锁内完成，否则新 refresh 可能恰好在检查后
     * 启动，旧 refresh 仍会把结果写进去，代际保护就失去意义。
     */
    private fun updateIfCurrent(
        expectedSession: Long,
        generation: Long,
        transform: (AppsUiState) -> AppsUiState,
    ) {
        synchronized(refreshGuard) {
            if (sessionGeneration.get() == expectedSession && generation == latestRefreshGeneration) {
                mutable.value = transform(mutable.value)
            }
        }
    }

    private fun updateIfSession(
        expectedSession: Long,
        transform: (AppsUiState) -> AppsUiState,
    ) {
        synchronized(refreshGuard) {
            if (sessionGeneration.get() == expectedSession) {
                mutable.value = transform(mutable.value)
            }
        }
    }

    private suspend fun mutate(
        key: String,
        block: suspend () -> AppsUiState,
    ) = mutex.withLock {
        val expectedSession = sessionGeneration.get()
        if (sessionGeneration.get() != expectedSession) return@withLock
        updateIfSession(expectedSession) { it.copy(pending = it.pending + key, error = null) }
        try {
            val next = block()
            if (sessionGeneration.get() != expectedSession) return@withLock
            updateIfSession(expectedSession) { current ->
                next.copy(pending = current.pending, loading = current.loading, error = null)
            }
            refreshForSession(expectedSession)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateIfSession(expectedSession) {
                it.copy(error = e.message ?: "apps_action_failed")
            }
        } finally {
            updateIfSession(expectedSession) { it.copy(pending = it.pending - key) }
        }
    }
}
