# ScoreCast — Project Specification

A sideloaded (non–Play Store) Android app for live sports scoring with an on-device burned-in scoreboard, streaming directly to a user-selected platform (YouTube, Facebook, or any custom RTMP target). Generic and config-driven across many sports. One primary operator runs video + scoring; a second device acts as a scoring-only remote paired by QR code. Feature scope is set to match the SportCam app (see §14 for the parity map).

This document is the build brief for Claude Code. It is implementation-ready: stack, data model, module breakdown, sample configs, and a phased build order.

---

## 1. Product summary

| Aspect | Decision |
|---|---|
| Distribution | Sideload only. Self-signed APK, privately hosted, in-app auto-update. Never published to Play Store. |
| Sports | Generic / configurable. Sports are defined by JSON config files, not hardcoded. |
| Operators | **Main device:** camera + scoreboard composite + RTMP stream + scoring. **Mirror device:** scoring/clock/details only, no video access. |
| Video target | Phone streams directly over RTMP to a user-selected platform: Facebook (in-app login + destination picker), YouTube, Twitch, or custom RTMP. One target per broadcast. |
| Overlay layers (burned in) | Scoreboard, plus sponsor/team **logos**, **custom text** banner, and **team colors** — all composited into the encoded frames. |
| Local recording | Optional record-to-phone simultaneously with streaming (or record without streaming). |
| Capture controls | Pinch / button zoom. |
| Orientation | Landscape (broadcast standard). |
| Overlay position | User-selectable: top-left, top-right, bottom-left, bottom-center, bottom-right. |
| State sync | Firebase Realtime Database. Last-write-wins. |
| Pairing | QR code encoding a session ID + short-lived join token. |
| Clock control | Controllable from both devices via a clock-anchor model (no per-second writes). |

---

## 2. Architecture overview

A single shared **game-state object**, synced through Firebase, keeps all consumers consistent — but rendering is **local-first** (rule below). Three consumers read it: the main device's scoring UI, the mirror device's scoring UI, and the main device's video overlay renderer. The mirror device never receives video — it only reads/writes state. Binary branding assets (logos, full-screen graphics) live **locally on the main device**, since only the main device renders the overlay.

```
                    ┌─────────────────────────────┐
                    │   Firebase Realtime DB        │
                    │   /sessions/{sessionId}       │
                    │   (shared scoring/text state)  │
                    └──────────────┬──────────────-─┘
                       read/write  │  read/write
              ┌────────────────────┴───────────────────┐
              │                                          │
   ┌──────────▼───────────┐                  ┌───────────▼──────────┐
   │  MAIN DEVICE          │                  │  MIRROR DEVICE        │
   │  • Camera capture     │                  │  • Scoring UI         │
   │  • Zoom control       │                  │  • Clock controls     │
   │  • Overlay renderer ──┤ reads state      │  • Team/player/period │
   │    (scoreboard+logos  │                  │  • Custom text edit   │
   │     +text, burned in) │                  │  • NO video           │
   │  • H.264 encode       │                  │                       │
   │  • RTMP → platform    │                  │  Pairs via QR scan    │
   │  • Local recording    │                  │                       │
   │  • Scoring UI         │                  │                       │
   │  • Logos/graphics     │                  │                       │
   │    (local assets)     │                  │                       │
   └──────────────────────┘                  └──────────────────────┘
```

Only the main device runs the capture → composite → encode → RTMP/record pipeline. The mirror is a thin scoring client.

**Local-first rule (required):** the main device's overlay and UI render from the **local state replica** and never wait on a network round-trip — writes apply locally and echo to Firebase in the background (enable Firebase offline persistence). If connectivity drops, the main device keeps scoring and rendering (the RTMP stream has its own resilience handling, §10); the mirror shows a clear "reconnecting" state and queues its writes. Firebase is the **sync layer**, not the render source.

---

