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

/** Provider 凭据、OAuth 与自定义 Provider 编辑弹窗。 */
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
