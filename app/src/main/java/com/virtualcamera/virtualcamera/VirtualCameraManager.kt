package com.virtualcamera.virtualcamera

import android.content.Context
import android.view.Surface

/**
 * Singleton manager that owns and coordinates the virtual camera pipelines.
 *
 * ### Basic usage
 * ```kotlin
 * val manager = VirtualCameraManager.getInstance(context)
 *
 * // Register your custom frame sources
 * manager.setBackCameraProvider(MyFrameProvider())
 * manager.setFrontCameraProvider(AnotherFrameProvider())
 *
 * // Start rendering to a SurfaceView
 * manager.startVirtualCamera(CameraFacing.BACK, surfaceView.holder.surface)
 *
 * // Later, stop
 * manager.stopVirtualCamera(CameraFacing.BACK)
 * ```
 */
class VirtualCameraManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: VirtualCameraManager? = null

        fun getInstance(context: Context): VirtualCameraManager =
            instance ?: synchronized(this) {
                instance ?: VirtualCameraManager(context.applicationContext).also {
                    instance = it
                }
            }
    }

    private val pipelines = mutableMapOf<CameraFacing, CameraStreamPipeline>()
    private val providers = mutableMapOf<CameraFacing, FrameProvider>()

    // -------------------------------------------------------------------------
    // Provider registration
    // -------------------------------------------------------------------------

    /** Set the [FrameProvider] used for the front-facing camera stream. */
    fun setFrontCameraProvider(provider: FrameProvider) {
        providers[CameraFacing.FRONT] = provider
    }

    /** Set the [FrameProvider] used for the rear-facing camera stream. */
    fun setBackCameraProvider(provider: FrameProvider) {
        providers[CameraFacing.BACK] = provider
    }

    // -------------------------------------------------------------------------
    // Pipeline lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the virtual camera pipeline for [facing], directing custom frames to
     * [outputSurface].
     *
     * If a pipeline is already running for [facing] it is stopped first.
     *
     * @throws IllegalStateException if no [FrameProvider] has been set for [facing].
     */
    fun startVirtualCamera(facing: CameraFacing, outputSurface: Surface) {
        val provider = providers[facing]
            ?: throw IllegalStateException("No FrameProvider registered for $facing camera. " +
                "Call setFrontCameraProvider() or setBackCameraProvider() first.")

        stopVirtualCamera(facing)

        val pipeline = CameraStreamPipeline(context, facing)
        pipelines[facing] = pipeline
        pipeline.start(provider, outputSurface)
    }

    /** Stop and release the pipeline for [facing]. No-op if not running. */
    fun stopVirtualCamera(facing: CameraFacing) {
        pipelines.remove(facing)?.stop()
    }

    /** Stop and release all running pipelines. */
    fun stopAll() {
        pipelines.values.forEach { it.stop() }
        pipelines.clear()
    }

    /** Returns `true` if a pipeline is currently running for [facing]. */
    fun isRunning(facing: CameraFacing): Boolean = pipelines.containsKey(facing)
}
