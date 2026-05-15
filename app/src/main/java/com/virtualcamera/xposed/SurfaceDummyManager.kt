package com.virtualcamera.xposed

import android.graphics.SurfaceTexture
import android.view.Surface
import com.virtualcamera.xposed.VirtualFrameInjector
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks the mapping from each app-visible Surface to the dummy SurfaceTexture-backed
 * Surface that actually receives camera hardware frames.
 *
 * When we hook [android.hardware.camera2.CameraDevice.createCaptureSession], we replace
 * the app's output surfaces with dummy ones so camera hardware writes to them (and we
 * discard those real frames). Then our injection thread writes virtual frames to the
 * original app surfaces.
 *
 * An additional hook on [android.hardware.camera2.CaptureRequest.Builder.addTarget]
 * transparently translates original surfaces → dummy surfaces so the app's capture
 * requests remain valid.
 */
object SurfaceDummyManager {

    /** original Surface → dummy Surface (which is what the camera session sees) */
    private val originalToDummy = ConcurrentHashMap<Surface, Surface>()

    /** dummy Surface → SurfaceTexture that backs it (kept to prevent GC) */
    private val dummySurfaceTextures = ConcurrentHashMap<Surface, SurfaceTexture>()

    /**
     * Create a dummy Surface for [original]. Returns the dummy, or `null` if the
     * original cannot be replaced (e.g., an ImageReader surface we should preserve).
     *
     * A soft trial via [Surface.isValid] is used; any Surface that is valid at this
     * point is assumed to be a preview surface and replaced.
     */
    fun createDummyFor(original: Surface): Surface? {
        if (!original.isValid) return null
        // Create a SurfaceTexture that receives (and discards) real camera frames
        val st = SurfaceTexture(false).also {
            it.setDefaultBufferSize(
                VirtualFrameInjector.DEFAULT_FRAME_WIDTH,
                VirtualFrameInjector.DEFAULT_FRAME_HEIGHT
            )
        }
        val dummy = Surface(st)
        originalToDummy[original] = dummy
        dummySurfaceTextures[dummy] = st
        return dummy
    }

    /** Translate an original surface to its dummy counterpart, if one exists. */
    fun getDummy(original: Surface): Surface? = originalToDummy[original]

    /** Return all original surfaces that were replaced by dummies. */
    fun getAllOriginals(): Set<Surface> = originalToDummy.keys

    /**
     * Release all dummy surfaces and textures associated with [originals].
     * Call this when the camera session is closed.
     */
    fun release(originals: List<Surface>) {
        originals.forEach { original ->
            val dummy = originalToDummy.remove(original) ?: return@forEach
            dummySurfaceTextures.remove(dummy)?.release()
            dummy.release()
        }
    }
}
