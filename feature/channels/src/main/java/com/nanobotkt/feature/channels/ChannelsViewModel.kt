package com.nanobotkt.feature.channels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel class ChannelsViewModel @Inject constructor(private val repository:ChannelsRepository):ViewModel(){val state=repository.state;init{refresh()};fun refresh()=viewModelScope.launch{repository.refresh()};fun enabled(name:String,value:Boolean,instanceId:String?=null)=viewModelScope.launch{repository.setEnabled(name,value,instanceId)};fun configure(name:String,values:Map<String,String>,enable:Boolean?=null,instanceId:String?=null)=viewModelScope.launch{repository.configure(name,values,enable,instanceId)};fun validate(name:String,values:Map<String,String>,instanceId:String?=null)=viewModelScope.launch{repository.validate(name,values,instanceId)};fun connect(name:String,instanceId:String?=null)=viewModelScope.launch{repository.startConnect(name,instanceId);while(true){val current=state.value.connection?:break;if(current.status!="pending")break;delay(current.intervalMs?.coerceIn(500,5000)?:1500);repository.pollConnect(name,current.sessionId)}};fun cancel(name:String,sessionId:String)=viewModelScope.launch{repository.cancelConnect(name,sessionId)}}
