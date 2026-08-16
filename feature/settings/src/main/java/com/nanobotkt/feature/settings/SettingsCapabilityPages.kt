package com.nanobotkt.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.nanobotkt.core.model.RuntimeSurface
import com.nanobotkt.core.network.GatewayServerAddressError
import com.nanobotkt.core.network.GatewayServerAddressResult
import com.nanobotkt.core.network.normalizeGatewayServerAddress

/** System、Security、Image、Voice 与 Web 等能力页。 */

/** 将严格地址校验错误映射为 Settings 内可直接展示的说明。 */
internal fun gatewayAddressErrorLabel(error: GatewayServerAddressError): String = when (error) {
    GatewayServerAddressError.EMPTY -> "Enter a Gateway address."
    GatewayServerAddressError.MISSING_SCHEME -> "Include http:// or https://."
    GatewayServerAddressError.UNSUPPORTED_SCHEME -> "Only http:// and https:// are supported."
    GatewayServerAddressError.INVALID_URL -> "Enter a valid Gateway address."
    GatewayServerAddressError.MISSING_HOST -> "The Gateway address must include a host."
    GatewayServerAddressError.EMBEDDED_CREDENTIALS -> "Do not include credentials in the address."
    GatewayServerAddressError.QUERY_NOT_ALLOWED -> "Query parameters are not allowed."
    GatewayServerAddressError.FRAGMENT_NOT_ALLOWED -> "Fragments are not allowed."
}

/**
 * Settings Gateway Manage 的提交资格纯规则。
 *
 * 规则故意不比较当前地址：同一 URL 配合新的 Secret 仍是一次完整重配。Secret 只做空白判断，
 * 不 trim、不规范化，避免改变服务端定义的不透明凭据值。
 */
internal fun canSubmitGatewayReconfiguration(
    address: GatewayServerAddressResult,
    bootstrapSecret: String,
    submitting: Boolean,
): Boolean = address is GatewayServerAddressResult.Valid &&
    bootstrapSecret.isNotBlank() &&
    !submitting

