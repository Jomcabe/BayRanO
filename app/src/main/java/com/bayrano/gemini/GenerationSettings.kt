package com.bayrano.gemini

/**
 * User-tunable generation knobs, surfaced in Settings. Defaults favour speed on a
 * wearable: medium media resolution and low thinking.
 */
enum class MediaResolution(val apiValue: String, val label: String) {
    LOW("media_resolution_low", "Low"),
    MEDIUM("media_resolution_medium", "Medium"),
    HIGH("media_resolution_high", "High"),
}

enum class ThinkingLevel(val apiValue: String, val label: String) {
    MINIMAL("minimal", "Minimal"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
}

/** Snapshot of the two generation knobs, read per request so Settings changes apply live. */
data class GenerationParams(
    val mediaResolution: String = MediaResolution.MEDIUM.apiValue,
    val thinkingLevel: String = ThinkingLevel.LOW.apiValue,
)
