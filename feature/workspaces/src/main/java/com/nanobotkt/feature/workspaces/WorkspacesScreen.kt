package com.nanobotkt.feature.workspaces
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkspacesScreen(onBack: () -> Unit, viewModel: WorkspacesViewModel = hiltViewModel()) {
 val state by viewModel.state.collectAsStateWithLifecycle(); val payload = state.payload
 Scaffold(topBar={ TopAppBar(title={Text("Workspaces")}, navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,null)}}, actions={IconButton(onClick=viewModel::refresh){Icon(Icons.Rounded.Refresh,"Refresh")}})}) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
   if(state.loading && payload==null) CircularProgressIndicator()
   state.error?.let { Text(it,color=MaterialTheme.colorScheme.error); TextButton(onClick=viewModel::refresh){Text("Retry")} }
   payload?.let { p ->
    Text("Default workspace", style=MaterialTheme.typography.titleMedium)
    ElevatedCard(Modifier.fillMaxWidth()){ Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(6.dp)){ Text(p.defaultScope.projectName ?: p.defaultScope.projectPath); Text(p.defaultScope.projectPath, style=MaterialTheme.typography.bodySmall); Text("Access: ${p.defaultScope.accessMode.name.lowercase()}"); Text("Restrict to workspace: ${p.defaultScope.restrictToWorkspace ?: false}"); p.defaultScope.sandboxStatus?.let { Text(it.summary) } } }
    Text("Controls", style=MaterialTheme.typography.titleMedium)
    Text("Change project: ${p.controls.canChangeProject}")
    Text("Full access: ${p.controls.canUseFullAccess}")
   }
  }
 }
}

