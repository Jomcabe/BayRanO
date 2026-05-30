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
 * Two connect paths:
 *  - **Real glasses**: initialise [Wearables], wait for the registered device to
 *    surface, then createSession → addStream → start. [CameraController] pulls
 *    stills from the live [Stream].
 *  - **Mock device** (emulator / no hardware): runs entirely in-app. We do NOT
 *    touch the toolkit at all — the SDK's mock-device injection proved
 *    unreliable (empty registration manifest, GC'd state monitors, "No eligible
 *    device found"). Instead [connect] just goes Ready and [CameraController]
 *    serves a synthetic frame from [MockDeviceController].
 */
class GlassesManager(private val appContext: Context) {

    private val mock = MockDeviceController(appContext)

    private val _state = MutableStateFlow<GlassesState>(GlassesState.Unregistered)
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var initialized = false

    /** True while running against the in-app mock device rather than real glasses. */
    private var mockActive = false

    /** The live camera stream, if connected to real glasses — used by [CameraController]. */
    fun currentStream(): Stream? = stream

    /** Whether the current connection is the in-app mock (no real Wearables session). */
    fun isMockActive(): Boolean = mockActive

    /** A synthetic frame for mock mode, or null when not in mock mode. */
    fun mockFrameJpeg(maxDimPx: Int, quality: Int): ByteArray? =
        if (mockActive) mock.frameJpeg(maxDimPx, quality) else null

    /** Initialise the real-glasses SDK once per process. Safe to call repeatedly. */
    private fun initialize() {
        if (initialized) return
        runCatching { Wearables.initialize(appContext) }
            .onFailure { Log.w(TAG, "Wearables.initialize failed", it) }
        initialized = true
    }

    /**
     * Open a device session and a camera stream so [CameraController] can capture.
     *
     * @param useMock when true, run the in-app mock path (no Wearables SDK).
     */
    suspend fun connect(useMock: Boolean) {
        if (_state.value == GlassesState.Ready) return
        _state.value = GlassesState.Connecting

        if (useMock) {
            // Mock mode is fully self-contained: no SDK init, no device session.
            // CameraController.capturePhotoJpeg() serves MockDeviceController's
            // synthetic frame directly. This avoids the toolkit's flaky mock
            // injection entirely.
            mockActive = true
            _state.value = GlassesState.Ready
            return
        }

        mockActive = false
        try {
            initialize()

            if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
                fail("Glasses not registered. Register from Settings, or enable the mock device.")
                return
            }

            // Wait for a device to surface in discovery before selecting one.
            val haveDevice = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                Wearables.devices.first { it.isNotEmpty() }
            } != null
            if (!haveDevice) {
                fail("No glasses found.")
                return
            }

            val sessionResult = Wearables.createSession(AutoDeviceSelector())
            val newSession = sessionResult.getOrNull()
            if (newSession == null) {
                Log.e(TAG, "createSession failed", sessionResult.exceptionOrNull())
                fail("Could not create a device session.")
                return
            }
            newSession.start()
            withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                newSession.state.first { it == DeviceSessionState.STARTED }
            }

            val config = StreamConfiguration(VideoQuality.MEDIUM, FRAME_RATE, /* compressVideo = */ true)
            val streamResult = newSession.addStream(config)
            val newStream = streamResult.getOrNull()
            if (newStream == null) {
                Log.e(TAG, "addStream failed", streamResult.exceptionOrNull())
                runCatching { newSession.stop() }
                fail("Could not start the camera stream.")
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
            fail(t.message ?: "Connection failed.")
        }
    }

    fun disconnect() {
        runCatching { session?.removeStream() }
        runCatching { session?.stop() }
        stream = null
        session = null
        mockActive = false
        _state.value = GlassesState.Unregistered
    }

    /** Tear down any half-open session and surface the error. */
    private fun fail(message: String) {
        runCatching { session?.removeStream() }
        runCatching { session?.stop() }
        stream = null
        session = null
        _state.value = GlassesState.Error(message)
    }

    private companion object {
        const val TAG = "GlassesManager"
        const val FRAME_RATE = 15 // valid: 2, 7, 15, 24, 30
        const val DISCOVERY_TIMEOUT_MS = 8_000L
        const val SESSION_TIMEOUT_MS = 5_000L
        const val STREAM_TIMEOUT_MS = 5_000L
    }
}
