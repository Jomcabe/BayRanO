package com.bayrano.gemini

/**
 * Static configuration for the Gemini call. The two live-tunable knobs
 * (mediaResolution, thinkingLevel) are NOT here — they come from Settings via a
 * [GenerationParams] provider so changes apply without rebuilding the client.
 *
 * REST casing (confirmed against the API): camelCase keys, mediaResolution
 * directly under generationConfig, thinkingLevel nested in thinkingConfig.
 */
data class GeminiConfig(
    val model: String = "gemini-3.5-flash",
    val systemInstruction: String =
        "You are BayRanO, a sharp, generally-intelligent assistant worn as smart " +
            "glasses. Be accurate and genuinely helpful across any topic — facts, " +
            "reasoning, advice, language, math. Answer in one or two short sentences, " +
            "phrased naturally for text-to-speech, and say plainly when you are unsure. " +
            "If an image is provided, ground your answer in what you actually see.",
    val elaborateInstruction: String =
        "You are BayRanO, a generally-intelligent assistant worn as smart glasses. " +
            "The user asked you to elaborate on your previous answer. Give a fuller, " +
            "accurate explanation, up to about four sentences, still natural to hear " +
            "via text-to-speech. If an image is provided, ground your answer in it.",

    /** Offer the google_search grounding tool and let Gemini decide when to use it. */
    val useGoogleSearch: Boolean = true,

    /** Number of prior user/model exchanges kept as conversational context. */
    val maxContextTurns: Int = 6,

    /** Retry policy for transient 429/503 responses (and network blips). */
    val maxRetries: Int = 3,
    val baseBackoffMs: Long = 500,
    val maxBackoffMs: Long = 8_000,
) {
    val endpoint: String
        get() = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
}
