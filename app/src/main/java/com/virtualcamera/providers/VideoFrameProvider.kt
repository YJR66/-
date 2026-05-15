package com.virtualcamera.providers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.virtualcamera.virtualcamera.FrameProviderBase
import kotlin.math.abs

/**
 * A [com.virtualcamera.virtualcamera.FrameProvider] that extracts and returns
 * frames from a local or remote video file.
 *
 * Frames are decoded with [MediaMetadataRetriever.getFrameAtTime], which
 * supports arbitrary seeking but is CPU-intensive at high frame rates. For
 * smooth 30 fps output it is recommended to use short, pre-processed videos
 * at the target resolution.
 *
 * For real-time GPU-decoded video, integrate MediaPlayer with an EGL offscreen
 * surface and a pixel-buffer readback instead.
 *
 * ### Usage
 * ```kotlin
 * val uri = Uri.parse("android.resource://${packageName}/${R.raw.sample_video}")
 * val provider = VideoFrameProvider(context, uri)
 * provider.prepare()
 * VirtualCameraManager.getInstance(ctx).setBackCameraProvider(provider)
 * ```
 *
 * @param context   Application context.
 * @param videoUri  URI of the video to play.
 * @param looping   When `true` (default) the video restarts after reaching the end.
 */
class VideoFrameProvider(
    private val context: Context,
    private val videoUri: Uri,
    private val looping: Boolean = true
) : FrameProviderBase() {

    companion object {
        private const val TAG = "VideoFrameProvider"
        /** Minimum microseconds between successive seeks (≈ 30 fps). */
        private const val MIN_SEEK_INTERVAL_US = 33_000L
    }

    private var retriever: MediaMetadataRetriever? = null
    private var durationUs: Long = 0L
    private var startTimeNs: Long = 0L

    private var cachedBitmap: Bitmap? = null
    private var lastSeekUs: Long = Long.MIN_VALUE

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Prepare the video retriever. **Must** be called before the pipeline is
     * started; safe to call from any thread.
     */
    fun prepare() {
        try {
            val ret = MediaMetadataRetriever()
            ret.setDataSource(context, videoUri)
            val durationMs = ret
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            durationUs = durationMs * 1_000L
            retriever = ret
            startTimeNs = System.nanoTime()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare VideoFrameProvider for $videoUri", e)
        }
    }

    // -------------------------------------------------------------------------
    // FrameProvider
    // -------------------------------------------------------------------------

    override fun onFrameRequested(width: Int, height: Int, timestampNs: Long): Bitmap? {
        if (isReleased) return null
        val ret = retriever ?: return null
        if (durationUs == 0L) return null

        // Map wall-clock time to a position within the video
        val elapsedUs = (timestampNs - startTimeNs) / 1_000L
        val positionUs = if (looping) {
            elapsedUs % durationUs
        } else {
            elapsedUs.coerceAtMost(durationUs)
        }

        // Skip the (expensive) seek if we are still within the same frame interval
        val cached = cachedBitmap
        if (cached != null && abs(positionUs - lastSeekUs) < MIN_SEEK_INTERVAL_US) {
            return cached
        }

        return try {
            val frame = ret.getFrameAtTime(
                positionUs, MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: return cached

            val scaled = if (frame.width == width && frame.height == height) {
                frame
            } else {
                val s = Bitmap.createScaledBitmap(frame, width, height, true)
                frame.recycle()
                s
            }

            cachedBitmap?.recycle()
            cachedBitmap = scaled
            lastSeekUs = positionUs
            scaled
        } catch (e: Exception) {
            Log.e(TAG, "getFrameAtTime failed at $positionUs µs", e)
            cached
        }
    }

    override fun onRelease() {
        try {
            retriever?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaMetadataRetriever", e)
        }
        retriever = null
        cachedBitmap?.recycle()
        cachedBitmap = null
    }
}
