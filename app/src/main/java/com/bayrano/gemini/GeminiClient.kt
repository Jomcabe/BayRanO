package com.bayrano.gemini

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of a generateContent call. */
sealed interface GeminiResult {
    data class Success(val text: String) : GeminiResult
    data class Error(val message: String, val httpCode: Int? = null) : GeminiResult
}

/**
 * Thin OkHttp wrapper over the Gemini REST `generateContent` endpoint. No SDK,
 * to avoid version drift. JSON is built/parsed with [org.json] (bundled with
 * Android) so we pull in no serialization library.
 */
class GeminiClient(
    private val config: GeminiConfig = GeminiConfig(),
    private val http: OkHttpClient = defaultHttp(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Sends [prompt] plus an optional JPEG ([jpegBytes]) and returns the model's
     * text. Runs on [Dispatchers.IO]; safe to call from a coroutine.
     */
    suspend fun ask(
        apiKey: String,
        prompt: String,
        jpegBytes: ByteArray? = null,
        detailed: Boolean = false,
    ): GeminiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext GeminiResult.Error("No Gemini API key configured.")
        }

        val systemInstruction =
            if (detailed) config.elaborateInstruction else config.systemInstruction
        val body = buildRequestJson(prompt, jpegBytes, systemInstruction).toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(config.endpoint)
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext GeminiResult.Error(
                        message = extractApiError(payload) ?: "HTTP ${response.code}",
                        httpCode = response.code,
                    )
                }
                parseText(payload)
                    ?.let { GeminiResult.Success(it) }
                    ?: GeminiResult.Error("Empty or unparseable response.")
            }
        } catch (e: IOException) {
            GeminiResult.Error("Network error: ${e.message}")
        }
    }

    private fun buildRequestJson(
        prompt: String,
        jpegBytes: ByteArray?,
        systemInstruction: String,
    ): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (jpegBytes != null) {
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", b64),
                ),
            )
        }

        val contents = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("parts", parts),
        )

        // Gemini-3 knobs. Casing is the documented snake_case; confirm against a
        // live call and switch to mediaResolution / thinkingConfig if needed.
        val generationConfig = JSONObject()
            .put("media_resolution", config.mediaResolution)
            .put("thinking_level", config.thinkingLevel)

        return JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction)),
                ),
            )
    }

    private fun parseText(payload: String): String? = runCatching {
        JSONObject(payload)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.let { parts ->
                buildString {
                    for (i in 0 until parts.length()) {
                        parts.optJSONObject(i)?.optString("text")?.let(::append)
                    }
                }
            }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun extractApiError(payload: String): String? = runCatching {
        JSONObject(payload).optJSONObject("error")?.optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
