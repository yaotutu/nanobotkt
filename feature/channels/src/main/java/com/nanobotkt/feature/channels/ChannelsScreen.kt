package com.nanobotkt.feature.channels

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.nanobotkt.core.model.ChannelConnectPayload
import com.nanobotkt.core.model.ChannelSetupContractField
import com.nanobotkt.core.model.NanobotChannelInstanceInfo
import com.nanobotkt.core.model.NanobotFeatureInfo

private data class ChannelSelection(
    val feature: NanobotFeatureInfo,
    val instance: NanobotChannelInstanceInfo? = null,
) {
    val instanceId: String? get() = instance?.id
    val key: String get() = channelConnectionKey(feature.name, instanceId)
    val displayName: String get() = instance?.displayName ?: feature.displayName
    val enabled: Boolean get() = instance?.enabled ?: feature.enabled
    val configured: Boolean get() = instance?.configured ?: (feature.configured == true)
    val runtimeStatus: String? get() = instance?.runtimeStatus ?: feature.runtimeStatus
    val configValues: Map<String, String>
        get() = instance?.configValues ?: feature.configValues.orEmpty()
    val configuredFields: List<String>
        get() = instance?.configuredFields ?: feature.configuredFields.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ChannelSelection?>(null) }
    val channels = state.payload?.features.orEmpty()
        .filter { it.type == "channel" || it.capabilities?.contains("channel") == true }
        .flatMap { feature ->
            // 后端返回 instances 时，必须把每个实例作为独立操作目标，避免误操作默认实例。
            feature.instances?.map { instance -> ChannelSelection(feature, instance) }
                ?: listOf(ChannelSelection(feature))
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Channels") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading && state.payload == null) item { CircularProgressIndicator() }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            items(channels, key = { it.key }) { channel ->
                val pending = channelPending(state, channel.feature.name, channel.instanceId)
                ElevatedCard(
                    onClick = { selected = channel },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(channel.displayName) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${channel.feature.status} · " +
                                        (channel.runtimeStatus
                                            ?: if (channel.configured) "configured" else "needs setup"),
                                )
                                (channel.instance?.runtimeError ?: channel.feature.runtimeError)
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { runtimeError ->
                                        Text(runtimeError, color = MaterialTheme.colorScheme.error)
                                    }
                                channel.feature.error
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { featureError ->
                                        Text(featureError, color = MaterialTheme.colorScheme.error)
                                    }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = channel.enabled,
                                onCheckedChange = {
                                    viewModel.enabled(channel.feature.name, it, channel.instanceId)
                                },
                                enabled = !pending,
                            )
                        },
                    )
                }
            }
            if (channels.isEmpty() && !state.loading) {
                item { Text("No channel integrations are exposed by this gateway.") }
            }
        }
    }

    selected?.let { channel ->
        val connection = state.connectionFor(channel.feature.name, channel.instanceId)
        ChannelDialog(
            channel = channel,
            pending = channelPending(state, channel.feature.name, channel.instanceId),
            cancelPending = channelCancelKey(channel.feature.name, channel.instanceId) in state.pending,
            validation = state.validationFor(channel.feature.name, channel.instanceId),
            connection = connection,
            requiresRestart = state.payload?.requiresRestart == true,
            onDismiss = { selected = null },
            onValidate = { values ->
                viewModel.validate(channel.feature.name, values, channel.instanceId)
            },
            onSave = { values ->
                // 保存并启用必须先通过服务端校验；与官方 WebUI 的两阶段行为保持一致。
                viewModel.saveAndEnable(channel.feature.name, values, channel.instanceId)
            },
            onConnect = { viewModel.connect(channel.feature.name, channel.instanceId) },
            onCancel = { sessionId ->
                viewModel.cancel(channel.feature.name, sessionId, channel.instanceId)
            },
        )
    }
}

private fun channelPending(state: ChannelsUiState, name: String, instanceId: String?): Boolean =
    listOf(
        channelActionKey(name, instanceId),
        channelConnectKey(name, instanceId),
        channelCancelKey(name, instanceId),
    ).any(state.pending::contains)

