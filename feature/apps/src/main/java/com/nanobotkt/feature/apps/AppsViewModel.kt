package com.nanobotkt.feature.apps
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel class AppsViewModel @Inject constructor(private val repository:AppsRepository):ViewModel(){val state=repository.state;init{refresh()};fun refresh()=viewModelScope.launch{repository.refresh()};fun cli(action:String,name:String)=viewModelScope.launch{repository.cliAction(action,name)};fun mcp(action:String,name:String,values:Map<String,String> = emptyMap())=viewModelScope.launch{repository.mcpAction(action,name,values)};fun importConfig(config:String)=viewModelScope.launch{repository.importConfig(config)}}
