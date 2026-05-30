package com.bayrano.app

import android.app.Application
import com.bayrano.assistant.AssistantEngine
import com.bayrano.core.ApiKeyStore
import com.bayrano.core.AppPreferences
import com.bayrano.data.AppDatabase
import com.bayrano.data.QueryLogDao
import com.bayrano.gemini.GeminiClient
import com.bayrano.gemini.GenerationParams
import com.bayrano.glasses.CameraController
import com.bayrano.glasses.GlassesManager

/**
 * Process-wide singletons. Kept deliberately tiny — no DI framework for a
 * solo project; the [Application] is the composition root.
 */
class BayRanOApp : Application() {

    val apiKeyStore: ApiKeyStore by lazy { ApiKeyStore(this) }
    val appPreferences: AppPreferences by lazy { AppPreferences(this) }
    val geminiClient: GeminiClient by lazy {
        // Read the speed knobs from Settings on every request so changes apply live.
        GeminiClient(generationParams = {
            GenerationParams(
                mediaResolution = appPreferences.mediaResolution.apiValue,
                thinkingLevel = appPreferences.thinkingLevel.apiValue,
            )
        })
    }
    val glassesManager: GlassesManager by lazy { GlassesManager(this) }
    val cameraController: CameraController by lazy { CameraController(glassesManager) }

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val queryLogDao: QueryLogDao by lazy { database.queryLogDao() }

    /** Process-lifetime assistant pipeline, shared by the UI, QS tile, and WakeService. */
    val assistantEngine: AssistantEngine by lazy {
        AssistantEngine(
            context = this,
            gemini = geminiClient,
            apiKeyStore = apiKeyStore,
            prefs = appPreferences,
            glasses = glassesManager,
            camera = cameraController,
            queryLogDao = queryLogDao,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Note: Wearables is intentionally NOT initialised here. It can only be
        // initialised once per process, and the Mock Device Kit must be enabled
        // beforehand — so init is deferred to the first GlassesManager.connect(),
        // which enables the mock first when the mock device is selected.
    }
}
