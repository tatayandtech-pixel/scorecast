---
name: ScoreCast
description: A flat, dark-first live-scoring system spanning the Android broadcast app and its web remote-scoring mirror.
colors:
  bg-void: "#111318"
  surface-card: "#1c1f26"
  text-primary: "#f2f2f2"
  text-muted: "#9aa0ab"
  border-subtle: "#3a3f4a"
  pill-surface: "#262a33"
  signal-lavender: "#b39ddb"
  signal-lavender-ink: "#1a1a1a"
  status-ok: "#66bb6a"
  status-pending: "#ffb74d"
  status-danger: "#e57373"
  bg-void-light: "#f4f4f6"
  surface-card-light: "#ffffff"
  text-primary-light: "#1a1a1a"
  text-muted-light: "#545b68"
  border-subtle-light: "#d0d3d9"
  pill-surface-light: "#eceef2"
  signal-lavender-light: "#6c4fa8"
  signal-lavender-ink-light: "#ffffff"
  status-ok-light: "#256029"
  status-pending-light: "#8f5300"
  status-danger-light: "#c62828"
typography:
  display:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: "2.5rem"
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: "normal"
    fontFeature: "tabular-nums"
  headline:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: "1.4rem"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "normal"
  title:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: "1.3rem"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "normal"
    fontFeature: "tabular-nums"
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: "0.9rem"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "normal"
  label:
    fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    fontSize: "0.85rem"
    fontWeight: 400
    lineHeight: 1.3
    letterSpacing: "normal"
rounded:
  sm: "10px"
  md: "12px"
  pill: "999px"
spacing:
  xs: "6px"
  sm: "8px"
  sm-md: "10px"
  md: "12px"
  md-lg: "14px"
  lg: "16px"
  xl: "20px"
components:
  button-primary:
    backgroundColor: "{colors.signal-lavender}"
    textColor: "{colors.signal-lavender-ink}"
    rounded: "{rounded.sm}"
    padding: "14px"
  button-primary-disabled:
    backgroundColor: "{colors.signal-lavender}"
    textColor: "{colors.signal-lavender-ink}"
    rounded: "{rounded.sm}"
    padding: "14px"
  pill-btn:
    backgroundColor: "{colors.pill-surface}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.pill}"
    padding: "14px 20px"
  pill-btn-active:
    backgroundColor: "{colors.signal-lavender}"
    textColor: "{colors.signal-lavender-ink}"
    rounded: "{rounded.pill}"
    padding: "14px 20px"
  link-btn:
    backgroundColor: "transparent"
    textColor: "{colors.signal-lavender}"
    rounded: "0"
    padding: "0"
  input-text:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.sm}"
    padding: "14px"
  card:
    backgroundColor: "{colors.surface-card}"
    textColor: "{colors.text-primary}"
    rounded: "{rounded.md}"
    padding: "16px"
---

# Design System: ScoreCast

## 1. Overview

**Creative North Star: "The Sideline Console"**

This is a control surface, not a page or an app in the marketing sense. Whether it's the operator glancing at a phone mounted courtside while also framing the camera shot, or a remote scorer half-attention on a live match at a laptop, the job is the same: glance, register a number, tap, and move on — no scrolling to discover a feature, no decorative pass that competes with the score itself. The dark-void background and flat card layering exist so the screen doesn't glare, and so the two things that actually matter — the score and the clock — are the highest-contrast elements on it by construction, not by accident. One brand, two platforms: the web mirror expresses it in CSS custom properties; the Android app expresses the identical hex values through a Material3 `ColorScheme` (`com.scorecast.app.theme.ScoreCastTheme`) — see the Cross-Platform Mapping under Colors.

