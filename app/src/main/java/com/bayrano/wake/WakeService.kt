package com.bayrano.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import com.bayrano.app.BayRanOApp
import com.bayrano.app.MainActivity
import com.bayrano.assistant.AssistantUiState
import com.bayrano.assistant.TranscriptEntry
import com.bayrano.core.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground media service that turns a glasses gesture into an assistant wake.
 *
 * ## How the gesture reaches us
 * On Ray-Ban Meta, a forward temple swipe is delivered to the phone over
 * Bluetooth AVRCP as a **media "volume up"**, and a double-tap as a media
 * **"skip to next"**. Android routes those transport events to whichever app is
 * the *active media session*. So to receive them we:
 *
 *  1. create a [MediaSessionCompat], give it a PLAYING playback state, mark it
 *     active, and attach a [VolumeProviderCompat] (VOLUME_CONTROL_RELATIVE);
 *  2. call [MediaSessionCompat.setPlaybackToRemote] so volume adjustments are
 *     delivered to our provider's [VolumeProviderCompat.onAdjustVolume] instead
 *     of changing the music stream;
 *  3. keep ourselves the active session by **playing a silent, looping,
 *     inaudible track** and holding audio focus — otherwise the system hands the
 *     active-session role (and the AVRCP events) to some other app.
 *
 * ## IMPORTANT LIMITATION
 * This ONLY works while **BayRanO owns audio focus** — i.e. no other music/video
 * app is actively playing. If you start Spotify/YouTube, that app becomes the
 * active media session, AVRCP volume/skip events go to *it*, and our swipe
 * trigger goes dark until we regain focus. That's an inherent constraint of
 * piggy-backing on the media transport, not a bug. The on-screen button and the
 * Quick Settings tile always work regardless of focus.
 *
 * ## Volume handling
 * On "volume up" we fire the assistant and DO NOT change the real volume; we also
 * reset our provider's currentVolume back to the midpoint so the perceived volume
 * never drifts and we keep receiving further up/down events.
 *
 * ## Last-resort fallback
 * Some devices don't deliver AVRCP volume to the VolumeProvider. As a backstop we
 * also watch [AudioManager.STREAM_MUSIC] via a [ContentObserver]; if the music
 * volume actually ticks up, we treat it as the wake gesture and restore the
 * previous volume. A short debounce prevents double-firing with the provider.
 */
class WakeService : Service() {

    private lateinit var prefs: AppPreferences
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var session: MediaSessionCompat? = null
    private var silentPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var volumeObserver: ContentObserver? = null

    private var lastWakeAtMs = 0L
    private var lastObservedMusicVol = 0

