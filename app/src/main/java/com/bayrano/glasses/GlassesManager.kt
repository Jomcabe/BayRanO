package com.bayrano.glasses

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.removeStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns everything that touches the Meta Wearables Device Access Toolkit — the
 * only class allowed to import `com.meta.wearable.*`, so SDK 0.x churn stays
 * contained here.
 *
 * The connect path is identical for real glasses and the emulator: the Mock
 * Device Kit ([MockDeviceController]) injects a fake device into [Wearables]
 * discovery, then the same createSession → addStream → start sequence runs.
 */
class GlassesManager(private val appContext: Context) {

    private val mock = MockDeviceController(appContext)

    private val _state = MutableStateFlow<GlassesState>(GlassesState.Unregistered)
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var initialized = false

    /** The live camera stream, if connected — used by [CameraController]. */
    fun currentStream(): Stream? = stream

    /** Initialise the SDK once per process. Safe to call repeatedly. */
    fun initialize() {
        if (initialized) return
        runCatching { Wearables.initialize(appContext) }
            .onFailure { Log.w(TAG, "Wearables.initialize failed", it) }
        initialized = true
    }

    /**
     * Open a device session and a camera stream so [CameraController] can capture.
     *
     * @param useMock when true, enable the Mock Device Kit first (emulator path).
     */
    suspend fun connect(useMock: Boolean) {
        if (_state.value == GlassesState.Ready) return
        _state.value = GlassesState.Connecting
        try {
            initialize()

            if (useMock) {
                mock.enableAndPair()
            } else if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
                _state.value = GlassesState.Error(
                    "Glasses not registered. Register from Settings, or enable the mock device.",
                )
                return
            }

            // Wait for a device to surface in discovery before selecting one.
            val haveDevice = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                Wearables.devices.first { it.isNotEmpty() }
            } != null
            if (!haveDevice) {
                _state.value = GlassesState.Error("No glasses found.")
                return
            }

            val newSession = Wearables.createSession(AutoDeviceSelector()).getOrNull()
            if (newSession == null) {
                _state.value = GlassesState.Error("Could not create a device session.")
                return
            }
            newSession.start()
            withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                newSession.state.first { it == DeviceSessionState.STARTED }
            }

            val config = StreamConfiguration(VideoQuality.MEDIUM, FRAME_RATE, /* compressVideo = */ true)
            val newStream = newSession.addStream(config).getOrNull()
            if (newStream == null) {
                _state.value = GlassesState.Error("Could not start the camera stream.")
                newSession.stop()
                return
            }
            newStream.start()
            withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                newStream.state.first { it == StreamState.STARTED || it == StreamState.STREAMING }
            }

            session = newSession
            stream = newStream
            _state.value = GlassesState.Ready
        } catch (t: Throwable) {
            Log.e(TAG, "connect failed", t)
            _state.value = GlassesState.Error(t.message ?: "Connection failed.")
        }
    }

    fun disconnect() {
        runCatching { session?.removeStream() }
        runCatching { session?.stop() }
        stream = null
        session = null
        mock.disable()
        _state.value = GlassesState.Unregistered
    }

    private companion object {
        const val TAG = "GlassesManager"
        const val FRAME_RATE = 15 // valid: 2, 7, 15, 24, 30
        const val DISCOVERY_TIMEOUT_MS = 8_000L
        const val SESSION_TIMEOUT_MS = 5_000L
        const val STREAM_TIMEOUT_MS = 5_000L
    }
}
