package com.nanobotkt.feature.skills
import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.SkillDetail
import com.nanobotkt.core.model.SkillsPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.flow.*
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
interface SkillsRepository { val state: StateFlow<SkillsUiState>; suspend fun refresh(); suspend fun select(name: String); fun clearSelection() }
data class SkillsUiState(val skills: SkillsPayload?=null, val selected: SkillDetail?=null, val loading:Boolean=false, val detailLoading:Boolean=false, val error:String?=null)
@Singleton class DefaultSkillsRepository @Inject constructor(private val api: GatewayApiClient): SkillsRepository {
 private val mutable=MutableStateFlow(SkillsUiState()); override val state=mutable.asStateFlow()
 override suspend fun refresh(){ val old=mutable.value; mutable.value=old.copy(loading=true,error=null); try{ mutable.value=mutable.value.copy(skills=api.get("/api/webui/skills"),loading=false)}catch(e:CancellationException){mutable.value=old;throw e}catch(e:Exception){mutable.value=old.copy(loading=false,error=e.message?:"skills_refresh_failed")} }
 override suspend fun select(name:String){ val old=mutable.value; mutable.value=old.copy(detailLoading=true,error=null); try{ val encoded=URLEncoder.encode(name,"UTF-8").replace("+","%20"); mutable.value=mutable.value.copy(selected=api.get("/api/webui/skills/$encoded"),detailLoading=false)}catch(e:CancellationException){mutable.value=old;throw e}catch(e:Exception){mutable.value=old.copy(detailLoading=false,error=e.message?:"skill_detail_failed")} }
 override fun clearSelection(){mutable.value=mutable.value.copy(selected=null)}
}
