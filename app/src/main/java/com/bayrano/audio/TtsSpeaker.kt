package com.bayrano.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** What's needed to speak with an ElevenLabs voice; null means "use system TTS". */
data class ElevenLabsConfig(val apiKey: String, val voiceId: String)

/**
 * Speaks Gemini's answer out the glasses' speaker.
 *
 * Voice selection:
 *  - When [elevenLabsConfig] returns a config (the user saved an ElevenLabs API
 *    key and picked a voice in Settings), the answer is synthesised to MP3 by
 *    [ElevenLabsClient] and played back. On any failure it falls back to the
 *    on-device [TextToSpeech] engine rather than going silent.
 *  - Otherwise the on-device engine is used directly.
 *
 * To FORCE playback to the glasses (a Bluetooth A2DP sink) rather than whatever
 * the system picks, we play through a [MediaPlayer] pinned to the A2DP
 * [AudioDeviceInfo] with [MediaPlayer.setPreferredDevice]. This matters because
 * right after mic capture the route can be stuck on SCO or the earpiece. When no
 * Bluetooth sink is present (e.g. emulator) playback uses the default output.
 */
class TtsSpeaker(
    context: Context,
    private val elevenLabs: ElevenLabsClient = ElevenLabsClient(),
    private val elevenLabsConfig: () -> ElevenLabsConfig? = { null },
) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val ready = CompletableDeferred<Boolean>()
    private val utteranceCounter = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val tts: TextToSpeech = TextToSpeech(appContext, ::onInit).apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                pending.remove(utteranceId)?.complete(false)
            }
        })
    }

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            tts.setAudioAttributes(mediaAttributes)
            ready.complete(true)
        } else {
            ready.complete(false)
        }
    }

    /** Speaks [text], using the configured ElevenLabs voice when set, else system TTS. */
    suspend fun speak(text: String) {
        if (text.isBlank()) return

        val config = elevenLabsConfig()
        if (config != null && speakWithElevenLabs(text, config)) return

        speakWithSystemTts(text)
    }

    /** Returns true if the ElevenLabs voice spoke the text; false to fall back. */
    private suspend fun speakWithElevenLabs(text: String, config: ElevenLabsConfig): Boolean {
        val mp3 = elevenLabs.synthesize(config.apiKey, config.voiceId, text)
        if (mp3 == null || mp3.isEmpty()) {
            Log.w(TAG, "ElevenLabs synthesis failed; falling back to system TTS")
            return false
        }
        val file = File(appContext.cacheDir, "tts_el_${nextId()}.mp3")
        return try {
            file.writeBytes(mp3)
            play(file, bluetoothOutputDevice())
            true
        } catch (t: Throwable) {
            Log.w(TAG, "ElevenLabs playback failed; falling back to system TTS", t)
            false
        } finally {
            runCatching { file.delete() }
        }
    }

    private suspend fun speakWithSystemTts(text: String) {
        if (!ready.await()) {
            Log.w(TAG, "TTS engine failed to initialise")
            return
        }
        val sink = bluetoothOutputDevice()
        if (sink != null) speakForcedTo(text, sink) else speakDirect(text)
    }

    private suspend fun speakDirect(text: String) {
        val id = nextId()
        val done = CompletableDeferred<Boolean>().also { pending[id] = it }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        done.await()
    }

    private suspend fun speakForcedTo(text: String, device: AudioDeviceInfo) {
        val id = nextId()
        val file = File(appContext.cacheDir, "tts_$id.wav")
        val synthDone = CompletableDeferred<Boolean>().also { pending[id] = it }

        val rc = tts.synthesizeToFile(text, android.os.Bundle(), file, id)
        if (rc != TextToSpeech.SUCCESS || !synthDone.await()) {
            pending.remove(id)
            speakDirect(text) // fall back rather than going silent
            return
        }
        play(file, device)
        runCatching { file.delete() }
    }

    /** Plays [file], pinned to [device] when non-null (the "force to glasses"). */
    private suspend fun play(file: File, device: AudioDeviceInfo?) =
        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            fun finish() {
                runCatching { player.release() }
                if (cont.isActive) cont.resume(Unit)
            }
            runCatching {
                player.setAudioAttributes(mediaAttributes)
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { finish() }
                player.setOnErrorListener { _, _, _ -> finish(); true }
                player.prepare()
                if (device != null) player.setPreferredDevice(device)
                player.start()
            }.onFailure {
                Log.w(TAG, "Playback failed", it)
                finish()
            }
            cont.invokeOnCancellation { runCatching { player.release() } }
        }

    /** The connected Bluetooth audio output (A2DP, or LE audio), if any. */
    private fun bluetoothOutputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    private fun nextId(): String = "utt_${utteranceCounter.incrementAndGet()}"

    private companion object {
        const val TAG = "TtsSpeaker"
    }
}
