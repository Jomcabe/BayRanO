package com.bayrano.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Routes the *input* (mic) path through the glasses over Bluetooth. The Meta
 * toolkit does NOT expose audio — mic and speaker are reached via standard
 * Android Bluetooth (HFP/SCO for two-way voice, A2DP for output). HFP is 8 kHz
 * mono.
 *
 * Used for speech capture: [routeToGlasses] before recognition, then
 * [clearRoute] afterwards so the output path is free to play TTS over A2DP (see
 * [TtsSpeaker]). The communication-device APIs are API 31+, so on API 30 this is
 * a no-op and the default mic is used.
 */
class BluetoothAudioRouter(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The connected Bluetooth SCO device (the glasses), if present. */
    private fun scoDevice(): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            null
        }

    /** Route communication (mic) audio to the glasses. No-op below API 31. */
    fun routeToGlasses(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = scoDevice() ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    fun clearRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }
}
