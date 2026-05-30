package com.bayrano.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Provides the synthetic camera frame used by "mock device" mode so the app runs
 * end-to-end on an emulator with no real glasses.
 *
 * Earlier revisions tried to drive the toolkit's Mock Device Kit
 * (`com.meta.wearable.dat.mockdevice`) so the emulator went through the exact
 * same Wearables session/stream/capture path as real hardware. That proved
 * unreliable: the SDK self-initialised against the real (unregistered) data
 * layer before the kit could be enabled, the registration manifest read empty
 * ("Manifest file is empty; hence, app is not registered"), the SDK's
 * weakly-held state monitors got garbage-collected mid-connect, and
 * `createSession` ultimately failed with `DatException: No eligible device
 * found`.
 *
 * Mock mode now sidesteps the toolkit completely: [GlassesManager] never touches
 * `Wearables` in mock mode, and [CameraController] serves the JPEG produced here
 * instead of a real `Stream.capturePhoto`. The result is deterministic and has
 * no dependency on the SDK's discovery/registration machinery.
 */
class MockDeviceController(@Suppress("unused") private val context: Context) {

    /** A labelled synthetic frame as JPEG bytes, ready to drop into a Gemini part. */
    fun frameJpeg(
        maxDimPx: Int = ImageScaling.DEFAULT_MAX_DIM_PX,
        quality: Int = ImageScaling.DEFAULT_QUALITY,
    ): ByteArray {
        val bitmap = renderFrame()
        return ImageScaling.toJpeg(bitmap, maxDimPx, quality).also { bitmap.recycle() }
    }

    /** Draws a labelled test image with a couple of recognisable shapes. */
    private fun renderFrame(): Bitmap {
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
        return bitmap
    }
}
