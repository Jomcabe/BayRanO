package com.bayrano.core

import android.content.Context
import com.bayrano.gemini.MediaResolution
import com.bayrano.gemini.ThinkingLevel
import com.bayrano.wake.WakeTrigger

/** Small non-secret prefs (the secret Gemini key lives in [ApiKeyStore]). */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Default on so the app connects to the mock device on an emulator out of the box. */
    var useMockDevice: Boolean
        get() = prefs.getBoolean(KEY_MOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_MOCK, value).apply()

    /** Whether the hands-free wake service (holds audio focus) is running. */
    var wakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_ENABLED, value).apply()

    /** Which glasses gesture triggers the assistant. */
    var wakeTrigger: WakeTrigger
        get() = prefs.getString(KEY_WAKE_TRIGGER, null)
            ?.let { runCatching { WakeTrigger.valueOf(it) }.getOrNull() }
            ?: WakeTrigger.VOLUME_UP
        set(value) = prefs.edit().putString(KEY_WAKE_TRIGGER, value.name).apply()

    /** Gemini speed knobs, defaulting to medium resolution / low thinking. */
    var mediaResolution: MediaResolution
        get() = prefs.getString(KEY_MEDIA_RES, null)
            ?.let { runCatching { MediaResolution.valueOf(it) }.getOrNull() }
            ?: MediaResolution.MEDIUM
        set(value) = prefs.edit().putString(KEY_MEDIA_RES, value.name).apply()

    var thinkingLevel: ThinkingLevel
        get() = prefs.getString(KEY_THINKING, null)
            ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
            ?: ThinkingLevel.LOW
        set(value) = prefs.edit().putString(KEY_THINKING, value.name).apply()

    /**
     * The chosen ElevenLabs voice id. When set (and an ElevenLabs API key is
     * stored) answers are spoken with that voice instead of the system TTS.
     */
    var elevenLabsVoiceId: String?
        get() = prefs.getString(KEY_ELEVENLABS_VOICE_ID, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_VOICE_ID, value).apply()

    /** Display name of the chosen voice, for showing the current selection in Settings. */
    var elevenLabsVoiceName: String?
        get() = prefs.getString(KEY_ELEVENLABS_VOICE_NAME, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_VOICE_NAME, value).apply()

    private companion object {
        const val PREFS_NAME = "bayrano_prefs"
        const val KEY_MOCK = "use_mock_device"
        const val KEY_MEDIA_RES = "media_resolution"
        const val KEY_THINKING = "thinking_level"
        const val KEY_WAKE_ENABLED = "wake_enabled"
        const val KEY_WAKE_TRIGGER = "wake_trigger"
        const val KEY_ELEVENLABS_VOICE_ID = "elevenlabs_voice_id"
        const val KEY_ELEVENLABS_VOICE_NAME = "elevenlabs_voice_name"
    }
}
