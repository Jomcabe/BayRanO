package com.bayrano.core

import android.content.Context

/** Small non-secret prefs (the secret Gemini key lives in [ApiKeyStore]). */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Default on so the app connects to the mock device on an emulator out of the box. */
    var useMockDevice: Boolean
        get() = prefs.getBoolean(KEY_MOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_MOCK, value).apply()

    private companion object {
        const val PREFS_NAME = "bayrano_prefs"
        const val KEY_MOCK = "use_mock_device"
    }
}