## 3. Technology stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin | |
| Min/target SDK | minSdk 26, targetSdk 34+ | Android 14 foreground-service rules apply. |
| Streaming + compositing | **StreamPack** (`io.github.thibaultbee.streampack`) | CameraX/Camera2-based, RTMP + SRT, GL overlay compositing. Renders scoreboard + logo + text layers composited into the camera texture **before** encode → frame-accurate burned-in graphics. Supports **simultaneous record-to-file** while streaming. Fallback: HaishinKit. Do **not** hand-roll MediaCodec + RTMP muxer. **Validate early** that the overlay path sustains ~1 bitmap update/sec at 30 fps output; if it can't, fall back to a custom GL overlay layer (scoping Phase 2 contingency). |
| Camera / zoom | CameraX | Exposes zoom ratio + pinch-to-zoom directly. |
| Local recording | StreamPack file output (MP4) | Same encoder fan-out: stream + file at once. |
| State sync | Firebase Realtime Database | Native last-write-wins. Sub-second propagation. |
| QR generation | ZXing (`journeyapps:zxing-android-embedded`) | Generate on main, scan on mirror. |
| UI | Jetpack Compose | Overlay UI and scoring panels. |
| Config | Bundled JSON sport definitions + user-created custom files | See §6. |
| Auto-update | Custom version-manifest check | App polls a hosted `version.json`, prompts APK download. Replaces Play Store update mechanism. |
| Crash & diagnostics | Firebase Crashlytics + local stream log | Sideloading = no Play vitals; Crashlytics is the only field-crash visibility. Keep an exportable per-session stream log (connect/drop/bitrate events) for venue debugging. |

---

## 4. Game-state object (Firebase schema)

Path: `/sessions/{sessionId}`. Holds **scoring + text** data that both devices edit. Binary assets (logos/graphics) are NOT here — they are main-device-local (see §7).

```json
{
  "sessionId": "abc123",
  "joinToken": "9f3k2",
  "sport": "basketball",
  "homeScore": 0,
  "awayScore": 0,
  "setsWon": { "home": 0, "away": 0 },
  "teamNames": { "home": "Lions", "away": "Tigers" },
  "teamColors": { "home": "#1E40AF", "away": "#B91C1C" },
  "players": { "home": [], "away": [] },
  "customText": "",
  "period": 1,
  "clockRunning": false,
  "startedAt": null,
  "baseRemaining": 600,
  "extraFields": { "fouls": { "home": 0, "away": 0 },
                   "timeouts": { "home": 0, "away": 0 } },
  "overlayPosition": "bottom_center",
  "lastUpdatedBy": "main",
  "updatedAt": 1718800000000
}
```

- `setsWon` — used by `setsGames` sports; `homeScore`/`awayScore` then hold **current-set points** and reset per set.
- `teamColors` — hex strings the overlay uses to tint each team's panel. Editable from either device.
- `players` — optional per-team name lists for sports that show player names (tennis, badminton, etc.); empty for team-only sports.
- `customText` — the free-text banner ("Championship Finals," team intro). Editable from either device, shown by the overlay when non-empty.

**Conflict resolution:** last-write-wins for names, colors, custom text, period, and the clock anchor. **Scores, sets, and other counters use Firebase atomic increments** (`ServerValue.increment`) so simultaneous taps from both devices both land instead of one overwriting the other.

### Database security rules (required)
Default-open Realtime Database rules must never ship. Clients sign in with **Firebase Anonymous Auth**; on session create the main device writes a `members` map, and the mirror's QR join (validated joinToken) adds its auth uid. Rules then enforce: **no read or write on a session without membership; joinToken validated in rules, not just client-side; tokens short-lived**. Exact rule wiring is an implementation detail, but membership-gated access is a hard requirement.

---

## 5. Clock model (critical — do not sync the ticking number)

Never write the live countdown second-by-second. That causes write storms and desync. Sync only the **anchor**:

- `clockRunning` (bool)
- `startedAt` (server/epoch ms when the clock was last started)
- `baseRemaining` (seconds left at the moment it was last started/adjusted)

Each device computes the live display **locally**:

```
if (clockRunning):
    elapsed = (now - startedAt) / 1000
    display = max(0, baseRemaining - elapsed)   // for countdown
else:
    display = baseRemaining
```

