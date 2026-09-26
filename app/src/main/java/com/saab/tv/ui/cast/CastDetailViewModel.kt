package com.saab.tv.ui.cast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saab.tv.data.tmdb.TmdbMetadataService
import com.saab.tv.data.tmdb.TmdbPersonDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CastDetailState {
    data object Loading : CastDetailState()
    data class Success(val person: TmdbPersonDetail) : CastDetailState()
    data class Error(val message: String) : CastDetailState()
}

@HiltViewModel
class CastDetailViewModel @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val personId: Int = (savedStateHandle.get<String>("personId") ?: "0").toIntOrNull() ?: 0
    val personName: String = java.net.URLDecoder.decode(
        savedStateHandle.get<String>("personName") ?: "", "UTF-8"
    )

    private val _state = MutableStateFlow<CastDetailState>(CastDetailState.Loading)
    val state: StateFlow<CastDetailState> = _state

    init {
        loadPersonDetail()
    }

    fun retry() {
        loadPersonDetail()
    }

    private fun loadPersonDetail() {
        _state.value = CastDetailState.Loading
        viewModelScope.launch {
            val detail = tmdbMetadataService.fetchPersonDetail(personId)
            if (detail != null) {
                _state.value = CastDetailState.Success(detail)
            } else {
                _state.value = CastDetailState.Error("Failed to load person details")
            }
        }
    }
}
