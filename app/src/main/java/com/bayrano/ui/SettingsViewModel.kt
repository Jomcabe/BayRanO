package com.bayrano.ui

import androidx.lifecycle.AndroidViewModel
import com.bayrano.app.BayRanOApp
import com.bayrano.gemini.MediaResolution
import com.bayrano.gemini.ThinkingLevel
import com.bayrano.wake.WakeService
import com.bayrano.wake.WakeTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val keyPreview: String = "",
    val hasUserKey: Boolean = false,
    val mediaResolution: MediaResolution = MediaResolution.MEDIUM,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.LOW,
    val wakeEnabled: Boolean = false,
    val wakeTrigger: WakeTrigger = WakeTrigger.VOLUME_UP,
)

class SettingsViewModel(app: BayRanOApp) : AndroidViewModel(app) {

    private val store = app.apiKeyStore
    private val prefs = app.appPreferences

    private val _uiState = MutableStateFlow(currentState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** The full current key, for pre-filling the edit field. */
    fun currentKey(): String = store.getGeminiKey()

    fun save(key: String) {
        store.setGeminiKey(key)
        _uiState.value = currentState()
    }

    fun clear() {
        store.clearGeminiKey()
        _uiState.value = currentState()
    }

    fun setMediaResolution(value: MediaResolution) {
        prefs.mediaResolution = value
        _uiState.value = currentState()
    }

    fun setThinkingLevel(value: ThinkingLevel) {
        prefs.thinkingLevel = value
        _uiState.value = currentState()
    }

    fun setWakeEnabled(enabled: Boolean) {
        prefs.wakeEnabled = enabled
        if (enabled) WakeService.start(getApplication()) else WakeService.stop(getApplication())
        _uiState.value = currentState()
    }

    fun setWakeTrigger(value: WakeTrigger) {
        prefs.wakeTrigger = value
        _uiState.value = currentState()
    }

    private fun currentState(): SettingsUiState {
        val key = store.getGeminiKey()
        return SettingsUiState(
            keyPreview = maskKey(key),
            hasUserKey = store.hasUserKey(),
            mediaResolution = prefs.mediaResolution,
            thinkingLevel = prefs.thinkingLevel,
            wakeEnabled = prefs.wakeEnabled,
            wakeTrigger = prefs.wakeTrigger,
        )
    }

    private fun maskKey(key: String): String = when {
        key.isBlank() -> "(none)"
        key.length <= 8 -> "•".repeat(key.length)
        else -> key.take(4) + "…" + key.takeLast(4)
    }
}
