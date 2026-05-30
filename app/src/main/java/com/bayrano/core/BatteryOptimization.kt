package com.bayrano.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers to ask the OS to exempt BayRanO from battery optimization, so the
 * hands-free [com.bayrano.wake.WakeService] keeps running with the screen off.
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Launches the system dialog to request exemption. Requires the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (declared in the manifest).
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                // Fall back to the general list if the direct request is blocked.
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
    }
}
