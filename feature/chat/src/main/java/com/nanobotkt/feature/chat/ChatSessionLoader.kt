package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.AutomationsPayload
import com.nanobotkt.core.model.SessionAutomationJob
import com.nanobotkt.core.model.WebUiThreadPayload
import com.nanobotkt.core.network.GatewayApiClient
import com.nanobotkt.core.network.GatewayException
import java.net.URLEncoder

/**
 * Chat 会话只读 HTTP 数据源，集中维护 sessionKey 的 path-segment 编码和分页协议。
 * Repository 仍负责会话身份校验与时间线归并；数据源不得持有当前选中会话，避免迟到响应自行写回。
 */
internal class ChatSessionLoader(
    private val api: GatewayApiClient,
) {
    suspend fun loadThread(
        sessionKey: String,
        before: String?,
        latest: Boolean,
    ): WebUiThreadPayload? {
        val query = buildMap<String, Any?> {
            put("limit", if (latest) 160 else 120)
            if (latest) put("direction", "latest")
            if (before != null) put("before", before)
        }
        return try {
            api.request(
                path = "/api/sessions/${sessionKey.pathEncoded()}/webui-thread",
                deserializer = WebUiThreadPayload.serializer(),
                query = query,
            )
        } catch (error: GatewayException.Http) {
            // WebUI 用 404 表示该会话尚无可读取的规范线程；其他 HTTP 错误必须继续上抛。
            if (error.status == 404) null else throw error
        }
    }

    suspend fun loadAutomations(sessionKey: String): List<SessionAutomationJob> =
        api.request(
            path = "/api/sessions/${sessionKey.pathEncoded()}/automations",
            deserializer = AutomationsPayload.serializer(),
        ).jobs
}

internal fun String.pathEncoded(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
