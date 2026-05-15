package com.virtualcamera.xposed

import android.graphics.Color
import de.robv.android.xposed.XSharedPreferences
import com.virtualcamera.providers.DemoCanvasProvider
import com.virtualcamera.providers.SolidColorProvider
import com.virtualcamera.virtualcamera.FrameProvider

/**
 * Reads the hook configuration that the companion app writes to SharedPreferences.
 *
 * LSPosed forwards the module's SharedPreferences so we can read them safely
 * via [XSharedPreferences] without root file-access at runtime.
 *
 * ### Keys written by [com.virtualcamera.ui.HookConfigActivity]
 * | Key                  | Type   | Default           |
 * |----------------------|--------|-------------------|
 * | `frame_source`       | String | `"DEMO_CANVAS"`   |
 * | `solid_color`        | Int    | `Color.GREEN`     |
 */
object HookSettings {

    /** Possible virtual-frame sources selectable by the user. */
    enum class FrameSource { DEMO_CANVAS, SOLID_COLOR }

    const val PREFS_NAME = "hook_settings"
    const val KEY_FRAME_SOURCE = "frame_source"
    const val KEY_SOLID_COLOR = "solid_color"

    private val prefs: XSharedPreferences by lazy {
        XSharedPreferences("com.virtualcamera", PREFS_NAME)
    }

    /** Build a [FrameProvider] according to the current settings. */
    fun createFrameProvider(): FrameProvider {
        prefs.reload()
        return when (FrameSource.valueOf(
            prefs.getString(KEY_FRAME_SOURCE, FrameSource.DEMO_CANVAS.name)
                ?: FrameSource.DEMO_CANVAS.name
        )) {
            FrameSource.DEMO_CANVAS -> DemoCanvasProvider()
            FrameSource.SOLID_COLOR -> SolidColorProvider(
                prefs.getInt(KEY_SOLID_COLOR, Color.GREEN)
            )
        }
    }
}
