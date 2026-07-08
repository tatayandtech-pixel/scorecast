package com.scorecast.app

enum class OverlayPosition { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

data class GameState(
    val homeTeam: String = "HOME",
    val awayTeam: String = "AWAY",
    val homeScore: Int = 0,
    val awayScore: Int = 0,
    // setsGames sports (spec §6): homeScore/awayScore hold the CURRENT set's points, reset to 0
    // when a set is won; setsWonHome/Away tally sets banked so far this match.
    val setsWonHome: Int = 0,
    val setsWonAway: Int = 0,
    val homeColorHex: String = "#1E40AF",
    val awayColorHex: String = "#B91C1C",
    val period: Int = 1,
    val periodLabel: String = "Q",
    val clockRunning: Boolean = false,
    val startedAtMs: Long = 0L,
    val baseRemainingSeconds: Float = 600f,
    val customText: String = "",
    val overlayPosition: OverlayPosition = OverlayPosition.BOTTOM_CENTER,
    // Phase 4: config-driven sport
    val sport: String = "basketball",
    val clockDirection: String = "down",
    val extraFields: Map<String, Int> = emptyMap(),
    val playersHome: List<String> = emptyList(),
    val playersAway: List<String> = emptyList(),
)

fun GameState.clockDisplay(): Float = when (clockDirection) {
    "up" -> if (clockRunning) {
        val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000f
        baseRemainingSeconds + elapsed
    } else baseRemainingSeconds
    "none" -> 0f
    else -> if (clockRunning) {
        val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000f
        (baseRemainingSeconds - elapsed).coerceAtLeast(0f)
    } else baseRemainingSeconds
}

fun Float.toClockString(): String {
    val total = toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
