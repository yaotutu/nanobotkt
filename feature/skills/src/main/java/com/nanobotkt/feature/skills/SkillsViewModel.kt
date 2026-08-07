package com.nanobotkt.feature.skills
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel class SkillsViewModel @Inject constructor(private val repository:SkillsRepository):ViewModel(){val state=repository.state;init{refresh()};fun refresh()=viewModelScope.launch{repository.refresh()};fun select(name:String)=viewModelScope.launch{repository.select(name)};fun closeDetail()=repository.clearSelection()}
