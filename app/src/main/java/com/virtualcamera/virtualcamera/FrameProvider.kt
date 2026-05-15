package com.virtualcamera.virtualcamera

import android.graphics.Bitmap
import android.view.Surface

/**
 * Core interface for injecting custom frames into the virtual camera pipeline.
 *
 * Implement this interface to control the video content produced by the virtual
 * camera. Frame callbacks are invoked on a background thread; implementations
 * must be thread-safe.
 *
 * ### Minimal implementation
 * ```kotlin
 * class MyProvider : FrameProvider {
 *     override fun onFrameRequested(width: Int, height: Int, timestampNs: Long): Bitmap? {
 *         val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
 *         Canvas(bmp).drawColor(Color.RED)
 *         return bmp
 *     }
 *     override fun release() {}
 * }
 * ```
 *
 * ### High-performance Surface path
 * Override [onSurfaceAvailable] to render directly into the output [Surface]
 * (e.g. via OpenGL ES or a [android.graphics.Canvas]) without the extra
 * Bitmap allocation/copy that [onFrameRequested] implies.
 * When [onSurfaceAvailable] is overridden, the pipeline skips [onFrameRequested]
 * for that surface.
 */
interface FrameProvider {

    /**
     * Called on every camera frame tick to obtain the next custom frame.
     *
     * The pipeline reuses or scales the returned [Bitmap] as needed; do **not**
     * recycle it inside this method.
     *
     * @param width       Requested frame width in pixels (matches the camera stream size).
     * @param height      Requested frame height in pixels.
     * @param timestampNs Frame timestamp in nanoseconds from the camera sensor.
     * @return A [Bitmap] to display, or `null` to skip rendering this frame.
     */
    fun onFrameRequested(width: Int, height: Int, timestampNs: Long): Bitmap?

    /**
     * Optional high-performance rendering path.
     *
     * Called once when an output [Surface] becomes available. Override to render
     * directly to the surface instead of returning a [Bitmap] from
     * [onFrameRequested]. If overridden, [onFrameRequested] will not be called.
     *
     * @param surface The output surface to draw into.
     */
    fun onSurfaceAvailable(surface: Surface) {
        // Default: no-op; the pipeline falls back to onFrameRequested()
    }

    /**
     * Release all resources held by this provider.
     * Called automatically when the pipeline is stopped.
     */
    fun release()
}
