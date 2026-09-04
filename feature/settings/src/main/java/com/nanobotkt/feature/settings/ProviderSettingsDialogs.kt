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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        title = { Text(stringResource(R.string.settings_edit_provider_title, provider.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCustom) {
                    OutlinedTextField(
                        displayName,
                        { displayName = it },
                        label = { Text(stringResource(R.string.settings_provider_name)) },
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
                                if (provider.apiKeyHint.isNullOrBlank()) {
                                    stringResource(R.string.settings_enter_api_key)
                                } else {
                                    stringResource(R.string.settings_replacement_api_key)
                                },
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
                                Text(stringResource(R.string.settings_keep_existing))
                            }
                            if (!provider.apiKeyHint.isNullOrBlank()) {
                                TextButton(
                                    onClick = {
                                        clearApiKey = true
                                        editingApiKey = false
                                        apiKey = ""
                                    }
                                ) {
                                    Text(stringResource(R.string.settings_clear), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    } else {
                        StoredSecretField(
                            hint = provider.apiKeyHint ?: stringResource(R.string.settings_configured),
                            onEdit = { editingApiKey = true },
                        )
                        TextButton(onClick = { clearApiKey = true }) {
                            Text(stringResource(R.string.settings_clear_stored_key), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (provider.authType != "oauth") {
                    OutlinedTextField(
                        apiBase,
                        { apiBase = it },
                        label = { Text(stringResource(R.string.settings_api_base)) },
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
                                label = { Text(stringResource(R.string.settings_api_type)) },
                                singleLine = true,
                            )
                        "proxy" ->
                            OutlinedTextField(
                                proxy,
                                { proxy = it },
                                label = { Text(stringResource(R.string.settings_proxy)) },
                                singleLine = true,
                            )
                        "thinking_style" ->
                            OutlinedTextField(
                                thinkingStyle,
                                { thinkingStyle = it },
                                label = { Text(stringResource(R.string.settings_thinking_style)) },
                                singleLine = true,
                            )
                        "region" ->
                            OutlinedTextField(
                                region,
                                { region = it },
                                label = { Text(stringResource(R.string.settings_region)) },
                                singleLine = true,
                            )
                        "profile" ->
                            OutlinedTextField(
                                profile,
                                { profile = it },
                                label = { Text(stringResource(R.string.settings_profile)) },
                                singleLine = true,
                            )
                        "extra_headers" ->
                            OutlinedTextField(
                                extraHeaders,
                                { extraHeaders = it },
                                label = { Text(stringResource(R.string.settings_extra_headers_json)) },
                                minLines = 2,
                            )
                        "extra_body" ->
                            OutlinedTextField(
                                extraBody,
                                { extraBody = it },
                                label = { Text(stringResource(R.string.settings_extra_body_json)) },
                                minLines = 2,
                            )
                        "extra_query" ->
                            OutlinedTextField(
                                extraQuery,
                                { extraQuery = it },
                                label = { Text(stringResource(R.string.settings_extra_query_json)) },
                                minLines = 2,
                            )
                        else ->
                            Text(
                                stringResource(R.string.settings_unsupported_advanced_field, field),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
                if (provider.oauthLoginSupported == true) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.settings_oauth),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!provider.oauthAccount.isNullOrBlank()) {
                        Text(
                            stringResource(R.string.settings_signed_in_as, provider.oauthAccount.orEmpty()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onOAuthLogout, enabled = !oauthPending) {
                            Text(
                                if (oauthPending) stringResource(R.string.settings_signing_out)
                                else stringResource(R.string.settings_sign_out)
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.settings_interactive_oauth_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onOAuthLogin, enabled = !oauthPending) {
                            Text(
                                if (oauthPending) stringResource(R.string.settings_starting)
                                else stringResource(R.string.settings_start_oauth_login)
                            )
                        }
                    }
                    if (oauth?.authorizationUrl != null) {
                        Text(
                            stringResource(R.string.settings_open_url_in_browser),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            oauth.authorizationUrl.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedTextField(
                            oauthCode,
                            { oauthCode = it },
                            label = { Text(stringResource(R.string.settings_authorization_code)) },
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
                            Text(
                            if (oauthPending) stringResource(R.string.settings_completing)
                            else stringResource(R.string.settings_complete_oauth_login)
                        )
                        }
                    }
                }
                if (!state.error.isNullOrBlank())
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                Text(
                    if (saving) stringResource(R.string.settings_saving_ellipsis)
                    else stringResource(R.string.settings_save)
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.settings_cancel)) } },
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
        title = { Text(stringResource(R.string.settings_add_custom_provider)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(R.string.settings_provider_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    apiBase,
                    { apiBase = it },
                    label = { Text(stringResource(R.string.settings_api_base)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    apiKey,
                    { apiKey = it },
                    label = { Text(stringResource(R.string.settings_api_key_optional)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    proxy,
                    { proxy = it },
                    label = { Text(stringResource(R.string.settings_proxy_optional)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    thinkingStyle,
                    { thinkingStyle = it },
                    label = { Text(stringResource(R.string.settings_thinking_style_optional)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    extraHeaders,
                    { extraHeaders = it },
                    label = { Text(stringResource(R.string.settings_extra_headers_json_optional)) },
                    minLines = 2,
                )
                OutlinedTextField(
                    extraBody,
                    { extraBody = it },
                    label = { Text(stringResource(R.string.settings_extra_body_json_optional)) },
                    minLines = 2,
                )
                OutlinedTextField(
                    extraQuery,
                    { extraQuery = it },
                    label = { Text(stringResource(R.string.settings_extra_query_json_optional)) },
                    minLines = 2,
                )
                if (!error.isNullOrBlank())
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                Text(
                    if (saving) stringResource(R.string.settings_saving_ellipsis)
                    else stringResource(R.string.settings_create)
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
