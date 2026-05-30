package com.bayrano.glasses

/** High-level lifecycle of the glasses connection, surfaced to the UI. */
sealed interface GlassesState {
    /** App not yet registered with the glasses (one-time pairing handshake). */
    data object Unregistered : GlassesState

    /** Registered; opening a device session / discovering the glasses. */
    data object Connecting : GlassesState

    /** Session live, camera stream open, ready for capturePhoto(). */
    data object Ready : GlassesState

    /** A frame stream is actively flowing (usually only during capture). */
    data object Streaming : GlassesState

    data class Error(val message: String) : GlassesState
}
