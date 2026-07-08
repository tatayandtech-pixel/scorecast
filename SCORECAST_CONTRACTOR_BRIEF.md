# ScoreCast — Contractor Brief: Media Core (Phases 1–3)

**Engagement:** fixed-scope contract for the live-streaming media core of an Android app.
**Working title:** ScoreCast (subject to change).
**Client:** [your name / company], Philippines. Remote engagement; async-friendly.
**Reference documents provided on engagement:** `SCORECAST_SPEC.md` (full product spec) and `SCORECAST_SCOPING.md` (effort baseline).

---

## 1. Summary

We are building a sideloaded Android app that livestreams amateur sports with a professional scoreboard **burned into the video** (composited pre-encode, not an on-screen view), streaming over RTMP to Facebook/YouTube/Twitch/custom targets, with simultaneous local recording. The full product (scoring engine, two-device sync, setup wizard, Facebook Graph API) is being developed by the Client. **This contract covers only the hard media core — Phases 1–3** — after which the Client continues development on top of your modules.

Because the Client continues the build after handover, **handover quality is part of the deliverable**, not an afterthought.

---

## 2. Scope of this engagement

Three phases, delivered and accepted in order. Section references (§) point to `SCORECAST_SPEC.md`.

### Phase 1 — Streaming pipeline (§3, §10, §11)
Kotlin app (minSdk 26, targetSdk 34+, Jetpack Compose, landscape) using **StreamPack** (`io.github.thibaultbee.streampack`; HaishinKit acceptable as fallback with justification):
- Camera capture (CameraX), a **static test scoreboard graphic composited into the encoded frames** via the GL pipeline, H.264 encode, RTMP/RTMPS push to a manually pasted ingest URL + stream key.
- Correct Android 14+ **foreground service** (`camera` + `microphone` types) and runtime permissions so the stream survives backgrounding and screen-off.
- Stream resilience: **adaptive bitrate regulation** on congestion, **reconnect with exponential backoff**, and health states (live / degraded / reconnecting / failed) surfaced to the UI.

**Acceptance criteria:**
1. Burned-in graphic is visibly present on a **live Facebook and YouTube broadcast** (Client verifies as viewer).
2. 30+ minutes continuous streaming without crash; stream continues with app backgrounded and screen off.
3. Configurable resolution/bitrate (at minimum 720p/1080p presets).
4. Verified on **3 physical devices** across at least 2 chipset families (e.g., Snapdragon + Exynos/MediaTek) — encoder behavior varies by SoC.
5. Induced-degradation test: throttled bandwidth mid-stream → bitrate steps down without stalling; brief network outage → automatic reconnect resumes the broadcast.

### Phase 2 — Dynamic overlay engine (§7)
- Overlay renders from an **in-memory `GameState` object** (schema in spec §4: team names, scores, colors, clock value, period, custom text) and re-composites immediately on state change.
- **Five selectable positions** (top-left, top-right, bottom-left, bottom-center, bottom-right) with correct anchoring + margins in the 16:9 frame, switchable at runtime.
- Team-color tinting of the scoreboard panels; a custom-text banner rendered when non-empty.
- Clean public API (e.g., `OverlayController.update(state)`, `setPosition(...)`) — the Client will drive this from scoring logic and Firebase later; your module must not know or care where state comes from.

**Acceptance criteria:** state changes reflect in the *live output stream* (not just preview) with no visible lag or flicker; a ticking clock at 1 update/sec sustained for 10 minutes with no output frame-rate degradation; all five positions verified on-stream; API documented.

### Phase 3 — Branding layer, zoom, local recording (§7, §8)
- **Logo layer:** up to 6 user-supplied PNGs composited into the output with position/size controls (local assets).
- **Zoom:** pinch-to-zoom + on-screen controls via CameraX zoom ratio, smooth during streaming.
- **Local recording:** three modes — stream only, **stream + record simultaneously** (encoder fan-out to RTMP + MP4), record only. Recordings contain the burned-in overlay, are playable/shareable, listed in a simple in-app screen. Low-storage warning.

**Acceptance criteria:** 30-minute simultaneous stream+record on a mid-range device without failure (document thermal/battery observations); resulting MP4 plays in standard players with overlay burned in; logos render correctly at chosen positions/sizes on-stream.

---

## 3. Explicitly OUT of scope

Do not build these; the Client will: Firebase sync, QR pairing / mirror device, the scoring engine and sport configs, clock logic, the setup wizard and match history, Facebook Login / Graph API, YouTube API, auto-update, break graphics, white-label. If your Phase 1–3 work needs a stub for any of these, stub it behind an interface and document it.

---

## 4. Technical constraints

- Kotlin, Jetpack Compose, minSdk 26, targetSdk 34+.
- Module boundaries per spec §12: your work lands in `:streaming`, `:overlay`, `:capture` (plus minimal `:app` glue). No logic bleeding across module lines.
- StreamPack strongly preferred; do **not** hand-roll MediaCodec + an RTMP muxer.
- No paid third-party SDKs without prior written approval.
- Client-owned Git repository from day 1; contractor pushes regularly (no big-bang final drop).

---

## 5. Handover requirements (gates final payment)

