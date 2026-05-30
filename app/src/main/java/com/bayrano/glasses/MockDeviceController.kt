package com.bayrano.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockRaybanMeta
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the toolkit's Mock Device Kit so the app runs end-to-end on an emulator
 * with no real glasses. Enabling the kit injects a fake Ray-Ban Meta into the
 * standard [com.meta.wearable.dat.core.Wearables] discovery flow, so the rest of
 * [GlassesManager] uses the exact same session/stream/capture code path as real
 * hardware.
 *
 * The mock camera is fed a synthetic JPEG (generated at runtime, no bundled
 * binary), so [com.meta.wearable.dat.camera.Stream.capturePhoto] returns a
 * deterministic, recognisable frame.
 */
class MockDeviceController(private val context: Context) {

    private var kit: MockDeviceKitInterface? = null
    private var device: MockRaybanMeta? = null

    /** Enable the kit, grant camera consent, pair + wear the glasses, feed a frame. */
    fun enableAndPair() {
        val k = MockDeviceKit.getInstance(context)
        if (!k.isEnabled) {
            k.enable(
                MockDeviceKitConfig(
                    /* initiallyRegistered = */ true,
                    /* initialPermissionsGranted = */ true,
                ),
            )
        }
        // Belt-and-braces: ensure the glasses' camera permission reads as granted.
        k.permissions.set(Permission.CAMERA, PermissionStatus.Granted)

        val d = k.pairRaybanMeta()
        d.powerOn()
        d.don() // "donning" = wearing; required before the camera will stream.

        val frame = writeSyntheticFrame()
        d.services.camera.setCapturedImage(frame) // what capturePhoto() returns
        d.services.camera.setCameraFeed(frame)     // what the video stream shows

        kit = k
        device = d
    }

    fun disable() {
        runCatching { device?.let { kit?.unpairDevice(it) } }
        runCatching { kit?.disable() }
        device = null
        kit = null
    }

    /** Draws a labelled test image to the cache dir and returns its file Uri. */
    private fun writeSyntheticFrame(): Uri {
        val width = 1024
        val height = 768
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.parseColor("#0B3D2E"))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 64f
            }
            drawText("BayRanO mock frame", 80f, 180f, paint)
            paint.textSize = 40f
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            drawText(stamp, 80f, 260f, paint)
            // A couple of shapes so vision answers have something concrete to say.
            paint.color = Color.parseColor("#4CC9A0")
            drawCircle(300f, 540f, 120f, paint)
            paint.color = Color.parseColor("#FFD23F")
            drawRect(540f, 420f, 860f, 640f, paint)
        }

        val file = File(context.cacheDir, "mock_frame.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}
