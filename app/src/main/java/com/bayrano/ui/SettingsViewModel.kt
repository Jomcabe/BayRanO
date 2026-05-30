package com.bayrano.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bayrano.app.BayRanOApp
import com.bayrano.audio.ElevenLabsClient
import com.bayrano.audio.ElevenLabsVoice
import com.bayrano.audio.VoicesResult
import com.bayrano.gemini.MediaResolution
import com.bayrano.gemini.ThinkingLevel
import com.bayrano.wake.WakeService
import com.bayrano.wake.WakeTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val keyPreview: String = "",
    val hasUserKey: Boolean = false,
    val mediaResolution: MediaResolution = MediaResolution.MEDIUM,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.LOW,
    val wakeEnabled: Boolean = false,
    val wakeTrigger: WakeTrigger = WakeTrigger.VOLUME_UP,
    // ElevenLabs voice
    val elevenLabsKeyPreview: String = "",
    val hasElevenLabsKey: Boolean = false,
    val selectedVoiceId: String? = null,
    val selectedVoiceName: String? = null,
    val voices: List<ElevenLabsVoice> = emptyList(),
    val voicesLoading: Boolean = false,
    val voicesError: String? = null,
)

class SettingsViewModel(app: BayRanOApp) : AndroidViewModel(app) {

    private val store = app.apiKeyStore
    private val prefs = app.appPreferences
    private val elevenLabs = ElevenLabsClient()

    private val _uiState = MutableStateFlow(currentState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** The full current key, for pre-filling the edit field. */
    fun currentKey(): String = store.getGeminiKey()

    fun save(key: String) {
        store.setGeminiKey(key)
        _uiState.update { currentState(it) }
    }

    fun clear() {
        store.clearGeminiKey()
        _uiState.update { currentState(it) }
    }

    fun setMediaResolution(value: MediaResolution) {
        prefs.mediaResolution = value
        _uiState.update { currentState(it) }
    }

    fun setThinkingLevel(value: ThinkingLevel) {
        prefs.thinkingLevel = value
        _uiState.update { currentState(it) }
    }

    fun setWakeEnabled(enabled: Boolean) {
        prefs.wakeEnabled = enabled
        if (enabled) WakeService.start(getApplication()) else WakeService.stop(getApplication())
        _uiState.update { currentState(it) }
    }

    fun setWakeTrigger(value: WakeTrigger) {
        prefs.wakeTrigger = value
        _uiState.update { currentState(it) }
    }

    // --- ElevenLabs voice ---

    /** The full current ElevenLabs key, for pre-filling the edit field. */
    fun currentElevenLabsKey(): String = store.getElevenLabsKey()

    fun saveElevenLabsKey(key: String) {
        store.setElevenLabsKey(key)
        _uiState.update { currentState(it) }
    }

    fun clearElevenLabs() {
        store.clearElevenLabsKey()
        prefs.elevenLabsVoiceId = null
        prefs.elevenLabsVoiceName = null
        _uiState.update {
            currentState(it).copy(voices = emptyList(), voicesError = null)
        }
    }

    /** Fetch the account's voices so the user can pick one. */
    fun loadVoices() {
        val key = store.getElevenLabsKey()
        if (key.isBlank()) {
            _uiState.update { it.copy(voicesError = "Save an ElevenLabs API key first.") }
            return
        }
        _uiState.update { it.copy(voicesLoading = true, voicesError = null) }
        viewModelScope.launch {
            when (val result = elevenLabs.listVoices(key)) {
                is VoicesResult.Success ->
                    _uiState.update {
                        it.copy(voicesLoading = false, voices = result.voices, voicesError = null)
                    }
                is VoicesResult.Error ->
                    _uiState.update {
                        it.copy(voicesLoading = false, voicesError = result.message)
                    }
            }
        }
    }

    fun selectVoice(voice: ElevenLabsVoice) {
        prefs.elevenLabsVoiceId = voice.id
        prefs.elevenLabsVoiceName = voice.name
        _uiState.update {
            it.copy(selectedVoiceId = voice.id, selectedVoiceName = voice.name)
        }
    }

    /** Rebuilds persisted state, preserving the transient voice-list fields from [prev]. */
    private fun currentState(prev: SettingsUiState? = null): SettingsUiState {
        val key = store.getGeminiKey()
        val elevenKey = store.getElevenLabsKey()
        return SettingsUiState(
            keyPreview = maskKey(key),
            hasUserKey = store.hasUserKey(),
            mediaResolution = prefs.mediaResolution,
            thinkingLevel = prefs.thinkingLevel,
            wakeEnabled = prefs.wakeEnabled,
            wakeTrigger = prefs.wakeTrigger,
            elevenLabsKeyPreview = maskKey(elevenKey),
            hasElevenLabsKey = store.hasElevenLabsKey(),
            selectedVoiceId = prefs.elevenLabsVoiceId,
            selectedVoiceName = prefs.elevenLabsVoiceName,
            voices = prev?.voices ?: emptyList(),
            voicesLoading = prev?.voicesLoading ?: false,
            voicesError = prev?.voicesError,
        )
    }

    private fun maskKey(key: String): String = when {
        key.isBlank() -> "(none)"
        key.length <= 8 -> "•".repeat(key.length)
        else -> key.take(4) + "…" + key.takeLast(4)
    }
}
