# Product

## Register

product

## Users

ScoreCast has two personas sharing one product, both under time pressure during a live broadcast:

1. **The operator (Android app)** — the person running the actual broadcast: camera framing, RTMP streaming, and usually scoring too, on a phone or tablet mounted courtside/pitchside. Their attention is split between the game and the device; every screen has to stay legible and tappable one-handed, often outdoors or in gym lighting, without pulling focus from the live action they're also filming.
2. **The remote scorer (web mirror, `web-mirror/`)** — someone other than the camera operator, seated at a laptop or desk, keeping score for whichever sport is running on the operator's stream. They join a live session via a pairing code typed in by hand (no camera/QR scanning on this surface — scanning is the Android-to-Android path). The operator's device is always the source of truth for video, overlay, and game state; this page is a thin remote control that mirrors and edits that state, nothing more.

The job to be done, every time, for both personas: glance at the current score/clock, tap the correct button, and trust that the tap registered — while a live broadcast is running and the game itself doesn't pause for UI friction.

## Product Purpose

A reliable live-scoring and broadcast tool with one shared brand across two surfaces:

- The **Android app** is the primary product: camera capture, burned-in scoreboard overlay, RTMP streaming to Facebook/YouTube/custom endpoints, local recording, and the full scoring panel (teams, clock, periods, sets). It is the source of truth for game state and video.
- The **web mirror** is a lightweight, reliable remote scoring surface for non-Android devices, so a second person can update scores/clock for a live-streamed match without needing an Android phone or the main app installed. It exists purely as an accessibility/convenience layer on top of the Android app's Firebase-synced pairing system (Phase 5) — it has no video, no camera, no independent state of its own.

Success is invisible on both surfaces: every tap lands exactly once, exactly as intended, with no lag or double-fire, even on a flaky connection or after the app/tab has been backgrounded.

## Brand Personality

Fast, minimal, unmissable. No decoration, no dashboard chrome — every screen on either surface is built around a single task (score, or join-then-score), not a menu of options. Every interaction gives immediate, confident feedback that the tap was received, since the cost of an unnoticed missed or duplicate tap is a wrong score on a live broadcast.

## Anti-references

No specific anti-reference named by the owner. Default to avoiding generic SaaS/dashboard scaffolding (card grids, gradient accents, eyebrow labels, hero-metric layouts) — these are utility screens for a task in progress, not a marketing or admin surface, and that scaffolding would work against the "fast, minimal, unmissable" goal.

## Design Principles

1. **Local truth, remote mirror** — the web mirror never originates game state; it reflects and requests changes, echoing the Android app's own local-first architecture (local state updates immediately; network only pushes diffs). Never design a flow that implies the mirror is authoritative over the main device.
2. **Confirm every tap** — visible, immediate feedback on every score/clock action, on both surfaces; ambiguity about whether a tap registered is the single worst failure mode for either.
3. **Never color-only** — status and delta indicators (connection state, score deltas, live/recording tallies) must be legible without color perception; pair color with shape, icon, or text.
4. **One task, no chrome** — resist adding dashboard-style structure (cards, panels, nav) beyond what each screen's actual task needs, on either surface.
5. **Degrade honestly** — when sync lags or drops, say so plainly rather than showing stale state as if it were live.

## Accessibility & Inclusion

WCAG AA baseline: body text ≥4.5:1 contrast, respect `prefers-reduced-motion`. Status indicators (connection dot, score deltas, live/recording tallies) must not rely on color alone — pair with an icon or text label so color-blind users get the same information. Touch targets ≥44×44px on both platforms — the same one-handed, under-time-pressure tapping conditions apply to the operator's Android screen as to the remote scorer's page.
