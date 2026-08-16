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
import com.nanobotkt.core.network.GatewayServerAddressResult
import com.nanobotkt.core.network.normalizeGatewayServerAddress

@Composable
fun AuthScreen(
    state: AuthState.Configuration,
    onSubmit: (GatewayConnectionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverUrl by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
    // Secret 只存在当前 Composable 内存中。禁止 rememberSaveable，避免进入 Bundle、
    // SavedStateHandle 或进程恢复快照；页面重建后要求用户重新输入是有意的安全行为。
    var secret by remember { mutableStateOf("") }
    var editedSinceFailure by remember { mutableStateOf(false) }
    val normalizedAddress = normalizeGatewayServerAddress(serverUrl)
    val canSubmit =
        normalizedAddress is GatewayServerAddressResult.Valid &&
            secret.isNotBlank() &&
            !state.submitting
    val submit = {
        val address = normalizedAddress as? GatewayServerAddressResult.Valid
        if (address != null && secret.isNotBlank() && !state.submitting) {
            editedSinceFailure = false
            // Secret 是不透明凭据，只判断 blank，不做 trim 或其他静默改写。
            onSubmit(GatewayConnectionConfig(address.url, secret))
        }
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

                val addressError = (normalizedAddress as? GatewayServerAddressResult.Invalid)
                    ?.error
                    ?.takeUnless { it == GatewayServerAddressError.EMPTY }
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        editedSinceFailure = true
                    },
                    enabled = !state.submitting,
                    label = { Text(stringResource(R.string.gateway_address)) },
                    supportingText = addressError?.let { error ->
                        { Text(addressErrorMessage(error)) }
                    },
                    isError = addressError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = {
                        secret = it
                        editedSinceFailure = true
                    },
                    enabled = !state.submitting,
                    label = { Text(stringResource(R.string.bootstrap_secret)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error
                    ?.takeIf { !editedSinceFailure }
                    ?.let { error ->
                        Text(
                            text = gatewayConfigurationErrorMessage(error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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

@Composable
fun gatewayConfigurationErrorMessage(error: GatewayConfigurationError): String = when (error) {
    is GatewayConfigurationError.InvalidAddress -> addressErrorMessage(error.reason)
    GatewayConfigurationError.MissingSecret -> stringResource(R.string.error_missing_secret)
    GatewayConfigurationError.AuthenticationRejected -> stringResource(R.string.error_configuration_rejected)
    GatewayConfigurationError.Timeout -> stringResource(R.string.error_gateway_timeout)
    GatewayConfigurationError.NetworkUnavailable -> stringResource(R.string.error_gateway_network)
    GatewayConfigurationError.HtmlResponse -> stringResource(R.string.error_gateway_html)
    GatewayConfigurationError.NonJsonResponse -> stringResource(R.string.error_gateway_non_json)
    GatewayConfigurationError.InvalidResponse -> stringResource(R.string.error_gateway_invalid_response)
    is GatewayConfigurationError.Http -> stringResource(R.string.error_gateway_http, error.status)
    GatewayConfigurationError.StorageFailure -> stringResource(R.string.error_gateway_storage)
    GatewayConfigurationError.Cancelled -> stringResource(R.string.error_gateway_cancelled)
    is GatewayConfigurationError.Unknown -> error.message ?: stringResource(R.string.error_gateway_unknown)
}

@Composable
internal fun addressErrorMessage(error: GatewayServerAddressError): String = when (error) {
    GatewayServerAddressError.EMPTY -> stringResource(R.string.error_address_empty)
    GatewayServerAddressError.MISSING_SCHEME -> stringResource(R.string.error_address_scheme)
    GatewayServerAddressError.UNSUPPORTED_SCHEME -> stringResource(R.string.error_address_unsupported_scheme)
    GatewayServerAddressError.INVALID_URL -> stringResource(R.string.error_address_invalid)
    GatewayServerAddressError.MISSING_HOST -> stringResource(R.string.error_address_host)
    GatewayServerAddressError.EMBEDDED_CREDENTIALS -> stringResource(R.string.error_address_credentials)
    GatewayServerAddressError.QUERY_NOT_ALLOWED -> stringResource(R.string.error_address_query)
    GatewayServerAddressError.FRAGMENT_NOT_ALLOWED -> stringResource(R.string.error_address_fragment)
}
