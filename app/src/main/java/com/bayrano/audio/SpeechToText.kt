package com.bayrano.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

sealed interface SpeechResult {
    data class Recognized(val text: String) : SpeechResult
    data class Failed(val reason: String) : SpeechResult
}

/**
 * Captures the user's spoken question with Android's on-device [SpeechRecognizer]
 * and returns the transcribed text. We send TEXT to Gemini, not raw audio.
 *
 * Mic audio comes from the glasses over Bluetooth SCO when the caller has routed
 * it there first (see [BluetoothAudioRouter]); SCO is 8 kHz mono, below the
 * recognizer's happy path, so the caller should surface the transcript on screen.
 *
 * On API 31+ this uses the explicit on-device recognizer; on API 30 it falls
 * back to the default recognizer biased offline via EXTRA_PREFER_OFFLINE.
 */
class SpeechToText(context: Context) {

    private val appContext = context.applicationContext

    /** Listens once and returns the recognised text or a failure reason. */
    suspend fun listenOnce(): SpeechResult = withContext(Dispatchers.Main.immediate) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            return@withContext SpeechResult.Failed("Speech recognition unavailable on this device.")
        }

        suspendCancellableCoroutine<SpeechResult> { cont ->
            val recognizer =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }

            var resumed = false
            fun finish(result: SpeechResult) {
                if (resumed) return
                resumed = true
                runCatching { recognizer.destroy() }
                cont.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    finish(
                        if (text.isNullOrEmpty()) SpeechResult.Failed("Didn't catch that.")
                        else SpeechResult.Recognized(text),
                    )
                }

                override fun onError(error: Int) = finish(SpeechResult.Failed(errorText(error)))

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }

            cont.invokeOnCancellation { runCatching { recognizer.cancel(); recognizer.destroy() } }
            recognizer.startListening(intent)
        }
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything."
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Network error during recognition."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy, try again."
        else -> "Speech recognition failed (code $error)."
    }
}
