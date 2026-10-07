import { initializeApp } from "https://www.gstatic.com/firebasejs/12.15.0/firebase-app.js";
import {
  getAuth, signInAnonymously, onAuthStateChanged,
} from "https://www.gstatic.com/firebasejs/12.15.0/firebase-auth.js";
import {
  getDatabase, ref, get, set, update, remove, onValue, onDisconnect, increment, serverTimestamp,
} from "https://www.gstatic.com/firebasejs/12.15.0/firebase-database.js";
import { firebaseConfig } from "./firebase-config.js";
import { SPORTS, DEFAULT_SPORT } from "./sports.js";

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getDatabase(app);

const el = (id) => document.getElementById(id);

// Mirrors Android's PRESET_COLORS in ScoringPanel.kt exactly, so a color picked here or on the
// main device reads as the same swatch on both surfaces.
const TEAM_COLORS = [
  "#1E40AF", "#B91C1C", "#15803D", "#7C3AED",
  "#0F766E", "#B45309", "#374151", "#6B7280",
];

// Registering any touchstart listener is what makes WebKit/iOS Safari honor :active on tap.
document.addEventListener("touchstart", () => {}, { passive: true });

const STORAGE_KEY = "scorecast-pairing-code";

let currentSessionId = null;
let currentUid = null;
let sessionRef = null;
let unsubscribeSession = null;
let unsubscribeConnected = null;
let unsubscribeOffset = null;
let latestSessionData = null;
let rejoinInFlight = false;

// Clock skew correction (spec §5): Firebase resolves this path server-side, so "now" for the
// clock-anchor math is Date.now() + serverOffsetMs rather than raw local time, matching the
// Android app's ServerTimeSync. Read-only here — the web mirror has no clock controls.
let serverOffsetMs = 0;
let clockTickHandle = null;
let shotClockTickHandle = null;

function showError(message) {
  const box = el("join-error");
  box.textContent = message;
  box.hidden = !message;
}

function showScoreError(message) {
  const box = el("score-error");
  box.textContent = message;
  box.hidden = !message;
}

function parsePairingCode(raw) {
  const trimmed = raw.trim();
  const sepIndex = trimmed.indexOf(":");
  if (sepIndex < 0) return null;
  const sessionId = trimmed.slice(0, sepIndex).trim();
  const joinToken = trimmed.slice(sepIndex + 1).trim();
  if (!sessionId || !joinToken) return null;
  return { sessionId, joinToken };
}

function ensureSignedIn() {
  return new Promise((resolve, reject) => {
    const unsub = onAuthStateChanged(auth, (user) => {
      unsub();
      if (user) {
        resolve(user.uid);
        return;
      }
      signInAnonymously(auth)
        .then((cred) => resolve(cred.user.uid))
        .catch(reject);
    });
  });
}

async function joinSession(sessionId, joinToken) {
  const uid = await ensureSignedIn();
  const tokenSnap = await get(ref(db, `sessions/${sessionId}/joinToken`));
  if (!tokenSnap.exists() || tokenSnap.val() !== joinToken) {
    throw new Error("Pairing code not recognized or expired.");
  }
  const memberRef = ref(db, `sessions/${sessionId}/members/${uid}`);
  await set(memberRef, joinToken);
  onDisconnect(memberRef).remove().catch(() => {
    // Best-effort — if this registration fails, an explicit "Leave session" or a future
    // successful onDisconnect registration on the next reconnect still cleans it up.
  });

  currentUid = uid;
  currentSessionId = sessionId;
  sessionRef = ref(db, `sessions/${sessionId}`);
  sessionStorage.setItem(STORAGE_KEY, `${sessionId}:${joinToken}`);
  attachListeners();
}

function attachListeners() {
  unsubscribeSession = onValue(
    sessionRef,
    (snapshot) => renderSession(snapshot.val()),
    () => handleSessionLost(),
  );
  const connectedRef = ref(db, ".info/connected");
  unsubscribeConnected = onValue(connectedRef, (snap) => {
    setConnectionState(snap.val() === true ? "connected" : "reconnecting");
  });
  const offsetRef = ref(db, ".info/serverTimeOffset");
  unsubscribeOffset = onValue(offsetRef, (snap) => {
    serverOffsetMs = snap.val() || 0;
  });
}

