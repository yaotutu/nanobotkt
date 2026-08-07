package com.nanobotkt.feature.security
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel class SecurityViewModel @Inject constructor(private val repository:SecurityRepository):ViewModel(){val state=repository.state;init{viewModelScope.launch{while(isActive){repository.refresh();delay(5000)}}};fun refresh()=viewModelScope.launch{repository.refresh()};fun approve(code:String)=viewModelScope.launch{repository.action("approve",code)};fun deny(code:String)=viewModelScope.launch{repository.action("deny",code)}}