(For count-up sports, add instead of subtract — driven by the sport config's `clockDirection`.)

A start/stop/adjust action writes new anchor values. Last-write-wins applies cleanly to the three anchor fields. Both screens and the burned-in overlay stay in lockstep with zero per-second traffic. Both devices may control the clock. Supports count-up, count-down, and manual adjust (SportCam parity).

**Clock skew (required):** never trust raw device time across two phones. Write `startedAt` with Firebase server timestamps (`ServerValue.TIMESTAMP`) and compute `now` on each device as `localTime + serverTimeOffset` (Firebase exposes `.info/serverTimeOffset`). Without this, two phones 10 seconds apart display clocks 10 seconds apart.

---

## 6. Config-driven scoring engine

Sports are **not** coded individually. Each sport is a JSON definition; the scoring UI and overlay both render from the schema. Adding a sport = adding a file, no rebuild. Bundle a starter set; allow user-created custom sports.

Each config declares a `scoringModel`:
- **`flat`** (default) — score is a running integer changed by `scoreIncrements`. Covers basketball, soccer, hockey, generic.
- **`setsGames`** — set-based scoring: **sets → points** (volleyball, badminton, squash, table tennis) or sets → games → points (tennis), with win-by-two, point caps, and final-set variations. **Volleyball uses this model** — it is rally-scored to 25 with sets won, not a running total. See note below.

**Sample: `basketball.json` (flat)**
```json
{
  "sport": "basketball",
  "displayName": "Basketball",
  "scoringModel": "flat",
  "usesPlayers": false,
  "periods": 4,
  "periodLabel": "Quarter",
  "periodLength": 600,
  "clockDirection": "down",
  "scoreIncrements": [1, 2, 3],
  "extraFields": [
    { "key": "fouls", "label": "Fouls", "perTeam": true, "max": null },
    { "key": "timeouts", "label": "Timeouts", "perTeam": true, "max": 7 }
  ]
}
```

**Sample: `soccer.json` (flat, count-up)**
```json
{
  "sport": "soccer",
  "displayName": "Soccer",
  "scoringModel": "flat",
  "usesPlayers": false,
  "periods": 2,
  "periodLabel": "Half",
  "periodLength": 2700,
  "clockDirection": "up",
  "scoreIncrements": [1],
  "extraFields": [
    { "key": "yellowCards", "label": "Yellow", "perTeam": true, "max": null },
    { "key": "redCards", "label": "Red", "perTeam": true, "max": null }
  ]
}
```

**Sample: `volleyball.json` (setsGames — the primary use case)**
```json
{
  "sport": "volleyball",
  "displayName": "Volleyball",
  "scoringModel": "setsGames",
  "usesPlayers": false,
  "bestOf": 5,
  "pointsToWinSet": 25,
  "finalSetPoints": 15,
  "winByTwo": true,
  "pointCap": null,
  "clockDirection": "none",
  "scoreIncrements": [1]
}
```
(Badminton / squash / table tennis are analogous: `pointsToWinSet` 21 / 11 / 11, `usesPlayers` true, caps where the sport defines them.)

Launch bundle: basketball, soccer, hockey, generic point-counter (`flat`) **plus volleyball (`setsGames`) — the primary sport ships day one**. Badminton, squash, and table tennis follow on the same core (build step 7). **Tennis is deferred**: its point display (15/30/40/Ad, tiebreaks) needs a display extension to `setsGames` — add it later rather than ship it wrong. The scoring UI dynamically builds increment buttons from `scoreIncrements`, renders `extraFields` as steppers, and shows player-name inputs when `usesPlayers` is true.

The sport-selection grid (Appendix B, step 1) renders one tile per installed config, so the catalog grows by adding files. The reference app lists ~20 sports; note that a few of those (baseball innings, cricket overs/wickets, curling ends) don't fit `flat` or `setsGames` cleanly — cover them later with either simplified flat configs or an additional scoring model. Don't let them block launch; ship the sports actually needed first.

**Note on `setsGames`:** set/point tracking with win-by-two and final-set rules is meaningfully more logic than `flat`, and **it ships in Phase 4 because volleyball — the primary use case — requires it**. Remaining racket sports are a fast-follow (build step 7); tennis additionally needs the point-display extension and stays deferred (§15).

---

## 7. Overlay & branding renderer

All visual layers below are rendered into a Surface/bitmap fed to StreamPack's GL compositor, so everything is **burned into the encoded frames** (and into the local recording).

- **Landscape only.**
- **Scoreboard layer** — from current game state: team names, scores, clock (computed locally), period, and any `extraFields` shown compactly. Tinted with `teamColors`. For `setsGames` sports it shows set/game/point columns and player names.
- **Sponsor / team logo layer** — one or more PNG logos the operator adds on the main device (team, club, league, sponsor). Position/size adjustable. Logos are **main-device-local image assets** (not synced through Firebase).
- **Custom text layer** — renders `customText` from state as a banner when non-empty (e.g. "Championship Finals"). Editable from either device.
- **Team colors** — `teamColors` drive scoreboard panel tinting; editable from either device.
- **Redraws** on every relevant state change (score, clock tick, period, names, colors, custom text) and on local logo/position edits.
- **Position selectable** by the operator: `top_left`, `top_right`, `bottom_left`, `bottom_center`, `bottom_right`. Stored in state (`overlayPosition`) and applied as an anchor + margin offset within the 16:9 frame. Default `bottom_center`.
- **Layout:** one universal scoreboard bar that adapts to the sport config. Per-sport bespoke layouts are deferred to v2 (§15).

The full in-game operator screen (scoreboard preview + scoring controls + clock + capture bar) is described as a UI reference in **Appendix A**.

**Deferred to v2 (see §15):** full-screen break / half-time / intermission graphics (a mode that swaps the camera feed for a full-frame graphic mid-stream without dropping RTMP).

---

## 8. Capture controls & local recording

These share the camera and encoder with the streaming pipeline (main device only).

- **Zoom** — pinch-to-zoom and/or on-screen +/− buttons via CameraX zoom ratio. Smooth, no extra hardware.
- **Local recording** — record the composited (scoreboard-burned) video to an MP4 in app storage. Three modes:
  - Stream only,
  - Stream **and** record simultaneously (StreamPack fans the encoded output to both RTMP and file),
  - Record only (no stream).
- **Mode selection** happens in the setup wizard (Appendix B, step 3): the **Save in memory** platform tile = record-only; the **"Save the video on my device while I'm live streaming"** toggle = stream+record.
- Recorded files are listed in-app for the user to play back, share, or upload later. Mind storage: long games are large — warn on low space and let the user pick quality/bitrate.
- **Recording-state indicator (UX pattern, from reference app):** the in-game screen always shows a clear, persistent recording state next to the timer — e.g. a colored dot + label reading **"video is not being stored"** vs **"recording"** — so the operator is never unsure whether the game is being saved locally. Carry this affordance into the in-game UI (see Appendix A).
- Recording must also survive backgrounding (same foreground-service requirement as streaming, §11).

### Power & thermal (required)
Two hours of camera + encode + upload cooks phones. The app must: recommend plugged-in operation at stream start; monitor thermal status (PowerManager thermal API) and step bitrate/resolution down **before** the OS throttles; offer a screen-dim mode while streaming; and warn on low battery with the stream still healthy enough to wind down cleanly.

---

## 9. QR pairing flow

1. Main device creates a session → generates `sessionId` + short-lived `joinToken`, writes the initial state object.
2. Main device renders a QR encoding `{ sessionId, joinToken }`.
3. Mirror device scans → validates token against the session → binds to the same Firebase path.
4. Both devices now read/write the shared state. `joinToken` is short-lived so a stray scan can't hijack the scoreboard.

The QR encodes the **session**, never the video URL — the mirror has no video access by design.

**Connection-status indicator (UX pattern, from reference app):** the in-game screen shows a live pairing state for the second scorer — e.g. a red/green dot with **"scorer offline"** / **"scorer connected"** — plus a visible **remote-scoring on/off toggle**. Drive it off the mirror's Firebase presence so the main operator always knows whether the remote scorer is live. (See Appendix A.)

---

## 10. Streaming platforms (multi-platform RTMP picker)

All supported platforms ingest over RTMP, so the same StreamPack pipeline serves all of them — only the ingest URL + stream key change. The app presents a **platform picker** (Appendix B, step 3): Facebook, YouTube, Twitch, or Custom RTMP — plus a record-only **Save in memory** option. **One target per broadcast.** Facebook connects via in-app login and a destination picker (below); the other platforms use manual ingest URL + stream key paste in v1.

### Supported targets
- **Facebook Live** — RTMP ingest to profile, **page**, or group. Default stream keys from Live Producer are typically **single-use** (regenerated per broadcast); Facebook offers a "persistent stream key" toggle the user must enable. Operator grabs a fresh key from Live Producer each session unless persistent is on. (Primary launch target.)
- **YouTube Live** — RTMP ingest. Stream keys can be made persistent (reusable across broadcasts).
- **Twitch** — named tile; standard RTMP ingest with a Twitch stream key (manual paste in v1).
- **Custom RTMP** — free-form ingest URL + key field for anything else (restream relays, self-hosted, etc.).
- **Save in memory** — shown alongside the platform tiles as a record-only choice (no stream); maps to §8's record-only mode.

### Facebook — in-app destination picker (v1)
Facebook is integrated via **Facebook Login + Graph API** rather than key paste:
1. Operator logs in with Facebook inside the app (Facebook Login SDK). The self-signed release keystore's **key hash** must be registered in the Meta app settings.
2. App requests permissions: `pages_show_list`, `pages_read_engagement`, `pages_manage_posts`, `publish_video`.
3. Destination picker: **profile/wall** or one of the user's **Pages** (currently selected Page shown). **Groups:** Meta deprecated the Groups API (2024), so group streaming via API is likely unavailable to new apps — verify against current Meta docs before promising it; the reference app may hold legacy access.
4. Operator enters a **description** (long limit, e.g. 5000 chars) posted with the broadcast.
5. App calls the Graph API live-videos endpoint for the chosen destination; the response returns the **RTMPS ingest URL + stream key**, fed straight into the StreamPack pipeline — the streaming layer is unchanged.
6. Token handling: long-lived user token + Page access tokens, refresh, and error states.

**App Review reality (important):** a Meta app in **Development Mode** grants these permissions to the app's admins/developers/testers **without App Review** — sufficient for a private, sideloaded app operated by your own team (add each operator's Facebook account as a tester). **Live Mode + Facebook App Review** (screencast, privacy policy URL, data-deletion callback, possibly business verification) is required only if people outside your Meta app roles will log in. Plan review lead time only if ScoreCast will be distributed beyond the team.

