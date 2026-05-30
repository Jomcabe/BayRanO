package com.bayrano.ui

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bayrano.app.BayRanOApp
import com.bayrano.audio.BluetoothAudioRouter
import com.bayrano.audio.SpeechResult
import com.bayrano.audio.SpeechToText
import com.bayrano.audio.TtsSpeaker
import com.bayrano.gemini.GeminiResult
import com.bayrano.glasses.GlassesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One line in the transcript. */
data class TranscriptEntry(
    val speaker: Speaker,
    val text: String,
) {
    enum class Speaker { USER, ASSISTANT, SYSTEM }
}

data class AssistantUiState(
    val transcript: List<TranscriptEntry> = emptyList(),
    val isBusy: Boolean = false,
    val isListening: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val glassesStatus: String = "Disconnected",
    val useMockDevice: Boolean = true,
)

class AssistantViewModel(app: BayRanOApp) : AndroidViewModel(app) {

    private val gemini = app.geminiClient
    private val apiKeyStore = app.apiKeyStore
    private val prefs = app.appPreferences
    private val glasses = app.glassesManager
    private val camera = app.cameraController

    private val stt = SpeechToText(app)
    private val tts = TtsSpeaker(app)
    private val audioRouter = BluetoothAudioRouter(app)

    // Context for the "elaborate" follow-up command.
    private var lastQuestion: String? = null
    private var lastAnswer: String? = null
    private var lastFrameJpeg: ByteArray? = null

    private val _uiState = MutableStateFlow(
        AssistantUiState(useMockDevice = prefs.useMockDevice),
    )
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    init {
        glasses.state
            .onEach { state ->
                _uiState.update {
                    it.copy(
                        glassesStatus = state.label(),
                        isConnected = state is GlassesState.Ready,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun setUseMockDevice(enabled: Boolean) {
        prefs.useMockDevice = enabled
        _uiState.update { it.copy(useMockDevice = enabled) }
    }

    fun connect() {
        if (_uiState.value.isConnecting || _uiState.value.isConnected) return
        _uiState.update { it.copy(isConnecting = true) }
        viewModelScope.launch {
            glasses.connect(useMock = _uiState.value.useMockDevice)
            _uiState.update { it.copy(isConnecting = false) }
        }
    }

    fun disconnect() = glasses.disconnect()

    /** Typed-question test path. */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _uiState.value.isBusy) return
        viewModelScope.launch { newQuery(q, attachFrame = true) }
    }

    /** Capture a frame and ask Gemini to describe it. */
    fun captureAndDescribe() {
        if (!_uiState.value.isConnected) {
            append(TranscriptEntry.Speaker.SYSTEM, "⚠️ Connect first.")
            return
        }
        if (_uiState.value.isBusy) return
        viewModelScope.launch { newQuery("Describe what you see.", attachFrame = true, requireFrame = true) }
    }

    /**
     * Push-to-talk: route the mic to the glasses (SCO), recognise speech, then
     * either run the "elaborate" follow-up or treat it as a new question.
     */
    fun startVoiceQuery() {
        if (_uiState.value.isBusy || _uiState.value.isListening) return
        _uiState.update { it.copy(isListening = true) }
        viewModelScope.launch {
            runCatching { audioRouter.routeToGlasses() } // best-effort SCO mic
            val result = stt.listenOnce()
            // Free SCO so TTS can play back over A2DP through the glasses.
            runCatching { audioRouter.clearRoute() }
            _uiState.update { it.copy(isListening = false) }

            when (result) {
                is SpeechResult.Failed ->
                    append(TranscriptEntry.Speaker.SYSTEM, "⚠️ ${result.reason}")
                is SpeechResult.Recognized -> {
                    val text = result.text
                    if (isElaborateCommand(text) && lastAnswer != null) {
                        elaborate()
                    } else {
                        newQuery(text, attachFrame = true)
                    }
                }
            }
        }
    }

    private suspend fun newQuery(
        question: String,
        attachFrame: Boolean,
        requireFrame: Boolean = false,
    ) {
        _uiState.update { it.copy(isBusy = true) }

        val jpeg = if (attachFrame && _uiState.value.isConnected) {
            runCatching { camera.capturePhotoJpeg() }.getOrNull()
        } else {
            null
        }
        if (requireFrame && jpeg == null) {
            append(TranscriptEntry.Speaker.SYSTEM, "⚠️ Couldn't capture a frame.")
            _uiState.update { it.copy(isBusy = false) }
            return
        }

        append(TranscriptEntry.Speaker.USER, if (jpeg != null) "📷 $question" else question)
        respond(prompt = question, jpeg = jpeg, detailed = false, rememberQuestion = question)
    }

    private suspend fun elaborate() {
        val q = lastQuestion ?: return
        val a = lastAnswer ?: return
        _uiState.update { it.copy(isBusy = true) }
        append(TranscriptEntry.Speaker.USER, "🗣️ Elaborate")
        val prompt =
            "Earlier I asked: \"$q\". You answered: \"$a\". Please elaborate with more useful detail."
        respond(prompt = prompt, jpeg = lastFrameJpeg, detailed = true, rememberQuestion = q)
    }

    private suspend fun respond(
        prompt: String,
        jpeg: ByteArray?,
        detailed: Boolean,
        rememberQuestion: String,
    ) {
        when (val result = gemini.ask(apiKeyStore.getGeminiKey(), prompt, jpeg, detailed)) {
            is GeminiResult.Success -> {
                append(TranscriptEntry.Speaker.ASSISTANT, result.text)
                lastQuestion = rememberQuestion
                lastAnswer = result.text
                lastFrameJpeg = jpeg
                _uiState.update { it.copy(isBusy = false) }
                tts.speak(result.text) // spoken out the glasses (A2DP)
            }
            is GeminiResult.Error -> {
                append(TranscriptEntry.Speaker.SYSTEM, "⚠️ ${result.message}")
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun isElaborateCommand(raw: String): Boolean {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?')
        if (t in ELABORATE_PHRASES) return true
        return t.startsWith("elaborate") || t.startsWith("tell me more") ||
            t.startsWith("say more") || t.startsWith("explain")
    }

    private fun append(speaker: TranscriptEntry.Speaker, text: String) {
        _uiState.update { it.copy(transcript = it.transcript + TranscriptEntry(speaker, text)) }
    }

    private fun GlassesState.label(): String = when (this) {
        GlassesState.Unregistered -> "Disconnected"
        GlassesState.Connecting -> "Connecting…"
        GlassesState.Ready -> "Connected — camera ready"
        GlassesState.Streaming -> "Streaming"
        is GlassesState.Error -> "Error: $message"
    }

    override fun onCleared() {
        tts.shutdown()
        runCatching { audioRouter.clearRoute() }
    }

    private companion object {
        val ELABORATE_PHRASES = setOf(
            "elaborate", "tell me more", "more", "go on", "continue", "expand",
            "explain", "say more", "more detail", "more details", "keep going",
        )
    }
}