- Code compiles and runs from a **clean clone** following your README.
- README: setup, how to run, how to test streaming, known device quirks.
- Short architecture notes per module (1–2 pages total): threading model, where compositing happens, how fan-out works, extension points the Client will use.
- One recorded walkthrough video (~20–30 min) + one live handover call.
- 30-day bug-fix warranty on accepted work (defects, not new features).

---

## 6. Suggested milestones & payment

| Milestone | Content | Payment |
|---|---|---|
| M0 (optional paid trial) | 8–16 hr trial: StreamPack sample streaming to a test key with a static PNG overlay burned in | Fixed small fee |
| M1 | Phase 1 accepted | ~45% |
| M2 | Phase 2 accepted | ~25% |
| M3 | Phase 3 + handover accepted | ~30% |

Payment on acceptance against the criteria above. Fixed price per milestone preferred (the spec is detailed enough to quote against); time-and-materials with a cap is acceptable if justified.

**Effort baseline (from `SCORECAST_SCOPING.md`):** Phase 1: 60–120 hrs · Phase 2: 35–65 hrs · Phase 3: 40–80 hrs · **Total 135–265 hrs.** Quotes far below the Phase 1 floor signal the streaming work has been misunderstood. If you anticipate the library's overlay path cannot sustain dynamic per-second updates and a custom GL layer will be needed, flag it in your quote (contingency budgeted at +15–30 hrs on Phase 2).

*(Client-internal note — remove before posting: at PH specialist rates of ₱500–1,500/hr this engagement realistically lands around **₱150k–₱400k** all-in. Decide whether to publish a budget range in the post.)*

---

## 7. Candidate requirements

**Must have (non-negotiable):**
- Shipped at least one Android app doing **live RTMP/RTMPS streaming from the camera** — provide a link, demo video, or repo.
- Hands-on with **on-device video compositing** (GL/Surface into the encode path) — not just drawing Views over a preview.
- Android 14 foreground-service and camera/mic permission experience.

**Strong plus:** StreamPack or HaishinKit specifically; simultaneous record+stream fan-out; CameraX zoom; experience debugging per-SoC encoder quirks.

### Screening questions (answer in your proposal)
1. Link one live-streaming Android project you built. What was your exact role?
2. How would you composite a dynamically changing bitmap into the camera frames **before** encoding? (We're listening for GL/Surface pipeline answers — an answer that only overlays a View on the preview is disqualifying, because it never reaches the stream.)
3. Which foreground service types does Android 14 require for this use case, and what happens if they're wrong?
4. Have you used StreamPack or HaishinKit? Name one real issue you hit and how you solved it.
5. How would you implement simultaneous RTMP streaming + MP4 recording from one encode session?
6. Quote hours per phase against Section 2, and flag anything you consider under-specified.

**Red flags we screen out:** proposals to use screen-recording/MediaProjection as the streaming path; overlay-as-View-only approaches; Phase 1 quotes dramatically below 60 hrs; no verifiable streaming work.

---

## 8. What the Client provides

- Full spec + scoping documents on engagement.
- Test stream keys (Facebook persistent key and/or YouTube).
- Acceptance testing on the Client's own devices in the Philippines (real-world network conditions).
- Fast async responses; weekly check-in call if useful.

## 9. Terms

- **IP:** work-for-hire; all code, assets, and documentation are the Client's sole property on payment. Contractor retains no rights and may not reuse project code. Portfolio mention only with written permission.
- **Confidentiality:** spec documents and product plans are confidential.
- **Ownership of accounts:** Git repo, keystore, and any platform accounts are Client-owned from day 1.
- Weekly progress demo (shared APK or screen recording).

## 10. How to propose

Send: (a) answers to the six screening questions, (b) per-phase hour estimate and price, (c) links to relevant work, (d) earliest start date and weekly availability, (e) preferred engagement type (fixed per milestone vs capped T&M).

---

---

## Appendix — Short job-post version (paste into Upwork / OnlineJobs.ph)

**Title:** Android Streaming Specialist — Live RTMP + Burned-in Overlay (Kotlin/StreamPack)

**Post:**
I need an experienced Android developer for the media core of a sports-streaming app: camera capture with a **scoreboard graphic composited into the encoded frames** (burned into the stream, not a screen overlay), H.264 → RTMP/RTMPS to Facebook/YouTube, Android 14 foreground service so the stream survives backgrounding, then a dynamic overlay engine (renders from a state object, 5 selectable positions, colors, text), logo layers, CameraX zoom, and **simultaneous stream + MP4 recording**.

Kotlin, Jetpack Compose, minSdk 26. StreamPack (or HaishinKit) — no hand-rolled MediaCodec/RTMP. Scope is 3 milestones, est. 135–265 hrs total, detailed spec + acceptance criteria provided. Scoring logic, Firebase, and UI beyond this core are NOT in scope — I continue the build after handover, so clean modules and documentation gate final payment.

**To apply, you must include:** a link to a live-streaming Android app you built, and a 2–3 sentence answer to: "How do you composite a dynamically changing bitmap into camera frames before encoding?" Applications without both are skipped. Only apply if you have shipped real RTMP streaming from Android.
