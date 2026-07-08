# ScoreCast — Ops & Pre-Launch Checklist

The operating side the build docs don't cover. Companion to `SCORECAST_SPEC.md`, `SCORECAST_SCOPING.md`, and `SCORECAST_CONTRACTOR_BRIEF.md`.

---

## 1. Keystore custody — DO THIS THE DAY IT'S CREATED
Losing the self-signed keystore (or its password) permanently kills the update path: every installed phone would need manual uninstall/reinstall forever.
- [ ] Generate the release keystore; record alias + both passwords in a password manager.
- [ ] Back up the `.jks/.keystore` file in **two independent locations** (e.g., encrypted cloud + offline drive).
- [ ] Register the keystore's **key hash** in the Meta developer app (Facebook Login breaks silently without it).
- [ ] Document who holds custody (you vs Iozera org) — see §6.

## 2. Audio & copyright strategy
Venue PA music picked up by the mic can get a live stream muted or interrupted by Facebook/YouTube automated systems mid-game.
- [ ] Default mic gain low; know where the in-app mic-off toggle is mid-stream.
- [ ] Ask the organizer to keep PA music off during play where possible.
- [ ] Know the platform appeal flow before you need it; accept that muted segments happen.

## 3. Connectivity plan per venue
Budget roughly **1–1.5 GB/hour at 720p, 2.5+ GB at 1080p** — a 3-game night can eat 8–10 GB.
- [ ] Decide the path: venue WiFi / dedicated data SIM (Smart or Globe — test both at your venues) / pocket WiFi.
- [ ] Speed-test **upload** at each regular venue in advance; you want sustained 4–6 Mbps up for 720p with headroom.
- [ ] Keep a fallback SIM from the other network in the kit bag.
- [ ] Default streams to 720p + adaptive bitrate (spec §10); 1080p only on proven links.

## 4. Event-day discipline
- [ ] **Never update the app on game day.** Freeze versions 48 hours before an event.
- [ ] Keep the last-known-good APK archived and installable.
- [ ] Full **dress rehearsal** before the first real match: 90 minutes, stream + mirror + simultaneous recording, phone on power, at the venue if possible.
- [ ] Pre-game checklist: battery >80% or plugged in, storage free (recordings are large), stream key/destination confirmed, mirror paired, overlay position checked in preview, mic state intentional.
- [ ] Arrive early enough to be live 10 minutes before first serve.

## 5. Filming consent & rights
- [ ] Confirm the league/organizer approves the broadcast — get it in writing (even a chat message).
- [ ] **Youth/school games:** confirm the organizer has parental/media consent covered before streaming minors.
- [ ] Check whether the league claims media/broadcast rights before monetizing or adding sponsors.
- (Practical guidance, not legal advice — the organizer conversation is the key step.)

## 6. Account & asset ownership
Decide **before** creating anything; migrating later is painful.
- [ ] Firebase project: personal vs Iozera org? Set **billing alerts** regardless (RTDB free tier likely suffices at this scale, but alerts are free).
- [ ] Meta developer app: same ownership decision; keep it in **Development Mode**; maintain the tester list of operator Facebook accounts.
- [ ] APK hosting + `version.json`: HTTPS host under the same ownership; document the URL.
- [ ] Git repository ownership (especially if a contractor is engaged).
- [ ] Operator Facebook accounts: 2FA on, correct Page roles for the fanpages you stream to.

## 7. Hardware kit
- [ ] Minimum device floor: mid-range 2020+ with hardware H.264 encode; the **main phone is the one you dress-rehearse on** (thermal behavior varies wildly per model).
- [ ] Kit bag: main phone + mirror phone, tripod + mount, 2 power banks (or wall power + long cable), cables, backup SIM, lens cloth.
- [ ] Charge policy: main phone plugged in for any stream over ~45 minutes.

## 8. Name & identity (before any public/commercial release)
- [ ] Search "ScoreCast" against Google Play, App Store, IPOPHL trademarks, and PH business registries — it's a common-sounding name; collisions likely.
- [ ] Keep ScoreCast branding visually distinct from SportCam (no yellow/black scheme, different icon language).
- [ ] If it stays team-internal, this can wait; if it ships to clients or leagues, do it first.

## 9. When something breaks mid-stream (pre-decided, not improvised)
- [ ] Stream dies, won't reconnect → restart broadcast with the fallback: **manual key paste** path (spec §10) or the backup phone.
- [ ] App crashes → recording mode means footage up to crash is saved; relaunch, rejoin session, resume.
- [ ] Facebook API/login fails → manual key from Live Producer (keep the persistent key enabled on your pages).
- [ ] Export the stream log + Crashlytics report after any incident (spec §3) — that's the bug report.

---

**This week:** items 1, 3, and 4. **Before first public match:** 2, 5, 7. **Before any release beyond the team:** 6, 8.
