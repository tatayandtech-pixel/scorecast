# ScoreCast — Scoping & Effort Estimate

A phase-by-phase breakdown of the ScoreCast build, with effort estimates, complexity/risk ratings, and indicative Philippine pricing. Hand this to any developer or agency alongside the full spec (`SCORECAST_SPEC.md`) so quotes are comparable and the hard parts can't be quietly underestimated.

**Scope note:** this version reflects full **SportCam feature parity in v1** — logos/sponsor overlay, custom text, team colors, player names, local recording, zoom, racket-sport scoring, the portrait setup flow (home, match history, 4-step wizard), a named Twitch tile, **and the Facebook in-app destination picker (Login + Graph API)**. Remaining v2: full-screen break graphics, per-sport bespoke layouts, scoreboard theme editor, white-label, YouTube in-app broadcast creation (OAuth), and the tennis point-display extension.

---

## How to use this document

1. Give vendors **both** this file and `SCORECAST_SPEC.md`.
2. Ask each to quote **per phase** using this structure, plus their assumed hourly/blended rate.
3. Compare their hour estimates against the ranges below. A vendor who quotes Phase 1 far below this range likely hasn't understood the streaming/compositing work — that's your single biggest risk signal.

**Honesty note on numbers:** the hour ranges are informed engineering estimates for an app of this complexity; the peso figures are indicative market ranges, not quotes, and rates shift over time. Use real written proposals for actual pricing.

---

## What makes this app cost what it does

This is **not** a simple CRUD app. The cost/risk drivers, in order:

1. **On-device live RTMP streaming with multiple burned-in layers** (scoreboard + logos + text), plus **simultaneous local recording**. Camera → composite → H.264 encode → RTMP + file, frame-accurate. Most Android devs have never touched StreamPack or GL compositing. This is the hard, risky core.
2. **Real-time two-device state sync** (main + QR-paired scoring mirror): local-first rendering, last-write-wins + atomic score increments, a server-offset clock-anchor model, and membership-gated security rules.
3. **Config-driven multi-sport architecture**, including the harder **racket-sport (sets/games/points)** scoring model.

Anyone pricing this like a basic app has misread the streaming requirement.

---

## Phase-by-phase breakdown

Effort is in **developer-hours** (one mid-to-senior Android dev). Complexity and risk are rated Low / Medium / High. Risk = likelihood of overrun or getting stuck.

### Phase 0 — Project setup & scaffold
Repo, Gradle, module structure, base landscape Compose app, role selection (main vs mirror) shell.
- **Effort:** 8–16 hrs · **Complexity:** Low · **Risk:** Low

### Phase 1 — Streaming pipeline (the hard core)
StreamPack integration, camera capture, GL composite of a **static** test overlay burned into frames, H.264 encode, RTMP push to a manually-pasted URL + key, Facebook Live as launch test target, Android 14 foreground-service + permissions so the stream survives backgrounding, **adaptive-bitrate regulation + reconnect-with-backoff (spec §10 resilience)**, on-device verification.
- **Effort:** 60–120 hrs · **Complexity:** High · **Risk:** High
- **Notes:** This is where builds stall. Device-specific camera/encoder quirks, foreground-service correctness, and RTMP reliability all live here. Demand evidence of prior live-streaming work. Prove this phase in isolation before anything else is built.

### Phase 2 — Dynamic overlay + positions + colors + text
Overlay redraws from a live (in-memory) state object; the five selectable positions; team-color tinting; the custom-text banner.
- **Effort:** 35–65 hrs · **Complexity:** Medium · **Risk:** Medium
- **Notes:** Tightly coupled to Phase 1's compositor; same dev, right after. **Validate early** that the library's overlay path sustains ~1 bitmap update/sec at 30 fps output; if it can't, a custom GL overlay layer applies here as a contingency (**+15–30 hrs**).

### Phase 3 — Branding + capture extras
Sponsor/team logo layer (local PNG assets, position/size), zoom controls (CameraX), and local recording in three modes (stream / stream+record / record-only) with a recordings list + playback/share.
- **Effort:** 40–80 hrs · **Complexity:** Medium-High · **Risk:** Medium
- **Notes:** Recording shares the encoder with streaming (StreamPack fan-out) but adds storage, quality/bitrate handling, and a files UI. Still media-pipeline work — ideally the same dev as Phases 1–2.

