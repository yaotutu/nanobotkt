package com.nanobotkt.feature.apps
import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.CliAppsPayload
import com.nanobotkt.core.model.McpPresetsPayload
import com.nanobotkt.core.model.SlashCommandsPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton
interface AppsRepository{val state:StateFlow<AppsUiState>;suspend fun refresh();suspend fun cliAction(action:String,name:String);suspend fun mcpAction(action:String,name:String,values:Map<String,String> = emptyMap());suspend fun saveCustom(values:Map<String,String>);suspend fun importConfig(config:String);suspend fun updateTools(name:String,tools:List<String>)}
data class AppsUiState(val cli:CliAppsPayload?=null,val mcp:McpPresetsPayload?=null,val commands:SlashCommandsPayload?=null,val loading:Boolean=false,val pending:Set<String> = emptySet(),val error:String?=null)
@Singleton class DefaultAppsRepository @Inject constructor(private val api:GatewayApiClient,private val json:Json):AppsRepository{
 private val mutable=MutableStateFlow(AppsUiState());override val state=mutable.asStateFlow();private val mutex=Mutex()
 override suspend fun refresh(){val old=mutable.value;mutable.value=old.copy(loading=true,error=null);try{val result=coroutineScope{val cli=async{api.get<CliAppsPayload>("/api/settings/cli-apps")};val mcp=async{api.get<McpPresetsPayload>("/api/settings/mcp-presets")};val commands=async{api.get<SlashCommandsPayload>("/api/commands")};Triple(cli.await(),mcp.await(),commands.await())};mutable.value=AppsUiState(result.first,result.second,result.third)}catch(e:CancellationException){mutable.value=old;throw e}catch(e:Exception){mutable.value=old.copy(loading=false,error=e.message?:"apps_refresh_failed")}}
 override suspend fun cliAction(action:String,name:String)=mutate("cli:$name"){mutable.value=mutable.value.copy(cli=api.get("/api/settings/cli-apps/$action",mapOf("name" to name)))}
 override suspend fun mcpAction(action:String,name:String,values:Map<String,String>)=mutate("mcp:$name"){mutable.value=mutable.value.copy(mcp=api.request("/api/settings/mcp-presets/$action",McpPresetsPayload.serializer(),query=mapOf("name" to name),headers=mcpHeader(values)))}
 override suspend fun saveCustom(values:Map<String,String>)=mutate("mcp:custom"){mutable.value=mutable.value.copy(mcp=api.request("/api/settings/mcp-presets/custom",McpPresetsPayload.serializer(),headers=mcpHeader(values)))}
 override suspend fun importConfig(config:String)=mutate("mcp:import"){mutable.value=mutable.value.copy(mcp=api.request("/api/settings/mcp-presets/import",McpPresetsPayload.serializer(),headers=mcpHeader(mapOf("config" to config))))}
 override suspend fun updateTools(name:String,tools:List<String>)=mutate("mcp:$name"){val body=buildJsonObject{put("name",name);putJsonArray("enabled_tools"){tools.forEach{add(kotlinx.serialization.json.JsonPrimitive(it))}}};mutable.value=mutable.value.copy(mcp=api.request("/api/settings/mcp-presets/tools",McpPresetsPayload.serializer(),headers=mapOf("X-Nanobot-MCP-Values" to json.encodeToString(body))))}
 private fun mcpHeader(values:Map<String,String>):Map<String,String> = values.mapValues{it.value.trim()}.filterValues{it.isNotEmpty()}.takeIf{it.isNotEmpty()}?.let{mapOf("X-Nanobot-MCP-Values" to json.encodeToString(it))}.orEmpty()
 private suspend fun mutate(key:String,block:suspend()->Unit)=mutex.withLock{mutable.value=mutable.value.copy(pending=mutable.value.pending+key,error=null);try{block();refresh()}catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=mutable.value.copy(error=e.message?:"apps_action_failed")}finally{mutable.value=mutable.value.copy(pending=mutable.value.pending-key)}}
}
