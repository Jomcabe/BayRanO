package com.bayrano.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that will host the always-available assistant pipeline:
 * keep the glasses session warm, listen for the trigger, run STT → capture →
 * Gemini → TTS. Declared with foregroundServiceType="microphone|connectedDevice"
 * in the manifest.
 *
 * SKELETON — starts/stops a foreground notification; pipeline wiring is TODO.
 */
class AssistantService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForegroundCompat()
        }
        // TODO: bind GlassesManager + audio pipeline; observe the trigger source.
        return START_STICKY
    }

    private fun startForegroundCompat() {
        createChannel()
        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BayRanO")
                .setContentText("Assistant ready")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BayRanO Assistant",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "bayrano_assistant"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.bayrano.action.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AssistantService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AssistantService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
