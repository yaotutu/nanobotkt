package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.FilePreviewPayload
import com.nanobotkt.core.network.GatewayApiClient
import java.util.concurrent.atomic.AtomicLong

/**
 * 文件预览请求的数据源与“最后一次请求获胜”代次门闩。
 *
 * 同一会话连续点击两个文件也会产生竞态，因此代次不能只依赖 sessionKey。Repository 在 reset、
 * 会话切换和关闭预览时调用 [invalidate]，网络响应返回后同时核对代次与会话身份再更新 UI。
 */
internal class ChatFilePreviewLoader(
    private val api: GatewayApiClient,
) {
    private val generation = AtomicLong(0L)

    fun beginRequest(): Long = generation.incrementAndGet()

    fun invalidate() {
        generation.incrementAndGet()
    }

    fun isCurrent(expectedGeneration: Long): Boolean = generation.get() == expectedGeneration

    suspend fun load(sessionKey: String, path: String): FilePreviewPayload =
        api.request(
            path = "/api/sessions/${sessionKey.pathEncoded()}/file-preview",
            deserializer = FilePreviewPayload.serializer(),
            query = mapOf("path" to path),
        )
}
