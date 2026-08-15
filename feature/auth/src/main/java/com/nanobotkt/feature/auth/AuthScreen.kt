package com.nanobotkt.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.network.GatewayServerAddressError

@Composable
fun AuthScreen(
    state: AuthState.Authentication,
    initialServerUrl: String,
    onSubmit: (serverUrl: String, secret: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverUrl by rememberSaveable(initialServerUrl) { mutableStateOf(initialServerUrl) }
    // Secret 刻意不从仓库恢复或预填，也不进入 SavedState。页面或进程重建后要求重新输入，
    // 避免候选服务器凭据落入 Bundle、SavedStateHandle 或跨页面意外复用。
    var secret by remember { mutableStateOf("") }
    val canSubmit = serverUrl.isNotBlank() && secret.isNotBlank() && !state.submitting
    val submit = {
        // 地址允许清理用户误输入的外围空白；Secret 是不透明凭据，必须原样交给仓库，
        // 否则合法的首尾空格会在 UI 层被静默改写并造成无法解释的认证失败。
        if (canSubmit) onSubmit(serverUrl.trim(), secret)
    }

    Column(
        modifier = modifier.fillMaxSize().imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.auth_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(R.string.auth_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.authenticationErrorMessage()?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    enabled = !state.submitting,
                    label = { Text(stringResource(R.string.server_address)) },
                    supportingText = { Text(stringResource(R.string.server_address_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    enabled = !state.submitting,
                    label = { Text(stringResource(R.string.bootstrap_secret)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.connect))
                    }
                }
            }
        }
    }
}

/** 把认证仓库的稳定错误类型映射为本地化文案，UI 不解析异常 message。 */
@Composable
private fun AuthState.Authentication.authenticationErrorMessage(): String? = when {
    failed -> stringResource(R.string.auth_rejected)
    connectionError is ServerConnectionError.InvalidAddress -> when (connectionError.reason) {
        GatewayServerAddressError.EMPTY -> stringResource(R.string.server_address_empty)
        GatewayServerAddressError.MISSING_SCHEME -> stringResource(R.string.server_address_scheme_required)
        GatewayServerAddressError.UNSUPPORTED_SCHEME -> stringResource(R.string.server_address_http_only)
        GatewayServerAddressError.EMBEDDED_CREDENTIALS -> stringResource(R.string.server_address_credentials_not_allowed)
        GatewayServerAddressError.QUERY_NOT_ALLOWED,
        GatewayServerAddressError.FRAGMENT_NOT_ALLOWED,
        -> stringResource(R.string.server_address_query_not_allowed)
        GatewayServerAddressError.INVALID_URL,
        GatewayServerAddressError.MISSING_HOST,
        -> stringResource(R.string.server_address_invalid)
    }
    connectionError != null -> stringResource(R.string.connection_validation_failed)
    else -> null
}
