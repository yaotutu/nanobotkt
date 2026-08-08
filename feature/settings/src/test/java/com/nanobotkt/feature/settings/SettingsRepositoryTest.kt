package com.nanobotkt.feature.settings

import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SettingsRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun newerRefreshCannotBeOverwrittenByOlderResponse() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val requestCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/settings" -> {
                    if (requestCount.incrementAndGet() == 1) {
                        firstStarted.countDown()
                        jsonResponse("""{"agent":{"model":"old"}}""")
                            .setBodyDelay(500, TimeUnit.MILLISECONDS)
                    } else {
                        jsonResponse("""{"agent":{"model":"new"}}""")
                    }
                }
                "/api/settings/api-service" -> jsonResponse("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = DefaultSettingsRepository(
            GatewayApiClient(
                OkHttpClient(),
                Json { ignoreUnknownKeys = true; explicitNulls = false },
                object : AuthContext {
                    override val baseUrl: String = server.url("/").toString()
                    override val apiToken: String? = null
                },
            ),
            Json { ignoreUnknownKeys = true; explicitNulls = false },
        )

        val first = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

        val second = async(Dispatchers.IO) { repository.refresh() }
        withTimeout(2_000) { second.await() }
        withTimeout(2_000) { first.await() }

        assertEquals("new", repository.state.value.payload?.agent?.model)
    }

    @Test
    fun webSearchPatchOmitsUnspecifiedFields() = runBlocking {
        val updateRequest = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/settings/web-search/update" -> {
                    updateRequest.set(request)
                    jsonResponse("""{"agent":{"model":"updated"}}""")
                }
                "/api/settings" -> jsonResponse("""{"agent":{"model":"refreshed"}}""")
                "/api/settings/api-service" -> jsonResponse("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.updateWebSearch(WebSearchSettingsUpdate(provider = "brave"))

        val query = updateRequest.get().requestUrl
        assertEquals("brave", query?.queryParameter("provider"))
        assertNull(query?.queryParameter("max_results"))
        assertNull(query?.queryParameter("timeout"))
        assertNull(query?.queryParameter("use_jina_reader"))
        assertEquals("refreshed", repository.state.value.payload?.agent?.model)
    }

    @Test
    fun explicitEmptyPatchValuesArePreservedAsQueryParameters() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return when (request.requestUrl?.encodedPath) {
                    "/api/settings/web-search/update" ->
                        jsonResponse("""{"agent":{"model":"web-empty"}}""")
                    "/api/settings/update" ->
                        jsonResponse("""{"agent":{"model":"settings-empty"}}""")
                    "/api/settings" -> jsonResponse("""{"agent":{"model":"refreshed"}}""")
                    "/api/settings/api-service" -> jsonResponse("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = repository()
        repository.updateWebSearch(WebSearchSettingsUpdate(provider = "brave", apiKey = ""))
        repository.update(SettingsUpdate(botName = ""))

        val webQuery = synchronized(requests) {
            requests.first { it.requestUrl?.encodedPath == "/api/settings/web-search/update" }
        }.requestUrl
        val settingsQuery = synchronized(requests) {
            requests.first { it.requestUrl?.encodedPath == "/api/settings/update" }
        }.requestUrl

        // 空字符串表示显式清除配置，不能被网络层当作“未提供”而丢弃。
        assertEquals("", webQuery?.queryParameter("api_key"))
        assertEquals("", settingsQuery?.queryParameter("bot_name"))
    }

    @Test
    fun imagePatchOmitsUnspecifiedFields() = runBlocking {
        val updateRequest = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = settingsMutationDispatcher("/api/settings/image-generation/update", updateRequest)

        val repository = repository()
        repository.updateImage(ImageGenerationSettingsUpdate(enabled = false))

        val query = updateRequest.get().requestUrl
        assertEquals("false", query?.queryParameter("enabled"))
        assertNull(query?.queryParameter("provider"))
        assertNull(query?.queryParameter("model"))
        assertNull(query?.queryParameter("default_aspect_ratio"))
        assertNull(query?.queryParameter("default_image_size"))
        assertNull(query?.queryParameter("max_images_per_turn"))
    }

    @Test
    fun transcriptionPatchOmitsUnspecifiedFields() = runBlocking {
        val updateRequest = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = settingsMutationDispatcher("/api/settings/transcription/update", updateRequest)

        val repository = repository()
        repository.updateTranscription(TranscriptionSettingsUpdate(enabled = false))

        val query = updateRequest.get().requestUrl
        assertEquals("false", query?.queryParameter("enabled"))
        assertNull(query?.queryParameter("provider"))
        assertNull(query?.queryParameter("model"))
        assertNull(query?.queryParameter("language"))
        assertNull(query?.queryParameter("max_duration_sec"))
        assertNull(query?.queryParameter("max_upload_mb"))
    }

    @Test
    fun imagePatchMapsEveryExplicitField() = runBlocking {
        val updateRequest = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = settingsMutationDispatcher("/api/settings/image-generation/update", updateRequest)

        repository().updateImage(
            ImageGenerationSettingsUpdate(
                enabled = true,
                provider = "openai",
                model = "gpt-image-1",
                defaultAspectRatio = "16:9",
                defaultImageSize = "1024x1024",
                maxImagesPerTurn = 4,
            ),
        )

        val query = updateRequest.get().requestUrl
        assertEquals("true", query?.queryParameter("enabled"))
        assertEquals("openai", query?.queryParameter("provider"))
        assertEquals("gpt-image-1", query?.queryParameter("model"))
        assertEquals("16:9", query?.queryParameter("default_aspect_ratio"))
        assertEquals("1024x1024", query?.queryParameter("default_image_size"))
        assertEquals("4", query?.queryParameter("max_images_per_turn"))
    }

    @Test
    fun transcriptionPatchMapsEveryExplicitField() = runBlocking {
        val updateRequest = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = settingsMutationDispatcher("/api/settings/transcription/update", updateRequest)

        repository().updateTranscription(
            TranscriptionSettingsUpdate(
                enabled = true,
                provider = "assemblyai",
                model = "best",
                language = "en",
                maxDurationSec = 300,
                maxUploadMb = 25,
            ),
        )

        val query = updateRequest.get().requestUrl
        assertEquals("true", query?.queryParameter("enabled"))
        assertEquals("assemblyai", query?.queryParameter("provider"))
        assertEquals("best", query?.queryParameter("model"))
        assertEquals("en", query?.queryParameter("language"))
        assertEquals("300", query?.queryParameter("max_duration_sec"))
        assertEquals("25", query?.queryParameter("max_upload_mb"))
    }

    @Test
    fun settingsUpdateMapsExplicitFieldsAndRefreshesPayload() = runBlocking {
        val updateRequest = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/settings/update" -> {
                    updateRequest.set(request)
                    jsonResponse("""{"agent":{"model":"mutation-result"}}""")
                }
                "/api/settings" -> jsonResponse("""{"agent":{"model":"refreshed-result"}}""")
                "/api/settings/api-service" -> jsonResponse("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.update(
            SettingsUpdate(
                modelPreset = "preset-a",
                model = "model-a",
                provider = "provider-a",
                contextWindowTokens = 8_192,
                timezone = "Asia/Kolkata",
                botName = "bot-a",
                botIcon = "🤖",
                toolHintMaxLength = 320,
            ),
        )

        val query = updateRequest.get().requestUrl
        assertEquals("preset-a", query?.queryParameter("model_preset"))
        assertEquals("model-a", query?.queryParameter("model"))
        assertEquals("provider-a", query?.queryParameter("provider"))
        assertEquals("8192", query?.queryParameter("context_window_tokens"))
        assertEquals("Asia/Kolkata", query?.queryParameter("timezone"))
        assertEquals("bot-a", query?.queryParameter("bot_name"))
        assertEquals("🤖", query?.queryParameter("bot_icon"))
        assertEquals("320", query?.queryParameter("tool_hint_max_length"))
        assertEquals("refreshed-result", repository.state.value.payload?.agent?.model)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun refreshSucceedsWhenApiServiceEndpointFails() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/settings" -> jsonResponse("""{"agent":{"model":"settings-ok"}}""")
                "/api/settings/api-service" -> MockResponse().setResponseCode(503).setBody("not managed")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.refresh()

        assertEquals("settings-ok", repository.state.value.payload?.agent?.model)
        assertNull(repository.state.value.apiService)
        assertTrue(!repository.state.value.loading)
        assertNull(repository.state.value.error)
    }

    @Test
    fun refreshFailureClearsLoadingAndExposesError() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/settings" -> MockResponse().setResponseCode(500).setBody("settings unavailable")
                "/api/settings/api-service" -> jsonResponse("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.refresh()

        assertTrue(!repository.state.value.loading)
        assertEquals("settings unavailable", repository.state.value.error)
        assertNull(repository.state.value.payload)
    }

    @Test
    fun apiServiceStartWithoutKeyOmitsSecretHeader() = runBlocking {
        val requestRef = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/settings/api-service/start") {
                    requestRef.set(request)
                    jsonResponse("""{"running":true,"host":"127.0.0.1","port":18765,"timeout":120}""")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        repository().startApiService("127.0.0.1", 18_765, 120, apiKey = null)

        val request = requestRef.get()
        assertNull(request.getHeader("X-Nanobot-API-Service-Values"))
        assertEquals("127.0.0.1", request.requestUrl?.queryParameter("host"))
        assertEquals("18765", request.requestUrl?.queryParameter("port"))
        assertEquals("120", request.requestUrl?.queryParameter("timeout"))
    }

    @Test
    fun webSearchUpdateFailureExposesErrorAndClearsPending() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/settings/web-search/update") {
                    MockResponse()
                        .setResponseCode(503)
                        .setBody("web search update failed")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()

        repository.updateWebSearch(WebSearchSettingsUpdate(provider = "brave"))

        // mutation 失败后必须把服务端错误暴露给 UI，同时 finally 必须清理 pending；
        // 否则对应设置区会永久停留在保存中状态，用户也无法判断失败原因。
        assertEquals("web search update failed", repository.state.value.error)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun versionCheckSuccessStoresAllReturnedVersionFieldsWithoutRefreshingSettings() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return if (request.requestUrl?.encodedPath == "/api/settings/version-check") {
                    jsonResponse(
                        """{"updateAvailable":{"currentVersion":"1.0.0","latestVersion":"1.1.0","pypiUrl":"https://pypi.org/project/nanobot"}}""",
                    )
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = repository()
        repository.checkVersion()

        val update = repository.state.value.versionCheck?.updateAvailable
        assertEquals("1.0.0", update?.currentVersion)
        assertEquals("1.1.0", update?.latestVersion)
        assertEquals("https://pypi.org/project/nanobot", update?.pypiUrl)
        assertTrue(repository.state.value.pending.isEmpty())
        assertNull(repository.state.value.error)
        // 版本检查是独立动作，成功后不应隐式再次拉取整个 Settings payload。
        assertEquals(listOf("/api/settings/version-check"), synchronized(requests) {
            requests.mapNotNull { it.requestUrl?.encodedPath }
        })
    }

    @Test
    fun versionCheckFailureExposesErrorAndClearsPending() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/settings/version-check") {
                    MockResponse()
                        .setResponseCode(500)
                        .setBody("version check failed")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()

        repository.checkVersion()

        // 版本检查不执行成功后的 refresh，单独覆盖该分支，确保失败路径仍统一写入
        // state.error，并在请求结束后移除 "version" pending 标记。
        assertEquals("version check failed", repository.state.value.error)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun resetIgnoresLateMutationResponseAndCleanup() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/settings/web-search/update") {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    jsonResponse("""{"agent":{"model":"stale"}}""")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        val mutation = async(Dispatchers.IO) {
            repository.updateWebSearch(WebSearchSettingsUpdate(provider = "brave"))
        }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // logout/reset 发生在保存请求返回前；旧 mutation 不能恢复 payload、error 或 pending。
        repository.reset()
        responseRelease.countDown()
        withTimeout(2_000) { mutation.await() }

        assertEquals(SettingsUiState(), repository.state.value)
    }

    @Test
    fun oauthLogoutReplacesPayloadClearsOAuthAndRefreshesSettings() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return when (request.requestUrl?.encodedPath) {
                    "/api/settings/provider/oauth-login" -> jsonResponse(
                        """{"status":"pending","provider":"openai","flow_id":"flow-logout"}""",
                    )
                    "/api/settings/provider/oauth-logout" -> jsonResponse(
                        """{"agent":{"model":"logout-result"}}""",
                    )
                    "/api/settings" -> jsonResponse(
                        """{"agent":{"model":"refreshed-after-logout"}}""",
                    )
                    "/api/settings/api-service" -> jsonResponse("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = repository()
        repository.oauthLogin("openai")
        assertEquals("flow-logout", repository.state.value.oauth?.flowId)

        repository.oauthLogout("openai")

        assertEquals("refreshed-after-logout", repository.state.value.payload?.agent?.model)
        assertNull(repository.state.value.oauth)
        assertTrue(repository.state.value.pending.isEmpty())
        assertNull(repository.state.value.error)
        val logoutRequest = synchronized(requests) {
            requests.first { it.requestUrl?.encodedPath == "/api/settings/provider/oauth-logout" }
        }
        assertEquals("openai", logoutRequest.requestUrl?.queryParameter("provider"))
        assertEquals(1, synchronized(requests) {
            requests.count { it.requestUrl?.encodedPath == "/api/settings" }
        })
    }

    @Test
    fun resetIgnoresLateRefreshStartedAfterMutationResponse() = runBlocking {
        val refreshStarted = CountDownLatch(1)
        val refreshRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/api/settings/web-search/update" ->
                        jsonResponse("""{"agent":{"model":"mutation-result"}}""")
                    "/api/settings" -> {
                        refreshStarted.countDown()
                        assertTrue(refreshRelease.await(2, TimeUnit.SECONDS))
                        jsonResponse("""{"agent":{"model":"stale-after-reset"}}""")
                    }
                    "/api/settings/api-service" -> jsonResponse("{}")
                    else -> MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        val mutation = async(Dispatchers.IO) {
            repository.updateWebSearch(WebSearchSettingsUpdate(provider = "brave"))
        }
        assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))
        assertTrue(repository.state.value.loading)

        // mutation 已经成功，但其自动 refresh 仍在途；reset 必须让这个过期响应失效。
        repository.reset()
        assertEquals(SettingsUiState(), repository.state.value)
        refreshRelease.countDown()
        withTimeout(2_000) { mutation.await() }

        assertEquals(SettingsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateRefreshResponseAndCleanup() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/api/settings" -> {
                        requestStarted.countDown()
                        assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                        jsonResponse("""{"agent":{"model":"stale-refresh"}}""")
                    }
                    "/api/settings/api-service" -> jsonResponse("{}")
                    else -> MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // refresh 已经把 loading 置为 true，但设置 payload 的旧响应仍被闩锁阻塞；此时模拟
        // logout/reset 切换会话，reset 必须立即清空 payload、loading、error 和 pending。
        assertTrue(repository.state.value.loading)
        repository.reset()
        assertEquals(SettingsUiState(), repository.state.value)

        // 放行 reset 前发出的旧 refresh。即使服务端正常返回 payload，响应写入和请求收尾
        // 都必须被会话代次拦截，不能再次恢复旧 payload/loading/error/pending 状态。
        responseRelease.countDown()
        withTimeout(2_000) { refresh.await() }

        assertEquals(SettingsUiState(), repository.state.value)
    }

    @Test
    fun resetClearsState() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/settings" -> jsonResponse("""{"agent":{"model":"loaded"}}""")
                "/api/settings/api-service" -> jsonResponse("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repository = DefaultSettingsRepository(
            GatewayApiClient(
                OkHttpClient(),
                Json { ignoreUnknownKeys = true; explicitNulls = false },
                object : AuthContext {
                    override val baseUrl: String = server.url("/").toString()
                    override val apiToken: String? = null
                },
            ),
            Json { ignoreUnknownKeys = true; explicitNulls = false },
        )

        repository.refresh()
        assertEquals("loaded", repository.state.value.payload?.agent?.model)

        repository.reset()

        assertEquals(SettingsUiState(), repository.state.value)
    }


    @Test
    fun providerUpdateSendsEncodedValuesHeaderAndProviderQuery() = runBlocking {
        val requestRef = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = settingsMutationDispatcher(
            path = "/api/settings/provider/update",
            updateRequest = requestRef,
        )

        repository().updateProvider(
            ProviderUpdate(
                provider = "openai",
                displayName = "Open AI",
                apiKey = "secret-value",
                apiBase = "https://api.example.test/v1",
                apiType = "responses",
                proxy = "http://proxy.example.test",
            ),
        )

        val request = requestRef.get()
        assertEquals("openai", request.requestUrl?.queryParameter("provider"))
        val encodedValues = request.getHeader("X-Nanobot-Provider-Values")
        assertTrue(encodedValues?.contains("%7B") == true)
        assertTrue(encodedValues?.contains("apiKey") == true)
        assertTrue(encodedValues?.contains("https%3A%2F%2Fapi.example.test%2Fv1") == true)
        assertTrue(request.getHeader("Authorization").isNullOrBlank())
    }

    @Test
    fun apiServiceStartFailureExposesErrorAndClearsPending() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/settings/api-service/start") {
                    MockResponse().setResponseCode(503).setBody("api service start failed")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        repository.startApiService("127.0.0.1", 18_765, 120, apiKey = null)

        assertEquals("api service start failed", repository.state.value.error)
        assertTrue(repository.state.value.pending.isEmpty())
        assertNull(repository.state.value.apiService)
    }

    @Test
    fun apiServiceStopFailureExposesErrorAndClearsPending() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/settings/api-service/stop") {
                    MockResponse().setResponseCode(500).setBody("api service stop failed")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        repository.stopApiService()

        assertEquals("api service stop failed", repository.state.value.error)
        assertTrue(repository.state.value.pending.isEmpty())
        assertNull(repository.state.value.apiService)
    }

    @Test
    fun providerModelsAndApiServiceActionsUpdateDedicatedStateWithoutSettingsRefresh() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return when (request.requestUrl?.encodedPath) {
                    "/api/settings/provider-models" -> jsonResponse(
                        """{"provider":"openai","label":"OpenAI","status":"ok","catalog_kind":"remote","models":[],"model_count":0}""",
                    )
                    "/api/settings/api-service/start" -> jsonResponse(
                        """{"running":true,"host":"0.0.0.0","port":19000,"timeout":30}""",
                    )
                    "/api/settings/api-service/stop" -> jsonResponse(
                        """{"running":false,"host":"0.0.0.0","port":19000,"timeout":30}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val repository = repository()

        repository.providerModels("openai")
        repository.startApiService("0.0.0.0", 19000, 30, "service-key")
        repository.stopApiService()

        assertEquals("openai", repository.state.value.providerModels?.provider)
        assertEquals(false, repository.state.value.apiService?.running)
        assertEquals(3, requests.size)
        val providerRequest = requests.first { it.requestUrl?.encodedPath == "/api/settings/provider-models" }
        assertEquals("openai", providerRequest.requestUrl?.queryParameter("provider"))
        val startRequest = requests.first { it.requestUrl?.encodedPath == "/api/settings/api-service/start" }
        assertEquals("0.0.0.0", startRequest.requestUrl?.queryParameter("host"))
        assertEquals("19000", startRequest.requestUrl?.queryParameter("port"))
        assertEquals("30", startRequest.requestUrl?.queryParameter("timeout"))
        assertEquals("{\"api_key\":\"service-key\"}", startRequest.getHeader("X-Nanobot-API-Service-Values"))
    }

    @Test
    fun oauthLoginStoresFlowAndCompleteReplacesPayloadAndSendsCodeHeader() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return when (request.requestUrl?.encodedPath) {
                    "/api/settings/provider/oauth-login" -> jsonResponse(
                        """{"status":"pending","provider":"openai","flow_id":"flow-1","authorization_url":"https://login.example.test"}""",
                    )
                    "/api/settings/provider/oauth-login/complete" -> jsonResponse(
                        """{"agent":{"model":"oauth-model"}}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val repository = repository()

        repository.oauthLogin("openai")
        assertEquals("flow-1", repository.state.value.oauth?.flowId)
        assertEquals("pending", repository.state.value.oauth?.status)

        repository.oauthComplete("openai", "flow-1", "auth-code")

        assertEquals("oauth-model", repository.state.value.payload?.agent?.model)
        assertNull(repository.state.value.oauth)
        val complete = requests.first { it.requestUrl?.encodedPath == "/api/settings/provider/oauth-login/complete" }
        assertEquals("openai", complete.requestUrl?.queryParameter("provider"))
        assertEquals("flow-1", complete.requestUrl?.queryParameter("flow_id"))
        assertEquals("auth-code", complete.getHeader("X-Nanobot-OAuth-Code"))
    }

    @Test
    fun networkSafetySendsBooleanAndModeAndRefreshesSettings() = runBlocking {
        val requestRef = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/settings/network-safety/update" -> {
                    requestRef.set(request)
                    jsonResponse("""{"agent":{"model":"safe"}}""")
                }
                "/api/settings" -> jsonResponse("""{"agent":{"model":"refreshed-safe"}}""")
                "/api/settings/api-service" -> jsonResponse("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repository = repository()

        repository.networkSafety(local = true, mode = "strict")

        val query = requestRef.get().requestUrl
        assertEquals("true", query?.queryParameter("webui_allow_local_service_access"))
        assertEquals("strict", query?.queryParameter("webui_default_access_mode"))
        assertEquals("refreshed-safe", repository.state.value.payload?.agent?.model)
    }

    @Test
    fun modelConfigurationMutationsMapQueryFieldsAndJsonOrder() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return when (request.requestUrl?.encodedPath) {
                    "/api/settings/model-configurations/create",
                    "/api/settings/model-configurations/update",
                    "/api/settings/model-configurations/delete",
                    "/api/settings/model-call-order/update" -> jsonResponse("""{"agent":{"model":"updated"}}""")
                    "/api/settings" -> jsonResponse("""{"agent":{"model":"refreshed"}}""")
                    "/api/settings/api-service" -> jsonResponse("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val repository = repository()

        repository.createModelConfiguration(
            ModelConfigurationCreate(
                label = "Fast model",
                name = "fast-model",
                model = "gpt-test",
                provider = "openai",
                maxTokens = 512,
                contextWindowTokens = 8_192,
                temperature = 0.2,
            ),
        )
        repository.updateModelConfiguration(ModelConfigurationUpdate(name = "fast-model", reasoningEffort = ""))
        repository.updateModelCallOrder(ModelCallOrderUpdate(listOf("fast-model", "default")))
        repository.deleteModelConfiguration("unused-model")

        val create = requests.first { it.requestUrl?.encodedPath == "/api/settings/model-configurations/create" }.requestUrl
        assertEquals("Fast model", create?.queryParameter("label"))
        assertEquals("fast-model", create?.queryParameter("name"))
        assertEquals("512", create?.queryParameter("max_tokens"))
        assertEquals("8192", create?.queryParameter("context_window_tokens"))
        assertEquals("0.2", create?.queryParameter("temperature"))

        val update = requests.first { it.requestUrl?.encodedPath == "/api/settings/model-configurations/update" }.requestUrl
        assertEquals("", update?.queryParameter("reasoning_effort"))
        val order = requests.first { it.requestUrl?.encodedPath == "/api/settings/model-call-order/update" }.requestUrl
        assertEquals("[\"fast-model\",\"default\"]", order?.queryParameter("order"))
        val delete = requests.first { it.requestUrl?.encodedPath == "/api/settings/model-configurations/delete" }.requestUrl
        assertEquals("unused-model", delete?.queryParameter("name"))
    }

    @Test
    fun modelConfigurationMigrationUsesEndpointAndReplacesFullPayload() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/settings/model-configurations/migrate" -> jsonResponse(
                    """{
                      "model_presets": [{
                        "name": "legacy-primary",
                        "label": "Legacy primary",
                        "active": true,
                        "is_default": false,
                        "model": "gpt-test",
                        "provider": "openai"
                      }],
                      "model_call_order": ["legacy-primary"],
                      "model_call_order_editable": true
                    }""".trimIndent(),
                )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.migrateModelConfigurations()

        val request = server.takeRequest(2, TimeUnit.SECONDS)
            ?: error("migration request was not received")
        assertEquals("/api/settings/model-configurations/migrate", request.requestUrl?.encodedPath)
        assertEquals(listOf("legacy-primary"), repository.state.value.payload?.modelCallOrder)
        assertTrue(repository.state.value.payload?.modelCallOrderEditable == true)
        assertEquals("legacy-primary", repository.state.value.payload?.modelPresets?.single()?.name)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun customProviderCreateUsesPrivateValuesHeader() = runBlocking {
        val requestRef = java.util.concurrent.atomic.AtomicReference<RecordedRequest>()
        server.dispatcher = settingsMutationDispatcher("/api/settings/provider/create", requestRef)

        repository().createProvider(
            CustomProviderCreate(
                displayName = "My Gateway",
                apiBase = "https://provider.example.test/v1",
                apiKey = "secret-value",
                proxy = "http://proxy.example.test",
                thinkingStyle = "enable_thinking",
                extraHeaders = "{\"X-Tenant\":\"engineering\"}",
                extraBody = "{\"service_tier\":\"priority\"}",
                extraQuery = "{\"api-version\":\"2026-01-01\"}",
            ),
        )

        val request = requestRef.get()
        assertEquals("My Gateway", request.requestUrl?.queryParameter("name"))
        val header = request.getHeader("X-Nanobot-Provider-Values")
        assertTrue(header?.contains("apiBase") == true)
        assertTrue(header?.contains("apiKey") == true)
        assertTrue(header?.contains("thinkingStyle") == true)
        assertTrue(header?.contains("extraHeaders") == true)
        assertTrue(header?.contains("extraBody") == true)
        assertTrue(header?.contains("extraQuery") == true)
        assertTrue(header?.contains("X-Tenant") == true)
        assertTrue(header?.contains("https%3A%2F%2Fprovider.example.test%2Fv1") == true)
    }

    private fun repository(): DefaultSettingsRepository = DefaultSettingsRepository(
        GatewayApiClient(
            OkHttpClient(),
            Json { ignoreUnknownKeys = true; explicitNulls = false },
            object : AuthContext {
                override val baseUrl: String = server.url("/").toString()
                override val apiToken: String? = null
            },
        ),
        Json { ignoreUnknownKeys = true; explicitNulls = false },
    )

    private fun settingsMutationDispatcher(
        path: String,
        updateRequest: java.util.concurrent.atomic.AtomicReference<RecordedRequest>,
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
            path -> {
                updateRequest.set(request)
                jsonResponse("""{"agent":{"model":"updated"}}""")
            }
            "/api/settings" -> jsonResponse("""{"agent":{"model":"refreshed"}}""")
            "/api/settings/api-service" -> jsonResponse("{}")
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
