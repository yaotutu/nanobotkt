package com.nanobotkt.feature.automations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.model.AutomationUpdatePayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel class AutomationsViewModel @Inject constructor(private val repository:AutomationsRepository):ViewModel(){val state=repository.state;init{refresh()};fun refresh()=viewModelScope.launch{repository.refresh()};fun action(action:String,id:String)=viewModelScope.launch{repository.action(action,id)};fun rename(id:String,name:String)=viewModelScope.launch{repository.update(id,AutomationUpdatePayload(name=name))}}
