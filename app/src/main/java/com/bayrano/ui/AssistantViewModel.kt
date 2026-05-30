package com.bayrano.ui

import androidx.lifecycle.AndroidViewModel
import com.bayrano.app.BayRanOApp
import com.bayrano.assistant.AssistantUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin adapter over the process-lifetime [com.bayrano.assistant.AssistantEngine].
 * The engine owns all pipeline state so the same loop can run from the UI, the
 * Quick Settings tile, and the background WakeService; the ViewModel just exposes
 * it to Compose and forwards user actions.
 */
class AssistantViewModel(app: BayRanOApp) : AndroidViewModel(app) {

    private val engine = app.assistantEngine

    val uiState: StateFlow<AssistantUiState> = engine.uiState

    fun setUseMockDevice(enabled: Boolean) = engine.setUseMockDevice(enabled)
    fun connect() = engine.connect()
    fun disconnect() = engine.disconnect()
    fun ask(question: String) = engine.ask(question)
    fun captureAndDescribe() = engine.captureAndDescribe()
    fun startVoiceQuery() = engine.startVoiceQuery()
}
