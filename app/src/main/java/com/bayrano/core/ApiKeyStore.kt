package com.bayrano.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bayrano.BuildConfig

/**
 * Persists the Gemini API key in [EncryptedSharedPreferences] (AES-256, backed
 * by a Keystore master key).
 *
 * On first run the key falls back to [BuildConfig.GEMINI_API_KEY] (read from
 * local.properties at build time) so a freshly-built debug app works without a
 * manual paste. Anything the user enters on the Settings screen overrides it.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The effective key: user-entered value, else the build-time default. */
    fun getGeminiKey(): String =
        prefs.getString(KEY_GEMINI, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GEMINI_API_KEY

    /** True once the user has explicitly saved a key (vs. the build default). */
    fun hasUserKey(): Boolean = !prefs.getString(KEY_GEMINI, null).isNullOrBlank()

    fun setGeminiKey(value: String) {
        prefs.edit().putString(KEY_GEMINI, value.trim()).apply()
    }

    fun clearGeminiKey() {
        prefs.edit().remove(KEY_GEMINI).apply()
    }

    /** The ElevenLabs API key, or "" when the user hasn't set one (system TTS). */
    fun getElevenLabsKey(): String =
        prefs.getString(KEY_ELEVENLABS, null)?.takeIf { it.isNotBlank() } ?: ""

    fun hasElevenLabsKey(): Boolean = !prefs.getString(KEY_ELEVENLABS, null).isNullOrBlank()

    fun setElevenLabsKey(value: String) {
        prefs.edit().putString(KEY_ELEVENLABS, value.trim()).apply()
    }

    fun clearElevenLabsKey() {
        prefs.edit().remove(KEY_ELEVENLABS).apply()
    }

    private companion object {
        const val PREFS_NAME = "bayrano_secure_prefs"
        const val KEY_GEMINI = "gemini_api_key"
        const val KEY_ELEVENLABS = "elevenlabs_api_key"
    }
}