### Phase 4 — Scoring engine (flat + set-based core incl. volleyball) + main UI + setup flow
JSON sport loading — `flat` model **plus the `setsGames` set-based core, because volleyball (the primary sport) is rally/set-scored, not a running total**. Dynamic scoring UI (increments, steppers, set tracking), atomic score increments, player-name inputs where `usesPlayers`, local-first state driving the overlay. Bundle: basketball, soccer, hockey, generic (`flat`) + **volleyball** (`setsGames`). Also the **portrait setup flow** (spec Appendix B): home screen, matches/history with rematch pre-fill, and the 4-step wizard.
- **Effort:** 75–130 hrs · **Complexity:** Medium-High · **Risk:** Medium
- **Notes:** The config schema is the architectural backbone — get it right early. Volleyball-as-`flat` was an audit-caught misclassification; do not regress it.

### Phase 5 — Firebase sync + QR pairing + mirror device
Move state to Firebase Realtime DB, read/write listeners, last-write-wins writes, QR generation (main) + scan/validation (mirror), session ID + short-lived join token lifecycle, scoring-only mirror UI (scores, names, colors, players, custom text, clock), cross-device verification.
- **Effort:** 50–95 hrs · **Complexity:** Medium-High · **Risk:** Medium
- **Notes:** Two-device testing is where subtle bugs surface. Budget real multi-device QA time here.

### Phase 6 — Clock anchor logic
Anchor model, local display computation (count-up / count-down / none), start/stop/adjust, both devices controlling the clock, no per-second writes.
- **Effort:** 16–30 hrs · **Complexity:** Medium · **Risk:** Medium
- **Notes:** Small but fiddly; edge cases (adjust mid-run, period rollover, drift) eat the time.

### Phase 7 — Remaining racket sports
Badminton, squash, and table-tennis configs on the Phase 4 `setsGames` core; overlay columns + player names. Optional: tennis point-display extension (15/30/40/Ad, tiebreaks) — otherwise tennis stays deferred.
- **Effort:** 15–35 hrs · **Complexity:** Medium · **Risk:** Low-Medium
- **Notes:** Fast-follow after launch; the core already exists from Phase 4.

### Phase 8 — Auto-update, multi-platform picker & polish
version.json check + APK download/install prompt, full platform picker (Facebook + YouTube + Twitch + custom RTMP) with recent-target storage, additional bundled sports, custom-sport creation UI, foreground-service hardening, general polish.
- **Effort:** 40–80 hrs · **Complexity:** Medium · **Risk:** Medium
- **Notes:** The self-managed update mechanism is mandatory for a sideloaded app — without it there's no way to push fixes.

### Phase 9 — Facebook destination picker (Graph API)
Facebook Login (key-hash setup for the self-signed keystore), permission set (`pages_show_list`, `pages_read_engagement`, `pages_manage_posts`, `publish_video`), profile + Pages destination picker, description field, live-video creation returning the RTMPS ingest fed into the existing pipeline, user/Page token lifecycle, error states, manual-paste fallback.
- **Effort:** 30–60 hrs · **Complexity:** Medium-High · **Risk:** Medium-High
- **Notes:** In **Development Mode** the permissions work for the Meta app's admins/developers/testers with **no App Review** — enough for a private, team-operated sideloaded app. Budget separate *calendar* time (not dev hours) for Facebook App Review + privacy policy + data-deletion URL only if outside users will ever log in. Groups streaming via API is likely unavailable (Groups API deprecated ~2024) — verify current Meta docs.

### Cross-cutting (spread across all phases)
- **UI/UX design** (scoreboard visual design, scoring panels, logo/color pickers, recordings UI, racket layouts): 40–75 hrs
- **QA & multi-device testing** (streaming + recording behave differently per device): 40–75 hrs
- **Project management / integration buffer:** ~15–20% of total

---

## Effort summary

| Phase | Low (hrs) | High (hrs) | Complexity | Risk |
|---|---|---|---|---|
| 0 — Setup & scaffold | 8 | 16 | Low | Low |
| 1 — Streaming pipeline | 60 | 120 | High | **High** |
| 2 — Dynamic overlay + positions + colors + text | 35 | 65 | Medium | Medium |
| 3 — Branding + capture (logos, zoom, recording) | 40 | 80 | Med-High | Medium |
| 4 — Scoring engine (flat + set core + volleyball) + UI + setup | 75 | 130 | Med-High | Medium |
| 5 — Firebase + QR + mirror | 50 | 95 | Med-High | Medium |
| 6 — Clock anchor | 16 | 30 | Medium | Medium |
| 7 — Remaining racket sports | 15 | 35 | Medium | Low-Med |
| 8 — Auto-update + multi-platform + polish | 40 | 80 | Medium | Medium |
| 9 — Facebook destination picker (Graph API) | 30 | 60 | Med-High | Med-High |
| Design (cross-cutting) | 40 | 75 | — | — |
| QA / multi-device (cross-cutting) | 50 | 90 | — | — |
| **Subtotal** | **459** | **876** | | |
| PM / integration buffer (~15–20%) | ~69 | ~175 | — | — |
| **Total (all-in)** | **~530** | **~1,050 hrs** | | |

