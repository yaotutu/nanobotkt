package com.nanobotkt.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json

/** 模型预设、Provider 配置及编辑弹窗。网络写操作仍统一委托给 SettingsViewModel。 */
@Composable
internal fun ModelsPage(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    showBrandLogos: Boolean,
) {
    val payload = state.payload
    var showCreateModel by rememberSaveable { mutableStateOf(false) }
    var editingModelName by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingModelName by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateProvider by rememberSaveable { mutableStateOf(false) }
    var editingProviderName by rememberSaveable { mutableStateOf<String?>(null) }
    val editingModel = payload?.modelPresets?.firstOrNull { it.name == editingModelName }
    val editingProvider = payload?.providers?.firstOrNull { it.name == editingProviderName }

    SettingsGroup("Models") {
        if (payload?.modelPresets.isNullOrEmpty()) {
            EmptySettingsRow(
                icon = Icons.Outlined.SmartToy,
                title = "No models available",
                subtitle = "Connect to the gateway to load model presets.",
                action = "Refresh",
                onClick = viewModel::refresh,
            )
        } else {
            payload?.modelPresets.orEmpty().forEachIndexed { index, preset ->
                SettingsRow(
                    icon = Icons.Outlined.SmartToy,
                    leadingProvider = preset.resolvedProvider ?: preset.provider,
                    showBrandLogos = showBrandLogos,
                    title = preset.label,
                    subtitle = "${preset.provider} · ${preset.model}",
                    value = if (preset.active) "Active" else null,
                    selected = preset.active,
                    // 保留原有语义：点击整行仍然切换当前活动模型。
                    onClick = { viewModel.update(SettingsUpdate(modelPreset = preset.name)) },
                    trailingContent =
                        if (!preset.isDefault) {
                            {
                                TextButton(onClick = { editingModelName = preset.name }) {
                                    Icon(
                                        Icons.Outlined.Edit,
                                        contentDescription = "Edit model",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                TextButton(onClick = { deletingModelName = preset.name }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Delete model",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        } else null,
                )
                if (index != payload.modelPresets.lastIndex) CardDivider()
            }
        }
        // 创建动作放在列表底部，保持原有“点击模型即切换默认模型”的行为不变。
        Button(
            onClick = { showCreateModel = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            enabled = !state.pending.contains("model-configuration"),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add model configuration")
        }
    }

    if (payload?.modelCallOrderEditable == false && payload.modelPresets.isNotEmpty()) {
        GroupSpacer()
        SettingsGroup("Legacy model configuration") {
            PreferenceBlock(
                title = "Migrate model configurations",
                description =
                    "Convert the legacy primary and fallback settings into named model presets.",
            )
            TextButton(
                onClick = viewModel::migrateModelConfigurations,
                enabled = "model-configuration-migration" !in state.pending,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    if ("model-configuration-migration" in state.pending) "Migrating…"
                    else "Migrate"
                )
            }
        }
    }

    if (payload?.modelCallOrderEditable == true) {
        val order =
            payload.modelCallOrder.ifEmpty {
                payload.modelPresets.filterNot { it.isDefault }.map { it.name }
            }
        GroupSpacer()
        SettingsGroup("Model call order") {
            PreferenceBlock(
                title = "Primary and fallback models",
                description =
                    "The first model is used first; following entries are tried when a request fails.",
            )
            order.forEachIndexed { index, name ->
                val preset = payload.modelPresets.firstOrNull { it.name == name }
                SettingsRow(
                    icon = Icons.Outlined.Tune,
                    leadingProvider = preset?.resolvedProvider ?: preset?.provider,
                    showBrandLogos = showBrandLogos,
                    title = preset?.label ?: name,
                    subtitle = if (preset == null) "Unknown preset: $name" else preset.model,
                    value = if (index == 0) "Primary" else "Fallback ${index}",
                    showChevron = false,
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val moved =
                                        order.toMutableList().apply {
                                            add(index - 1, removeAt(index))
                                        }
                                    viewModel.updateModelCallOrder(ModelCallOrderUpdate(moved))
                                },
                                enabled = index > 0 && "model-call-order" !in state.pending,
                            ) {
                                Icon(
                                    Icons.Outlined.ArrowUpward,
                                    contentDescription = "Move up",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    val moved =
                                        order.toMutableList().apply {
                                            add(index + 1, removeAt(index))
                                        }
                                    viewModel.updateModelCallOrder(ModelCallOrderUpdate(moved))
                                },
                                enabled =
                                    index < order.lastIndex && "model-call-order" !in state.pending,
                            ) {
                                Icon(
                                    Icons.Outlined.ArrowDownward,
                                    contentDescription = "Move down",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                )
                if (index != order.lastIndex) CardDivider()
            }
        }
    }

    GroupSpacer()
    SettingsGroup("Providers") {
        if (payload?.providers.isNullOrEmpty()) {
            EmptySettingsRow(
                icon = Icons.Outlined.Dns,
                title = "Providers unavailable",
                subtitle = "Provider settings could not be loaded.",
                action = "Refresh",
                onClick = viewModel::refresh,
            )
        } else {
            payload?.providers.orEmpty().forEachIndexed { index, provider ->
                SettingsRow(
                    icon = Icons.Outlined.Dns,
                    leadingProvider = provider.name,
                    showBrandLogos = showBrandLogos,
                    title = provider.label,
                    subtitle =
                        listOfNotNull(
                                if (provider.configured) "Configured" else "Not configured",
                                provider.oauthAccount?.let { "OAuth: $it" },
                            )
                            .joinToString(" · "),
                    value = if (provider.configured) "Connected" else null,
                    // 点击 Provider 仍然加载模型目录；编辑入口单独放在尾部。
                    onClick = { viewModel.providerModels(provider.name) },
                    trailingContent = {
                        TextButton(onClick = { editingProviderName = provider.name }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Edit provider",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
                if (index != payload.providers.lastIndex) CardDivider()
            }
        }
        Button(
            onClick = { showCreateProvider = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            enabled = !state.pending.contains("provider:create"),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add custom provider")
        }
    }

    state.providerModels?.let { catalog ->
        GroupSpacer()
        SettingsGroup(catalog.label) {
            if (catalog.models.isEmpty()) {
                EmptySettingsRow(
                    Icons.Outlined.Search,
                    "No models found",
                    catalog.message ?: "This provider returned no models.",
                )
            } else {
                catalog.models.forEachIndexed { index, model ->
                    SettingsRow(
                        icon = Icons.Outlined.SmartToy,
                        leadingProvider = catalog.provider,
                        showBrandLogos = showBrandLogos,
                        title = model.label ?: model.id,
                        subtitle = model.id,
                        showChevron = false,
                    )
                    if (index != catalog.models.lastIndex) CardDivider()
                }
            }
        }
    }

    if (showCreateModel) {
        ModelConfigurationDialog(
            providers = payload?.providers.orEmpty().map { it.name to it.label },
            initial = null,
            saving = state.pending.contains("model-configuration"),
            error = state.error,
            onDismiss = {
                if (!state.pending.contains("model-configuration")) showCreateModel = false
            },
            onConfirm = { form ->
                viewModel.createModelConfiguration(
                    ModelConfigurationCreate(
                        label = form.label,
                        name = form.name,
                        model = form.model,
                        provider = form.provider,
                        maxTokens = form.maxTokens,
                        contextWindowTokens = form.contextWindowTokens,
                        temperature = form.temperature,
                        reasoningEffort = form.reasoningEffort,
                    )
                )
                showCreateModel = false
            },
        )
    }
    editingModel?.let { preset ->
        ModelConfigurationDialog(
            providers = payload?.providers.orEmpty().map { it.name to it.label },
            initial = preset,
            saving = state.pending.contains("model-configuration"),
            error = state.error,
            onDismiss = {
                if (!state.pending.contains("model-configuration")) editingModelName = null
            },
            onConfirm = { form ->
                viewModel.updateModelConfiguration(
                    ModelConfigurationUpdate(
                        name = preset.name,
                        label = form.label,
                        model = form.model,
                        provider = form.provider,
                        maxTokens = form.maxTokens,
                        contextWindowTokens = form.contextWindowTokens,
                        temperature = form.temperature,
                        // 空字符串是服务端约定的“清除 reasoning effort”。
                        reasoningEffort = form.reasoningEffort.orEmpty(),
                    )
                )
                editingModelName = null
            },
        )
    }
    editingProvider?.let { provider ->
        ProviderEditDialog(
            provider = provider,
            state = state,
            saving = state.pending.contains("provider:${provider.name}"),
            onDismiss = {
                if (!state.pending.contains("provider:${provider.name}")) editingProviderName = null
            },
            onSave = { update ->
                viewModel.provider(update)
                editingProviderName = null
            },
            onOAuthLogin = { viewModel.oauth(provider.name) },
            onOAuthComplete = { flowId, code ->
                viewModel.oauthComplete(provider.name, flowId, code)
            },
            onOAuthLogout = { viewModel.oauthLogout(provider.name) },
        )
    }
    if (showCreateProvider) {
        CustomProviderDialog(
            saving = state.pending.contains("provider:create"),
            error = state.error,
            onDismiss = {
                if (!state.pending.contains("provider:create")) showCreateProvider = false
            },
            onConfirm = { create ->
                viewModel.createProvider(create)
                showCreateProvider = false
            },
        )
    }
    deletingModelName?.let { name ->
        val preset = payload?.modelPresets?.firstOrNull { it.name == name }
        AlertDialog(
            onDismissRequest = {
                if (!state.pending.contains("model-configuration")) deletingModelName = null
            },
            title = { Text("Delete model configuration?") },
            text = {
                Text(
                    "${preset?.label ?: name} will be removed from the gateway. A model in the call order must be moved first."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModelConfiguration(name)
                        deletingModelName = null
                    },
                    enabled = !state.pending.contains("model-configuration"),
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingModelName = null }) { Text("Cancel") }
            },
        )
    }
}

internal data class ModelConfigurationForm(
    val label: String,
    val name: String?,
    val model: String,
    val provider: String,
    val maxTokens: Int?,
    val contextWindowTokens: Int?,
    val temperature: Double?,
    val reasoningEffort: String?,
)

@Composable
internal fun ModelConfigurationDialog(
    providers: List<Pair<String, String>>,
    initial: com.nanobotkt.core.model.ModelPresetInfo?,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (ModelConfigurationForm) -> Unit,
) {
    val editing = initial != null
    var label by rememberSaveable(initial?.name) { mutableStateOf(initial?.label.orEmpty()) }
    var name by rememberSaveable(initial?.name) { mutableStateOf(initial?.name.orEmpty()) }
    var model by rememberSaveable(initial?.name) { mutableStateOf(initial?.model.orEmpty()) }
    var provider by
        rememberSaveable(initial?.name) {
            mutableStateOf(initial?.provider ?: providers.firstOrNull()?.first.orEmpty())
        }
    var maxTokens by
        rememberSaveable(initial?.name) {
            mutableStateOf(initial?.maxTokens?.takeIf { it > 0 }?.toString().orEmpty())
        }
    var contextWindow by
        rememberSaveable(initial?.name) {
            mutableStateOf(initial?.contextWindowTokens?.takeIf { it > 0 }?.toString().orEmpty())
        }
    var temperature by
        rememberSaveable(initial?.name) {
            // 0.0 是服务端允许的合法值，不能和“未设置”混为一谈。
            mutableStateOf(initial?.temperature?.toString().orEmpty())
        }
    var reasoningEffort by
        rememberSaveable(initial?.name) { mutableStateOf(initial?.reasoningEffort.orEmpty()) }

    val maxTokensValue = maxTokens.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
    val contextWindowValue = contextWindow.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
    val temperatureValue = temperature.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
    val numericValuesValid =
        (maxTokensValue == null || maxTokensValue > 0) &&
            (contextWindowValue == null || contextWindowValue > 0) &&
            (temperatureValue == null || temperatureValue in 0.0..2.0)
    val valid =
        label.isNotBlank() && model.isNotBlank() && provider.isNotBlank() && numericValuesValid
    val reasoningOptions = initial?.reasoningEffortValues.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Edit model configuration" else "Add model configuration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("Label") },
                    singleLine = true,
                )
                if (!editing) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        name,
                        {},
                        label = { Text("Name") },
                        singleLine = true,
                        readOnly = true,
                    )
                }
                OutlinedTextField(
                    model,
                    { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                )
                PillPicker(
                    value = provider,
                    options = (listOf("auto" to "Auto") + providers).withCurrent(provider),
                    onSelected = { provider = it },
                )
                OutlinedTextField(
                    maxTokens,
                    { maxTokens = it },
                    label = { Text("Max tokens (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    contextWindow,
                    { contextWindow = it },
                    label = { Text("Context window (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    temperature,
                    { temperature = it },
                    label = { Text("Temperature (0–2, optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (reasoningOptions.isNotEmpty()) {
                    PillPicker(
                        value = reasoningEffort,
                        options =
                            reasoningOptions.map { it to if (it.isBlank()) "Default" else it },
                        onSelected = { reasoningEffort = it },
                    )
                } else {
                    OutlinedTextField(
                        reasoningEffort,
                        { reasoningEffort = it },
                        label = { Text("Reasoning effort (optional)") },
                        singleLine = true,
                    )
                }
                if (!numericValuesValid) {
                    Text(
                        "Numeric values are invalid. Check the allowed ranges.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                if (!error.isNullOrBlank())
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ModelConfigurationForm(
                            label = label.trim(),
                            name = name.trim().takeIf(String::isNotBlank),
                            model = model.trim(),
                            provider = provider,
                            maxTokens = maxTokensValue,
                            contextWindowTokens = contextWindowValue,
                            temperature = temperatureValue,
                            reasoningEffort = reasoningEffort.trim().takeIf(String::isNotBlank),
                        )
                    )
                },
                enabled = valid && !saving,
            ) {
                Text(if (saving) "Saving…" else if (editing) "Save" else "Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

/**
 * 将服务端返回的结构化 Provider 字段重新编码为合法 JSON 文本。
 *
 * 不能使用 `Map.toString()`：它生成的是 `{key=value}`，而服务端按 JSON 解析
 * extra_headers/extra_query；用户只修改其他字段时也会把这些旧值一并提交。
 */
internal fun Map<String, String>.toJsonObjectString(): String = Json.encodeToString(this)

/**
 * Provider 编辑器同时覆盖标准 Provider 与自定义 Provider。 服务端只接受 provider 声明的 advanced_fields，因此未知字段仍显示为可编辑文本，
 * 但保存时只提交协议允许的字段，避免客户端构造出服务端无法解析的配置。
 */
@Composable
internal fun ProviderEditDialog(
    provider: com.nanobotkt.core.model.ProviderSettingsInfo,
    state: SettingsUiState,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderUpdate) -> Unit,
    onOAuthLogin: () -> Unit,
    onOAuthComplete: (String, String?) -> Unit,
    onOAuthLogout: () -> Unit,
) {
    val isCustom = provider.isCustom == true
    var displayName by rememberSaveable(provider.name) { mutableStateOf(provider.label) }
    var apiBase by rememberSaveable(provider.name) { mutableStateOf(provider.apiBase.orEmpty()) }
    var apiKey by rememberSaveable(provider.name) { mutableStateOf("") }
    var apiType by rememberSaveable(provider.name) { mutableStateOf(provider.apiType.orEmpty()) }
    var proxy by rememberSaveable(provider.name) { mutableStateOf(provider.proxy.orEmpty()) }
    var thinkingStyle by
        rememberSaveable(provider.name) { mutableStateOf(provider.thinkingStyle.orEmpty()) }
    var region by rememberSaveable(provider.name) { mutableStateOf(provider.region.orEmpty()) }
    var profile by rememberSaveable(provider.name) { mutableStateOf(provider.profile.orEmpty()) }
    var extraHeaders by
        rememberSaveable(provider.name) {
            mutableStateOf(provider.extraHeaders?.toJsonObjectString().orEmpty())
        }
    var extraBody by
        rememberSaveable(provider.name) { mutableStateOf(provider.extraBody?.toString().orEmpty()) }
    var extraQuery by
        rememberSaveable(provider.name) {
            mutableStateOf(provider.extraQuery?.toJsonObjectString().orEmpty())
        }
    var editingApiKey by rememberSaveable(provider.name) { mutableStateOf(false) }
    var clearApiKey by rememberSaveable(provider.name) { mutableStateOf(false) }
    var oauthCode by rememberSaveable(provider.name) { mutableStateOf("") }

    val advanced = provider.advancedFields.orEmpty()
    val oauth = state.oauth?.takeIf { it.provider == provider.name }
    val oauthPending = "oauth:${provider.name}" in state.pending
    val dirty =
        (isCustom && displayName != provider.label) ||
            apiBase != provider.apiBase.orEmpty() ||
            apiType != provider.apiType.orEmpty() ||
            proxy != provider.proxy.orEmpty() ||
            thinkingStyle != provider.thinkingStyle.orEmpty() ||
            region != provider.region.orEmpty() ||
            profile != provider.profile.orEmpty() ||
            extraHeaders != provider.extraHeaders?.toJsonObjectString().orEmpty() ||
            extraBody != provider.extraBody?.toString().orEmpty() ||
            extraQuery != provider.extraQuery?.toJsonObjectString().orEmpty() ||
            editingApiKey && apiKey.isNotBlank() ||
            clearApiKey

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${provider.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCustom) {
                    OutlinedTextField(
                        displayName,
                        { displayName = it },
                        label = { Text("Provider name") },
                        singleLine = true,
                    )
                }
                if (provider.apiKeyRequired != false && provider.authType != "oauth") {
                    if (editingApiKey || provider.apiKeyHint.isNullOrBlank()) {
                        SecretPillTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                clearApiKey = false
                            },
                            placeholder =
                                if (provider.apiKeyHint.isNullOrBlank()) "Enter API key"
                                else "Replacement API key",
                            visible = false,
                            onToggleVisibility = {},
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    editingApiKey = false
                                    apiKey = ""
                                }
                            ) {
                                Text("Keep existing")
                            }
                            if (!provider.apiKeyHint.isNullOrBlank()) {
                                TextButton(
                                    onClick = {
                                        clearApiKey = true
                                        editingApiKey = false
                                        apiKey = ""
                                    }
                                ) {
                                    Text("Clear", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    } else {
                        StoredSecretField(
                            hint = provider.apiKeyHint ?: "Configured",
                            onEdit = { editingApiKey = true },
                        )
                        TextButton(onClick = { clearApiKey = true }) {
                            Text("Clear stored key", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (provider.authType != "oauth") {
                    OutlinedTextField(
                        apiBase,
                        { apiBase = it },
                        label = { Text("API base") },
                        placeholder = { Text(provider.defaultApiBase.orEmpty()) },
                        singleLine = true,
                    )
                }
                advanced.forEach { field ->
                    when (field) {
                        "api_type" ->
                            OutlinedTextField(
                                apiType,
                                { apiType = it },
                                label = { Text("API type") },
                                singleLine = true,
                            )
                        "proxy" ->
                            OutlinedTextField(
                                proxy,
                                { proxy = it },
                                label = { Text("Proxy") },
                                singleLine = true,
                            )
                        "thinking_style" ->
                            OutlinedTextField(
                                thinkingStyle,
                                { thinkingStyle = it },
                                label = { Text("Thinking style") },
                                singleLine = true,
                            )
                        "region" ->
                            OutlinedTextField(
                                region,
                                { region = it },
                                label = { Text("Region") },
                                singleLine = true,
                            )
                        "profile" ->
                            OutlinedTextField(
                                profile,
                                { profile = it },
                                label = { Text("Profile") },
                                singleLine = true,
                            )
                        "extra_headers" ->
                            OutlinedTextField(
                                extraHeaders,
                                { extraHeaders = it },
                                label = { Text("Extra headers (JSON)") },
                                minLines = 2,
                            )
                        "extra_body" ->
                            OutlinedTextField(
                                extraBody,
                                { extraBody = it },
                                label = { Text("Extra body (JSON)") },
                                minLines = 2,
                            )
                        "extra_query" ->
                            OutlinedTextField(
                                extraQuery,
                                { extraQuery = it },
                                label = { Text("Extra query (JSON)") },
                                minLines = 2,
                            )
                        else ->
                            Text(
                                "Unsupported advanced field: $field",
                                color = SecondaryText,
                                fontSize = 12.sp,
                            )
                    }
                }
                if (provider.oauthLoginSupported == true) {
                    HorizontalDivider()
                    Text("OAuth", fontWeight = FontWeight.SemiBold, color = PrimaryText)
                    if (!provider.oauthAccount.isNullOrBlank()) {
                        Text(
                            "Signed in as ${provider.oauthAccount}",
                            color = SecondaryText,
                            fontSize = 12.sp,
                        )
                        TextButton(onClick = onOAuthLogout, enabled = !oauthPending) {
                            Text(if (oauthPending) "Signing out…" else "Sign out")
                        }
                    } else {
                        Text(
                            "This provider uses an interactive OAuth flow.",
                            color = SecondaryText,
                            fontSize = 12.sp,
                        )
                        TextButton(onClick = onOAuthLogin, enabled = !oauthPending) {
                            Text(if (oauthPending) "Starting…" else "Start OAuth login")
                        }
                    }
                    if (oauth?.authorizationUrl != null) {
                        Text("Open this URL in a browser:", color = SecondaryText, fontSize = 12.sp)
                        Text(
                            oauth.authorizationUrl.orEmpty(),
                            color = PrimaryText,
                            fontSize = 11.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedTextField(
                            oauthCode,
                            { oauthCode = it },
                            label = { Text("Authorization code (if requested)") },
                            singleLine = true,
                        )
                        TextButton(
                            onClick = {
                                onOAuthComplete(
                                    oauth.flowId.orEmpty(),
                                    oauthCode.trim().takeIf(String::isNotBlank),
                                )
                            },
                            enabled = !oauthPending && !oauth.flowId.isNullOrBlank(),
                        ) {
                            Text(if (oauthPending) "Completing…" else "Complete OAuth login")
                        }
                    }
                }
                if (!state.error.isNullOrBlank())
                    Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ProviderUpdate(
                            provider = provider.name,
                            displayName = displayName.trim().takeIf { isCustom },
                            apiKey =
                                when {
                                    clearApiKey -> ""
                                    editingApiKey && apiKey.isNotBlank() -> apiKey.trim()
                                    else -> null
                                },
                            apiBase = apiBase.trim().takeIf { provider.authType != "oauth" },
                            apiType = apiType.trim().takeIf { "api_type" in advanced },
                            proxy =
                                proxy.trim().takeIf {
                                    "proxy" in advanced ||
                                        provider.proxy != null ||
                                        provider.authType == "oauth"
                                },
                            thinkingStyle =
                                thinkingStyle.trim().takeIf { "thinking_style" in advanced },
                            region = region.trim().takeIf { "region" in advanced },
                            profile = profile.trim().takeIf { "profile" in advanced },
                            extraHeaders =
                                extraHeaders.trim().takeIf { "extra_headers" in advanced },
                            extraBody =
                                extraBody.trim().takeIf {
                                    "extra_body" in advanced || provider.authType == "oauth"
                                },
                            extraQuery = extraQuery.trim().takeIf { "extra_query" in advanced },
                        )
                    )
                },
                enabled = dirty && !saving && displayName.isNotBlank(),
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
internal fun CustomProviderDialog(
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (CustomProviderCreate) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var apiBase by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var proxy by rememberSaveable { mutableStateOf("") }
    var thinkingStyle by rememberSaveable { mutableStateOf("") }
    var extraHeaders by rememberSaveable { mutableStateOf("") }
    var extraBody by rememberSaveable { mutableStateOf("") }
    var extraQuery by rememberSaveable { mutableStateOf("") }
    val valid = name.isNotBlank() && apiBase.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Provider name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    apiBase,
                    { apiBase = it },
                    label = { Text("API base") },
                    singleLine = true,
                )
                OutlinedTextField(
                    apiKey,
                    { apiKey = it },
                    label = { Text("API key (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    proxy,
                    { proxy = it },
                    label = { Text("Proxy (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    thinkingStyle,
                    { thinkingStyle = it },
                    label = { Text("Thinking style (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    extraHeaders,
                    { extraHeaders = it },
                    label = { Text("Extra headers (JSON object, optional)") },
                    minLines = 2,
                )
                OutlinedTextField(
                    extraBody,
                    { extraBody = it },
                    label = { Text("Extra body (JSON object, optional)") },
                    minLines = 2,
                )
                OutlinedTextField(
                    extraQuery,
                    { extraQuery = it },
                    label = { Text("Extra query (JSON object, optional)") },
                    minLines = 2,
                )
                if (!error.isNullOrBlank())
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        CustomProviderCreate(
                            displayName = name.trim(),
                            apiBase = apiBase.trim(),
                            apiKey = apiKey.trim().takeIf(String::isNotBlank),
                            proxy = proxy.trim().takeIf(String::isNotBlank),
                            thinkingStyle = thinkingStyle.trim().takeIf(String::isNotBlank),
                            extraHeaders = extraHeaders.trim().takeIf(String::isNotBlank),
                            extraBody = extraBody.trim().takeIf(String::isNotBlank),
                            extraQuery = extraQuery.trim().takeIf(String::isNotBlank),
                        )
                    )
                },
                enabled = valid && !saving,
            ) {
                Text(if (saving) "Saving…" else "Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}
