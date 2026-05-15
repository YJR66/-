package com.virtualcamera.providers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.virtualcamera.virtualcamera.FrameProviderBase
import kotlin.math.sin

/**
 * A [com.virtualcamera.virtualcamera.FrameProvider] that renders each frame by
 * drawing onto a [Canvas].
 *
 * Subclass [CanvasDrawProvider] and implement [onDraw] to produce fully custom
 * canvas-rendered content:
 *
 * ```kotlin
 * val provider = object : CanvasDrawProvider() {
 *     override fun onDraw(canvas: Canvas, width: Int, height: Int, timestampNs: Long) {
 *         canvas.drawColor(Color.BLUE)
 *         // … draw text, shapes, bitmaps, etc.
 *     }
 * }
 * VirtualCameraManager.getInstance(ctx).setBackCameraProvider(provider)
 * ```
 *
 * The underlying [Bitmap] is reused across frames to minimise allocation.
 */
abstract class CanvasDrawProvider : FrameProviderBase() {

    private var frameBitmap: Bitmap? = null
    private var lastWidth = -1
    private var lastHeight = -1

    final override fun onFrameRequested(width: Int, height: Int, timestampNs: Long): Bitmap? {
        if (isReleased) return null

        // Allocate (or re-allocate on size change) the backing bitmap
        if (frameBitmap == null || lastWidth != width || lastHeight != height) {
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            lastWidth = width
            lastHeight = height
        }

        val bmp = frameBitmap!!
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK) // clear previous frame
        onDraw(canvas, width, height, timestampNs)
        return bmp
    }

    /**
     * Draw the next frame onto [canvas].
     *
     * Called on the camera background thread for every frame tick.
     *
     * @param canvas      A cleared [Canvas] backed by the frame [Bitmap].
     * @param width       Frame width in pixels.
     * @param height      Frame height in pixels.
     * @param timestampNs Sensor timestamp in nanoseconds (use for animations).
     */
    protected abstract fun onDraw(canvas: Canvas, width: Int, height: Int, timestampNs: Long)

    override fun onRelease() {
        frameBitmap?.recycle()
        frameBitmap = null
    }
}

// ---------------------------------------------------------------------------
// Built-in demo implementation
// ---------------------------------------------------------------------------

/**
 * Ready-to-use [CanvasDrawProvider] that renders an animated colour-cycling
 * background, a pulsing circle, and a "Virtual Camera" label.
 *
 * Useful as a default provider or as a reference implementation.
 */
class DemoCanvasProvider : CanvasDrawProvider() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas, width: Int, height: Int, timestampNs: Long) {
        val t = timestampNs / 1_000_000_000.0 // elapsed seconds

        // Slowly cycling background hue
        val hue = ((t * 30.0) % 360.0).toFloat()
        bgPaint.color = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.45f))
        canvas.drawRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), bgPaint)

        // Pulsing circle with complementary hue
        val pulse = (sin(t * 2.0) * 0.08 + 0.30).toFloat()
        circlePaint.color = Color.HSVToColor(floatArrayOf((hue + 180f) % 360f, 0.8f, 0.9f))
        canvas.drawCircle(width / 2f, height / 2f, width * pulse, circlePaint)

        // Label — scale text to frame size
        textPaint.textSize = (height * 0.07f).coerceAtLeast(24f)
        canvas.drawText("Virtual Camera", width / 2f, height * 0.15f, textPaint)
    }
}
