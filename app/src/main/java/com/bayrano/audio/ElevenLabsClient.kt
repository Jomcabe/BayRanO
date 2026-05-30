package com.bayrano.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A voice available on the user's ElevenLabs account. */
data class ElevenLabsVoice(val id: String, val name: String)

/** Result of listing the account's voices. */
sealed interface VoicesResult {
    data class Success(val voices: List<ElevenLabsVoice>) : VoicesResult
    data class Error(val message: String) : VoicesResult
}

/**
 * Thin OkHttp wrapper over the ElevenLabs REST API. No SDK; JSON via [org.json]
 * (bundled with Android) to avoid pulling in a serialization library — same
 * approach as [com.bayrano.gemini.GeminiClient].
 *
 * Authenticated with the `xi-api-key` header.
 *  - [listVoices] backs the voice picker in Settings (GET /v1/voices).
 *  - [synthesize] turns answer text into MP3 audio (POST /v1/text-to-speech/{id}).
 */
class ElevenLabsClient(
    private val http: OkHttpClient = defaultHttp(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Fetch the voices available on [apiKey]'s account, for the Settings picker. */
    suspend fun listVoices(apiKey: String): VoicesResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext VoicesResult.Error("No ElevenLabs API key set.")

        val request = Request.Builder()
            .url("$BASE_URL/v1/voices")
            .header("xi-api-key", apiKey)
            .get()
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            return@withContext VoicesResult.Error("Network error: ${e.message}")
        }

        val code = response.code
        val payload = response.body?.string().orEmpty()
        response.close()

        if (code !in 200..299) {
            return@withContext VoicesResult.Error(extractApiError(payload) ?: "HTTP $code")
        }

        runCatching {
            val arr = JSONObject(payload).optJSONArray("voices")
            buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val v = arr.optJSONObject(i) ?: continue
                        val id = v.optString("voice_id").takeIf { it.isNotBlank() } ?: continue
                        val name = v.optString("name").ifBlank { id }
                        add(ElevenLabsVoice(id, name))
                    }
                }
            }
        }.fold(
            onSuccess = { VoicesResult.Success(it) },
            onFailure = { VoicesResult.Error("Could not parse voices response.") },
        )
    }

    /**
     * Synthesize [text] with [voiceId] and return MP3 bytes, or null on failure.
     * Runs on [Dispatchers.IO]; safe to call from a coroutine.
     */
    suspend fun synthesize(
        apiKey: String,
        voiceId: String,
        text: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || voiceId.isBlank() || text.isBlank()) return@withContext null

        val body = JSONObject()
            .put("text", text)
            .put("model_id", MODEL_ID)
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$BASE_URL/v1/text-to-speech/$voiceId")
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/mpeg")
            .post(body)
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (_: IOException) {
            return@withContext null
        }

        response.use {
            if (it.code !in 200..299) return@withContext null
            it.body?.bytes()
        }
    }

    private fun extractApiError(payload: String): String? = runCatching {
        // ElevenLabs errors look like {"detail":{"message":"..."}} or {"detail":"..."}.
        val detail = JSONObject(payload).opt("detail")
        when (detail) {
            is JSONObject -> detail.optString("message").takeIf { it.isNotBlank() }
            is String -> detail.takeIf { it.isNotBlank() }
            else -> null
        }
    }.getOrNull()

    companion object {
        private const val BASE_URL = "https://api.elevenlabs.io"
        // Low-latency multilingual model; good default for a wearable assistant.
        private const val MODEL_ID = "eleven_turbo_v2_5"

        private fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
