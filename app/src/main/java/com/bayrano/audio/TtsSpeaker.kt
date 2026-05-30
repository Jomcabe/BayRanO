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

/**
 * Speaks Gemini's answer out the glasses' speaker via [TextToSpeech].
 *
 * To FORCE playback to the glasses (a Bluetooth A2DP sink) rather than whatever
 * the system picks, we synthesise to a file and play it through a [MediaPlayer]
 * pinned to the A2DP [AudioDeviceInfo] with [MediaPlayer.setPreferredDevice].
 * This matters because right after mic capture the route can be stuck on SCO or
 * the earpiece. When no Bluetooth sink is present (e.g. emulator) we fall back to
 * speaking directly through the default output.
 */
class TtsSpeaker(context: Context) {

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

    /** Speaks [text], forced to the Bluetooth A2DP sink when one is connected. */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
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
        playPinned(file, device)
        runCatching { file.delete() }
    }

    private suspend fun playPinned(file: File, device: AudioDeviceInfo) =
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
                player.setPreferredDevice(device) // the actual "force to glasses"
                player.start()
            }.onFailure {
                Log.w(TAG, "Pinned playback failed", it)
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