function detachListeners() {
  if (unsubscribeSession) unsubscribeSession();
  if (unsubscribeConnected) unsubscribeConnected();
  if (unsubscribeOffset) unsubscribeOffset();
  unsubscribeSession = null;
  unsubscribeConnected = null;
  unsubscribeOffset = null;
  stopClockTicking();
  // Was missing: leaving a basketball session with the shot clock running left its 500ms interval
  // firing for the life of the tab.
  stopShotClockTicking();
}

// Fires when the session listener is denied — most commonly because a dropped connection
// (screen lock, backgrounded tab) let the onDisconnect-queued membership removal fire, and the
// rules now deny our stale read. Try one silent rejoin with the same stored code before giving
// up and sending the operator back to the join screen.
async function handleSessionLost() {
  if (rejoinInFlight) return;
  rejoinInFlight = true;
  detachListeners();
  const stored = sessionStorage.getItem(STORAGE_KEY);
  const parsed = stored ? parsePairingCode(stored) : null;
  if (parsed) {
    try {
      await joinSession(parsed.sessionId, parsed.joinToken);
      rejoinInFlight = false;
      return;
    } catch (err) {
      // Fall through to returning to the join screen below.
    }
  }
  rejoinInFlight = false;
  sessionStorage.removeItem(STORAGE_KEY);
  currentSessionId = null;
  currentUid = null;
  sessionRef = null;
  latestSessionData = null;
  el("join-screen").hidden = false;
  el("score-screen").hidden = true;
  showError("Session ended or the connection was lost. Enter the pairing code again to rejoin.");
}

function setConnectionState(state) {
  const dot = el("connection-dot");
  dot.dataset.state = state;
  dot.textContent =
    state === "connected" ? "● Connected"
    : state === "reconnecting" ? "● Reconnecting…"
    : "● Connecting…";
}

let lastRenderedSportKey = null;
let colorSwatchesBuilt = false;
let homeNameFocused = false;
let awayNameFocused = false;

function renderSession(data) {
  if (!data) {
    // The session node itself was deleted (main device ended the match) rather than just our
    // membership — same recovery path as a lost/denied listener.
    handleSessionLost();
    return;
  }
  latestSessionData = data;
  const sport = Object.prototype.hasOwnProperty.call(SPORTS, data.sport)
    ? SPORTS[data.sport]
    : DEFAULT_SPORT;
  const isSetsGames = sport.scoringModel === "setsGames";

  el("sport-name").textContent = sport.displayName;
  // Only overwrite the field when the operator isn't actively editing it — same on-blur-commit
  // pattern as Android's ScoringPanel.kt TeamColumn, to avoid our own write echoing back mid-typing
  // and clobbering characters typed after that keystroke (see the "stuck at AW" bug it fixed there).
  if (!homeNameFocused) el("home-name").value = (data.teamNames && data.teamNames.home) ?? "HOME";
  if (!awayNameFocused) el("away-name").value = (data.teamNames && data.teamNames.away) ?? "AWAY";
  if (!colorSwatchesBuilt) {
    colorSwatchesBuilt = true;
    buildColorSwatches("home");
    buildColorSwatches("away");
  }
  updateColorSelection("home", (data.teamColors && data.teamColors.home) ?? "#1E40AF");
  updateColorSelection("away", (data.teamColors && data.teamColors.away) ?? "#B91C1C");
  el("home-score").textContent = data.homeScore ?? 0;
  el("away-score").textContent = data.awayScore ?? 0;

  el("sets-row").hidden = !isSetsGames;
  if (isSetsGames) {
    el("home-sets").textContent = (data.setsWon && data.setsWon.home) ?? 0;
    el("away-sets").textContent = (data.setsWon && data.setsWon.away) ?? 0;
  }

  // Period/quarter row — shown for every sport now (all entries in sports.js carry periods +
  // periodLabel), same stepper behavior as Android's ScoringPanel.kt Period row: clamped to
  // [1, sport.periods].
  const period = data.period ?? 1;
  el("period-row").hidden = !sport.periods;
  el("period-label").textContent = sport.periodLabel ?? "Period";
  el("period-value").textContent = `${period} / ${sport.periods ?? "?"}`;
  el("period-minus").disabled = period <= 1;
  el("period-plus").disabled = sport.periods != null && period >= sport.periods;

  el("home-minus").disabled = (data.homeScore ?? 0) <= 0;
  el("away-minus").disabled = (data.awayScore ?? 0) <= 0;

  // Clock (spec §5 anchor model), hidden for clockDirection "none" sports (e.g. volleyball) the
  // same way the Android panel hides its clock row. Now editable — same Start/Stop/±30 controls
  // as ScoringPanel.kt, writing through the same anchor fields (startedAt/baseRemaining) Android
  // does, so a mirror-driven clock edit is indistinguishable from a main-device one.
  const clockDir = data.clockDirection || "down";
  el("clock-row").hidden = clockDir === "none";
  el("clock-toggle").textContent = data.clockRunning ? "Stop" : "Start";
  updateClockDisplay();
  manageClockTicking(data.clockRunning === true);

  // Shot clock — basketball only (sport.shotClockSeconds), same anchor model, always counts down.
  el("shot-clock-row").hidden = !sport.shotClockSeconds;
  el("shot-clock-toggle").textContent = data.shotClockRunning ? "Stop" : "Start";
  updateShotClockDisplay();
  manageShotClockTicking(data.shotClockRunning === true);

  // Rebuild the +N buttons only when the sport (and therefore its increments) actually
  // changes — NOT on every score update. Every write we make immediately echoes back through
  // our own onValue listener, so rebuilding on every render meant the tapped button's DOM node
  // was destroyed and replaced mid-tap. On iOS Safari this can re-target the in-flight click at
  // whatever new element ends up under the same screen coordinates, firing bumpScore again,
  // which writes again, which rebuilds again — a self-sustaining loop with no further taps
  // needed (this was the "score climbs on its own, can't stop it" bug).
  if (data.sport !== lastRenderedSportKey) {
    lastRenderedSportKey = data.sport;
    renderScoreButtons(sport);
  }
}

