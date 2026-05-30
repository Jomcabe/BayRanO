package com.bayrano.assistant

import android.content.Context
import android.os.SystemClock
import com.bayrano.audio.BluetoothAudioRouter
import com.bayrano.audio.ElevenLabsConfig
import com.bayrano.audio.SpeechResult
import com.bayrano.audio.SpeechToText
import com.bayrano.audio.TtsSpeaker
import com.bayrano.core.ApiKeyStore
import com.bayrano.core.AppPreferences
import com.bayrano.core.Connectivity
import com.bayrano.data.QueryLog
import com.bayrano.data.QueryLogDao
import com.bayrano.gemini.GeminiClient
import com.bayrano.gemini.GeminiResult
import com.bayrano.glasses.CameraController
import com.bayrano.glasses.GlassesManager
import com.bayrano.glasses.GlassesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * The assistant pipeline (connect → capture frame → listen → Gemini → speak),
 * extracted from the ViewModel so it can be driven from anywhere — the UI, the
 * Quick Settings tile, and especially the background [com.bayrano.wake.WakeService]
 * when the glasses' swipe/volume gesture fires while the app isn't on screen.
 *
 * Process-lifetime singleton owned by the Application; it has its own
 * [CoroutineScope] rather than a ViewModel scope.
 */
class AssistantEngine(
    context: Context,
    private val gemini: GeminiClient,
    private val apiKeyStore: ApiKeyStore,
    private val prefs: AppPreferences,
    private val glasses: GlassesManager,
    private val camera: CameraController,
    private val queryLogDao: QueryLogDao,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val stt = SpeechToText(appContext)

    // Speak with the user's ElevenLabs voice when they've saved a key and picked
    // one in Settings; otherwise fall back to the on-device TTS engine. The
    // config is read per utterance so changes apply live.
    private val tts = TtsSpeaker(appContext) {
        val key = apiKeyStore.getElevenLabsKey()
        val voiceId = prefs.elevenLabsVoiceId
        if (key.isNotBlank() && !voiceId.isNullOrBlank()) {
            ElevenLabsConfig(apiKey = key, voiceId = voiceId)
        } else {
            null
        }
    }
    private val audioRouter = BluetoothAudioRouter(appContext)
    private val connectivity = Connectivity(appContext)

    // Context for the "elaborate" follow-up command.
    private var lastQuestion: String? = null
    private var lastAnswer: String? = null
    private var lastFrameJpeg: ByteArray? = null

    private val _uiState = MutableStateFlow(AssistantUiState(useMockDevice = prefs.useMockDevice))
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
            .launchIn(scope)
    }

    fun setUseMockDevice(enabled: Boolean) {
        prefs.useMockDevice = enabled
        _uiState.update { it.copy(useMockDevice = enabled) }
    }

    fun connect() {
        if (_uiState.value.isConnecting || _uiState.value.isConnected) return
        _uiState.update { it.copy(isConnecting = true) }
        scope.launch {
            glasses.connect(useMock = _uiState.value.useMockDevice)
            _uiState.update { it.copy(isConnecting = false) }
        }
    }

    fun disconnect() = glasses.disconnect()

    /** Typed-question path. */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _uiState.value.isBusy) return
        scope.launch { newQuery(q, attachFrame = true) }
    }

    /** Capture a frame and ask Gemini to describe it. */
    fun captureAndDescribe() {
        if (!_uiState.value.isConnected) {
            append(TranscriptEntry.Speaker.SYSTEM, "⚠️ Connect first.")
            return
        }
        if (_uiState.value.isBusy) return
        scope.launch { newQuery("Describe what you see.", attachFrame = true, requireFrame = true) }
    }

    /**
     * The wake entry point — same loop as the on-screen mic button. Called by the
     * volume-up / double-tap trigger, the Quick Settings tile, and the UI.
     */
    fun onWake() = startVoiceQuery()

    /**
     * Route the mic to the glasses (SCO), recognise speech, then run the
     * "elaborate" follow-up or treat it as a new question.
     */
    fun startVoiceQuery() {
        if (_uiState.value.isBusy || _uiState.value.isListening) return
        _uiState.update { it.copy(isListening = true) }
        scope.launch {
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
        _uiState.update { it.copy(isBusy = true) }
        append(TranscriptEntry.Speaker.USER, "🗣️ Elaborate")
        // Conversation context carries the prior Q/A, so just ask to expand.
        respond(
            prompt = "Please elaborate on your previous answer with more useful detail.",
            jpeg = lastFrameJpeg,
            detailed = true,
            rememberQuestion = q,
        )
    }

    private suspend fun respond(
        prompt: String,
        jpeg: ByteArray?,
        detailed: Boolean,
        rememberQuestion: String,
    ) {
        val model = gemini.modelName
        val hadImage = jpeg != null

        // Handle no-network up front with a short spoken message rather than
        // waiting for a socket timeout.
        if (!connectivity.isOnline()) {
            append(TranscriptEntry.Speaker.SYSTEM, "⚠️ No connection.")
            _uiState.update { it.copy(isBusy = false) }
            logQuery(prompt, model, answer = "", latencyMs = 0, hadImage = hadImage, error = "offline")
            tts.speak("No connection.")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        val result = gemini.ask(apiKeyStore.getGeminiKey(), prompt, jpeg, detailed)
        val latencyMs = SystemClock.elapsedRealtime() - startedAt

        when (result) {
            is GeminiResult.Success -> {
                append(TranscriptEntry.Speaker.ASSISTANT, result.text)
                lastQuestion = rememberQuestion
                lastAnswer = result.text
                lastFrameJpeg = jpeg
                _uiState.update { it.copy(isBusy = false) }
                logQuery(prompt, model, result.text, latencyMs, hadImage, error = null)
                tts.speak(result.text) // spoken out the glasses (A2DP)
            }
            is GeminiResult.Error -> {
                append(TranscriptEntry.Speaker.SYSTEM, "⚠️ ${result.message}")
                _uiState.update { it.copy(isBusy = false) }
                logQuery(prompt, model, answer = "", latencyMs = latencyMs, hadImage = hadImage, error = result.message)
                // A network-class failure (no httpCode) gets the short spoken cue too.
                if (result.httpCode == null) tts.speak("No connection.")
            }
        }
    }

    private fun logQuery(
        question: String,
        model: String,
        answer: String,
        latencyMs: Long,
        hadImage: Boolean,
        error: String?,
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                queryLogDao.insert(
                    QueryLog(
                        timestamp = System.currentTimeMillis(),
                        question = question,
                        model = model,
                        answer = answer,
                        latencyMs = latencyMs,
                        hadImage = hadImage,
                        error = error,
                    ),
                )
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

    private companion object {
        val ELABORATE_PHRASES = setOf(
            "elaborate", "tell me more", "more", "go on", "continue", "expand",
            "explain", "say more", "more detail", "more details", "keep going",
        )
    }
}
