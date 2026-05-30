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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    // Process-lived scope. Its collectors below hold strong references to the
    // SDK's state monitors, which the toolkit otherwise keeps only weakly — see
    // [startMonitors].
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<GlassesState>(GlassesState.Unregistered)
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    // Mirrors of the SDK flows. Reading these (instead of one-shot `.value` /
    // `.first {}` against Wearables directly) goes through the long-lived
    // collectors started in [startMonitors], so the monitors stay alive.
    private val registration = MutableStateFlow<RegistrationState?>(null)
    private val hasDevice = MutableStateFlow(false)

    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var initialized = false
    private var monitorsStarted = false

    /** The live camera stream, if connected — used by [CameraController]. */
    fun currentStream(): Stream? = stream

    /** Initialise the SDK once per process. Safe to call repeatedly. */
    fun initialize() {
        if (initialized) return
        runCatching { Wearables.initialize(appContext) }
            .onFailure { Log.w(TAG, "Wearables.initialize failed", it) }
        startMonitors()
        initialized = true
    }

    /**
     * Keep long-lived collectors on the SDK's registration and device flows.
     *
     * The toolkit holds its `ACDC` state monitors via *weak* references: once
     * nothing is actively collecting `Wearables.registrationState` /
     * `Wearables.devices`, the monitors get garbage-collected, registration
     * "reverts to null", and an injected mock device drops out of discovery.
     * That made [connect] fail intermittently with "Could not create a device
     * session" depending on GC timing. Collecting for the life of the process
     * pins the monitors so the state stays valid through the whole connect.
     */
    private fun startMonitors() {
        if (monitorsStarted) return
        monitorsStarted = true
        Wearables.registrationState
            .onEach { registration.value = it }
            .launchIn(scope)
        Wearables.devices
            .onEach { hasDevice.value = it.isNotEmpty() }
            .launchIn(scope)
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
            } else {
                // Await registration settling rather than reading a single
                // synchronous snapshot, which can be stale right after init.
                val registered = withTimeoutOrNull(REGISTRATION_TIMEOUT_MS) {
                    registration.first { it == RegistrationState.REGISTERED }
                } != null
                if (!registered) {
                    fail("Glasses not registered. Register from Settings, or enable the mock device.")
                    return
                }
            }

            // Wait for a device to surface in discovery before selecting one.
            val haveDevice = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                hasDevice.first { it }
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
        mock.disable()
        _state.value = GlassesState.Unregistered
    }

    /** Tear down any half-open session/mock state and surface the error. */
    private fun fail(message: String) {
        runCatching { session?.removeStream() }
        runCatching { session?.stop() }
        stream = null
        session = null
        mock.disable()
        _state.value = GlassesState.Error(message)
    }

    private companion object {
        const val TAG = "GlassesManager"
        const val FRAME_RATE = 15 // valid: 2, 7, 15, 24, 30
        const val REGISTRATION_TIMEOUT_MS = 3_000L
        const val DISCOVERY_TIMEOUT_MS = 8_000L
        const val SESSION_TIMEOUT_MS = 5_000L
        const val STREAM_TIMEOUT_MS = 5_000L
    }
}
