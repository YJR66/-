package com.virtualcamera.xposed

import android.hardware.camera2.CameraCaptureSession
import android.view.Surface
import com.virtualcamera.virtualcamera.FrameProvider

/**
 * Wraps the app's [CameraCaptureSession.StateCallback] so we can intercept
 * [onConfigured] (to start virtual-frame injection) and [onClosed] (to stop it).
 *
 * All other callbacks are forwarded transparently to [wrapped].
 *
 * @param wrapped        The original callback supplied by the target app.
 * @param originalSurfaces The app-visible surfaces we replaced with dummies.
 * @param provider       The [FrameProvider] to draw frames from.
 */
class SessionCallbackProxy(
    private val wrapped: CameraCaptureSession.StateCallback,
    private val originalSurfaces: List<Surface>,
    private val provider: FrameProvider
) : CameraCaptureSession.StateCallback() {

    override fun onConfigured(session: CameraCaptureSession) {
        // Start injecting virtual frames to the original (app-visible) surfaces.
        VirtualFrameInjector.start(
            sessionKey = session.hashCode(),
            surfaces = originalSurfaces,
            provider = provider
        )
        wrapped.onConfigured(session)
    }

    override fun onConfigureFailed(session: CameraCaptureSession) {
        SurfaceDummyManager.release(originalSurfaces)
        wrapped.onConfigureFailed(session)
    }

    override fun onReady(session: CameraCaptureSession) =
        wrapped.onReady(session)

    override fun onActive(session: CameraCaptureSession) =
        wrapped.onActive(session)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCaptureQueueEmpty(session: CameraCaptureSession) =
        wrapped.onCaptureQueueEmpty(session)

    override fun onSurfacePrepared(session: CameraCaptureSession, surface: Surface) =
        wrapped.onSurfacePrepared(session, surface)

    override fun onClosed(session: CameraCaptureSession) {
        VirtualFrameInjector.stop(session.hashCode())
        SurfaceDummyManager.release(originalSurfaces)
        wrapped.onClosed(session)
    }
}