function clockDisplaySeconds(data, nowMs) {
  const dir = data.clockDirection || "down";
  const base = data.baseRemaining ?? 0;
  if (dir === "none") return 0;
  if (!data.clockRunning) return base;
  const elapsed = (nowMs - (data.startedAt ?? 0)) / 1000;
  return dir === "up" ? base + elapsed : Math.max(0, base - elapsed);
}

function formatClock(totalSeconds) {
  const whole = Math.floor(totalSeconds);
  const m = Math.floor(whole / 60);
  const s = whole % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

function updateClockDisplay() {
  if (!latestSessionData) return;
  const seconds = clockDisplaySeconds(latestSessionData, Date.now() + serverOffsetMs);
  el("clock-time").textContent = formatClock(seconds);
}

function manageClockTicking(running) {
  if (running) {
    if (clockTickHandle) return;
    clockTickHandle = setInterval(updateClockDisplay, 500);
  } else {
    stopClockTicking();
  }
}

function stopClockTicking() {
  if (clockTickHandle) {
    clearInterval(clockTickHandle);
    clockTickHandle = null;
  }
}

// Mirrors GameStateHolder.startClock/stopClock/adjustClock exactly: writes the same anchor
// fields (clockRunning/startedAt/baseRemaining) a main device would, so a clock edit made here
// is indistinguishable from one made on the Android app.
function startClock() {
  if (!latestSessionData || latestSessionData.clockRunning) return;
  pushUpdate({ clockRunning: true, startedAt: serverTimestamp() });
}

function stopClock() {
  if (!latestSessionData || !latestSessionData.clockRunning) return;
  const remaining = clockDisplaySeconds(latestSessionData, Date.now() + serverOffsetMs);
  pushUpdate({ clockRunning: false, baseRemaining: remaining, startedAt: 0 });
}

function adjustClock(deltaSeconds) {
  if (!latestSessionData) return;
  const current = clockDisplaySeconds(latestSessionData, Date.now() + serverOffsetMs);
  const updates = { baseRemaining: Math.max(0, current + deltaSeconds) };
  if (latestSessionData.clockRunning) updates.startedAt = serverTimestamp();
  pushUpdate(updates);
}

function shotClockDisplaySeconds(data, nowMs) {
  const base = data.shotClockBaseRemaining ?? 24;
  if (!data.shotClockRunning) return base;
  const elapsed = (nowMs - (data.shotClockStartedAt ?? 0)) / 1000;
  return Math.max(0, base - elapsed);
}

function updateShotClockDisplay() {
  if (!latestSessionData) return;
  const seconds = shotClockDisplaySeconds(latestSessionData, Date.now() + serverOffsetMs);
  el("shot-clock-time").textContent = formatClock(seconds);
}

function manageShotClockTicking(running) {
  if (running) {
    if (shotClockTickHandle) return;
    shotClockTickHandle = setInterval(updateShotClockDisplay, 500);
  } else {
    stopShotClockTicking();
  }
}

function stopShotClockTicking() {
  if (shotClockTickHandle) {
    clearInterval(shotClockTickHandle);
    shotClockTickHandle = null;
  }
}

function startShotClock() {
  if (!latestSessionData || latestSessionData.shotClockRunning) return;
  pushUpdate({ shotClockRunning: true, shotClockStartedAt: serverTimestamp() });
}

function stopShotClock() {
  if (!latestSessionData || !latestSessionData.shotClockRunning) return;
  const remaining = shotClockDisplaySeconds(latestSessionData, Date.now() + serverOffsetMs);
  pushUpdate({ shotClockRunning: false, shotClockBaseRemaining: remaining, shotClockStartedAt: 0 });
}

function adjustShotClock(deltaSeconds) {
  if (!latestSessionData) return;
  const current = shotClockDisplaySeconds(latestSessionData, Date.now() + serverOffsetMs);
  const updates = { shotClockBaseRemaining: Math.max(0, current + deltaSeconds) };
  if (latestSessionData.shotClockRunning) updates.shotClockStartedAt = serverTimestamp();
  pushUpdate(updates);
}

function resetShotClock() {
  if (!latestSessionData) return;
  const sport = Object.prototype.hasOwnProperty.call(SPORTS, latestSessionData.sport)
    ? SPORTS[latestSessionData.sport]
    : DEFAULT_SPORT;
  pushUpdate({
    shotClockRunning: false,
    shotClockBaseRemaining: sport.shotClockSeconds ?? 24,
    shotClockStartedAt: 0,
  });
}

// Mirrors ScoringPanel.kt's Period stepper: clamped to [1, sport.periods], same as Android.
function adjustPeriod(delta) {
  if (!latestSessionData) return;
  const sport = Object.prototype.hasOwnProperty.call(SPORTS, latestSessionData.sport)
    ? SPORTS[latestSessionData.sport]
    : DEFAULT_SPORT;
  const current = latestSessionData.period ?? 1;
  if (delta < 0) {
    const next = Math.max(1, current - 1);
    if (next !== current) pushUpdate({ period: next });
  } else if (sport.periods == null || current < sport.periods) {
    pushUpdate({ period: current + 1 });
  }
}

// Built once and never rebuilt afterward — the same "don't destructively rebuild a DOM element
// in direct response to its own click" lesson as renderScoreButtons applies here too, even though
// colors don't change per-sport. Only the data-selected attribute updates on each render.
function buildColorSwatches(side) {
  const row = el(`${side}-colors`);
  for (const hex of TEAM_COLORS) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "color-swatch";
    btn.style.backgroundColor = hex;
    btn.dataset.hex = hex;
    btn.setAttribute("aria-label", `${side === "home" ? "Home" : "Away"} color ${hex}`);
    btn.setAttribute("aria-pressed", "false");
    btn.addEventListener("click", () => pushUpdate({ [`teamColors/${side}`]: hex }));
    row.appendChild(btn);
  }

  // Collapse/expand the preset row. Only toggles `hidden` and aria-expanded — it never rebuilds
  // a node in response to a click on that same node, per the iOS Safari runaway-loop lesson above.
  // Picking a colour deliberately does NOT auto-collapse, so a mis-pick can be corrected in place.
  const toggle = el(`${side}-color-toggle`);
  toggle.addEventListener("click", () => {
    const nowExpanded = row.hidden;
    row.hidden = !nowExpanded;
    toggle.setAttribute("aria-expanded", String(nowExpanded));
    toggle.querySelector(".color-toggle-caret").textContent = nowExpanded ? "▾" : "▸";
  });
}

