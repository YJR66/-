package com.virtualcamera.virtualcamera

import android.view.Surface

/**
 * Abstract base class for [FrameProvider] implementations.
 *
 * Handles the idempotent [release] lifecycle so subclasses only need to
 * override [onRelease] to free their own resources.
 */
abstract class FrameProviderBase : FrameProvider {

    /**
     * `true` after [release] has been called. Subclasses should guard
     * resource access with this flag.
     */
    @Volatile
    var isReleased: Boolean = false
        private set

    final override fun release() {
        if (!isReleased) {
            isReleased = true
            onRelease()
        }
    }

    /**
     * Called exactly once when this provider is released.
     * Override to free bitmaps, file handles, or other resources.
     */
    protected open fun onRelease() {}

    /** Default: no-op; pipeline uses [onFrameRequested] instead. */
    override fun onSurfaceAvailable(surface: Surface) {}
}
