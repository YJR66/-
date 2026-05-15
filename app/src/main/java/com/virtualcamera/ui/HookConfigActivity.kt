package com.virtualcamera.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.virtualcamera.databinding.ActivityHookConfigBinding
import com.virtualcamera.xposed.HookSettings

/**
 * Companion-app configuration screen for the Xposed/LSPosed virtual-camera hook.
 *
 * Users select a virtual-frame source here; the choice is persisted to
 * SharedPreferences ([HookSettings.PREFS_NAME]) which the Xposed hook reads via
 * [de.robv.android.xposed.XSharedPreferences] when a target app opens the camera.
 */
class HookConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHookConfigBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHookConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Use MODE_WORLD_READABLE so that XSharedPreferences can read without root
        @Suppress("DEPRECATION")
        prefs = getSharedPreferences(HookSettings.PREFS_NAME, Context.MODE_WORLD_READABLE)

        loadCurrentSettings()
        setupListeners()
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun loadCurrentSettings() {
        val src = HookSettings.FrameSource.valueOf(
            prefs.getString(HookSettings.KEY_FRAME_SOURCE, HookSettings.FrameSource.DEMO_CANVAS.name)
                ?: HookSettings.FrameSource.DEMO_CANVAS.name
        )
        when (src) {
            HookSettings.FrameSource.DEMO_CANVAS -> binding.radioCanvas.isChecked = true
            HookSettings.FrameSource.SOLID_COLOR -> binding.radioSolidColor.isChecked = true
        }

        // Restore saved hex color (default green)
        val savedColor = prefs.getInt(HookSettings.KEY_SOLID_COLOR, Color.GREEN)
        binding.editHexColor.setText(colorToHex(savedColor))
    }

    private fun setupListeners() {
        binding.btnApply.setOnClickListener {
            val source = when {
                binding.radioCanvas.isChecked -> HookSettings.FrameSource.DEMO_CANVAS
                binding.radioSolidColor.isChecked -> HookSettings.FrameSource.SOLID_COLOR
                else -> HookSettings.FrameSource.DEMO_CANVAS
            }

            val color = parseHexColor(binding.editHexColor.text?.toString()) ?: run {
                Toast.makeText(this, getString(com.virtualcamera.R.string.hook_invalid_color), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString(HookSettings.KEY_FRAME_SOURCE, source.name)
                .putInt(HookSettings.KEY_SOLID_COLOR, color)
                .apply()

            Toast.makeText(this, getString(com.virtualcamera.R.string.hook_settings_saved), Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // Color helpers
    // -------------------------------------------------------------------------

    private fun colorToHex(color: Int): String =
        String.format("#%08X", color)

    private fun parseHexColor(hex: String?): Int? {
        if (hex.isNullOrBlank()) return Color.GREEN
        return try {
            Color.parseColor(hex.trim())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