function updateColorSelection(side, selectedHex) {
  const row = el(`${side}-colors`);
  for (const btn of row.children) {
    const isSelected = btn.dataset.hex.toLowerCase() === selectedHex.toLowerCase();
    btn.dataset.selected = isSelected;
    // Visual selection was already a border rather than colour alone (good); this makes the same
    // state available to assistive tech, which previously saw eight identical plain buttons.
    btn.setAttribute("aria-pressed", String(isSelected));
  }
  // Keep the collapsed chip showing the team's actual current colour.
  el(`${side}-color-current`).style.backgroundColor = selectedHex;
}

function renderScoreButtons(sport) {
  const homeIncrements = el("home-increments");
  const awayIncrements = el("away-increments");
  homeIncrements.innerHTML = "";
  awayIncrements.innerHTML = "";

  for (const amount of sport.scoreIncrements) {
    homeIncrements.appendChild(makeScoreButton("home", amount));
    awayIncrements.appendChild(makeScoreButton("away", amount));
  }
}

function makeScoreButton(side, amount) {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "pill-btn";
  btn.textContent = `+${amount}`;
  btn.addEventListener("click", () => bumpScore(side, amount));
  return btn;
}

function bumpScore(side, amount) {
  if (!latestSessionData) return;
  const sport = Object.prototype.hasOwnProperty.call(SPORTS, latestSessionData.sport)
    ? SPORTS[latestSessionData.sport]
    : DEFAULT_SPORT;

  if (sport.scoringModel === "setsGames") {
    bumpSetsGamesScore(side, amount, sport);
    return;
  }

  const field = side === "home" ? "homeScore" : "awayScore";
  const current = (side === "home" ? latestSessionData?.homeScore : latestSessionData?.awayScore) ?? 0;
  if (amount < 0 && current <= 0) return; // matches Android's own floor-of-zero clamp
  pushUpdate({ [field]: increment(amount) });
}

