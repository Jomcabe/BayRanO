package com.bayrano.ui

import androidx.lifecycle.AndroidViewModel
import com.bayrano.app.BayRanOApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val keyPreview: String = "",
    val hasUserKey: Boolean = false,
    val savedAt: Long = 0L,
)

class SettingsViewModel(app: BayRanOApp) : AndroidViewModel(app) {

    private val store = app.apiKeyStore

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

    private fun currentState(): SettingsUiState {
        val key = store.getGeminiKey()
        return SettingsUiState(
            keyPreview = maskKey(key),
            hasUserKey = store.hasUserKey(),
        )
    }

    private fun maskKey(key: String): String = when {
        key.isBlank() -> "(none)"
        key.length <= 8 -> "•".repeat(key.length)
        else -> key.take(4) + "…" + key.takeLast(4)
    }
}