@Composable
internal fun SystemPage(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    connectionStatus: String,
    gatewayEndpoint: String,
    onReconnect: () -> Unit,
    onReconfigureGateway: (serverUrl: String, bootstrapSecret: String) -> Unit,
    reconfigurationInProgress: Boolean,
    reconfigurationError: String?,
    reconfigurationSuccessGeneration: Long,
) {
    val payload = state.payload
    val service = state.apiService
    var serverUrl by rememberSaveable(gatewayEndpoint) { mutableStateOf(gatewayEndpoint) }
    // Bootstrap Secret 是一次性提交凭据，禁止使用 rememberSaveable。旋转屏幕或进程恢复后
    // 主动清空比把凭据写入 Bundle 更安全；地址不是秘密，可以保留编辑进度。
    var bootstrapSecret by remember { mutableStateOf("") }
    var editedSinceFailure by remember { mutableStateOf(false) }
    val normalizedAddress = normalizeGatewayServerAddress(serverUrl)
    val addressError = (normalizedAddress as? GatewayServerAddressResult.Invalid)?.error
    val canSubmit = canSubmitGatewayReconfiguration(
        address = normalizedAddress,
        bootstrapSecret = bootstrapSecret,
        submitting = reconfigurationInProgress,
    )

    LaunchedEffect(reconfigurationSuccessGeneration, gatewayEndpoint) {
        if (reconfigurationSuccessGeneration > 0L) {
            // 成功代次而不是地址变化是唯一清空信号：同一 URL 换一整套配置同样属于成功重配。
            serverUrl = gatewayEndpoint
            bootstrapSecret = ""
            editedSinceFailure = false
        }
    }

    SettingsGroup("Server connection") {
        SettingsRow(
            icon = Icons.Outlined.Dns,
            title = "Current Gateway",
            subtitle = connectionStatus,
            // 这里展示 Android 客户端真实使用的入口，不读取服务端内部监听地址。
            value = gatewayEndpointLabel(gatewayEndpoint),
            showChevron = false,
        )
        CardDivider()
        PreferenceBlock(
            title = "Replace Gateway configuration",
            description =
                "The address and bootstrap secret are validated and replaced as one configuration. " +
                    "A failed candidate leaves the current Gateway connected.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        editedSinceFailure = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !reconfigurationInProgress,
                    label = { Text("Gateway address") },
                    supportingText = addressError?.let { error ->
                        { Text(gatewayAddressErrorLabel(error)) }
                    },
                    isError = addressError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = bootstrapSecret,
                    onValueChange = {
                        bootstrapSecret = it
                        editedSinceFailure = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !reconfigurationInProgress,
                    label = { Text("Bootstrap secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (reconfigurationError != null && !editedSinceFailure) {
                    Text(
                        text = reconfigurationError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = {
                        val valid = normalizedAddress as? GatewayServerAddressResult.Valid
                        if (valid != null && bootstrapSecret.isNotBlank()) {
                            editedSinceFailure = false
                            // Secret 是不透明值，只判断 blank，不 trim、不规范化、不写入 UI 状态。
                            onReconfigureGateway(valid.url, bootstrapSecret)
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (reconfigurationInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (reconfigurationInProgress) "Validating..." else "Validate and reconfigure")
                }
                OutlinePillButton(
                    text = "Reconnect current",
                    onClick = onReconnect,
                    enabled = !reconfigurationInProgress,
                    icon = Icons.Outlined.Sync,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    GroupSpacer()
    SettingsGroup("Runtime") {
        SettingsRow(
            icon = Icons.Outlined.FolderOpen,
            title = "Workspace",
            subtitle = shortPath(payload?.runtime?.workspacePath),
            value = "Default workspace",
            showChevron = false,
        )
    }

    GroupSpacer()
    SettingsGroup("API service") {
        PreferenceBlock(
            title = if (service?.running == true) "Running" else "Stopped",
            description = service?.endpoint?.takeIf { it.isNotBlank() }
                ?: "Local API service is unavailable.",
        ) {
            SegmentedSetting(
                options = listOf("Stop", "Start"),
                selectedIndex = if (service?.running == true) 1 else 0,
                onSelected = { index ->
                    if (index == 1 && service?.running != true) viewModel.apiService(true)
                    if (index == 0 && service?.running == true) viewModel.apiService(false)
                },
            )
        }
    }
}

@Composable
internal fun SecurityPage(state: SettingsUiState, viewModel: SettingsViewModel) {
    val payload = state.payload
    val advanced = payload?.advanced
    val currentLocalAccess = advanced?.webuiAllowLocalServiceAccess == true
    val currentAccessMode = advanced?.webuiDefaultAccessMode ?: "default"
    var localAccess by rememberSaveable(currentLocalAccess) { mutableStateOf(currentLocalAccess) }
    var accessMode by rememberSaveable(currentAccessMode) { mutableStateOf(currentAccessMode) }
    val dirty = localAccess != currentLocalAccess || accessMode != currentAccessMode
    val saving = "network" in state.pending
    val nativeSurface = payload?.runtimeSurface == RuntimeSurface.NATIVE

    SettingsGroup(if (nativeSurface) "App safety" else "Web safety") {
        PreferenceBlock(
            title = "Local Service Access",
            description =
                if (nativeSurface) {
                    "Allow Full Access shell commands to reach services on this device."
                } else {
                    "Allow Full Access shell commands to reach localhost services."
                },
        ) {
            ToggleSetting(checked = localAccess, onCheckedChange = { localAccess = it })
        }
        CardDivider()
        PreferenceBlock(
            title = "Default access",
            description =
                if (nativeSurface) {
                    "Used by native chats without a project-specific permission."
                } else {
                    "Used by web chats without a project-specific permission."
                },
        ) {
            SegmentedSetting(
                options = listOf("Default Permission", "Full Access"),
                selectedIndex = if (accessMode == "full") 1 else 0,
                onSelected = { accessMode = if (it == 1) "full" else "default" },
            )
        }
        SettingsSaveFooter(
            dirty = dirty,
            saving = saving,
            pendingRestart = state.restartPendingFor("runtime", "security"),
            error = state.error,
            onSave = { viewModel.network(localAccess, accessMode) },
        )
    }
    Spacer(Modifier.height(20.dp))
    Text(
        text =
            "Web fetches always protect local, private, and metadata services. Core channel safety stays in config.json.",
        modifier = Modifier.padding(horizontal = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
internal fun ImageGenerationPage(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    onOpenProviders: () -> Unit,
    onSave: (ImageGenerationSettingsUpdate) -> Unit,
) {
    val settings = state.payload?.imageGeneration
    if (settings == null) {
        UnavailableSettingsPage("Image generation")
        return
    }

    var enabled by rememberSaveable(settings.enabled) { mutableStateOf(settings.enabled) }
    var provider by rememberSaveable(settings.provider) { mutableStateOf(settings.provider) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var aspect by
        rememberSaveable(settings.defaultAspectRatio) {
            mutableStateOf(settings.defaultAspectRatio)
        }
    var imageSize by
        rememberSaveable(settings.defaultImageSize) { mutableStateOf(settings.defaultImageSize) }
    var maxImages by
        rememberSaveable(settings.maxImagesPerTurn) { mutableStateOf(settings.maxImagesPerTurn) }

    // 服务端返回 provider 列表时，未知 provider 不能静默回退到第一项，
    // 否则会把另一个 provider 的凭据状态和模型误显示到当前配置上。
    // provider 列表非空时严格按当前 provider 匹配；列表为空才使用服务端
    // 给出的整体 configured 状态，避免未知 provider 借用第一项的状态。
    val selectedProvider = settings.providers.firstOrNull { it.name == provider }
    val providerConfigured =
        if (settings.providers.isEmpty()) {
            settings.providerConfigured
        } else {
            selectedProvider?.configured == true
        }
    val dirty =
        enabled != settings.enabled ||
            provider != settings.provider ||
            model != settings.model ||
            aspect != settings.defaultAspectRatio ||
            imageSize != settings.defaultImageSize ||
            maxImages != settings.maxImagesPerTurn
    val saving = "image" in state.pending

    SettingsGroup("Image generation") {
        FormSettingRow("Image generation") {
            ToggleSetting(checked = enabled, onCheckedChange = { enabled = it })
        }
        CardDivider()
        FormSettingRow("Image provider") {
            PillPicker(
                value = provider,
                options = settings.providers.map { it.name to it.label }.withCurrent(provider),
                showProviderLogos = showBrandLogos,
                onSelected = { next ->
                    provider = next
                    settings.providers
                        .firstOrNull { it.name == next }
                        ?.let { row ->
                            model = row.defaultModel ?: row.models.firstOrNull() ?: model
                        }
                },
            )
        }
        CardDivider()
        FormSettingRow(
            title = "Provider status",
            description = "Image generation reuses provider credentials from Providers.",
        ) {
            StatusPill(
                text = if (providerConfigured) "Configured" else "Not configured",
                positive = providerConfigured,
            )
            if (!providerConfigured) {
                Spacer(Modifier.height(9.dp))
                OutlinePillButton("Configure provider", onOpenProviders)
            }
        }
        CardDivider()
        ReadOnlyFormRow(
            title = "Provider base",
            value =
                selectedProvider?.apiBase
                    ?: selectedProvider?.defaultApiBase
                    ?: selectedProvider?.name
                    ?: "Not available",
        )
    }

    GroupSpacer()
    SettingsGroup("Defaults") {
        FormSettingRow("Image model") {
            ModelIdPicker(
                provider = provider,
                providerConfigured = providerConfigured,
                showProviderLogos = showBrandLogos,
                value = model,
                models = selectedProvider?.models.orEmpty(),
                onSelected = { model = it },
            )
        }
        CardDivider()
        FormSettingRow("Default aspect") {
            PillPicker(
                value = aspect,
                options = IMAGE_ASPECT_RATIOS.map { it to it }.withCurrent(aspect),
                onSelected = { aspect = it },
            )
        }
        CardDivider()
        FormSettingRow("Default size") {
            PillPicker(
                value = imageSize,
                options = IMAGE_SIZES.map { it to it }.withCurrent(imageSize),
                onSelected = { imageSize = it },
            )
        }
        CardDivider()
        FormSettingRow("Max images per turn") {
            NumberStepper(value = maxImages, range = 1..8, onValueChange = { maxImages = it })
        }
        CardDivider()
        ReadOnlyFormRow("Save directory", settings.saveDir.ifBlank { "Not available" })
        SettingsSaveFooter(
            dirty = dirty,
            saving = saving,
            pendingRestart = state.restartPendingFor("image", "runtime"),
            disabledMessage =
                if (enabled && !providerConfigured) {
                    "Configure this provider before enabling image generation."
                } else {
                    null
                },
            error = state.error,
            onSave = {
                onSave(
                    ImageGenerationSettingsUpdate(
                        enabled = enabled,
                        provider = provider,
                        model = model.trim(),
                        defaultAspectRatio = aspect,
                        defaultImageSize = imageSize,
                        maxImagesPerTurn = maxImages,
                    )
                )
            },
        )
    }
}

@Composable
internal fun TranscriptionPage(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    onOpenProviders: () -> Unit,
    onSave: (TranscriptionSettingsUpdate) -> Unit,
) {
    val settings = state.payload?.transcription
    if (settings == null) {
        // 与 Image/Web section 保持一致：网关没有暴露 transcription 配置时，
        // 不展示可编辑的本地默认值，避免用户误保存一份虚构配置。
        UnavailableSettingsPage("Voice input")
        return
    }

    var enabled by rememberSaveable(settings.enabled) { mutableStateOf(settings.enabled) }
    var provider by rememberSaveable(settings.provider) { mutableStateOf(settings.provider) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var language by
        rememberSaveable(settings.language) { mutableStateOf(settings.language.orEmpty()) }
    var maxDuration by
        rememberSaveable(settings.maxDurationSec) { mutableStateOf(settings.maxDurationSec) }
    var maxUpload by rememberSaveable(settings.maxUploadMb) { mutableStateOf(settings.maxUploadMb) }

    val selectedProvider = settings.providers.firstOrNull { it.name == provider }
    val providerConfigured =
        if (settings.providers.isEmpty()) {
            settings.providerConfigured
        } else {
            selectedProvider?.configured == true
        }
    val dirty =
        enabled != settings.enabled ||
            provider != settings.provider ||
            model != settings.model ||
            language != settings.language.orEmpty() ||
            maxDuration != settings.maxDurationSec ||
            maxUpload != settings.maxUploadMb
    val saving = "voice" in state.pending

    SettingsGroup("Voice input") {
        FormSettingRow(
            title = "Transcription",
            description =
                "Transcribe microphone input before sending it. Chat channel voice messages use the same settings.",
        ) {
            ToggleSetting(checked = enabled, onCheckedChange = { enabled = it })
        }
        CardDivider()
        FormSettingRow("Provider") {
            PillPicker(
                value = provider,
                options = settings.providers.map { it.name to it.label }.withCurrent(provider),
                showProviderLogos = showBrandLogos,
                onSelected = { provider = it },
            )
        }
        CardDivider()
        FormSettingRow(
            title = "Provider status",
            description = "API keys stay under providers, not in transcription settings.",
        ) {
            StatusPill(
                text = if (providerConfigured) "Configured" else "Not configured",
                positive = providerConfigured,
            )
            if (!providerConfigured) {
                Spacer(Modifier.height(9.dp))
                OutlinePillButton("Configure provider", onOpenProviders)
            }
        }
        CardDivider()
        FormSettingRow(
            title = "Model",
            description =
                "Leave as the resolved default unless your provider needs a custom model id.",
        ) {
            PillTextField(value = model, onValueChange = { model = it })
        }
        CardDivider()
        FormSettingRow(
            title = "Language",
            description = "Optional ISO-639 hint such as en, zh, ja, or ko.",
        ) {
            PillTextField(value = language, onValueChange = { language = it }, placeholder = "Auto")
        }
        CardDivider()
        FormSettingRow("Limits") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumberStepper(
                    value = maxDuration,
                    range = 1..600,
                    suffix = "s",
                    onValueChange = { maxDuration = it },
                )
                NumberStepper(
                    value = maxUpload,
                    range = 1..100,
                    suffix = "MB",
                    onValueChange = { maxUpload = it },
                )
            }
        }
        SettingsSaveFooter(
            dirty = dirty,
            saving = saving,
            pendingRestart = state.restartPendingFor("runtime", "voice", "transcription"),
            error = state.error,
            onSave = {
                onSave(
                    TranscriptionSettingsUpdate(
                        enabled = enabled,
                        provider = provider,
                        model = model.trim(),
                        language = language.trim(),
                        maxDurationSec = maxDuration,
                        maxUploadMb = maxUpload,
                    )
                )
            },
        )
    }
}

@Composable
internal fun WebSearchPage(
    state: SettingsUiState,
    showBrandLogos: Boolean,
    onSave: (WebSearchSettingsUpdate) -> Unit,
) {
    val settings = state.payload?.webSearch
    val web = state.payload?.web
    if (settings == null || web == null) {
        UnavailableSettingsPage("Web search")
        return
    }

    var provider by rememberSaveable(settings.provider) { mutableStateOf(settings.provider) }
    var apiKey by rememberSaveable(settings.provider) { mutableStateOf("") }
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl.orEmpty()) }
    var maxResults by rememberSaveable(settings.maxResults) { mutableStateOf(settings.maxResults) }
    var timeout by rememberSaveable(settings.timeout) { mutableStateOf(settings.timeout) }
    var useJinaReader by
        rememberSaveable(web.fetch.useJinaReader) { mutableStateOf(web.fetch.useJinaReader) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var keyEditing by rememberSaveable { mutableStateOf(false) }
    var wasSaving by remember { mutableStateOf(false) }

    val selectedProvider =
        settings.providers.firstOrNull { it.name == provider } ?: settings.providers.firstOrNull()
    val acceptsApiKey =
        selectedProvider?.credential == "api_key" ||
            selectedProvider?.credential == "optional_api_key"
    val requiresApiKey = selectedProvider?.credential == "api_key"
    val hasExistingSecret =
        acceptsApiKey && provider == settings.provider && !settings.apiKeyHint.isNullOrBlank()
    val showKeyInput = acceptsApiKey && (!hasExistingSecret || keyEditing)
    val missingCredential =
        (requiresApiKey && apiKey.isBlank() && !hasExistingSecret) ||
            (selectedProvider?.credential == "base_url" && baseUrl.isBlank())
    val dirty =
        provider != settings.provider ||
            apiKey.isNotBlank() ||
            baseUrl != settings.baseUrl.orEmpty() ||
            maxResults != settings.maxResults ||
            timeout != settings.timeout ||
            useJinaReader != web.fetch.useJinaReader
    val saving = "web" in state.pending

    LaunchedEffect(saving) {
        if (wasSaving && !saving && state.error == null) {
            apiKey = ""
            keyEditing = false
            showApiKey = false
        }
        wasSaving = saving
    }

    SettingsGroup("Web search") {
        FormSettingRow("Provider") {
            PillPicker(
                value = provider,
                options = settings.providers.map { it.name to it.label }.withCurrent(provider),
                showProviderLogos = showBrandLogos,
                onSelected = {
                    provider = it
                    apiKey = ""
                    keyEditing = false
                    showApiKey = false
                    baseUrl = if (it == settings.provider) settings.baseUrl.orEmpty() else ""
                },
            )
        }
        if (selectedProvider?.credential == "none") {
            CardDivider()
            FormSettingRow("Credentials") { StatusPill("No credential required", positive = true) }
        }
        if (acceptsApiKey) {
            CardDivider()
            FormSettingRow(
                title = "API key",
                description = "Stored by the gateway and never shown in full again.",
            ) {
                if (showKeyInput) {
                    SecretPillTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        placeholder =
                            if (hasExistingSecret) "Enter a replacement key" else "Enter API key",
                        visible = showApiKey,
                        onToggleVisibility = { showApiKey = !showApiKey },
                    )
                } else {
                    StoredSecretField(
                        hint = settings.apiKeyHint ?: "Configured",
                        onEdit = { keyEditing = true },
                    )
                }
            }
        }
        if (selectedProvider?.credential == "base_url") {
            CardDivider()
            FormSettingRow(
                title = "Base URL",
                description = "Endpoint used by this search provider.",
            ) {
                PillTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = "https://…",
                )
            }
        }
    }

    GroupSpacer()
    SettingsGroup("Behavior") {
        FormSettingRow("Max results") {
            NumberStepper(value = maxResults, range = 1..10, onValueChange = { maxResults = it })
        }
        CardDivider()
        FormSettingRow("Timeout") {
            NumberStepper(
                value = timeout,
                range = 1..120,
                suffix = "s",
                onValueChange = { timeout = it },
            )
        }
        CardDivider()
        FormSettingRow(
            title = "Jina reader",
            description = "Use Jina Reader for web_fetch when available.",
        ) {
            ToggleSetting(checked = useJinaReader, onCheckedChange = { useJinaReader = it })
        }
        SettingsSaveFooter(
            dirty = dirty,
            saving = saving,
            pendingRestart = state.restartPendingFor("runtime", "browser", "web"),
            disabledMessage =
                if (missingCredential) "Enter the credential required by this provider." else null,
            error = state.error,
            onSave = {
                onSave(
                    WebSearchSettingsUpdate(
                        provider = provider,
                        apiKey =
                            apiKey.trim().let { key ->
                                when {
                                    key.isNotEmpty() -> key
                                    selectedProvider?.credential == "optional_api_key" &&
                                        keyEditing -> ""
                                    else -> null
                                }
                            },
                        baseUrl =
                            baseUrl.trim().takeIf { selectedProvider?.credential == "base_url" },
                        maxResults = maxResults,
                        timeout = timeout,
                        useJinaReader = useJinaReader,
                    )
                )
            },
        )
    }
}

@Composable
internal fun UnavailableSettingsPage(title: String) {
    SettingsGroup(title) {
        PreferenceBlock(
            title = "Unavailable",
            description = "This gateway did not expose this settings section.",
        )
    }
}