// Mirrors GameStateHolder.addSetsGamesScore's exact rule (deciding-set target, win-by-two,
// point-cap override) from the Android app. The rule itself is computed from the currently-known
// score, same as Android does locally — but every write here is an increment() diffed against
// that known score, exactly like FirebaseSessionSync.pushDiff, never an absolute overwrite. That's
// what lets the main device and a mirror score at the same instant without one silently dropping
// the other's point (see the "Scores/sets use atomic increments" note in CLAUDE.md).
function bumpSetsGamesScore(side, delta, config) {
  const isHome = side === "home";
  const homeScore = latestSessionData.homeScore ?? 0;
  const awayScore = latestSessionData.awayScore ?? 0;
  const period = latestSessionData.period ?? 1;

  if (delta < 0 && (isHome ? homeScore : awayScore) <= 0) return;

  const newHomeScore = Math.max(0, isHome ? homeScore + delta : homeScore);
  const newAwayScore = Math.max(0, !isHome ? awayScore + delta : awayScore);

  const isDecidingSet = period >= (config.bestOf ?? Infinity);
  const target = (isDecidingSet ? config.finalSetPoints : null) ?? config.pointsToWinGame ?? 25;
  const leader = Math.max(newHomeScore, newAwayScore);
  const diff = Math.abs(newHomeScore - newAwayScore);
  const hardCapped = config.pointCap != null && leader >= config.pointCap;
  const setWon = delta > 0 && leader >= target && (!config.winByTwo || diff >= 2 || hardCapped);

  const updates = {};
  if (setWon) {
    const homeWonSet = newHomeScore > newAwayScore;
    updates.homeScore = increment(-homeScore);
    updates.awayScore = increment(-awayScore);
    updates[homeWonSet ? "setsWon/home" : "setsWon/away"] = increment(1);
    updates.period = Math.min(period + 1, config.periods ?? period + 1);
  } else {
    if (newHomeScore !== homeScore) updates.homeScore = increment(newHomeScore - homeScore);
    if (newAwayScore !== awayScore) updates.awayScore = increment(newAwayScore - awayScore);
  }
  pushUpdate(updates);
}

function pushUpdate(fields) {
  if (!sessionRef) return;
  showScoreError("");
  update(sessionRef, { ...fields, updatedAt: serverTimestamp(), lastUpdatedBy: "mirror-web" }).catch(
    (err) => showScoreError(err.message || "Couldn't save that change — check your connection."),
  );
}

