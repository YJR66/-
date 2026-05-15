package com.virtualcamera.xposed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.virtualcamera.virtualcamera.FrameProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives the virtual-frame injection loop for active camera sessions.
 *
 * For each session a [HandlerThread] is started.  Every ~33 ms (≈30 fps) it
 * calls [FrameProvider.onFrameRequested] and draws the resulting [Bitmap]
 * onto every live output surface via [Surface.lockCanvas].
 *
 * Non-lockable surfaces (e.g. hardware ImageReader surfaces) are silently
 * skipped; software-backed surfaces (SurfaceView, TextureView) work correctly.
 */
object VirtualFrameInjector {

    private const val TAG = "VirtualCamera/Injector"
    private const val FRAME_INTERVAL_MS = 33L // ~30 fps

    /** sessionHashCode → (HandlerThread, surfaces) */
    private val activeSessions =
        ConcurrentHashMap<Int, Pair<HandlerThread, List<Surface>>>()

    /**
     * Start injecting virtual frames from [provider] into every surface in
     * [surfaces]. The [sessionKey] uniquely identifies the session so we can
     * stop it later.
     */
    fun start(sessionKey: Int, surfaces: List<Surface>, provider: FrameProvider) {
        stop(sessionKey) // cancel any previous session with same key

        val thread = HandlerThread("VCamInject-$sessionKey").also { it.start() }
        activeSessions[sessionKey] = thread to surfaces
        scheduleFrame(Handler(thread.looper), surfaces, provider)
    }

    /** Stop the injection loop for [sessionKey]. */
    fun stop(sessionKey: Int) {
        activeSessions.remove(sessionKey)?.first?.quitSafely()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun scheduleFrame(
        handler: Handler,
        surfaces: List<Surface>,
        provider: FrameProvider
    ) {
        handler.post(object : Runnable {
            override fun run() {
                val validSurfaces = surfaces.filter { it.isValid }
                if (validSurfaces.isEmpty()) return

                try {
                    val bitmap = provider.onFrameRequested(1280, 720, System.nanoTime())
                    if (bitmap != null) {
                        validSurfaces.forEach { surface -> drawTo(bitmap, surface) }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Frame generation error", e)
                }

                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        })
    }

    private fun drawTo(bitmap: Bitmap, surface: Surface) {
        val canvas: Canvas = try {
            surface.lockCanvas(null) ?: return
        } catch (e: Exception) {
            return // surface doesn't support lockCanvas – skip silently
        }
        try {
            val src = Rect(0, 0, bitmap.width, bitmap.height)
            val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
            canvas.drawBitmap(bitmap, src, dst, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
