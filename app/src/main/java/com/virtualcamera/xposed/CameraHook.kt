package com.virtualcamera.xposed

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed/LSPosed module entry point.
 *
 * This class hooks the Android Camera2 API at the session-creation level so
 * that every application's camera preview shows the user-selected virtual frame
 * instead of real camera footage.
 *
 * ## How it works
 *
 * ```
 * App calls createCaptureSession(surfaces, callback, handler)
 *       │
 *       ▼  [hook: beforeHookedMethod]
 *       ├─ For each app surface → create a dummy SurfaceTexture-backed Surface
 *       │   Camera hardware writes real frames here (discarded).
 *       ├─ Replace surfaces list with dummy list
 *       └─ Replace callback with SessionCallbackProxy
 *
 * App calls CaptureRequest.Builder.addTarget(originalSurface)
 *       │
 *       ▼  [hook: beforeHookedMethod]
 *       └─ Translate originalSurface → dummySurface so the request stays valid
 *
 * Session opens → SessionCallbackProxy.onConfigured()
 *       └─ VirtualFrameInjector starts drawing 30 fps virtual frames to original surfaces
 *
 * Session closes → SessionCallbackProxy.onClosed()
 *       └─ VirtualFrameInjector stops; dummy SurfaceTextures released
 * ```
 *
 * The module skips its own package (`com.virtualcamera`) to avoid recursion.
 */
class CameraHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "VirtualCamera/Hook"
        private const val OWN_PACKAGE = "com.virtualcamera"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == OWN_PACKAGE) return

        hookCreateCaptureSessionLegacy(lpparam)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hookCreateCaptureSessionWithConfig(lpparam)
        }
        hookCaptureRequestBuilderAddTarget(lpparam)
    }

    // -------------------------------------------------------------------------
    // createCaptureSession(List<Surface>, StateCallback, Handler)  — API 21+
    // -------------------------------------------------------------------------

    private fun hookCreateCaptureSessionLegacy(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                CameraDevice::class.java.name,
                lpparam.classLoader,
                "createCaptureSession",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        val originalSurfaces =
                            (param.args[0] as? List<*>)?.filterIsInstance<Surface>()
                                ?: return
                        val originalCallback =
                            param.args[1] as? CameraCaptureSession.StateCallback ?: return

                        val (dummySurfaces, replaced) = buildDummySurfaces(originalSurfaces)
                        if (!replaced) return

                        val provider = HookSettings.createFrameProvider()
                        param.args[0] = dummySurfaces
                        param.args[1] = SessionCallbackProxy(
                            wrapped = originalCallback,
                            originalSurfaces = originalSurfaces,
                            provider = provider
                        )
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook createCaptureSession (legacy): $e")
        }
    }

    // -------------------------------------------------------------------------
    // createCaptureSession(SessionConfiguration)  — API 28+
    // -------------------------------------------------------------------------

    private fun hookCreateCaptureSessionWithConfig(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sessionConfigClass =
                XposedHelpers.findClass(
                    "android.hardware.camera2.params.SessionConfiguration",
                    lpparam.classLoader
                )
            XposedHelpers.findAndHookMethod(
                CameraDevice::class.java.name,
                lpparam.classLoader,
                "createCaptureSession",
                sessionConfigClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = param.args[0] ?: return

                        // Extract List<OutputConfiguration> via reflection
                        val outputConfigs = try {
                            XposedHelpers.callMethod(config, "getOutputConfigurations") as? List<*>
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        // Collect Surfaces from OutputConfigurations
                        val originalSurfaces = outputConfigs.mapNotNull { oc ->
                            try {
                                XposedHelpers.callMethod(oc, "getSurface") as? Surface
                            } catch (_: Throwable) {
                                null
                            }
                        }

                        if (originalSurfaces.isEmpty()) return
                        val (_, replaced) = buildDummySurfaces(originalSurfaces)
                        if (!replaced) return

                        // Replace each OutputConfiguration's surface with its dummy
                        outputConfigs.forEach { oc ->
                            try {
                                val original = XposedHelpers.callMethod(oc, "getSurface") as? Surface
                                    ?: return@forEach
                                val dummy = SurfaceDummyManager.getDummy(original) ?: return@forEach
                                // OutputConfiguration has no public setSurface; replace via reflection.
                                // "mSurface" is the canonical AOSP field name; OEM variants may differ.
                                try {
                                    XposedHelpers.setObjectField(oc, "mSurface", dummy)
                                } catch (e: Throwable) {
                                    XposedBridge.log("$TAG: setObjectField mSurface failed (${e.message}); trying mSurfaces")
                                    // Some OEMs store surfaces in "mSurfaces" list — best-effort
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$TAG: Failed to replace OutputConfiguration surface: $e")
                            }
                        }

                        // Wrap the StateCallback inside SessionConfiguration
                        val originalCallback = try {
                            XposedHelpers.callMethod(
                                config, "getStateCallback"
                            ) as? CameraCaptureSession.StateCallback
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        val proxy = SessionCallbackProxy(
                            wrapped = originalCallback,
                            originalSurfaces = originalSurfaces,
                            provider = HookSettings.createFrameProvider()
                        )
                        try {
                            XposedHelpers.setObjectField(config, "mStateCallback", proxy)
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG: setObjectField mStateCallback failed (${e.message}); hook may not work for this session")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook createCaptureSession (SessionConfig): $e")
        }
    }

    // -------------------------------------------------------------------------
    // CaptureRequest.Builder.addTarget(Surface)
    // -------------------------------------------------------------------------

    /**
     * Translates original surfaces → dummy surfaces so that [CaptureRequest]s
     * built by the app remain valid (the camera session only knows about dummies).
     */
    private fun hookCaptureRequestBuilderAddTarget(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CaptureRequest\$Builder",
                lpparam.classLoader,
                "addTarget",
                Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val requested = param.args[0] as? Surface ?: return
                        val dummy = SurfaceDummyManager.getDummy(requested) ?: return
                        param.args[0] = dummy
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook CaptureRequest.Builder.addTarget: $e")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build dummy surfaces for each item in [originals].
     *
     * @return (list of dummy surfaces to pass to the camera, true if any were replaced)
     */
    private fun buildDummySurfaces(originals: List<Surface>): Pair<List<Surface>, Boolean> {
        var anyReplaced = false
        val dummies = originals.map { original ->
            val dummy = SurfaceDummyManager.createDummyFor(original)
            if (dummy != null) {
                anyReplaced = true
                dummy
            } else {
                original
            }
        }
        return dummies to anyReplaced
    }
}
