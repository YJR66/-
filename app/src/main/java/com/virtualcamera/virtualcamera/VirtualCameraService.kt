package com.virtualcamera.virtualcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.virtualcamera.R
import com.virtualcamera.ui.PreviewActivity

/**
 * Foreground service that keeps the virtual camera pipelines alive while
 * the app is in the background.
 *
 * ### Start / stop
 * ```kotlin
 * // Start
 * val intent = Intent(context, VirtualCameraService::class.java)
 *     .setAction(VirtualCameraService.ACTION_START)
 * startForegroundService(intent)
 *
 * // Stop (via broadcast / button)
 * val stopIntent = Intent(context, VirtualCameraService::class.java)
 *     .setAction(VirtualCameraService.ACTION_STOP)
 * startService(stopIntent)
 * ```
 *
 * ### Bind for direct access
 * Bind to this service and cast the returned [IBinder] to [LocalBinder] to
 * obtain a reference to the service instance.
 */
class VirtualCameraService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "virtual_camera_channel"

        const val ACTION_START = "com.virtualcamera.ACTION_START"
        const val ACTION_STOP = "com.virtualcamera.ACTION_STOP"
    }

    inner class LocalBinder : Binder() {
        fun getService(): VirtualCameraService = this@VirtualCameraService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAsForeground()
            ACTION_STOP -> {
                VirtualCameraManager.getInstance(this).stopAll()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        VirtualCameraManager.getInstance(this).stopAll()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun startAsForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VirtualCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PreviewActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .addAction(0, getString(R.string.stop_service), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