This excludes the deferred v2 work below.

---

## Indicative Philippine pricing

Map the **~530–1,050 hour** range to whatever rate a vendor quotes. Common 2026-era PH reference rates:

| Route | Typical rate | Implied range for this build |
|---|---|---|
| Solo freelancer (vetted, streaming-capable) | ₱500–₱1,500 / hr | ~₱300,000 – ₱800,000 (realistic midpoint) |
| Boutique studio / small team | blended ₱1,500–₱2,500 / hr | ~₱700,000 – ₱2,000,000 |
| Mid-to-large agency | blended ₱2,500–₱4,000 / hr | ~₱1,500,000 – ₱3,800,000+ |

Most "built properly, end-to-end" outcomes for an app at this scope land in the **₱700k–₱2M** band with a small team. A solo specialist can come in lower but carries higher delivery risk on the streaming core.

**Maintenance (separate, ongoing):** budget ~15–20% of build cost per year. A sideloaded app means *you* own the update channel — there's no store handling it.

---

## Scope boundaries

**Included in v1** (priced above): everything in `SCORECAST_SPEC.md` Phases 1–9 — Facebook in-app login + destination picker (with manual-paste fallback), manual key paste for YouTube/Twitch/custom RTMP, setup wizard + match history, two-device scoring, multi-sport config (flat + racket), logos/sponsor overlay, custom text, team colors, player names, local recording, zoom, auto-update.

**Deferred / NOT included (v2):**
- Full-screen break / half-time / intermission graphics (mid-stream full-frame swap).
- Per-sport bespoke scoreboard layouts (v1 = one universal adaptive bar).
- Scoreboard style/theme editor beyond position + colors.
- Tennis point-display extension (15/30/40/Ad, tiebreaks) on top of `setsGames`.
- White-label / per-org rebranding.
- In-app broadcast creation via platform APIs (Facebook Graph API + app review; YouTube Data API v3 + Google OAuth) — separate integration per platform.
- On-device simultaneous multistreaming (use a restream relay via custom RTMP instead).
- Cloud upload / highlight clips of recorded games.
- Multiple mirror devices on one session.

Each is a clean add-on you can price separately later.

---

## A lower-cost route worth considering

You already have (a) a complete, well-structured spec and (b) Claude Code as a build environment. That changes the buy-vs-build math.

Instead of paying an agency for the whole thing, the highest-leverage option may be to **hire a streaming-experienced Android dev for the genuinely hard media core — Phases 1–3 (StreamPack pipeline, overlay/branding compositor, and local recording)** — and drive the rest (scoring engine, Firebase sync, clock, racket logic, polish) yourself through Claude Code.

- Pure streaming + overlay core (Phases 1–2): ~95–185 hrs.
- Full media pipeline incl. logos + recording (Phases 1–3): ~135–265 hrs.

De-risking just that with a specialist could compress a ₱700k+ engagement into a far smaller, targeted one. If you want, the next deliverable can be a **scoped contractor brief for Phases 1–3 only** — the exact post you'd hand that specialist.

---

## What a good proposal should contain

Use this as a checklist when evaluating quotes:
- Per-phase hour estimates (compared against this doc).
- Explicit confirmation they've built **live RTMP streaming with an overlay AND simultaneous recording** before — ask for a demo or repo.
- Familiarity with **Facebook Login + Graph API live-video publishing** (Development vs Live Mode, App Review implications).
- Named device-testing plan (which Android devices, how many).
- Who owns the keystore, Firebase project, and update hosting.
- Fixed-price vs time-and-materials, and what happens on overrun.
- Warranty / bug-fix window after delivery.
- Source code ownership and handover terms.

---

## Reminder

Match SportCam's *capabilities*, not its identity. Do not copy its name, icon, branding, UI, or assets. This product ships as **ScoreCast** — and if that name turns out to be too close to an existing mark in your market, change it before any public release.
