package com.scorecast.app

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.ServerValue

/** Maps [GameState] to/from the `/sessions/{sessionId}` schema (spec §4). */

fun GameState.toFirebaseMap(joinToken: String, mainUid: String): Map<String, Any?> {
    // A running clock's startedAt is rewritten to the server's "now" below, so its base has to be
    // re-anchored to the remaining time as of now too — otherwise the session (and its echo back
    // into GameStateHolder) restarts the clock from wherever it was last started, e.g. 10:00.
    val nowMs = ServerTimeSync.nowMs()
    val base = if (clockRunning) clockDisplay(nowMs) else baseRemainingSeconds
    val shotBase = if (shotClockRunning) shotClockDisplay(nowMs) else shotClockBaseRemainingSeconds
    return mapOf(
        "joinToken" to joinToken,
        "sport" to sport,
        "homeScore" to homeScore,
        "awayScore" to awayScore,
        "setsWon" to mapOf("home" to setsWonHome, "away" to setsWonAway),
        "teamNames" to mapOf("home" to homeTeam, "away" to awayTeam),
        "teamColors" to mapOf("home" to homeColorHex, "away" to awayColorHex),
        "players" to mapOf("home" to playersHome, "away" to playersAway),
        "customText" to customText,
        "period" to period,
        "periodLabel" to periodLabel,
        "clockDirection" to clockDirection,
        "clockRunning" to clockRunning,
        // Server-resolved (spec §5 "Clock skew") whenever the clock is actually running, so a paired
        // device's elapsed-time math isn't thrown off by this device's own clock error. Irrelevant
        // while stopped since clockDisplay() ignores startedAt in that state.
        "startedAt" to if (clockRunning) ServerValue.TIMESTAMP else startedAtMs,
        "baseRemaining" to base.toDouble(),
        "shotClockRunning" to shotClockRunning,
        "shotClockStartedAt" to if (shotClockRunning) ServerValue.TIMESTAMP else shotClockStartedAtMs,
        "shotClockBaseRemaining" to shotBase.toDouble(),
        "extraFields" to extraFields,
        "overlayPosition" to overlayPosition.name,
        "lastUpdatedBy" to "main",
        "updatedAt" to ServerValue.TIMESTAMP,
        // The creator's own membership entry, keyed the same way a mirror's join entry is (spec §9) —
        // its value is the joinToken itself, which is what the security rules check a new joiner
        // against. See database.rules.json.
        "members" to mapOf(mainUid to joinToken),
    )
}

fun DataSnapshot.toGameState(): GameState? {
    if (!exists()) return null

    fun str(path: String, default: String) = child(path).getValue(String::class.java) ?: default
    fun int(path: String, default: Int) = child(path).getValue(Long::class.java)?.toInt() ?: default
    fun double(path: String, default: Float): Float {
        child(path).getValue(Double::class.java)?.let { return it.toFloat() }
        child(path).getValue(Long::class.java)?.let { return it.toFloat() }
        return default
    }
    fun bool(path: String, default: Boolean) = child(path).getValue(Boolean::class.java) ?: default

    val position = runCatching {
        OverlayPosition.valueOf(str("overlayPosition", OverlayPosition.BOTTOM_CENTER.name))
    }.getOrDefault(OverlayPosition.BOTTOM_CENTER)

    @Suppress("UNCHECKED_CAST")
    val extraFields = (child("extraFields").value as? Map<String, Any?>)
        ?.mapNotNull { (k, v) -> (v as? Long)?.let { k to it.toInt() } }
        ?.toMap()
        ?: emptyMap()

    val playersHome = (child("players").child("home").value as? List<*>)
        ?.mapNotNull { it as? String } ?: emptyList()
    val playersAway = (child("players").child("away").value as? List<*>)
        ?.mapNotNull { it as? String } ?: emptyList()

    return GameState(
        homeTeam = str("teamNames/home", "HOME"),
        awayTeam = str("teamNames/away", "AWAY"),
        homeScore = int("homeScore", 0),
        awayScore = int("awayScore", 0),
        setsWonHome = int("setsWon/home", 0),
        setsWonAway = int("setsWon/away", 0),
        homeColorHex = str("teamColors/home", "#1E40AF"),
        awayColorHex = str("teamColors/away", "#B91C1C"),
        period = int("period", 1),
        periodLabel = str("periodLabel", "Q"),
        clockRunning = bool("clockRunning", false),
        startedAtMs = child("startedAt").getValue(Long::class.java) ?: 0L,
        baseRemainingSeconds = double("baseRemaining", 600f),
        shotClockRunning = bool("shotClockRunning", false),
        shotClockStartedAtMs = child("shotClockStartedAt").getValue(Long::class.java) ?: 0L,
        shotClockBaseRemainingSeconds = double("shotClockBaseRemaining", 24f),
        customText = str("customText", ""),
        overlayPosition = position,
        sport = str("sport", "basketball"),
        clockDirection = str("clockDirection", "down"),
        extraFields = extraFields,
        playersHome = playersHome,
        playersAway = playersAway,
    )
}