**Fallback:** manual server URL + stream key paste remains available for Facebook (via Live Producer) whenever the operator isn't logged in or the API path fails.

### Other platforms — manual key paste (v1)
YouTube / Twitch / Custom RTMP: operator creates the broadcast on the platform, copies the RTMP ingest URL + stream key, pastes both into the app. No OAuth. Store recent targets locally for quick re-selection.

### v2 (later)
- **YouTube in-app broadcast creation**: Data API v3 + Google OAuth + Google verification flow. Separate effort; do it only if manual YouTube paste becomes a real friction point.

### Stream resilience (required)
- **Adaptive bitrate:** enable bitrate regulation so the encoder steps down under congestion instead of stalling (StreamPack supports this), and steps back up on recovery.
- **Reconnect with exponential backoff** on drops, preserving the session; surface states — live / degraded / reconnecting / failed — to the in-game UI (Appendix A).
- Defaults tuned for venue networks: start at 720p with regulation active; 1080p as an explicit operator choice.

### Multistreaming note (important)
A phone can comfortably push **one** RTMP target. Encoding and uploading to two platforms simultaneously from the device is a battery, heat, and bandwidth problem and is **out of scope for on-device**. If simultaneous multi-platform output is ever required, push a single stream to a **restream relay** (Restream, Castr, etc.) via the Custom RTMP option and let the relay fan it out.

