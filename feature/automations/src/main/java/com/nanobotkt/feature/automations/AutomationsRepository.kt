package com.nanobotkt.feature.automations
import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.AutomationUpdatePayload
import com.nanobotkt.core.model.AutomationsPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
interface AutomationsRepository{val state:StateFlow<AutomationsUiState>;suspend fun refresh();suspend fun action(action:String,id:String);suspend fun update(id:String,values:AutomationUpdatePayload)}
data class AutomationsUiState(val payload:AutomationsPayload?=null,val loading:Boolean=false,val pending:Set<String> = emptySet(),val error:String?=null)
@Singleton class DefaultAutomationsRepository @Inject constructor(private val api:GatewayApiClient):AutomationsRepository{
 private val mutable=MutableStateFlow(AutomationsUiState());override val state=mutable.asStateFlow();private val mutex=Mutex()
 override suspend fun refresh(){val old=mutable.value;mutable.value=old.copy(loading=true,error=null);try{mutable.value=AutomationsUiState(api.get("/api/webui/automations"))}catch(e:CancellationException){mutable.value=old;throw e}catch(e:Exception){mutable.value=old.copy(loading=false,error=e.message?:"automations_refresh_failed")}}
 override suspend fun action(action:String,id:String)=mutate(id){api.get<AutomationsPayload>("/api/webui/automations/$action",mapOf("id" to id))}
 override suspend fun update(id:String,values:AutomationUpdatePayload)=mutate(id){api.request("/api/webui/automations/update",AutomationsPayload.serializer(),query=mapOf("id" to id),headers=mapOf("X-Nanobot-Automation-Values" to URLEncoder.encode(api.encode(values,AutomationUpdatePayload.serializer()),"UTF-8")))}
 private suspend fun mutate(id:String,block:suspend()->AutomationsPayload)=mutex.withLock{mutable.value=mutable.value.copy(pending=mutable.value.pending+id,error=null);try{mutable.value=mutable.value.copy(payload=block());refresh()}catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=mutable.value.copy(error=e.message?:"automation_action_failed")}finally{mutable.value=mutable.value.copy(pending=mutable.value.pending-id)}}
}
