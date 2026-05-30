package com.bayrano.app

import android.app.Application
import com.bayrano.core.ApiKeyStore
import com.bayrano.core.AppPreferences
import com.bayrano.gemini.GeminiClient
import com.bayrano.glasses.CameraController
import com.bayrano.glasses.GlassesManager

/**
 * Process-wide singletons. Kept deliberately tiny — no DI framework for a
 * solo project; the [Application] is the composition root.
 */
class BayRanOApp : Application() {

    val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(this) }
    val appPreferences: AppPreferences by lazy { AppPreferences(this) }
    val geminiClient: GeminiClient by lazy { GeminiClient() }
    val glassesManager: GlassesManager by lazy { GlassesManager(this) }
    val cameraController: CameraController by lazy { CameraController(glassesManager) }

    override fun onCreate() {
        super.onCreate()
        glassesManager.initialize()
    }
}