---

## 11. Sideload requirements

- Sign the release APK with a self-managed keystore.
- Host the APK privately (your own URL / storage).
- **Auto-update:** app checks a hosted `version.json` (version code + APK URL + changelog) on launch; if newer, prompts the user to download and install. This replaces the Play Store update path — without it there is no update mechanism.
- **Android 14+ foreground services:** declare a foreground service with the correct `camera` and `microphone` service types, plus `RECORD_AUDIO`, `CAMERA`, and `INTERNET` permissions (and storage handling for local recordings). Without the correct FG service-type declaration the stream/recording drops when the app is backgrounded.
- Because there is no Play policy review, request the capture permissions directly — but still follow runtime-permission UX.
- **Secrets at rest:** stream keys and Facebook tokens are stored via EncryptedSharedPreferences (Android Keystore-backed) — never plaintext preferences or logs.
- **Updater security:** `version.json` and APKs served over **HTTPS only**; declare `REQUEST_INSTALL_PACKAGES`; verify the downloaded APK's signature matches the app's own signing cert before prompting install.

---

## 12. Module breakdown

| Module | Responsibility |
|---|---|
| `:core-state` | Firebase game-state model (scores, names, colors, players, custom text, clock anchor), read/write, listeners, last-write-wins writes. Also persists local match history (teams, colors, dates) for the Matches screen (Appendix B). |
| `:core-config` | Sport JSON loading (bundled + custom), schema parsing, validation, `flat` + `setsGames` models. |
| `:clock` | Anchor model, local computation of live display (up/down/none), start/stop/adjust writes. |
| `:streaming` | StreamPack pipeline: camera capture, layer composite, H.264 encode, RTMP push, platform picker (Facebook / YouTube / Twitch / custom RTMP), stream-key/URL handling, simultaneous file recording. |
| `:platform-api` | Facebook Login + Graph API: Pages list, live-video creation (returns RTMPS ingest + key), description posting, token lifecycle; manual-paste fallback. |
| `:capture` | Zoom controls and recording mode selection (stream / stream+record / record-only), recordings list + playback/share. |
| `:overlay` | Scoreboard + logo + custom-text rendering, team-color tinting, position anchoring, redraw-on-change. Logo/graphic local asset management. |
| `:scoring-ui` | Compose scoring panel built dynamically from sport config — increments, extra-field steppers, player-name inputs, team-color pickers, custom-text editor (shared by main + mirror). |
| `:pairing` | QR generation (main) and scanning/validation (mirror), session + join-token lifecycle. |
| `:update` | Version-manifest check, APK download + install prompt. |
| `:app` | Role selection (main vs mirror), navigation, foreground service, permissions. |

