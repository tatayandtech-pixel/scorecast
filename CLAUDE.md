# ScoreCast — Claude Code Project Rules

## Source of truth
Read `SCORECAST_SPEC.md` in full before any work. It defines the architecture, data model, module boundaries (§12), build order (§13), and UI references (Appendices A–B). When this file and the spec conflict, ask the owner.

## Current status (updated 2026-07-08)
Built and verified on-device:
- **Phase 1 — streaming pipeline**: camera → GL-composited burned-in scoreboard → H.264 → RTMP(S), confirmed live on Facebook with the graphic visible. Android 14 foreground service (camera+microphone types) in place. Two real bugs were found and fixed this cycle: a camera-session race triggered by StreamPack's encoder color-format fallback (Legacy-tier Camera2 HALs reject reconfiguring an active session), and camera warm-up now happens *before* the RTMP connection opens (it was racing Facebook's ingest timeout).
- **Phases 2–4 — dynamic overlay, branding, config-driven scoring**: logos, position/color/custom-text, zoom, local recording (stream/record/both), JSON sport configs. Volleyball's `setsGames` model (sets to 25, win-by-two, lower target on the deciding set) is now correctly wired end-to-end — data model → scoring logic → operator UI → burned-in overlay. It had shipped mismarked as `flat` scoring; fixed and verified live (set win banks correctly, resets, advances the set number, shows in the burned-in graphic).
- **Setup flow (spec Appendix B)**: Home → Matches (local match history, rematch pre-fill) → 4-step wizard (sport → teams/live preview → platform → destination/logos), all portrait, auto-rotating into the landscape in-game screen and back.
- **In-game screen (spec Appendix A)**: stream health chip, recording-state indicator, remote-scoring/scorer-status stub (Phase 5 placeholder), mic on/off (real `AudioManager` mute, confirmed via `dumpsys audio`), share sheet, battery indicator, End Match confirmation flow, clock manual-edit dialog.

Not started: Firebase sync + QR pairing (Phase 5), clock anchor/server-timestamp sync (Phase 6 — clock is local-only today), remaining racket sports (Phase 7), Facebook Graph API login (Phase 8), auto-update (Phase 9).

Open/unconfirmed: a repeated RTMP disconnect seen during heavy same-day testing is suspected to be Facebook-side throttling from rapid reconnects rather than a pipeline bug, but hasn't been re-verified after a cooldown. The §10 resilience layer (adaptive bitrate regulation, reconnect-with-backoff, real degraded/reconnecting states) isn't built yet — today's health chip only distinguishes live/starting. Also noted but unfixed: if the Activity is recreated while a stream is running in the background (e.g. backing all the way out to the launcher), the app resets to the Home screen instead of resuming into the live view — the foreground service itself keeps running fine.

## Known deviation from spec
Spec §12 calls for `:streaming`, `:overlay`, `:capture`, `:core-state` etc. as separate Gradle modules. The codebase is currently a single `:app` module (package `com.scorecast.app`) — no module split has been done. Flag this if a future session wants to enforce the module boundary; it hasn't blocked anything so far.

## Phase discipline
- The status section above is the reference for what's done vs. open — don't re-derive it from git history alone, it undersells how much shipped in a few large early commits.
- Don't scaffold, stub, or "prepare for" a later phase beyond the interfaces the spec's module boundaries require, unless the owner explicitly asks to jump ahead (as happened with the setup wizard and in-game polish before Phase 5 existed).
- Still explicitly deferred until the owner asks for them: Firebase, QR pairing, Facebook OAuth, auto-update, remaining racket sports, tennis point display.

## Stack constraints
- Kotlin, Jetpack Compose, minSdk 26, targetSdk 34+, landscape for the streaming/in-game screen; portrait for the setup flow (Home/Matches/wizard), switched via `requestedOrientation` — see `MainActivity.kt`'s `AppRoot`.
- Streaming: **StreamPack** (`io.github.thibaultbee.streampack`), pinned to 3.1.2 in `gradle/libs.versions.toml`. Do NOT hand-roll MediaCodec + an RTMP muxer. HaishinKit only with owner approval.
- No navigation library — the setup/wizard/in-game screen router is a plain sealed `Screen` enum + `when` in `MainActivity.kt`, deliberately avoiding a new dependency for a handful of screens.
- Ask before adding any dependency not named in the spec.

## Environment
- Windows 11, PowerShell/Git Bash — use `.\gradlew.bat` (or `./gradlew.bat` from bash) for all Gradle commands. `JAVA_HOME` needs to point at Android Studio's bundled JBR (e.g. `C:\Program Files\Android\Android Studio\jbr`) for CLI builds.
- Streaming behavior must be verified on a **physical device** (USB debugging); the emulator is for compile checks only.
- For on-device UI automation, prefer `adb shell uiautomator dump` for exact element bounds over eyeballing screenshot coordinates — screenshots get downscaled for viewing and manual coordinate estimates have repeatedly been off by the scale factor.

## Security
- Never hardcode, log, or commit stream keys, tokens, or keystores. Stream keys are runtime input only.
- Recent-target storage (`StreamTarget.kt` / `RecentTargetsStore`) persists ingest URLs for quick re-selection but deliberately never persists the stream key itself.
- Add keystore files and any local secrets to `.gitignore` in the very first commit.

## Working style
- At each phase/feature start: present a short plan (files to create and why), then WAIT for the owner's explicit go-ahead before writing code.
- Ask when the spec is ambiguous rather than guessing.
- Commit after each verified working step; small commits with clear messages. The owner keeps a last-known-good build at all times.
