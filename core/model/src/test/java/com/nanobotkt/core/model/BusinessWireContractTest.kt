package com.nanobotkt.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessWireContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `skills decode snake case fields and tolerate future fields`() {
        val payload = json.decodeFromString<SkillsPayload>(
            """
            {
              "skills": [{
                "name": "android",
                "description": "Native Android workflows",
                "source": "bundled",
                "available": false,
                "unavailable_reason": "missing adb",
                "future_gateway_field": {"ignored": true}
              }]
            }
            """.trimIndent(),
        )

        assertEquals("android", payload.skills.single().name)
        assertEquals("missing adb", payload.skills.single().unavailableReason)
    }

    @Test
    fun `cli and mcp catalogs preserve wire names`() {
        val cli = json.decodeFromString<CliAppsPayload>(
            """{
              "installed_count": 1,
              "catalog_refresh_pending": true,
              "apps": [{
                "name": "gh",
                "display_name": "GitHub CLI",
                "entry_point": "gh",
                "install_supported": true,
                "skill_installed": true
              }]
            }""",
        )
        assertEquals(1, cli.installedCount)
        assertTrue(cli.catalogRefreshPending == true)
        assertEquals("GitHub CLI", cli.apps.single().displayName)
        assertEquals("gh", cli.apps.single().entryPoint)

        val mcp = json.decodeFromString<McpPresetsPayload>(
            """{
              "installed_count": 0,
              "requires_restart": false,
              "presets": [{
                "name": "filesystem",
                "display_name": "Filesystem",
                "required_fields": [{
                  "name": "root",
                  "label": "Root",
                  "env_var": "MCP_ROOT",
                  "required": true
                }],
                "enabled_tools": ["read_file"]
              }]
            }""",
        )
        assertFalse(mcp.requiresRestart == true)
        assertEquals("MCP_ROOT", mcp.presets.single().requiredFields.single().envVar)
        assertEquals(listOf("read_file"), mcp.presets.single().enabledTools)
    }

    @Test
    fun `automation schedules and pairing preserve nullable semantics`() {
        val automations = json.decodeFromString<AutomationsPayload>(
            """{
              "jobs": [{
                "id": "job-1",
                "name": "Daily summary",
                "enabled": true,
                "delete_after_run": false,
                "schedule": {"kind": "cron", "expr": "0 9 * * *", "tz": "Asia/Shanghai"},
                "payload": {"message": "Summarize"},
                "state": {"next_run_at_ms": 1785987600000, "pending": false},
                "origin": {"session_key": "webui:chat-1", "channel": "webui", "chat_id": "chat-1"}
              }]
            }""",
        )
        val job = automations.jobs.single()
        assertEquals("0 9 * * *", job.schedule.expr)
        assertEquals("Asia/Shanghai", job.schedule.tz)
        assertNull(job.schedule.atMs)
        assertEquals("webui:chat-1", job.origin?.sessionKey)

        val pairing = json.decodeFromString<PairingPayload>(
            """{
              "requests": [{
                "code": "123456",
                "channel": "telegram",
                "sender_id": "u-1",
                "expires_in_seconds": 42
              }]
            }""",
        )
        assertEquals("u-1", pairing.requests.single().senderId)
        assertEquals(42L, pairing.requests.single().expiresInSeconds)
    }

    @Test
    fun `channel and settings payloads decode representative canonical fixtures`() {
        val channels = json.decodeFromString<NanobotFeaturesPayload>(
            """{
              "enabled_count": 1,
              "requires_restart": true,
              "features": [{
                "name": "telegram",
                "display_name": "Telegram",
                "type": "channel",
                "enabled": true,
                "configured": true,
                "runtime_status": "running",
                "setup": {
                  "official_url": "https://example.invalid/docs",
                  "fields": [{
                    "key": "token",
                    "field": "bot_token",
                    "kind": "secret",
                    "required": true,
                    "default_value": ""
                  }]
                }
              }]
            }""",
        )
        assertTrue(channels.requiresRestart == true)
        assertEquals("bot_token", channels.features.single().setup?.fields?.single()?.field)

        val settings = json.decodeFromString<SettingsPayload>(
            """{
              "agent": {
                "model": "gpt-test",
                "provider": "openai",
                "has_api_key": true,
                "model_preset": "fast",
                "context_window_tokens": 128000,
                "tool_hint_max_length": 500
              },
              "model_call_order": ["openai", "fallback"],
              "model_call_order_editable": true,
              "providers": [{
                "name": "openai",
                "label": "OpenAI",
                "configured": true,
                "oauth_login_supported": true
              }],
              "runtime": {"config_path": "/tmp/config", "workspace_path": "/tmp/workspace"},
              "advanced": {
                "restrict_to_workspace": true,
                "webui_allow_local_service_access": false,
                "webui_default_access_mode": "workspace"
              },
              "requires_restart": false
            }""",
        )
        assertEquals(128000, settings.agent.contextWindowTokens)
        assertTrue(settings.modelCallOrderEditable)
        assertTrue(settings.providers.single().oauthLoginSupported == true)
        assertTrue(settings.advanced.restrictToWorkspace)
    }

    @Test
    fun `settings capability sections decode official webui contract`() {
        val settings = json.decodeFromString<SettingsPayload>(
            """{
              "web_search": {
                "provider": "duckduckgo",
                "max_results": 5,
                "timeout": 30,
                "providers": [{"name": "duckduckgo", "label": "DuckDuckGo", "credential": "none"}]
              },
              "web": {
                "enable": true,
                "search": {"max_results": 5, "timeout": 30},
                "fetch": {"use_jina_reader": true}
              },
              "image_generation": {
                "enabled": true,
                "provider": "openrouter",
                "provider_configured": true,
                "model": "openai/gpt-image",
                "default_aspect_ratio": "16:9",
                "default_image_size": "2K",
                "max_images_per_turn": 4,
                "save_dir": "/tmp/images",
                "providers": [{
                  "name": "openrouter",
                  "label": "OpenRouter",
                  "configured": true,
                  "default_api_base": "https://openrouter.ai/api/v1",
                  "models": ["openai/gpt-image"]
                }]
              },
              "transcription": {
                "enabled": true,
                "provider": "groq",
                "provider_configured": true,
                "model": "whisper-large-v3",
                "language": "zh",
                "max_duration_sec": 120,
                "max_upload_mb": 25,
                "providers": [{"name": "groq", "label": "Groq", "configured": true}]
              }
            }""".trimIndent(),
        )

        assertEquals("DuckDuckGo", settings.webSearch?.providers?.single()?.label)
        assertTrue(settings.web?.fetch?.useJinaReader == true)
        assertEquals("16:9", settings.imageGeneration?.defaultAspectRatio)
        assertEquals("https://openrouter.ai/api/v1", settings.imageGeneration?.providers?.single()?.defaultApiBase)
        assertEquals("zh", settings.transcription?.language)
        assertEquals(25, settings.transcription?.maxUploadMb)
    }

    @Test
    fun `oauth response round trips exact snake case keys`() {
        val original = ProviderOAuthResult(
            status = "pending",
            provider = "example",
            flowId = "flow-1",
            authorizationUrl = "https://example.invalid/oauth",
            expiresIn = 300,
        )
        val encoded = json.encodeToString(original)
        assertTrue(encoded.contains("\"flow_id\""))
        assertTrue(encoded.contains("\"authorization_url\""))
        assertTrue(encoded.contains("\"expires_in\""))
        assertEquals(original, json.decodeFromString<ProviderOAuthResult>(encoded))
    }
}
