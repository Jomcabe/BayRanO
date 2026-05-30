package com.bayrano.glasses

import android.util.Log

/**
 * Captures a single still from the glasses to feed Gemini. We keep a stream
 * session open (in [GlassesManager]) but pull ONE photo per query rather than
 * processing the live frame Flow — far cheaper for an assistant.
 *
 * The returned bytes are a downscaled (~768px longest edge) JPEG, ready to drop
 * into a Gemini `inline_data` part.
 */
class CameraController(private val glasses: GlassesManager) {

    suspend fun capturePhotoJpeg(
        maxDimPx: Int = ImageScaling.DEFAULT_MAX_DIM_PX,
        quality: Int = ImageScaling.DEFAULT_QUALITY,
    ): ByteArray? {
        val stream = glasses.currentStream()
        if (stream == null) {
            Log.w(TAG, "capturePhotoJpeg called with no active stream")
            return null
        }

        val result = stream.capturePhoto()
        val photo = result.getOrNull()
        if (photo == null) {
            Log.w(TAG, "capturePhoto failed: ${result.errorOrNull()}")
            return null
        }

        return ImageScaling.photoToJpeg(photo, maxDimPx, quality).also {
            if (it == null) Log.w(TAG, "Could not decode PhotoData to JPEG")
        }
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
