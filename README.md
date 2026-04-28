# Virtual Camera — Android

An Android application that replaces the front and rear camera preview with
fully custom virtual frames, exposing a clean **`FrameProvider`** interface so
any caller can inject arbitrary image content into the camera pipeline.

---

## Features

| Feature | Description |
|---------|-------------|
| Virtual front / back camera | Real camera frames are discarded; custom frames are rendered instead |
| `FrameProvider` API | Single interface to implement — return any `Bitmap` per frame |
| `CanvasDrawProvider` | Base class for Canvas-based animations / graphics |
| `StaticImageProvider` | Display a static bitmap (swap at runtime with `updateBitmap`) |
| `VideoFrameProvider` | Extract and display frames from a local/remote video URI |
| `VirtualCameraService` | Foreground service keeps the pipeline alive in the background |
| Android 5+ compatible | `minSdk = 21`; uses Camera2 API |

---

## Architecture

```
Physical Camera (Camera2)
        │
   ImageReader          ← drives the frame clock; real frames discarded
        │
  FrameProvider         ← YOU implement this interface
  .onFrameRequested()
        │
   CameraStreamPipeline
        │
   SurfaceView          ← rendered to the preview surface via Canvas
```

### Key classes

```
app/src/main/java/com/virtualcamera/
├── virtualcamera/
│   ├── FrameProvider.kt          # Interface: implement to inject custom frames
│   ├── FrameProviderBase.kt      # Abstract base with lifecycle helpers
│   ├── CameraFacing.kt           # FRONT / BACK enum
│   ├── CameraStreamPipeline.kt   # Camera2 pipeline + frame rendering
│   ├── VirtualCameraManager.kt   # Singleton: manages front/back pipelines
│   └── VirtualCameraService.kt   # Foreground service
├── providers/
│   ├── StaticImageProvider.kt    # Static bitmap provider
│   ├── CanvasDrawProvider.kt     # Canvas-draw base + DemoCanvasProvider
│   └── VideoFrameProvider.kt     # Video frame extraction provider
└── ui/
    ├── PreviewActivity.kt        # Main UI: preview + switch + provider picker
    └── ProviderSelectorFragment.kt
```

---

## Implementing your own `FrameProvider`

```kotlin
class MyProvider : FrameProvider {

    override fun onFrameRequested(width: Int, height: Int, timestampNs: Long): Bitmap? {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.BLUE)
            // … draw whatever you need
        }
        return bmp
    }

    override fun release() { /* free resources */ }
}

// Register and start
val manager = VirtualCameraManager.getInstance(context)
manager.setBackCameraProvider(MyProvider())
manager.startVirtualCamera(CameraFacing.BACK, surfaceView.holder.surface)
```

For best performance, subclass `CanvasDrawProvider` and override `onDraw()` —
it reuses a single `Bitmap` buffer instead of allocating one per frame.

---

## Building

Requirements: Android Studio Hedgehog (2023.1.1) or newer / AGP 8.2+.

```bash
# debug APK
./gradlew assembleDebug

# install on connected device
./gradlew installDebug
```

---

## Permissions required

| Permission | Reason |
|---|---|
| `CAMERA` | Open the physical camera to drive the frame clock |
| `FOREGROUND_SERVICE` | Keep the pipeline alive in the background |
| `FOREGROUND_SERVICE_CAMERA` | Android 14+ requirement for camera foreground services |
| `RECORD_AUDIO` | Optional — for future microphone virtualisation |

---

## Notes

* **Frame injection scope** — by default only *this* app sees the virtual frames
  (Camera2 pipeline scope). System-wide virtual cameras (visible to other apps
  like video-call apps) require Android 14's `VirtualDeviceManager` API and the
  `MANAGE_VIRTUAL_DEVICE` system permission (needs platform signature or
  pre-installation).

* **`VideoFrameProvider` frame rate** — `MediaMetadataRetriever.getFrameAtTime`
  is CPU-intensive. For smooth 30 fps, pre-encode short clips at the target
  resolution, or replace the retriever with a MediaPlayer + EGL offscreen
  surface + pixel readback for real-time playback.