@Composable
private fun ChannelDialog(
    channel: ChannelSelection,
    pending: Boolean,
    cancelPending: Boolean,
    validation: com.nanobotkt.core.model.ChannelValidationPayload?,
    connection: ChannelConnectPayload?,
    requiresRestart: Boolean,
    onDismiss: () -> Unit,
    onValidate: (Map<String, String>) -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onConnect: () -> Unit,
    onCancel: (String) -> Unit,
) {
    val fields = channel.feature.setup?.fields.orEmpty()
    var values by remember(channel.key) {
        mutableStateOf(
            fields.associate { field ->
                field.key to (channel.configValues[field.key] ?: field.defaultValue.orEmpty())
            },
        )
    }
    var validationMessage by remember(channel.key) { mutableStateOf<String?>(null) }
    // 只展示与当前表单值对应的 validation；字段修改后旧结果立即失效，
    // 避免用户把上一组凭据的成功状态误认为当前输入仍然可用。
    var validatedValues by remember(channel.key) { mutableStateOf<Map<String, String>?>(null) }
    // Connect 能力来自服务端插件的 connector 元数据；不要在客户端维护频道名称白名单。
    // 对旧服务端返回 null 时保持不可用，避免再次向不支持的频道发送 404 请求。
    val connectCapable = channel.feature.connectSupported == true
    val submittedValues = {
        // 空 secret 代表“沿用已保存凭据”，不能把服务端的 secret 清空。
        values.filter { (key, value) ->
            val field = fields.firstOrNull { it.key == key }
            !(field?.kind == "secret" && value.isBlank())
        }
    }
    val requiredMissing = fields.any { field ->
        field.required && values[field.key].orEmpty().isBlank() && field.key !in channel.configuredFields
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(channel.displayName) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(channel.feature.status) }
                if (requiresRestart ||
                    channel.feature.requiresRestart ||
                    validation?.requiresRestart == true ||
                    connection?.nanobotFeatures?.requiresRestart == true
                ) {
                    item {
                        Text(
                            "Gateway restart required for this change to take effect.",
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                fields.forEach { field ->
                    item(key = field.key) {
                        ChannelField(
                            field = field,
                            value = values[field.key].orEmpty(),
                            onValueChange = { value ->
                                values = values + (field.key to value)
                                validationMessage = null
                                validatedValues = null
                            },
                            configured = field.key in channel.configuredFields,
                        )
                    }
                }
                validationMessage?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                channel.feature.error
                    ?.takeIf(String::isNotBlank)
                    ?.let { featureError ->
                        item { Text(featureError, color = MaterialTheme.colorScheme.error) }
                    }
                validation
                    ?.takeIf { validatedValues == submittedValues() }
                    ?.let { result ->
                    item {
                        Text(
                            result.message ?: result.status,
                            color = if (result.canEnable && result.status != "invalid") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    if (result.missingFields.isNotEmpty()) {
                        item {
                            Text(
                                "Missing: ${result.missingFields.joinToString()}",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    result.identity?.let { identity ->
                        item {
                            val identityText = listOfNotNull(
                                identity.name,
                                identity.workspace,
                                identity.account,
                            ).filter(String::isNotBlank).joinToString(" · ")
                            if (identityText.isNotBlank()) Text("Identity: $identityText")
                        }
                    }
                    items(result.checks, key = { it.id }) { check ->
                        ValidationCheck(check)
                    }
                    result.checkedAt?.let { checkedAt ->
                        item { Text("Checked: $checkedAt") }
                    }
                }
                connection?.let { current ->
                    item { ConnectionStatus(current, cancelPending, onCancel) }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = !pending,
                    onClick = {
                        if (requiredMissing) {
                            validationMessage = "Please fill all required fields."
                        } else {
                            val currentValues = submittedValues()
                            validatedValues = currentValues
                            onValidate(currentValues)
                        }
                    },
                ) { Text("Validate") }
                TextButton(
                    enabled = !pending && !requiredMissing,
                    onClick = {
                        val currentValues = submittedValues()
                        validatedValues = currentValues
                        onSave(currentValues)
                    },
                ) { Text("Save & enable") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (connectCapable) {
                    TextButton(enabled = !pending, onClick = onConnect) { Text("Connect") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun ValidationCheck(check: com.nanobotkt.core.model.ChannelValidationCheck) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${check.label}: ${check.status}${check.message?.let { " — $it" }.orEmpty()}",
            color = when (check.status) {
                "fail" -> MaterialTheme.colorScheme.error
                "pass" -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        check.actionUrl?.takeIf(String::isNotBlank)?.let { actionUrl ->
            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl)))
                },
            ) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                Text("Open help")
            }
        }
    }
}

@Composable
private fun ChannelField(
    field: ChannelSetupContractField,
    value: String,
    onValueChange: (String) -> Unit,
    configured: Boolean,
) {
    if (field.kind == "bool") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(field.field + if (field.required) " *" else "")
            Switch(checked = value.toBoolean(), onCheckedChange = { onValueChange(it.toString()) })
        }
        return
    }
    if (field.kind == "enum" && field.choices.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(field.field + if (field.required) " *" else "")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                field.choices.forEach { choice ->
                    FilterChip(
                        selected = value == choice,
                        onClick = { onValueChange(choice) },
                        label = { Text(choice) },
                    )
                }
            }
        }
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(field.field + if (field.required) " *" else "") },
        placeholder = {
            if (field.kind == "secret" && configured) Text("Configured; leave blank to keep")
        },
        singleLine = true,
        visualTransformation = if (field.kind == "secret") {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (field.kind == "int") KeyboardType.Number else KeyboardType.Unspecified,
        ),
    )
}

@Composable
private fun ConnectionStatus(
    connection: ChannelConnectPayload,
    cancelPending: Boolean,
    onCancel: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connection: ${connection.status} ${connection.message.orEmpty()}")
        connection.qrUrl?.let { qrUrl ->
            val bitmap = remember(qrUrl) { qrBitmap(qrUrl) }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "QR code",
                    modifier = Modifier.size(220.dp),
                )
            }
            Text("Scan this QR code to finish connecting.")
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(qrUrl)))
                },
            ) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                Text("Open QR data")
            }
        }
        if (connection.status == "pending") {
            TextButton(
                enabled = !cancelPending,
                onClick = { onCancel(connection.sessionId) },
            ) { Text(if (cancelPending) "Cancelling…" else "Cancel connection") }
        }
    }
}

private fun qrBitmap(value: String, size: Int = 640): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}.getOrNull()
