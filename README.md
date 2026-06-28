# ScoreCast

A sideloaded Android app for live sports broadcasting with a burned-in scoreboard overlay, RTMP streaming to Facebook Live / YouTube / custom endpoints, and a QR-paired remote scoring device (Phase 5).

---

## Features

| Feature | Status |
|---|---|
| RTMP/RTMPS live streaming (Facebook, YouTube, custom) | ✅ |
| Burned-in scoreboard overlay (OpenGL ES 2.0, encoded into video) | ✅ |
| Config-driven sports (Basketball, Soccer, Volleyball, Hockey, Generic) | ✅ |
| Dynamic score buttons (+1/+2/+3 for basketball, +1 for others) | ✅ |
| Extra-field stats (fouls, timeouts, yellow/red cards, penalties, shots) | ✅ |
| Count-up clock (soccer), count-down (basketball/hockey), no clock (volleyball) | ✅ |
| Logo overlay with position & scale controls | ✅ |
| Local MP4 recording (stream-only / record-only / simultaneous) | ✅ |
| Pinch-to-zoom + mirror / flip / rotate orientation | ✅ |
| Fullscreen camera when live; tabbed setup panel before going live | ✅ |
| QR-based remote scoring device (Firebase sync) | 🔜 Phase 5 |
| Racket sports — sets/games model (tennis, badminton) | 🔜 Phase 7 |

---

## Requirements

- Android 8.0+ (API 26), tested on Android 11
- Camera + Microphone permissions
- Internet access for RTMP streaming

---

## Build

### Prerequisites

- Android Studio (Hedgehog or later)
- JDK bundled with Android Studio

### From the command line (Windows)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

### Install to a connected device

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\<you>\AppData\Local\Android\Sdk"
.\gradlew.bat installDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n com.scorecast.app/.MainActivity
```

---

## Usage

1. Open the app — you land on the **Setup panel**
2. **Scoring tab**: pick a sport, enter team names, adjust colors, add logos
3. **Stream tab**: enter your RTMP ingest URL and stream key, choose Stream / Record / Both
4. Tap **Go Live** — camera goes fullscreen with the scoreboard burned in
5. Tap **📊 Score** to slide in the scoring panel while broadcasting
6. Adjust scores, clock, and stats live; overlay updates every 250 ms
7. Tap **⏹ Stop** to end the broadcast

### Getting a Facebook Live stream key

1. Go to **facebook.com/live/producer** → **Streaming software**
2. Copy the **Server URL** (`rtmps://live-api-s.facebook.com:443/rtmp/`) and **Stream Key**
3. Paste both into ScoreCast's Stream tab

---

## Sport configs

Sports are defined in `app/src/main/assets/sports/*.json`. Add a new sport by dropping in a JSON file — no code change required.

```json
{
  "sport": "basketball",
  "displayName": "Basketball",
  "scoringModel": "flat",
  "periods": 4,
  "periodLabel": "Q",
  "periodLength": 600,
  "clockDirection": "down",
  "scoreIncrements": [1, 2, 3],
  "extraFields": [
    { "key": "fouls",    "label": "Fouls",    "perTeam": true, "max": null },
    { "key": "timeouts", "label": "Timeouts", "perTeam": true, "max": 7 }
  ]
}
```

| Field | Values |
|---|---|
| `clockDirection` | `"down"` (basketball/hockey), `"up"` (soccer), `"none"` (volleyball) |
| `scoringModel` | `"flat"` (current), `"setsGames"` (Phase 7 — racket sports) |
| `scoreIncrements` | Array of point values — buttons are generated dynamically |

Bundled sports: **Basketball**, **Soccer**, **Volleyball**, **Hockey**, **Generic**

---

## Architecture

```
Camera
  └── CameraOverlayVideoSource  (StreamPack IVideoSourceInternal)
        └── OverlayCompositor   (OpenGL ES 2.0)
              ├── OES camera texture
              └── 2D overlay texture ← ScoreboardOverlay.create(GameState, logos, SportConfig, size)
                                                                ↑
                                                     GameStateHolder (StateFlow)
                                                     LogoHolder     (StateFlow)
                                                     SportConfigLoader (JSON cache)
        └── SingleStreamer       (StreamPack 3.1.2)
              └── CombineEndpointFactory → RTMP stream + local MP4
```

### Key files

| File | Role |
|---|---|
| `overlay/OverlayCompositor.kt` | EGL14 context, OES + 2D shaders, alpha-blend before encode |
| `overlay/CameraOverlayVideoSource.kt` | 250 ms ticker; pushes new overlay bitmap each tick |
| `overlay/ScoreboardOverlay.kt` | Canvas scoreboard bar + logo drawing; dynamic extra-fields row |
| `StreamerHolder.kt` | SingleStreamer lifecycle, zoom, flip/rotate, recording mode |
| `StreamingService.kt` | Android 14 foreground service (camera + microphone types) |
| `GameState.kt` / `GameStateHolder.kt` | Single source of truth for all live scoring state |
| `SportConfig.kt` / `SportConfigLoader.kt` | JSON sport definitions loaded from assets |
| `ScoringPanel.kt` | Config-driven Compose UI: sport picker, score buttons, stats, players |
| `MainActivity.kt` | Box layout: fullscreen camera + setup overlay (pre-live) / live overlay |

### How the overlay burn-in works

StreamPack 3.x has no overlay API. The supported seam is a custom `IVideoSourceInternal`:

1. `CameraOverlayVideoSource` wraps the StreamPack `CameraSource`
2. `OverlayCompositor` creates an EGL context with two GL programs:
   - Program 1: draws the camera OES texture (with orientation matrix)
   - Program 2: alpha-blends the 2D scoreboard bitmap on top
3. Output surface feeds the StreamPack encoder — the overlay is part of every encoded frame

---

## Roadmap

- **Phase 5** — Firebase Realtime Database sync + QR pairing for a remote scoring device
- **Phase 6** — Synchronized clock anchor across paired devices
- **Phase 7** — Sets/games scoring model (tennis, badminton, squash, table tennis)
- **Phase 8** — In-app auto-update, YouTube stream key picker, scoreboard theme options

---

## License

Private / proprietary. All rights reserved.
