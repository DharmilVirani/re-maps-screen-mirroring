package com.dharmil.mirrordash.projection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionPhase {
    IDLE,
    REQUESTING_CAPTURE,
    CONNECTING_WIFI,
    AUTHENTICATING,
    STREAMING,
    ERROR,
}

data class SessionUiState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val message: String = "Ready when parked",
    val panelOff: Boolean = false,
)

object SessionUiStateStore {
    private val mutableState = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = mutableState.asStateFlow()

    fun update(value: SessionUiState) {
        mutableState.value = value
    }
}
