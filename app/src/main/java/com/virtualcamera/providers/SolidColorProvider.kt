package com.virtualcamera.providers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.virtualcamera.virtualcamera.FrameProviderBase

/**
 * A [com.virtualcamera.virtualcamera.FrameProvider] that fills every frame with a
 * single solid [color].  Useful as a lightweight test source or as the "blank" option
 * in the hook configuration UI.
 */
class SolidColorProvider(private val color: Int = Color.GREEN) : FrameProviderBase() {

    private val fillPaint = Paint().also { it.color = color }
    private var frameBitmap: Bitmap? = null

    override fun onFrameRequested(width: Int, height: Int, timestampNs: Long): Bitmap? {
        if (isReleased) return null

        val bmp = if (frameBitmap?.width == width && frameBitmap?.height == height) {
            frameBitmap!!
        } else {
            frameBitmap?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { frameBitmap = it }
        }

        Canvas(bmp).drawRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), fillPaint)
        return bmp
    }

    override fun onRelease() {
        frameBitmap?.recycle()
        frameBitmap = null
    }
}
