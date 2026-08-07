package com.nanobotkt.feature.security
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
@Composable fun SecurityScreen(onBack:()->Unit,viewModel:SecurityViewModel=hiltViewModel()){val state by viewModel.state.collectAsStateWithLifecycle();Scaffold(topBar={TopAppBar(title={Text("Security & pairing")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,null)}},actions={IconButton(onClick=viewModel::refresh){Icon(Icons.Rounded.Refresh,"Refresh")}})}){p->LazyColumn(Modifier.fillMaxSize().padding(p),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("Pairing requests",style=MaterialTheme.typography.titleLarge);Text("This screen polls while it is open. Approvals and denials are always confirmed by the gateway.")};state.error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}};if(state.loading&&state.payload==null)item{CircularProgressIndicator()};items(state.payload?.requests.orEmpty(),key={it.code}){r->ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text(r.channel,style=MaterialTheme.typography.titleMedium);Text(r.senderId);Text("Code: ${r.code}");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(enabled=r.code !in state.pending,onClick={viewModel.approve(r.code)}){Text("Approve")};OutlinedButton(enabled=r.code !in state.pending,onClick={viewModel.deny(r.code)}){Text("Deny")}}}}};if(state.payload?.requests?.isEmpty()==true)item{Text("No pending pairing requests")};state.payload?.lastAction?.let{item{Text(it.message,color=if(it.ok)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}}}}
}