async function leaveSession() {
  detachListeners();
  sessionStorage.removeItem(STORAGE_KEY);
  if (currentSessionId && currentUid) {
    try {
      await remove(ref(db, `sessions/${currentSessionId}/members/${currentUid}`));
    } catch (err) {
      // Best-effort — onDisconnect() already queued the same removal server-side.
    }
  }
  currentSessionId = null;
  currentUid = null;
  sessionRef = null;
  latestSessionData = null;
  el("join-screen").hidden = false;
  el("score-screen").hidden = true;
  el("pairing-code").value = "";
}

el("home-minus").addEventListener("click", () => bumpScore("home", -1));
el("away-minus").addEventListener("click", () => bumpScore("away", -1));
el("leave-btn").addEventListener("click", leaveSession);

el("period-minus").addEventListener("click", () => adjustPeriod(-1));
el("period-plus").addEventListener("click", () => adjustPeriod(1));

el("clock-toggle").addEventListener("click", () => {
  if (latestSessionData && latestSessionData.clockRunning) stopClock();
  else startClock();
});
el("clock-minus").addEventListener("click", () => adjustClock(-30));
el("clock-plus").addEventListener("click", () => adjustClock(30));

el("shot-clock-toggle").addEventListener("click", () => {
  if (latestSessionData && latestSessionData.shotClockRunning) stopShotClock();
  else startShotClock();
});
el("shot-clock-minus").addEventListener("click", () => adjustShotClock(-5));
el("shot-clock-plus").addEventListener("click", () => adjustShotClock(5));
el("shot-clock-reset").addEventListener("click", resetShotClock);

el("home-name").addEventListener("focus", () => { homeNameFocused = true; });
el("home-name").addEventListener("blur", () => {
  homeNameFocused = false;
  pushUpdate({ "teamNames/home": el("home-name").value });
});
el("away-name").addEventListener("focus", () => { awayNameFocused = true; });
el("away-name").addEventListener("blur", () => {
  awayNameFocused = false;
  pushUpdate({ "teamNames/away": el("away-name").value });
});

el("join-form").addEventListener("submit", async (evt) => {
  evt.preventDefault();
  showError("");
  const parsed = parsePairingCode(el("pairing-code").value);
  if (!parsed) {
    showError("Enter the code as sessionId:joinToken.");
    return;
  }
  const joinBtn = el("join-btn");
  joinBtn.disabled = true;
  joinBtn.textContent = "Joining…";
  try {
    await joinSession(parsed.sessionId, parsed.joinToken);
    el("join-screen").hidden = true;
    el("score-screen").hidden = false;
  } catch (err) {
    showError(err.message || "Could not join session.");
  } finally {
    joinBtn.disabled = false;
    joinBtn.textContent = "Join";
  }
});

// Opened from the main device's pairing QR (or a shared join link): the code arrives as
// `#join=sessionId:joinToken`. It's a fragment so it never reaches Hosting's request logs, and
// it's stripped from the address bar and history right away so the token can't be re-shared by
// copying the URL. The code is only filled in — the scorer still taps Join themselves (owner request).
function fillCodeFromLink() {
  const prefix = "#join=";
  if (!location.hash.startsWith(prefix)) return false;
  const code = decodeURIComponent(location.hash.slice(prefix.length));
  history.replaceState(null, "", location.pathname + location.search);
  if (!parsePairingCode(code)) return false;
  el("pairing-code").value = code;
  el("join-btn").focus();
  return true;
}

// Auto-rejoin on load if we have a previously-successful pairing code (spec-equivalent of
// Android's activity-recreation gap, but here caused by iOS Safari's more aggressive
// backgrounded-tab eviction reloading the page from scratch mid-match). A join link takes
// precedence: it's a deliberate scan for what may be a newer match, so don't rejoin the old one.
(async function attemptStoredSession() {
  if (fillCodeFromLink()) return;
  const stored = sessionStorage.getItem(STORAGE_KEY);
  const parsed = stored ? parsePairingCode(stored) : null;
  if (!parsed) return;
  showError("Rejoining your previous session…");
  try {
    await joinSession(parsed.sessionId, parsed.joinToken);
    showError("");
    el("join-screen").hidden = true;
    el("score-screen").hidden = false;
  } catch (err) {
    sessionStorage.removeItem(STORAGE_KEY);
    showError("");
  }
})();
