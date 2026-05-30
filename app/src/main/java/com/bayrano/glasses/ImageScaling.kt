package com.bayrano.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meta.wearable.dat.camera.types.PhotoData
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Converts a captured [PhotoData] into a compact JPEG suitable for an inline
 * Gemini image part: downscaled so its longest edge is ~[maxDimPx], then
 * JPEG-compressed. Keeping the upload small is the main latency/cost lever on a
 * wearable; Gemini's media_resolution handles tokenisation on its side.
 */
object ImageScaling {

    const val DEFAULT_MAX_DIM_PX = 768
    const val DEFAULT_QUALITY = 80

    /** Decodes the sealed [PhotoData] variants to a [Bitmap], or null on failure. */
    fun toBitmap(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> {
            val buffer = photo.data
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            // HEIC decode is supported from API 28; minSdk is 30.
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /** Downscale [bitmap] to [maxDimPx] longest edge and encode as JPEG bytes. */
    fun toJpeg(
        bitmap: Bitmap,
        maxDimPx: Int = DEFAULT_MAX_DIM_PX,
        quality: Int = DEFAULT_QUALITY,
    ): ByteArray {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > maxDimPx && longest > 0) {
            val ratio = maxDimPx.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                /* filter = */ true,
            )
        } else {
            bitmap
        }

        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== bitmap) scaled.recycle()
            out.toByteArray()
        }
    }

    /** Convenience: [PhotoData] straight to downscaled JPEG bytes. */
    fun photoToJpeg(
        photo: PhotoData,
        maxDimPx: Int = DEFAULT_MAX_DIM_PX,
        quality: Int = DEFAULT_QUALITY,
    ): ByteArray? = toBitmap(photo)?.let { toJpeg(it, maxDimPx, quality) }
}
