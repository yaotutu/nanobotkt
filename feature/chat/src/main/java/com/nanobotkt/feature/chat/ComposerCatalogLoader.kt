package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.CliAppsPayload
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.McpPresetsPayload
import com.nanobotkt.core.model.SettingsPayload
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.SkillsPayload
import com.nanobotkt.core.model.SlashCommand
import com.nanobotkt.core.model.SlashCommandsPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.CancellationException

/**
 * Composer 目录的认证 HTTP 数据源。
 *
 * 本类只负责协议路径、反序列化与“允许部分成功”的加载语义，不持有 UI StateFlow 或认证代次。
 * 是否允许结果写回由 Repository 的 sessionEpoch/generation 决定，避免网络层反向控制页面状态。
 */
internal class ComposerCatalogLoader(
    private val api: GatewayApiClient,
) {
    suspend fun load(): ComposerCatalogLoadResult {
        val commands = requestPart {
            api.request(
                path = "/api/commands",
                deserializer = SlashCommandsPayload.serializer(),
            ).commands.filter(SlashCommand::hasSupportedLifecycle)
        }
        val skills = requestPart {
            api.request(
                path = COMPOSER_SKILLS_PATH,
                deserializer = SkillsPayload.serializer(),
            ).skills
        }
        val cliApps = requestPart {
            api.request(
                path = "/api/settings/cli-apps",
                deserializer = CliAppsPayload.serializer(),
                query = mapOf("installed_only" to 1),
            ).apps
        }
        val mcpPresets = requestPart {
            api.request(
                path = "/api/settings/mcp-presets",
                deserializer = McpPresetsPayload.serializer(),
            ).presets
        }
        val settings = loadModelSettingsPart()
        return ComposerCatalogLoadResult(
            slashCommands = commands.value,
            skills = skills.value,
            cliApps = cliApps.value,
            mcpPresets = mcpPresets.value,
            settings = settings.value,
            complete = listOf(commands, skills, cliApps, mcpPresets, settings).all(CatalogPart<*>::succeeded),
        )
    }

    suspend fun loadModelSettings(): SettingsPayload? = loadModelSettingsPart().value

    private suspend fun loadModelSettingsPart(): CatalogPart<SettingsPayload> = requestPart {
        api.request(
            path = "/api/settings",
            deserializer = SettingsPayload.serializer(),
        )
    }

    /**
     * 单个目录失败不应让其余目录全部消失；但协程取消必须继续传播，不能被 runCatching 吞掉后
     * 继续发送四个无意义请求。返回 succeeded 供认证层决定后续 Ready 是否需要重试。
     */
    private suspend fun <T> requestPart(block: suspend () -> T): CatalogPart<T> = try {
        CatalogPart(value = block(), succeeded = true)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        CatalogPart(value = null, succeeded = false)
    }

    internal companion object {
        const val COMPOSER_SKILLS_PATH = "/api/webui/skills"
    }
}

internal data class ComposerCatalogLoadResult(
    val slashCommands: List<SlashCommand>?,
    val skills: List<SkillSummary>?,
    val cliApps: List<CliAppInfo>?,
    val mcpPresets: List<McpPresetInfo>?,
    val settings: SettingsPayload?,
    val complete: Boolean,
)

private data class CatalogPart<T>(
    val value: T?,
    val succeeded: Boolean,
)
