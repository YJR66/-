package com.virtualcamera.virtualcamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Camera2-based pipeline that:
 * 1. Opens a physical camera to drive the frame clock.
 * 2. Discards real camera frames (via [ImageReader]).
 * 3. Requests custom frames from a [FrameProvider] on each tick.
 * 4. Draws those custom frames to the caller-supplied output [Surface].
 *
 * The output surface is typically the [android.view.SurfaceView] holder surface
 * from [PreviewActivity], but can be any lockable [Surface].
 */
class CameraStreamPipeline(
    private val context: Context,
    private val facing: CameraFacing
) {

    companion object {
        private const val TAG = "CameraStreamPipeline"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Protects cameraDevice open/close across threads
    private val openCloseLock = Semaphore(1)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    @Volatile private var outputSurface: Surface? = null
    @Volatile private var frameProvider: FrameProvider? = null

    var streamWidth: Int = DEFAULT_WIDTH
        private set
    var streamHeight: Int = DEFAULT_HEIGHT
        private set

    // -------------------------------------------------------------------------
    // Camera state callback
    // -------------------------------------------------------------------------

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            openCloseLock.release()
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            openCloseLock.release()
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            openCloseLock.release()
            camera.close()
            cameraDevice = null
            Log.e(TAG, "Camera device error code: $error")
        }
    }

    // -------------------------------------------------------------------------
    // Frame available callback — core of the pipeline
    // -------------------------------------------------------------------------

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        // Acquire and immediately discard the real camera frame.
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        val timestampNs = image.timestamp
        image.close()

        val surface = outputSurface ?: return@OnImageAvailableListener
        val provider = frameProvider ?: return@OnImageAvailableListener

        try {
            val bitmap = provider.onFrameRequested(streamWidth, streamHeight, timestampNs)
            if (bitmap != null && surface.isValid) {
                drawBitmapToSurface(bitmap, surface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering custom frame", e)
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start the pipeline: open the camera, begin the repeating capture request,
     * and direct custom frames from [provider] to [outputSurface].
     *
     * Requires [android.Manifest.permission.CAMERA].
     */
    @SuppressLint("MissingPermission")
    fun start(provider: FrameProvider, outputSurface: Surface) {
        this.frameProvider = provider
        this.outputSurface = outputSurface

        startBackgroundThread()

        val cameraId = findCameraId(facing) ?: run {
            Log.e(TAG, "No camera found for facing: $facing")
            return
        }

        // Determine the best matching stream size
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888)
        val chosen = chooseBestSize(sizes, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        streamWidth = chosen.width
        streamHeight = chosen.height

        imageReader = ImageReader.newInstance(
            streamWidth, streamHeight, ImageFormat.YUV_420_888, /* maxImages= */ 2
        ).apply {
            setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
        }

        if (!openCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Timed out waiting to open camera $cameraId")
        }
        cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
    }

    /** Stop the pipeline and release all camera resources. */
    fun stop() {
        try {
            openCloseLock.acquire()
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            imageReader?.close(); imageReader = null
        } finally {
            openCloseLock.release()
        }
        stopBackgroundThread()
        frameProvider = null
        outputSurface = null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun createCaptureSession() {
        val reader = imageReader ?: return
        val device = cameraDevice ?: return

        @Suppress("DEPRECATION")
        device.createCaptureSession(
            listOf(reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startRepeatingRequest(session, reader.surface)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                }
            },
            backgroundHandler
        )
    }

    private fun startRepeatingRequest(session: CameraCaptureSession, readerSurface: Surface) {
        val device = cameraDevice ?: return
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(readerSurface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }.build()
        session.setRepeatingRequest(request, null, backgroundHandler)
    }

    /**
     * Draw [bitmap] onto [surface], scaling to fit the surface dimensions.
     * Uses [Surface.lockCanvas] / [Surface.unlockCanvasAndPost] (software path).
     */
    private fun drawBitmapToSurface(bitmap: Bitmap, surface: Surface) {
        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            Log.w(TAG, "lockCanvas failed: ${e.message}")
            return
        } ?: return

        try {
            val src = Rect(0, 0, bitmap.width, bitmap.height)
            val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
            canvas.drawBitmap(bitmap, src, dst, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun findCameraId(facing: CameraFacing): String? {
        val target = when (facing) {
            CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            CameraFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
        }
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == target
        }
    }

    private fun chooseBestSize(sizes: Array<Size>?, targetW: Int, targetH: Int): Size {
        if (sizes.isNullOrEmpty()) return Size(targetW, targetH)
        val targetArea = targetW.toLong() * targetH
        return sizes.minByOrNull { abs(it.width.toLong() * it.height - targetArea) }
            ?: Size(targetW, targetH)
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CamBG-$facing").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }
}
