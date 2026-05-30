package com.bayrano.gemini

/**
 * Tunables for the Gemini call. Defaults favour low latency for a wearable:
 * [thinkingLevel] "minimal" (Flash-only) and a single high-resolution image.
 *
 * NOTE: the REST field casing for these knobs (mediaResolution /
 * thinkingConfig.thinkingLevel vs. the snake_case names in the docs) should be
 * confirmed against a live call before relying on them — see GeminiClient.
 */
data class GeminiConfig(
    val model: String = "gemini-3.5-flash",
    val mediaResolution: String = "media_resolution_high",
    val thinkingLevel: String = "minimal",
    val systemInstruction: String =
        "You are BayRanO, a concise spoken assistant for smart glasses. " +
            "Answer in one or two short sentences suitable for text-to-speech. " +
            "If an image is provided, ground your answer in what you see.",
    val elaborateInstruction: String =
        "You are BayRanO, a spoken assistant for smart glasses. The user asked you " +
            "to elaborate on your previous answer. Give a fuller explanation, up to " +
            "about four sentences, still natural to hear via text-to-speech. " +
            "If an image is provided, ground your answer in what you see.",
) {
    val endpoint: String
        get() = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
}
