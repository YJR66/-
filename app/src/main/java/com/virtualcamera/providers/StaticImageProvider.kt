package com.virtualcamera.providers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.virtualcamera.virtualcamera.FrameProviderBase

/**
 * A [com.virtualcamera.virtualcamera.FrameProvider] that returns a static [Bitmap]
 * for every camera frame.
 *
 * ### Usage
 * ```kotlin
 * val provider = StaticImageProvider(myBitmap)
 * VirtualCameraManager.getInstance(ctx).setBackCameraProvider(provider)
 * ```
 *
 * Call [updateBitmap] at any time to swap the displayed image without
 * restarting the pipeline.
 *
 * If no bitmap has been set, a black placeholder with the text "No Image"
 * is rendered instead.
 */
class StaticImageProvider(bitmap: Bitmap? = null) : FrameProviderBase() {

    @Volatile
    private var sourceBitmap: Bitmap? = bitmap

    private var scaledBitmap: Bitmap? = null
    private var scaledWidth = -1
    private var scaledHeight = -1

    /**
     * Replace the displayed image. Safe to call from any thread while the
     * pipeline is running.
     */
    fun updateBitmap(newBitmap: Bitmap) {
        sourceBitmap = newBitmap
        // Invalidate cached scaled copy so it is re-created on the next frame.
        scaledBitmap?.recycle()
        scaledBitmap = null
        scaledWidth = -1
        scaledHeight = -1
    }

    override fun onFrameRequested(width: Int, height: Int, timestampNs: Long): Bitmap? {
        if (isReleased) return null

        val source = sourceBitmap ?: return createPlaceholder(width, height)

        // Re-scale only when the requested dimensions change
        if (scaledWidth != width || scaledHeight != height) {
            scaledBitmap?.recycle()
            scaledBitmap = Bitmap.createScaledBitmap(source, width, height, true)
            scaledWidth = width
            scaledHeight = height
        }

        return scaledBitmap
    }

    override fun onRelease() {
        scaledBitmap?.recycle()
        scaledBitmap = null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun createPlaceholder(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (height * 0.06f).coerceAtLeast(32f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No Image", width / 2f, height / 2f, paint)
        return bmp
    }
}
