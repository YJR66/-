package com.virtualcamera.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.virtualcamera.R
import com.virtualcamera.databinding.ActivityPreviewBinding
import com.virtualcamera.providers.DemoCanvasProvider
import com.virtualcamera.virtualcamera.CameraFacing
import com.virtualcamera.virtualcamera.VirtualCameraManager
import com.virtualcamera.virtualcamera.VirtualCameraService

/**
 * Main activity: shows a full-screen [android.view.SurfaceView] with the
 * virtual camera output plus controls to:
 * - Switch between front / back camera.
 * - Open [ProviderSelectorFragment] to choose a different [com.virtualcamera.virtualcamera.FrameProvider].
 *
 * The activity starts and binds to [VirtualCameraService] so the virtual camera
 * keeps running when the screen is off.
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding

    private var currentFacing = CameraFacing.BACK
    private var isBound = false
    private var surfaceReady = false

    // -------------------------------------------------------------------------
    // Service connection
    // -------------------------------------------------------------------------

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            isBound = true
            if (surfaceReady) startCamera()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    // -------------------------------------------------------------------------
    // Permission launcher
    // -------------------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            bindAndStartService()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register default frame providers
        val manager = VirtualCameraManager.getInstance(this)
        manager.setBackCameraProvider(DemoCanvasProvider())
        manager.setFrontCameraProvider(DemoCanvasProvider())

        setupSurface()
        setupButtons()
        checkPermissionsAndStart()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        VirtualCameraManager.getInstance(this).stopAll()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    private fun setupSurface() {
        // RGBA_8888 allows Canvas-based software rendering from the pipeline
        binding.surfaceView.holder.setFormat(PixelFormat.RGBA_8888)
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (isBound) startCamera()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
                // Restart so the pipeline picks up the new surface dimensions
                if (surfaceReady && isBound) startCamera()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                VirtualCameraManager.getInstance(this@PreviewActivity)
                    .stopVirtualCamera(currentFacing)
            }
        })
    }

    private fun setupButtons() {
        binding.btnSwitch.setOnClickListener {
            currentFacing = if (currentFacing == CameraFacing.BACK) {
                CameraFacing.FRONT
            } else {
                CameraFacing.BACK
            }
            if (surfaceReady && isBound) startCamera()
        }

        binding.btnSelectProvider.setOnClickListener {
            ProviderSelectorFragment
                .newInstance(currentFacing)
                .show(supportFragmentManager, ProviderSelectorFragment.TAG)
        }

        binding.btnHookConfig.setOnClickListener {
            startActivity(Intent(this, HookConfigActivity::class.java))
        }
    }

    // -------------------------------------------------------------------------
    // Camera helpers
    // -------------------------------------------------------------------------

    private fun startCamera() {
        val surface = binding.surfaceView.holder.surface ?: return
        val manager = VirtualCameraManager.getInstance(this)
        manager.stopAll()
        try {
            manager.startVirtualCamera(currentFacing, surface)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.camera_error, e.message), Toast.LENGTH_SHORT)
                .show()
        }
    }

    /** Called by [ProviderSelectorFragment] after the user picks a new provider. */
    fun restartCamera() {
        if (surfaceReady && isBound) startCamera()
    }

    // -------------------------------------------------------------------------
    // Permission + service helpers
    // -------------------------------------------------------------------------

    private fun checkPermissionsAndStart() {
        val required = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            bindAndStartService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun bindAndStartService() {
        val intent = Intent(this, VirtualCameraService::class.java)
            .setAction(VirtualCameraService.ACTION_START)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
}