    private val trigger: WakeTrigger get() = prefs.wakeTrigger

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        lastObservedMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        requestAudioFocus()
        ensureMediaSession()
        startSilentPlayback()
        registerVolumeObserver()
        observeAssistant()
        return START_STICKY
    }

    // --- Media session + volume interception ------------------------------------

    private fun ensureMediaSession() {
        if (session != null) return
        val s = MediaSessionCompat(this, "BayRanOWake")
        s.setCallback(object : MediaSessionCompat.Callback() {
            // Double-tap on the glasses arrives here as "skip to next".
            override fun onSkipToNext() {
                if (trigger == WakeTrigger.DOUBLE_TAP) fireWake("double-tap")
            }
            override fun onPlay() {}
            override fun onPause() {}
        })
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
                )
                // Looks like ongoing playback so we stay the active session.
                .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                .build(),
        )

        // Relative remote volume: hardware/AVRCP volume keys are delivered to
        // onAdjustVolume instead of changing the music stream.
        val volumeProvider = object : VolumeProviderCompat(
            VOLUME_CONTROL_RELATIVE,
            /* maxVolume = */ MAX_VOLUME,
            /* currentVolume = */ MID_VOLUME,
        ) {
            override fun onAdjustVolume(direction: Int) {
                // direction: +1 = up (swipe forward), -1 = down, 0 = unknown.
                if (trigger == WakeTrigger.VOLUME_UP && direction > 0) {
                    fireWake("volume-up")
                }
                // Keep perceived volume unchanged and keep headroom both ways.
                currentVolume = MID_VOLUME
            }

            override fun onSetVolumeTo(volume: Int) {
                currentVolume = MID_VOLUME
            }
        }
        s.setPlaybackToRemote(volumeProvider)
        s.isActive = true
        session = s
    }

    // --- Silent playback to retain audio focus / active-session ownership -------

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // GAIN takes focus from any other media app — required to be the active
        // session, and the reason this only works when nothing else is playing.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { /* loss handled implicitly */ }
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun startSilentPlayback() {
        if (silentPlayer != null) return
        val file = writeSilentWav()
        silentPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setDataSource(file.absolutePath)
            isLooping = true
            setVolume(0f, 0f) // inaudible; it exists only to hold the session
            setOnPreparedListener { start() }
            prepareAsync()
        }
    }

    /** A tiny mono 16-bit PCM WAV of silence, looped forever. */
    private fun writeSilentWav(): File {
        val file = File(cacheDir, "silence.wav")
        if (file.exists() && file.length() > 0) return file
        val sampleRate = 8000
        val seconds = 1
        val dataLen = sampleRate * seconds * 2 // 16-bit mono
        val totalLen = 36 + dataLen
        FileOutputStream(file).use { out ->
            fun le32(v: Int) = byteArrayOf(
                (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
            )
            fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
            out.write("RIFF".toByteArray()); out.write(le32(totalLen)); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
            out.write(le32(sampleRate)); out.write(le32(sampleRate * 2)); out.write(le16(2)); out.write(le16(16))
            out.write("data".toByteArray()); out.write(le32(dataLen))
            out.write(ByteArray(dataLen)) // zeros = silence
        }
        return file
    }

    // --- Fallback: watch the music stream volume directly -----------------------

    private fun registerVolumeObserver() {
        if (volumeObserver != null) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (current > lastObservedMusicVol && trigger == WakeTrigger.VOLUME_UP) {
                    fireWake("volume-observer")
                    // Restore so the user never actually loses/gains volume.
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, lastObservedMusicVol, 0)
                }
                lastObservedMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volumeObserver = observer
    }

    // --- Wake firing (debounced) ------------------------------------------------

    private fun fireWake(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeAtMs < WAKE_DEBOUNCE_MS) return
        lastWakeAtMs = now
        Log.i(TAG, "Wake fired from $source")
        (application as BayRanOApp).assistantEngine.onWake()
    }

    // --- Foreground notification ------------------------------------------------

    private fun startForegroundCompat() {
        createChannel()
        val notification = buildNotification(IDLE_TEXT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BayRanO hands-free")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    /** Keep the persistent notification in sync with the assistant loop's state. */
    private fun observeAssistant() {
        (application as BayRanOApp).assistantEngine.uiState
            .onEach { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(statusText(state)))
            }
            .launchIn(serviceScope)
    }

    private fun statusText(state: AssistantUiState): String = when {
        state.isListening -> "Listening…"
        state.isBusy -> "Thinking…"
        else -> state.transcript.lastOrNull {
            it.speaker == TranscriptEntry.Speaker.ASSISTANT
        }?.text?.take(90) ?: IDLE_TEXT
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "BayRanO Wake", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { silentPlayer?.stop(); silentPlayer?.release() }
        silentPlayer = null
        runCatching { focusRequest?.let { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
        runCatching { volumeObserver?.let { contentResolver.unregisterContentObserver(it) } }
        volumeObserver = null
        runCatching { session?.isActive = false; session?.release() }
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WakeService"
        private const val CHANNEL_ID = "bayrano_wake"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.bayrano.wake.STOP"
        private const val IDLE_TEXT = "Swipe forward on your glasses to ask"
        private const val WAKE_DEBOUNCE_MS = 1_200L

        // Relative volume range: midpoint with ±1 headroom so every adjust registers.
        private const val MAX_VOLUME = 2
        private const val MID_VOLUME = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WakeService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
