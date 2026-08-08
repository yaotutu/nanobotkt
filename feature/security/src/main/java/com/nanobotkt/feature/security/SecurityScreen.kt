package com.nanobotkt.feature.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.model.PairingRequestInfo
import kotlinx.coroutines.delay

/**
 * 将 pairing 请求的绝对过期时间转换为用户可读的短文本。
 *
 * 优先使用服务端返回的 expires_at_ms；只有旧服务端没有绝对时间时，才根据
 * created_at_ms + expires_in_seconds 推导。这样 Android 不会因为本地轮询延迟
 * 误把仍有效的请求提前删除，也不会把过期展示误当成服务端操作。
 */
internal fun pairingExpiryText(request: PairingRequestInfo, nowMs: Long): String {
    val expiresAtMs = request.expiresAtMs ?: request.createdAtMs?.let { createdAt ->
        request.expiresInSeconds?.let { seconds -> createdAt + seconds * 1_000L }
    } ?: return "Expiry unavailable"
    val remainingMs = expiresAtMs - nowMs
    if (remainingMs <= 0L) return "Expired"
    val remainingSeconds = (remainingMs + 999L) / 1_000L
    return when {
        remainingSeconds < 60L -> "Expires in ${remainingSeconds}s"
        remainingSeconds < 3_600L -> "Expires in ${remainingSeconds / 60L}m"
        else -> "Expires in ${remainingSeconds / 3_600L}h"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // ViewModel 可能因导航复用而继续存活；轮询必须绑定页面可见生命周期，
    // 不能只依赖 ViewModel.onCleared 才停止。
    DisposableEffect(viewModel) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    // 倒计时只影响本地文本，不触发 approve/deny 或任何服务端写操作。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security & pairing") },
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
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Pairing requests", style = MaterialTheme.typography.titleLarge)
                Text("This screen polls while it is open. Approvals and denials are always confirmed by the gateway.")
            }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            if (state.loading && state.payload == null) {
                item { CircularProgressIndicator() }
            }
            items(state.payload?.requests.orEmpty(), key = { it.code }) { request ->
                PairingRequestCard(
                    request = request,
                    nowMs = nowMs,
                    pending = request.code in state.pending,
                    onApprove = { viewModel.approve(request.code) },
                    onDeny = { viewModel.deny(request.code) },
                )
            }
            if (state.payload?.requests?.isEmpty() == true) {
                item { Text("No pending pairing requests") }
            }
            state.payload?.lastAction?.let { action ->
                item {
                    Text(
                        action.message,
                        color = if (action.ok) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingRequestCard(
    request: PairingRequestInfo,
    nowMs: Long,
    pending: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(request.channel, style = MaterialTheme.typography.titleMedium)
            Text(request.senderId)
            Text("Code: ${request.code}")
            // 同一次重组只计算一次倒计时文本，避免文本和颜色读取到不同的本地时间。
            val expiryText = pairingExpiryText(request, nowMs)
            Text(
                expiryText,
                color = if (expiryText == "Expired") {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !pending, onClick = onApprove) { Text("Approve") }
                OutlinedButton(enabled = !pending, onClick = onDeny) { Text("Deny") }
            }
        }
    }
}