---

## 13. Build order

1. **Prove the pipeline.** StreamPack camera → RTMP with a *static* test overlay, using one platform as the test target (Facebook is the launch target; YouTube has a reusable key that's handy for repeat testing). Manual stream-key paste. Landscape. Confirm a burned-in graphic reaches the live broadcast.
2. **Dynamic overlay from local state.** Scoreboard redraws from an in-memory state object; wire the five position options; add team-color tinting and the custom-text banner.
3. **Branding + capture extras.** Sponsor/team logo layer (local assets, position/size), zoom controls, and local recording (stream / stream+record / record-only) with a recordings list.
4. **Config-driven scoring engine + main scoring UI.** JSON sport loading — `flat` **plus the set-based `setsGames` core including volleyball (primary sport)** — dynamic buttons/steppers, set tracking, atomic score increments, player-name inputs where `usesPlayers`, local-first state driving the overlay.
5. **Firebase sync + QR pairing.** Move state to Firebase; mirror device scans QR and edits scores/names/colors/text/clock; last-write-wins verified across devices.
6. **Clock anchor logic.** Anchor model, local display computation, both devices control start/stop/adjust.
7. **Remaining racket sports.** Badminton/squash/table-tennis configs on the existing `setsGames` core + overlay columns. Optional tennis point-display extension (§15). (Fast-follow.)
8. **Facebook destination picker (Graph API).** Facebook Login, Pages list, live-video creation feeding the existing RTMP pipeline, description field, token handling, manual-paste fallback. Run the Meta app in Development Mode with team accounts as testers; start App Review in parallel only if outside users are planned.
9. **Auto-update + multi-platform + polish.** version.json check, install prompt, full platform picker with recent-target storage, additional bundled sports, custom-sport creation, foreground-service hardening for Android 14+.

---

## 14. Competitive feature parity (SportCam)

Status of every SportCam feature against this spec. **In v1** = built in the phases above. **v2** = deferred (§15).

| SportCam feature | Status here | Where |
|---|---|---|
| Live stream to Facebook (profile/page/group) | In v1 | §10 |
| Live stream to YouTube | In v1 | §10 |
| Any RTMP platform | In v1 | §10 |
| Professional scoreboard overlay (names, score, timer) | In v1 | §7 |
| Multiple sports, layout adapts | In v1 (config-driven; more flexible) | §6 |
| Racket sports (volleyball day one; badminton/squash/table tennis fast-follow) | In v1 — tennis deferred (point-display extension) | §6 `setsGames`, build steps 4 & 7 |
| Remote scoring (two people) | In v1 (QR-paired mirror) | §9 |
| Player names | In v1 | §4 `players`, §6 `usesPlayers` |
| Logo / sponsor overlay | In v1 | §7 |
| Custom text overlay | In v1 | §7 `customText` |
| Team color customization | In v1 | §4 `teamColors`, §7 |
| Count-up / count-down / manual timer | In v1 | §5 |
| Local recording (record to phone) | In v1 | §8 |
| Zoom controls | In v1 | §8 |
| 4-step setup wizard (sport → teams → platform → destination) | In v1 | Appendix B |
| Match history / rematch list | In v1 | Appendix B |
| Twitch as a named platform | In v1 (manual key) | §10 |
| Facebook destination picker (profile / fanpage) + in-app description | In v1 (Facebook Login + Graph API; manual paste fallback; groups pending API availability) | §10, Appendix B |
| Full-screen break / half-time graphics | **v2** | §15 |
| Per-sport bespoke scoreboard layouts | **v2** (v1 = universal adaptive bar) | §15 |
| White-label / per-org rebranding | **v2** | §15 |

Note: matching SportCam's *capabilities* is the goal; do not copy its name, branding, icon, UI, or assets. This product ships as **ScoreCast**.

---

## 15. Deferred / v2 candidates

- **Full-screen break / half-time / intermission / sponsor graphics** — swap the camera feed for a full-frame graphic mid-stream without dropping RTMP.
- **Per-sport bespoke scoreboard layouts** (v1 ships one universal adaptive bar).
- **Scoreboard style/theme editor** beyond position + colors (reference app's "Style your scoreboard").
- **Tennis point-display extension** (15/30/40/Ad, tiebreaks) on top of `setsGames`.
- **White-label support** — rebrand the app per club/league/federation (better designed in early if it becomes a priority).
- **YouTube in-app broadcast creation** (Data API v3 + Google OAuth + verification). Facebook's destination picker is already in v1 (§10).
- **On-device simultaneous multistreaming** — explicitly **not** planned; use a restream relay via custom RTMP if ever needed.
- **SRT output** (StreamPack supports it) for lower-latency / more resilient ingest.
- **Multiple mirror devices** on one session.
- **Cloud upload / highlight clips** of recorded games.

---

## 16. Appendix A — In-game UI reference (main operator screen)

This is the **main operator's in-game screen** — what appears once a session is running, with camera live and scoring active. It is **not** the app's launch screen; a setup flow precedes it (role select → sport select → team setup / session create). Those initial/setup screens are specified in **Appendix B (§17)**.

**Directive:** replicate all the *functions and general layout* below. **Do not** reuse the reference app's color scheme — ScoreCast uses its own visual identity (the reference app's yellow/black is its brand, not ours). Colors, icons, and styling are ScoreCast's own; the arrangement and behaviors are what we're matching.

### Layout regions and elements

**Top-left — live preview**
- Small live camera preview with the **burned-in scoreboard visible inside it** (team names, scores, period) — confirms the composited overlay is what viewers see.
- Fullscreen-toggle icon to expand the preview.

**Top bar**
- **Remote-scoring on/off toggle.**
- **Scorer connection status** — colored dot + label ("scorer offline" / "scorer connected"), driven by mirror presence (§9).
- **Stream health chip** — live / degraded / reconnecting / failed (§10 resilience).
- **Share** icon and an overflow **"…"** menu (settings).

**Clock block (upper area)**
- Mode label (**COUNT UP** / **COUNT DOWN** per sport config).
- Time display with a **pencil = manual edit** affordance.
- **Play / pause** control (start/stop the clock). Backed by the clock-anchor model (§5).
- **Mic on/off** toggle for stream audio.

**Two team panels (center)**
- Per team: **team-color dot**, **team name**, **pencil = rename**.
- Large **−** and **+** score buttons flanking the current **POINT** total.
- Score buttons reflect the sport config's `scoreIncrements`; for `setsGames` sports the panel also shows set/game/point columns and player names.

**Bottom bar**
- **Start** control (begin stream and/or recording).
- **Recording timer + recording-state label** — persistent "video is not being stored" vs "recording" indicator (§8).
- **Set / Period** stepper (**−** / value / **+**).
- **Battery** indicator and a **Share** action.
- **End match** action → confirmation → stops stream/recording, saves the final result to match history (Appendix B, B2), closes the session.

### Behaviors to preserve
- Everything the operator changes here (scores, names, colors, clock, period, custom text) is the same shared state the mirror device edits and the overlay renders — one source of truth (§4).
- The screen must remain usable one-handed in landscape on a tripod-mounted phone (large tap targets, thumb-reachable score buttons).
- Recording and streaming continue if the app is backgrounded (foreground service, §11).

### Setup flow
Role selection (main vs mirror), sport selection, team naming, session creation + QR display, platform/stream-key entry, and logo setup live **before** this screen — specified in **Appendix B**. Wire this in-game screen to assume a session already exists.

---

## 17. Appendix B — Setup flow & initial screens (reference)

The portrait screens that precede the in-game screen (Appendix A). Same directive as Appendix A: **replicate the functions and layout; use ScoreCast's own colors and branding**, not the reference app's yellow/black.

### B1 — Home (app launch screen)
- Brand header + settings gear.
- Primary card: **"Create a new live stream — and score yourself"** → opens Matches (B2).
- Secondary card: **"Join as remote scorer"** → opens the QR scanner (mirror role, §9). *(Replaces the reference app's "Stream Rankedin events" card — that's their proprietary tournament-platform tie-in and is out of scope for ScoreCast.)*
- Optional: a "See examples" strip linking to sample streams. Nice-to-have, not required.

### B2 — Matches
- Info banner: "Remote scoring from a second device" with a learn-more link.
- Primary CTA: **+ Stream new match** → wizard (B3).
- **History list:** past sessions as cards — both team names with their color dots, plus the match date. Tapping a card pre-fills a new session with those teams/colors (rematch flow). Stored locally on the main device; sessions land here automatically via the in-game **End match** action.

### B3 — Wizard step 1 of 4: Choose a sport
- Stepper (1–4) across the top.
- Grid of sport tiles (icon + name) rendered **from the installed sport configs** — adding a config file adds a tile (§6).
- Reference app ships ~20 sports (football, baseball, basketball, cricket, handball, volleyball, beach volleyball, pool/billiards, American football, curling, badminton, squash, tennis, padel, ice hockey, field hockey, floorball, teqball, pickleball, table tennis). See the §6 note on which of these need more than `flat`/`setsGames`.
- NEXT.

### B4 — Wizard step 2 of 4: Teams & scoreboard
- **Live scoreboard preview** at the top, reflecting entries in real time.
- **Style your scoreboard** (v1 = overlay position + team colors; a deeper theme editor is v2, §15) and **Preview scoreboard** actions.
- Per team: **color swatch picker** + **name field** (character-limited, e.g. 20 chars, with a live counter).
- **Team logo** upload slot per team + **Preview added logos** (§7).
- NEXT.

### B5 — Wizard step 3 of 4: Platform
- "Choose the platform to stream to" tile grid: **Facebook**, **YouTube**, **Twitch**, **RTMP (custom)**, and **Save in memory** (record-only, no stream — §8).
- Toggle: **"Save the video on my device while I'm live streaming"** (stream+record mode, §8).
- NEXT.

### B6 — Wizard step 4 of 4: Destination & extras
The destination block depends on the platform chosen in B5:
- **Facebook (v1, API):** after Facebook Login, pick the destination — **profile/wall** or **fanpage** (currently selected Page shown; groups pending API availability, §10) — plus a **description** field (long limit, e.g. 5000 chars) posted with the broadcast. Manual key paste remains as a fallback.
- **YouTube / Twitch / Custom RTMP (v1, manual):** paste the **server URL + stream key**, with recent targets for quick reuse (§10). Broadcast title/description is set on the platform side.
- **v2:** YouTube gains its own OAuth destination flow (§10 v2).

Also on this step:
- **Sponsor logos:** add up to **6** (thumbnail grid with edit + remove per logo) + **Preview added logos** (§7).
- **Break graphics:** the reference app reserves slots here ("Add a break graphic, 0/4"); the break-graphic feature itself is **v2** (§15). v1 hides this section or shows it disabled.
- Finish → session created → QR available for the mirror → **in-game screen (Appendix A)**.

### Orientation
The setup flow (B1–B6) is **portrait**; the in-game screen (Appendix A) is **landscape**. Handle the rotation at the wizard → in-game transition.
