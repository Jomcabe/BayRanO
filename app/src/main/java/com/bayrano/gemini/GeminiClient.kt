package com.bayrano.gemini

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Result of a generateContent call. */
sealed interface GeminiResult {
    data class Success(val text: String) : GeminiResult
    data class Error(val message: String, val httpCode: Int? = null) : GeminiResult
}

/**
 * Thin OkHttp wrapper over the Gemini REST `generateContent` endpoint
 * (gemini-3.5-flash), authenticated with the `x-goog-api-key` header. No SDK, to
 * avoid version drift; JSON is built/parsed with [org.json] (bundled with
 * Android) so we pull in no serialization library.
 *
 * Features:
 *  - image (inline_data base64 JPEG) + question text + system instruction
 *  - Grounding with Google Search (the `google_search` tool) when requested
 *  - the last N user/model turns sent as conversational context
 *  - exponential-backoff retry on HTTP 429/503 (and network blips)
 *
 * The two speed knobs come from [generationParams] (Settings-backed) so changes
 * apply per request.
 */
class GeminiClient(
    private val config: GeminiConfig = GeminiConfig(),
    private val generationParams: () -> GenerationParams = { GenerationParams() },
    private val http: OkHttpClient = defaultHttp(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** The model id used for requests (for logging/telemetry). */
    val modelName: String get() = config.model

    private data class Turn(val role: String, val text: String) // role: "user" | "model"
    private val historyLock = Any()
    private val history = ArrayDeque<Turn>()

    /** Drop all conversational context (e.g. when starting a new session). */
    fun resetConversation() = synchronized(historyLock) { history.clear() }

    /**
     * Send [prompt] (plus an optional JPEG and the kept context) and return the
     * model's text. Runs on [Dispatchers.IO]; safe to call from a coroutine.
     *
     * @param detailed use the "elaborate" system instruction (longer answer).
     *
     * Grounding: the google_search tool is always offered (see [GeminiConfig.useGoogleSearch])
     * and Gemini itself decides whether to actually search — we don't gate it.
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
        val priorTurns = synchronized(historyLock) { history.toList() }
        val body = buildRequestJson(prompt, jpegBytes, systemInstruction, priorTurns)
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(config.endpoint)
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()

        var attempt = 0
        while (true) {
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                if (attempt < config.maxRetries) {
                    delay(backoffDelayMs(attempt, retryAfterMs = null))
                    attempt++
                    continue
                }
                return@withContext GeminiResult.Error("Network error: ${e.message}")
            }

            val code = response.code
            val retryAfter = response.header("Retry-After")
            val payload = response.body?.string().orEmpty()
            response.close()

            if (code in 200..299) {
                val text = parseText(payload)
                return@withContext if (text != null) {
                    recordTurn(prompt, text)
                    GeminiResult.Success(text)
                } else {
                    GeminiResult.Error("Empty or unparseable response.")
                }
            }

            val retryable = code == 429 || code == 503
            if (retryable && attempt < config.maxRetries) {
                delay(backoffDelayMs(attempt, parseRetryAfterMs(retryAfter)))
                attempt++
                continue
            }

            return@withContext GeminiResult.Error(
                message = extractApiError(payload) ?: "HTTP $code",
                httpCode = code,
            )
        }
        @Suppress("UNREACHABLE_CODE")
        GeminiResult.Error("Unreachable")
    }

    private fun buildRequestJson(
        prompt: String,
        jpegBytes: ByteArray?,
        systemInstruction: String,
        priorTurns: List<Turn>,
    ): JSONObject {
        val contents = JSONArray()
        // Prior context turns (text only — we don't resend old images).
        for (turn in priorTurns) {
            contents.put(
                JSONObject()
                    .put("role", turn.role)
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text))),
            )
        }

        // Current user turn: text + optional inline image.
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (jpegBytes != null) {
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", b64),
                ),
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", parts))

        // camelCase knobs: mediaResolution direct; thinkingLevel nested. Never
        // send thinkingBudget alongside thinkingLevel (both 400).
        val params = generationParams()
        val generationConfig = JSONObject()
            .put("mediaResolution", params.mediaResolution)
            .put("thinkingConfig", JSONObject().put("thinkingLevel", params.thinkingLevel))

        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction)),
                ),
            )

        if (config.useGoogleSearch) {
            // Grounding with Google Search. The tool is always offered; Gemini
            // decides per request whether it actually needs to search the web.
            body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        return body
    }

    /** Append the exchange to context and trim to the last [GeminiConfig.maxContextTurns]. */
    private fun recordTurn(userText: String, modelText: String) = synchronized(historyLock) {
        history.addLast(Turn("user", userText))
        history.addLast(Turn("model", modelText))
        val maxEntries = config.maxContextTurns * 2
        while (history.size > maxEntries) history.removeFirst()
    }

    private fun backoffDelayMs(attempt: Int, retryAfterMs: Long?): Long {
        if (retryAfterMs != null) return retryAfterMs.coerceIn(0, config.maxBackoffMs)
        val exponential = config.baseBackoffMs shl attempt // base * 2^attempt
        val jitter = Random.nextLong(0, config.baseBackoffMs)
        return (exponential + jitter).coerceAtMost(config.maxBackoffMs)
    }

    /** Parse a numeric `Retry-After` (seconds) to milliseconds; ignore HTTP-date form. */
    private fun parseRetryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.let { it * 1000 }

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