It explicitly rejects dashboard styling: no card grids beyond what a screen's actual content needs, no gradients, no hero-metric treatment, no navigation chrome beyond what each app's own established patterns (Android's wizard steps, top bar) already require. Every element on screen is either the current game state or a control to change it. Nothing is here to look designed; it's here to be trusted at a glance during a live broadcast.

**Key Characteristics:**
- Two-tone flat layering (void background, one card/surface tone) — no shadows anywhere, on either platform
- One accent color, used only for the primary action and the "this is now selected" state
- System font stack on both platforms — the web mirror's `-apple-system/Segoe UI/Roboto` stack and the Android app's default Material typography (backed by the OS's own Roboto) are the same principle applied per-platform, not a shared font file
- Full light/dark parity via `prefers-color-scheme` (web) / `isSystemInDarkTheme()` (Android), not a manual toggle on either platform

## 2. Colors

A near-monochrome dark console with a single soft accent and three status colors that carry real meaning. Every semantic color redefines itself for the light-mode override — none of them is dark-mode-only.

### Primary
- **Muted Signal Lavender** (#b39ddb dark / #6c4fa8 light): The Join button and the active/tapped state of a score pill. Deliberately soft and non-alarming in dark mode — it means "this is the thing you act on," never "something needs attention." Also used for the "Leave session" text link. The light-mode value is a deeper, more saturated purple, not a lightness-inverted copy — see the Per-Theme Contrast Rule below.

### Status
- **Connected** (#66bb6a dark / #256029 light): Paired with "● Connected" text on the connection dot — color reinforces, never carries the meaning alone.
- **Reconnecting / Connecting** (#ffb74d dark / #8f5300 light): Same dot, paired with "● Reconnecting…" / "● Connecting…" text.
- **Error** (#e57373 dark / #c62828 light): Inline error copy under the pairing-code field and the score screen.

### Neutral
- **Void** (#111318 dark / #f4f4f6 light): Page background.
- **Card Surface** (#1c1f26 dark / #ffffff light): The one layering tone above Void — team cards, the clock row, the sets row, text inputs.
- **Primary Text** (#f2f2f2 dark / #1a1a1a light): Scores, team names, the page title.
- **Muted Text** (#9aa0ab dark / #545b68 light): Labels, hints, the sport name, secondary copy.
- **Subtle Border** (#3a3f4a dark / #d0d3d9 light): Input and pill-button borders — visible enough to define a tap target, not enough to compete with content.
- **Pill Surface** (#262a33 dark / #eceef2 light): The score-pill background specifically — one step off Card Surface, not a reuse of it (the two only happened to look similar before this was tokenized).

### Named Rules
**The One Accent Rule.** Signal Lavender appears in exactly two places: the primary Join action, and confirming which pill was just tapped. It never appears as decoration, and it never carries status meaning — that's the job of the status colors above.

**The Per-Theme Contrast Rule.** Dark mode and light mode are not palette-swaps of the same five hues; each semantic color (accent, ok, pending, danger, muted) is independently verified at ≥4.5:1 against its own theme's background, and darkened/re-saturated for light mode where the dark-mode value would fail (amber in particular needs to go notably darker on a light surface than intuition suggests, since amber's native luminance is high). Never add or edit a color role without checking both themes.

**The Accent-Ink Rule.** Signal Lavender is used as a *fill* on the Join button and the active pill (not just as text), so its paired ink color (`--accent-ink`) flips with it: dark ink (#1a1a1a) on the light lavender fill in dark mode, white ink (#ffffff) on the deeper purple fill in light mode. Any new component that fills with `--accent` must pair it with `--accent-ink`, never a hardcoded ink color.

### Cross-Platform Mapping

The Android app (`app/src/main/java/com/scorecast/app/theme/Theme.kt`) defines the identical hex values above as a Material3 `ColorScheme`, switching on `isSystemInDarkTheme()` the same way the web mirror switches on `prefers-color-scheme`:

| Web token | Material3 role | Where it lands automatically |
|---|---|---|
| `--accent` (Signal Lavender) | `primary` | Every default `Button` (wizard "Next", "+ Stream new match"), `ScoreCastColors` status dots |
| `--accent-ink` | `onPrimary` | Text on those buttons |
| `--bg` (Void) | `background` | Scaffold backgrounds, the wizard's scoreboard-preview backdrop |
| `--text` (Primary Text) | `onBackground` / `onSurface` | Default `Text` color |
| `--card` (Card Surface) | `surface` | `Card` backgrounds (Home cards, match cards) |
| `--pill-bg` (Pill Surface) | `surfaceVariant` | `LogoPanel`'s logo-row background tint |
| `--muted` (Muted Text) | `onSurfaceVariant` | Every secondary/hint `Text` across every screen — already the most-used role in the app before this pass |
| `--border` (Subtle Border) | `outline` | Every `OutlinedButton`/`OutlinedTextField` border app-wide (`SmallButton`, wizard Back button, team-name field) |
| `--danger` (Error) | `error` | The "End match" button, `MirrorScanScreen`'s error text, `MirrorScoringScreen`'s Failed state |
| `--ok` / `--pending` | `ScoreCastColors.ok` / `.pending` (`.okLight` / `.pendingLight`) | Connection dots in `MainActivity` and `MirrorScoringScreen` — Material3 has no built-in success/warning role, so these live as explicit constants rather than a ColorScheme override |

**The pure-red exception.** The Android app's "● LIVE" and "● Recording" tally-light chips (`MainActivity.kt`) intentionally stay `Color.Red`/`Color.Yellow`, not `error`/`--danger` — a live/recording indicator is a broadcast convention, not an error state, and conflating the two would misrepresent a normal, expected condition as something wrong.

### Named Rules (cross-platform)

**The One Brand, Two Renderers Rule.** A color value lives in exactly one place conceptually (this section) and two places physically: the CSS custom property in `web-mirror/style.css` and the Kotlin `Color(...)` constant in `Theme.kt`. Changing one without the other is drift, not a platform-specific decision — treat any edit to a brand color as a two-file change unless the divergence is deliberate and documented (like the pure-red exception above).

## 3. Typography

**Web:** -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif (system stack — no webfont)
**Android:** Material3's default `Typography` (Roboto via the OS) — no custom `FontFamily` set anywhere in the app.

**Character:** One typeface per platform, weight and size doing all the work. Nothing here is stylized; legibility at a glance under time pressure is the entire brief. The two platforms don't share a literal font file — a shared webfont would defeat the "instant, no loading" principle on web and add nothing on Android, where the OS default already satisfies the same "system font, no custom load" rule.

### Hierarchy
- **Display** (700, 2.5rem, 1.1 line-height, tabular-nums): The score itself (`.score`) — the single most-glanced-at element on the page.
- **Headline** (700, 1.4rem, 1.2 line-height): The page title ("ScoreCast Remote Scorer").
- **Title** (700, 1.3rem, tabular-nums): The live clock (`.clock-time`) — tabular figures so digits don't shift width as seconds tick.
- **Body** (400, 0.9rem, 1.4 line-height, muted color): Hints and instructional copy (join-screen hint text, the sets-readout row).
- **Label** (400, 0.85rem, muted color): Field labels, the subtitle under the page title, the sport name, connection status text.

### Named Rules
**The Tabular Clock Rule.** Any element showing a live-updating number that must not visually jitter gets `font-variant-numeric: tabular-nums` — applied to the clock, the score, and the period/set-number readout (`.period-value`).

## 4. Elevation

Completely flat by intent on both platforms. Depth is conveyed by exactly one background-tone step — Void behind, Card/Surface in front — never by shadow. This is deliberate: shadows read as decoration on a screen whose entire job is to disappear behind the numbers it shows, and flat tonal layering costs nothing to render on an older phone browser mid-broadcast.

**Known platform divergence.** Material3's default `Card` composable applies its own subtle default `tonalElevation`/`shadowElevation` unless explicitly overridden — this harmonization pass did not touch that (it was scoped to color, not elevation overrides). The visual effect is minor at Material3's default values, but it means Android's `Card` usages (`HomeScreen`, `MatchesScreen`) are not *strictly* flat the way the web mirror is. Flag this if a future pass wants full Flat-Only compliance on Android — it would mean passing `CardDefaults.cardElevation(0.dp, 0.dp)` at each `Card` call site.

### Named Rules
**The Flat-Only Rule.** No `box-shadow` (web) / no non-zero `Card` elevation (Android, aspirational — see the divergence noted above) anywhere in this system. If a new component needs to visually separate from its background, give it the Card/Surface tone, not a shadow.

## 5. Components

### Buttons
- **Shape:** 10px radius for the primary action button; fully pill (999px) for score-increment/decrement buttons. *(Android's `Button`/`OutlinedButton` use Material3's default shape rather than matching these exact radii — a cosmetic platform difference, not a brand-color one, and out of scope for this color-harmonization pass.)*
- **Primary (`#join-btn`; Android: default `Button`, e.g. wizard "Next", "+ Stream new match"):** Signal Lavender background, `--accent-ink` text (dark #1a1a1a in dark mode, white in light mode — see the Accent-Ink Rule), 600 weight, full-width, 14px padding. Disabled state drops to 0.6 opacity — no color change, since disabled here means "request in flight," not "unavailable." Hover (pointer devices only, via `@media (hover: hover)`): `filter: brightness(1.1)`, skipped while disabled. Android has no hover concept (touch-first); this is web-only.
- **Pill (score increments, `.pill-btn`; Android: `SmallButton` in `ScoringPanel.kt`, via `OutlinedButton`):** Pill Surface background, Subtle Border, 64px min-width × 56px min-height, 1.15rem/600 weight (sized up from the original 44px WCAG-minimum touch target after live on-device use — the score buttons are the single most-tapped control on the page, and the extra size trades a little density for faster, more confident tapping; flex-centered so the number stays centered regardless of padding). On tap (`:active`), inverts to Signal Lavender background with `--accent-ink` text — the primary way a tap confirms itself. Hover (pointer devices only): border brightens to Signal Lavender, previewing the tap without committing to it.
- **Link (`.link-btn`):** No background or border, Signal Lavender text, 0.85rem — used once, for "Leave session." Hover: underline.
- **Focus (all of the above, plus the text input):** a shared `outline: 2px solid` Signal Lavender, 2px offset, on `:focus-visible` — the primary user context (someone seated at a laptop/desk) is keyboard/mouse-first, so a deliberate focus ring matters here more than it would on a touch-only surface. Android's touch-first primary interaction doesn't need an equivalent; Compose's default focus handling (keyboard/D-pad navigation) is left as-is.

### Cards / Containers
- **Corner Style:** 12px radius (team cards, clock row, sets row). Android's `Card` uses Material3's default shape.
- **Background:** Card Surface tone only (web `--card`; Android `MaterialTheme.colorScheme.surface`).
- **Shadow Strategy:** None on web — see Elevation. Android carries a known minor divergence here; see Elevation.
- **Border:** None on cards (borders are reserved for interactive elements: inputs and pills, both platforms).
- **Internal Padding:** 16px for team cards and the sets row; 10px 16px for the clock row (web-specific values; Android cards use their own per-screen padding, not yet unified to this exact scale).
- **Sets row (`.sets-row`):** for setsGames sports (volleyball, badminton, squash, table tennis) only. Contains a period/set-number readout (`.period-readout`/`.period-value`, e.g. "Set 2 of 5") above the sets-won readout — both fully live and editable via the same pill buttons as flat-scoring sports, not a read-only display. The scoring rule itself (deciding-set target, win-by-two, point-cap override) is ported from `GameStateHolder.addSetsGamesScore` in `app.js`'s `bumpSetsGamesScore`, and every write is diffed against the known score and sent as `increment()`, never an absolute overwrite, so a simultaneous tap from the main device or another mirror can't be silently dropped.

### Inputs / Fields
- **Style:** Card Surface background, Subtle Border stroke, 10px radius, 14px padding, 1rem font size — sized for a confident single-handed tap, not a dense form. Android's `OutlinedTextField` (team-name field, clock-edit dialog) inherits Subtle Border automatically via `colorScheme.outline`.
- **Focus:** Signal Lavender `:focus-visible` outline (2px, 2px offset) — see the shared focus rule under Buttons. Web-only, per the note above.
- **Error:** A dedicated `.error` line (`role="alert"`) in status-danger red beneath the field, not a border-color change on the input itself. Android's error text (`MirrorScanScreen`) uses `MaterialTheme.colorScheme.error` directly, same color, same principle.
- **Disabled:** Not used on inputs in this system; only buttons have a disabled state.

### Status Indicator (Signature Component)
The connection dot (`#connection-dot` on web; `ConnectionDot` in `MirrorScoringScreen.kt` and the top-bar chips in `MainActivity.kt` on Android) is the one place state must be unambiguous under a bad connection. It pairs a colored bullet with a text label on the same element — "● Connected" (status-ok green), "● Reconnecting…" / "● Connecting…" (status-pending amber) — so meaning never depends on color perception alone. Any future status indicator in this system, on either platform, must follow the same color-plus-text pairing. The one deliberate exception is the Android "● LIVE"/"● Recording" tally-light chips, which stay pure red/yellow per the broadcast-convention note under Colors.

## 6. Do's and Don'ts

### Do:
- **Do** keep every status indicator paired with a text label alongside its color, exactly as the connection dot already does — never ship a color-only status chip.
- **Do** use Signal Lavender only for the primary action and the "just tapped" confirmation state; if a third use case appears, that's a sign it's drifting into decoration.
- **Do** give every tappable control immediate, visible feedback (the `:active` pill inversion is the model) — an unconfirmed tap is the single worst failure mode for this surface.
- **Do** give every interactive element a `:hover` state gated behind `@media (hover: hover)` — the confirmed primary user (someone seated at a laptop/desk) is mouse-first, not touch-first, so hover feedback isn't optional polish here the way it might be on a phone-only surface. Gating behind the media query keeps touch devices from getting a stuck hover state.
- **Do** give every focusable element a visible `:focus-visible` ring (Signal Lavender, 2px, 2px offset) — the same laptop/desk context implies keyboard and precise-pointer use, where focus indication matters more than on a touch-only surface.
- **Do** keep the system font stack. No webfont loading on a page that must render instantly on a possibly-poor connection mid-broadcast.
- **Do** apply `tabular-nums` to any new live-updating numeric display, matching the clock.
- **Do** verify every semantic color independently against both the dark and light background before shipping it, per the Per-Theme Contrast Rule — never assume a value that passes in one theme passes in the other.
- **Do** pair any `--accent` fill with `--accent-ink`, never a hardcoded ink color, per the Accent-Ink Rule.
- **Do** reference `MaterialTheme.colorScheme.*` or `ScoreCastColors.*` for any Android brand/status color — never a bare `Color(0x...)` literal — per the One Brand, Two Renderers Rule. Most components already do this by default (buttons, borders, muted text) simply by using standard Material3 components.
- **Do** update both `web-mirror/style.css` and `Theme.kt` together when a brand color changes; treat a one-file edit to a shared color as a bug, not a platform-specific tweak.

### Don't:
- **Don't** add card-grid dashboard scaffolding, gradients, hero-metric layouts, or navigation chrome — this is a single-task utility screen, not a product dashboard.
- **Don't** add `box-shadow` anywhere; depth is tonal (Void vs. Card Surface) only, per the Flat-Only Rule.
- **Don't** rely on color alone to convey state (connection status, score deltas, errors) — always pair with text or shape, per the color-blind-safe requirement in PRODUCT.md.
- **Don't** rebuild a DOM element in direct response to its own click on the same event tick (the exact cause of the iOS Safari runaway score-write loop fixed in this codebase) — rebuild score buttons only when the sport itself changes, never on every score render.
- **Don't** introduce a second accent color; the One Accent Rule holds until a real second use case is confirmed with the owner.
- **Don't** assume a dark-mode color value works unchanged in light mode — the light-mode override (2026-07-17 fix) darkened accent/ok/pending/danger substantially from their dark-mode values to hit 4.5:1; a lightness-inverted copy of the dark palette would have failed.
- **Don't** write a color as a literal hex value in a CSS rule — every color in this system, including one-offs like Pill Surface, is a `--token` with both a dark and light definition. A literal that skips the token is exactly what let the pending-amber and Pill Surface colors go unnoticed by the light-mode override until this pass caught them.
- **Don't** conflate the Android LIVE/Recording tally-light red with the brand's status-danger red — they're deliberately different colors for a deliberately different reason (broadcast convention vs. error state). Don't "fix" this into one red.
- **Don't** touch `ScoreboardOverlay.kt`'s team-color strips or white score/name text as part of brand harmonization — those are driven by user-picked team colors and video-legibility requirements respectively, not the app's own chrome.
