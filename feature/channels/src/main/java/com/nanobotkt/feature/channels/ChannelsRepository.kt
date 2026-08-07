package com.nanobotkt.feature.channels
import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.*
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
interface ChannelsRepository{val state:StateFlow<ChannelsUiState>;suspend fun refresh();suspend fun setEnabled(name:String,enabled:Boolean,instanceId:String?=null);suspend fun configure(name:String,values:Map<String,String>,enable:Boolean?=null,instanceId:String?=null);suspend fun validate(name:String,values:Map<String,String>,instanceId:String?=null);suspend fun startConnect(name:String,instanceId:String?=null);suspend fun pollConnect(name:String,sessionId:String);suspend fun cancelConnect(name:String,sessionId:String)}
data class ChannelsUiState(val payload:NanobotFeaturesPayload?=null,val validation:ChannelValidationPayload?=null,val connection:ChannelConnectPayload?=null,val loading:Boolean=false,val pending:Set<String> = emptySet(),val error:String?=null)
@Singleton class DefaultChannelsRepository @Inject constructor(private val api:GatewayApiClient,private val json:Json):ChannelsRepository{private val mutable=MutableStateFlow(ChannelsUiState());override val state=mutable.asStateFlow();override suspend fun refresh(){val old=mutable.value;mutable.value=old.copy(loading=true,error=null);try{mutable.value=old.copy(payload=api.get("/api/settings/nanobot-features"),loading=false,error=null)}catch(e:CancellationException){mutable.value=old;throw e}catch(e:Exception){mutable.value=old.copy(loading=false,error=e.message?:"channels_refresh_failed")}}
 override suspend fun setEnabled(name:String,enabled:Boolean,instanceId:String?)=mutate(name){mutable.value=mutable.value.copy(payload=api.get("/api/settings/nanobot-features/${if(enabled)"enable" else "disable"}",mapOf("name" to name,"instance_id" to instanceId)));refresh()}
 override suspend fun configure(name:String,values:Map<String,String>,enable:Boolean?,instanceId:String?)=mutate(name){val query=mapOf("name" to name,"enable" to enable,"instance_id" to instanceId);val result=api.request("/api/settings/channels/configure",ChannelConfigurePayload.serializer(),query=query,headers=mapOf("X-Nanobot-Channel-Values" to json.encodeToString(values)));result.nanobotFeatures?.let{mutable.value=mutable.value.copy(payload=it)}?:refresh()}
 override suspend fun validate(name:String,values:Map<String,String>,instanceId:String?)=mutate(name){mutable.value=mutable.value.copy(validation=api.request("/api/settings/channels/validate",ChannelValidationPayload.serializer(),query=mapOf("name" to name,"instance_id" to instanceId),headers=mapOf("X-Nanobot-Channel-Values" to json.encodeToString(values))))}
 override suspend fun startConnect(name:String,instanceId:String?)=mutate(name){mutable.value=mutable.value.copy(connection=api.get("/api/settings/channels/${name.path()}/connect/start",mapOf("instance_id" to instanceId)))}
 override suspend fun pollConnect(name:String,sessionId:String)=mutate(name){val c=api.get<ChannelConnectPayload>("/api/settings/channels/${name.path()}/connect/poll",mapOf("session_id" to sessionId));mutable.value=mutable.value.copy(connection=c);c.nanobotFeatures?.let{mutable.value=mutable.value.copy(payload=it)}}
 override suspend fun cancelConnect(name:String,sessionId:String)=mutate(name){mutable.value=mutable.value.copy(connection=api.get("/api/settings/channels/${name.path()}/connect/cancel",mapOf("session_id" to sessionId)))}
 private suspend fun mutate(key:String,block:suspend()->Unit){mutable.value=mutable.value.copy(pending=mutable.value.pending+key,error=null);try{block()}catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=mutable.value.copy(error=e.message?:"channel_action_failed")}finally{mutable.value=mutable.value.copy(pending=mutable.value.pending-key)}}
 private fun String.path()=URLEncoder.encode(this,"UTF-8").replace("+","%20")}
