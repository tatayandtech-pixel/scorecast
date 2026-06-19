# ScoreCast — Phase 1

Phase 1 of the spec only: a **landscape** Kotlin/Compose app that captures the camera, **burns a
static test scoreboard into every encoded frame**, encodes H.264, and pushes **RTMP** to a
manually-pasted ingest URL + stream key. Success = the burned-in graphic appears on a live YouTube
broadcast.

Nothing from later phases is here — no Firebase, scoring logic, QR pairing, mirror device, clock,
auto-update, or platform picker.

## Stack

- Kotlin, Jetpack Compose, `minSdk 26`, `targetSdk 34`, `compileSdk 35`
- **StreamPack 3.1.2** (`io.github.thibaultbee.streampack:streampack-core` + `:streampack-rtmp`)
  for capture → composite → H.264 → RTMP. The RTMP endpoint is discovered reflectively by
  StreamPack's `DynamicEndpoint`, so `:streampack-rtmp` must stay on the classpath.

## How the burn-in works (important)

StreamPack 3.x has **no overlay API**, and its GL surface processor is `private`. The supported seam
is a **custom video source**, so:

```
CameraSource (StreamPack Camera2)
   → OverlayCompositor (our GL: draw camera, then alpha-blend the scoreboard)
      → encoder/processor surface  → H.264 → RTMP → YouTube
      → operator preview SurfaceView (optional)
```

Key files:
- [`overlay/OverlayCompositor.kt`](app/src/main/java/com/scorecast/app/overlay/OverlayCompositor.kt) — EGL/GLES2 compositor (the burn-in).
- [`overlay/CameraOverlayVideoSource.kt`](app/src/main/java/com/scorecast/app/overlay/CameraOverlayVideoSource.kt) — wraps StreamPack's `CameraSource` and feeds the compositor.
- [`overlay/ScoreboardOverlay.kt`](app/src/main/java/com/scorecast/app/overlay/ScoreboardOverlay.kt) — the static test bitmap.
- [`StreamerHolder.kt`](app/src/main/java/com/scorecast/app/StreamerHolder.kt) — builds/owns the `SingleStreamer`.
- [`StreamingService.kt`](app/src/main/java/com/scorecast/app/StreamingService.kt) — foreground service (camera|microphone) so the stream survives backgrounding.
- [`MainActivity.kt`](app/src/main/java/com/scorecast/app/MainActivity.kt) — Compose UI: URL/key fields, preview, Go live / Stop, permissions.

## Build & run

1. Open the project in **Android Studio** (it writes `local.properties` pointing at your SDK), or
   build from the CLI with the committed Gradle wrapper: `./gradlew assembleDebug` (set `ANDROID_HOME`
   or add `sdk.dir=` to `local.properties` first). Gradle 8.11.1 is pinned in `gradle/wrapper`.
2. Plug in a **real device** (the GL/camera/encoder path does not work on the emulator). Build & run.
3. In **YouTube Studio → Go live → Stream**, copy the **Stream URL**
   (`rtmp://a.rtmp.youtube.com/live2`, pre-filled) and the **Stream key**.
4. Paste the key in the app, tap **Go live**, grant Camera/Mic/Notifications.
5. Watch the YouTube preview — you should see the camera with the scoreboard bar burned in.

## Bring-up order (de-risk the pipeline first)

The hardest part is the GL compositor (orientation/timestamps are device-specific and have **not**
been verified on hardware from where this was written). Bring it up in two steps using the
compile-time toggle in [`StreamConfig.kt`](app/src/main/java/com/scorecast/app/StreamConfig.kt):

1. **Prove the pipeline.** Set `USE_OVERLAY = false`. You should see raw camera reach YouTube. This
   isolates camera → encode → RTMP from the overlay.
2. **Prove the burn-in.** Set `USE_OVERLAY = true`. The scoreboard should appear composited in.

If the live image is rotated or mirrored, use the **Mirror horizontally** / **Flip vertically**
toggles in the app — they take effect on the next frame, even mid-stream, so you can tune
orientation on-device without recompiling. `CAMERA_FLIP_VERTICAL` / `CAMERA_FLIP_HORIZONTAL` in
`StreamConfig.kt` just set the boot defaults for those toggles.

## Android 14 foreground service / permissions

Declared in the manifest and applied at `startForeground`:
`INTERNET`, `CAMERA`, `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`,
`FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`; service type `camera|microphone`.

## Known Phase 1 limitations (by design)

- Single hard-coded camera (back). Camera switching is not in Phase 1.
- Overlay position fixed at `BOTTOM_CENTER` (the five positions are wired in `OverlayPosition` but
  not yet operator-selectable — that's Phase 2).
- Recent-target storage, platform picker, and OAuth are Phase 6 / v2.
