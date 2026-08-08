package com.nanobotkt.feature.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.McpPresetInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val repository: AppsRepository,
) : ViewModel() {
    val state = repository.state

    /** MCP preset 的字段输入只属于当前 ViewModel，不写回服务端列表状态。 */
    private val mutableMcpFieldValues = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val mcpFieldValues: StateFlow<Map<String, Map<String, String>>> = mutableMcpFieldValues.asStateFlow()

    /**
     * tools 选择也只保存当前页面的草稿，点击 Save 后才通过 repository 写入配置。
     * 使用不可变 Set 替换旧值，保证 Compose 能够观察到每次勾选变化。
     */
    private val mutableMcpToolSelections = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val mcpToolSelections: StateFlow<Map<String, Set<String>>> = mutableMcpToolSelections.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch { repository.refresh() }

    fun cli(action: String, name: String) =
        viewModelScope.launch { repository.cliAction(action, name) }

    /**
     * CLI 更新能力由现有 manifest 的安装策略推导：pip/npm/brew/uv 都有后端 update
     * 路径；bundled 虽然可以执行一次状态检查，但不是用户可见的“更新”操作。
     */
    fun canUpdateCli(app: CliAppInfo): Boolean {
        val installPlan = app.manifest?.install ?: return false
        return app.installed &&
            app.installSupported &&
            installPlan.supported &&
            installPlan.strategy in UPDATE_CAPABLE_STRATEGIES
    }

    /**
     * 记录某个 MCP preset 的字段输入。按 preset 分组保存，切换 tab 或刷新列表时仍能保留
     * 尚未提交的内容；Map 的复制更新也避免了原地修改导致 Compose 不感知变化。
     */
    fun setMcpFieldValue(presetName: String, fieldName: String, value: String) {
        mutableMcpFieldValues.update { current ->
            current + (presetName to (current[presetName].orEmpty() + (fieldName to value)))
        }
    }

    /**
     * Enable/Test 共用此入口。requiredFields 未配置且没有非空输入时直接返回，
     * 即使未来有别的调用方绕过按钮 disabled 状态，也不会发出不完整的 MCP 请求。
     *
     * Remove 只依赖已安装 preset 的名称，服务端不会再次读取配置字段；如果把同一套
     * required 校验套到 Remove 上，已安装但字段失效的 preset 将无法被用户卸载。因此
     * 这里只对真正需要提交配置的 Enable/Test 做前置校验。
     */
    fun mcp(
        action: String,
        name: String,
        values: Map<String, String> = mcpFieldValues.value[name].orEmpty(),
    ) {
        val preset = state.value.mcp?.presets?.firstOrNull { it.name == name }
        val requiresConfiguredFields = action == "enable" || action == "test"
        if (requiresConfiguredFields && preset != null && hasMissingRequiredFields(preset, values)) return
        viewModelScope.launch { repository.mcpAction(action, name, values) }
    }

    /** 保存自定义 MCP；名称、传输类型和对应连接地址/命令不完整时不触发远程请求。 */
    fun saveCustom(values: Map<String, String>) {
        if (!isCustomConfigValid(values)) return
        viewModelScope.launch { repository.saveCustom(values) }
    }

    /**
     * 返回自定义 MCP 表单的第一项可读校验错误。Screen 用它控制 Save 按钮，测试也可以
     * 直接覆盖所有入口条件，而不需要启动 Compose 或发起真实远程操作。
     */
    fun customConfigError(values: Map<String, String>): String? {
        val name = values["name"].orEmpty().trim()
        val transport = values["transport"].orEmpty().trim()
        val command = values["command"].orEmpty().trim()
        val url = values["url"].orEmpty().trim()
        if (name.isEmpty()) return "Name is required"
        if (!NAME_PATTERN.matches(name)) return "Name must use letters, numbers, '_' or '-'"
        if (transport !in SUPPORTED_MCP_TRANSPORTS) return "Unsupported transport"
        if (transport == "stdio" && command.isEmpty()) return "Command is required for stdio"
        if (transport != "stdio" && url.isEmpty()) return "URL is required for remote MCP"
        if (!isJsonArrayOrBlank(values["args"])) return "Args must be a JSON array"
        if (!isJsonObjectOrBlank(values["env"])) return "Env must be a JSON object"
        if (!isJsonObjectOrBlank(values["headers"])) return "Headers must be a JSON object"
        return null
    }

    fun isCustomConfigValid(values: Map<String, String>): Boolean = customConfigError(values) == null

    /**
     * 从服务端的 enabled_tools 初始化本地选择：'*' 表示服务端当前允许全部工具，
     * 因而在已知 toolNames 时默认全部勾选；显式列表则只勾选列表中的工具。
     */
    fun selectedMcpTools(preset: McpPresetInfo): Set<String> =
        mutableMcpToolSelections.value[preset.name]
            ?: when {
                "*" in preset.enabledTools.orEmpty() -> preset.toolNames.orEmpty().toSet()
                else -> preset.enabledTools.orEmpty().toSet().intersect(preset.toolNames.orEmpty().toSet())
            }

    fun isMcpToolSelected(preset: McpPresetInfo, toolName: String): Boolean =
        toolName in selectedMcpTools(preset)

    /** 更新单个 checkbox 的草稿状态，不会立即写入远程配置。 */
    fun setMcpToolSelected(preset: McpPresetInfo, toolName: String, selected: Boolean) {
        mutableMcpToolSelections.update { current ->
            val next = selectedMcpTools(preset).let { selectedTools ->
                if (selected) selectedTools + toolName else selectedTools - toolName
            }
            current + (preset.name to next)
        }
    }

    /** 保存已安装 preset 当前的 tools 选择；显式传参主要用于测试和未来批量入口。 */
    fun updateTools(name: String, tools: List<String>? = null) {
        val selected = tools ?: state.value.mcp?.presets
            ?.firstOrNull { it.name == name }
            ?.let { selectedMcpTools(it).toList() }
            ?: return
        viewModelScope.launch { repository.updateTools(name, selected) }
    }

    fun importConfig(config: String) =
        viewModelScope.launch { repository.importConfig(config) }

    fun importCursorConfig(config: String) =
        viewModelScope.launch { repository.importCursorConfig(config) }

    private fun hasMissingRequiredFields(
        preset: McpPresetInfo,
        values: Map<String, String>,
    ): Boolean = preset.requiredFields.any { field ->
        field.required && !field.configured && values[field.name].orEmpty().trim().isEmpty()
    }

    private fun isJsonArrayOrBlank(value: String?): Boolean =
        value.isNullOrBlank() || runCatching { Json.parseToJsonElement(value) is JsonArray }.getOrDefault(false)

    private fun isJsonObjectOrBlank(value: String?): Boolean =
        value.isNullOrBlank() || runCatching { Json.parseToJsonElement(value) is JsonObject }.getOrDefault(false)

    private companion object {
        val UPDATE_CAPABLE_STRATEGIES = setOf("pip", "npm", "brew", "uv")
        val SUPPORTED_MCP_TRANSPORTS = setOf("stdio", "sse", "streamableHttp")
        val NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$")
    }
}
