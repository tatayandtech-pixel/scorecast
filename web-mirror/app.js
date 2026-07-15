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
  el("home-name").textContent = (data.teamNames && data.teamNames.home) ?? "HOME";
  el("away-name").textContent = (data.teamNames && data.teamNames.away) ?? "AWAY";
  el("home-score").textContent = data.homeScore ?? 0;
  el("away-score").textContent = data.awayScore ?? 0;

  el("sets-row").hidden = !isSetsGames;
  if (isSetsGames) {
    el("home-sets").textContent = (data.setsWon && data.setsWon.home) ?? 0;
    el("away-sets").textContent = (data.setsWon && data.setsWon.away) ?? 0;
  }

  el("home-minus").disabled = isSetsGames || (data.homeScore ?? 0) <= 0;
  el("away-minus").disabled = isSetsGames || (data.awayScore ?? 0) <= 0;

  // Clock (spec §5 anchor model) — read-only here, hidden for clockDirection "none" sports
  // (e.g. volleyball) the same way the Android panel hides its clock row.
  const clockDir = data.clockDirection || "down";
  el("clock-row").hidden = clockDir === "none";
  updateClockDisplay();
  manageClockTicking(data.clockRunning === true);

  // Rebuild the +N buttons only when the sport (and therefore its increments) actually
  // changes — NOT on every score update. Every write we make immediately echoes back through
  // our own onValue listener, so rebuilding on every render meant the tapped button's DOM node
  // was destroyed and replaced mid-tap. On iOS Safari this can re-target the in-flight click at
  // whatever new element ends up under the same screen coordinates, firing bumpScore again,
  // which writes again, which rebuilds again — a self-sustaining loop with no further taps
  // needed (this was the "score climbs on its own, can't stop it" bug).
  if (data.sport !== lastRenderedSportKey) {
    lastRenderedSportKey = data.sport;
    renderScoreButtons(sport, isSetsGames);
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

function renderScoreButtons(sport, disabled) {
  const homeIncrements = el("home-increments");
  const awayIncrements = el("away-increments");
  homeIncrements.innerHTML = "";
  awayIncrements.innerHTML = "";
  if (disabled) return;

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
  const field = side === "home" ? "homeScore" : "awayScore";
  const current = (side === "home" ? latestSessionData?.homeScore : latestSessionData?.awayScore) ?? 0;
  if (amount < 0 && current <= 0) return; // matches Android's own floor-of-zero clamp
  pushUpdate({ [field]: increment(amount) });
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

// Auto-rejoin on load if we have a previously-successful pairing code (spec-equivalent of
// Android's activity-recreation gap, but here caused by iOS Safari's more aggressive
// backgrounded-tab eviction reloading the page from scratch mid-match).
(async function attemptStoredSession() {
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
