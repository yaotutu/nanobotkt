package com.nanobotkt.feature.security
import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.PairingPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
interface SecurityRepository{val state:StateFlow<SecurityUiState>;suspend fun refresh();suspend fun action(action:String,code:String)}
data class SecurityUiState(val payload:PairingPayload?=null,val loading:Boolean=false,val pending:Set<String> = emptySet(),val error:String?=null)
@Singleton class DefaultSecurityRepository @Inject constructor(private val api:GatewayApiClient):SecurityRepository{private val mutable=MutableStateFlow(SecurityUiState());override val state=mutable.asStateFlow();override suspend fun refresh(){val old=mutable.value;mutable.value=old.copy(loading=true,error=null);try{mutable.value=SecurityUiState(api.get("/api/settings/pairing"))}catch(e:CancellationException){mutable.value=old;throw e}catch(e:Exception){mutable.value=old.copy(loading=false,error=e.message?:"pairing_refresh_failed")}};override suspend fun action(action:String,code:String){mutable.value=mutable.value.copy(pending=mutable.value.pending+code,error=null);try{mutable.value=mutable.value.copy(payload=api.get("/api/settings/pairing/$action",mapOf("code" to code)));refresh()}catch(e:CancellationException){throw e}catch(e:Exception){mutable.value=mutable.value.copy(error=e.message?:"pairing_action_failed")}finally{mutable.value=mutable.value.copy(pending=mutable.value.pending-code)}}}
