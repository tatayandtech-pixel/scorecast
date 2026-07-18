package com.scorecast.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GameStateHolder {
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    fun update(block: GameState.() -> GameState) {
        _state.value = _state.value.block()
    }

    /**
     * Applies a state snapshot received from Firebase (spec §4) without going through the normal
     * mutation helpers — used by [FirebaseSessionSync] when a remote change comes in. Local-first
     * (spec §2): this only ever runs in response to a remote update, never blocks a local edit.
     */
    fun applyRemote(remote: GameState) {
        _state.value = remote
    }

    fun startClock() = update {
        if (clockRunning) this
        else copy(clockRunning = true, startedAtMs = ServerTimeSync.nowMs())
    }

    fun stopClock() = update {
        if (!clockRunning) this
        else copy(clockRunning = false, baseRemainingSeconds = clockDisplay(ServerTimeSync.nowMs()), startedAtMs = 0L)
    }

    fun adjustClock(deltaSeconds: Float) = update {
        val newBase = (clockDisplay(ServerTimeSync.nowMs()) + deltaSeconds).coerceAtLeast(0f)
        copy(
            baseRemainingSeconds = newBase,
            startedAtMs = if (clockRunning) ServerTimeSync.nowMs() else startedAtMs,
        )
    }

    fun resetClock(totalSeconds: Float = 600f) = update {
        copy(clockRunning = false, baseRemainingSeconds = totalSeconds, startedAtMs = 0L)
    }

    fun startShotClock() = update {
        if (shotClockRunning) this
        else copy(shotClockRunning = true, shotClockStartedAtMs = ServerTimeSync.nowMs())
    }

    fun stopShotClock() = update {
        if (!shotClockRunning) this
        else copy(
            shotClockRunning = false,
            shotClockBaseRemainingSeconds = shotClockDisplay(ServerTimeSync.nowMs()),
            shotClockStartedAtMs = 0L,
        )
    }

    fun adjustShotClock(deltaSeconds: Float) = update {
        val newBase = (shotClockDisplay(ServerTimeSync.nowMs()) + deltaSeconds).coerceAtLeast(0f)
        copy(
            shotClockBaseRemainingSeconds = newBase,
            shotClockStartedAtMs = if (shotClockRunning) ServerTimeSync.nowMs() else shotClockStartedAtMs,
        )
    }

    fun resetShotClock(totalSeconds: Float) = update {
        copy(shotClockRunning = false, shotClockBaseRemainingSeconds = totalSeconds, shotClockStartedAtMs = 0L)
    }

    /** Returns the new value so the caller (MainActivity) can persist it via [ScoreboardStylePrefs]. */
    fun toggleScoreboardBackground(): Boolean {
        val newValue = !_state.value.scoreboardLight
        update { copy(scoreboardLight = newValue) }
        return newValue
    }

    // Phase 4 — sport selection: resets period, clock, extra fields, and player lists.
    // Team names, scores, colors, custom text, and overlay position are preserved.
    fun selectSport(config: SportConfig) = update {
        val initBase = if (config.clockDirection == "up") 0f else config.periodLength.toFloat()
        copy(
            sport = config.sport,
            period = 1,
            periodLabel = config.periodLabel,
            clockDirection = config.clockDirection,
            clockRunning = false,
            startedAtMs = 0L,
            baseRemainingSeconds = initBase,
            shotClockRunning = false,
            shotClockStartedAtMs = 0L,
            shotClockBaseRemainingSeconds = (config.shotClockSeconds ?: 24).toFloat(),
            extraFields = emptyMap(),
            homeScore = 0,
            awayScore = 0,
            setsWonHome = 0,
            setsWonAway = 0,
            playersHome = if (config.usesPlayers) List(2) { "" } else emptyList(),
            playersAway = if (config.usesPlayers) List(2) { "" } else emptyList(),
        )
    }

    /**
     * Rally-point scoring for `setsGames` sports (spec §6) — e.g. volleyball to 25, win-by-two,
     * with a lower target on the deciding (final) set. A point that wins the set banks it and
     * resets both teams' points to 0 for the next set; the "Period" field doubles as the set
     * number, so the existing Period stepper UI needs no changes.
     */
    fun addSetsGamesScore(isHome: Boolean, delta: Int, config: SportConfig) = update {
        val newHomeScore = (if (isHome) homeScore + delta else homeScore).coerceAtLeast(0)
        val newAwayScore = (if (!isHome) awayScore + delta else awayScore).coerceAtLeast(0)

        val isDecidingSet = period >= (config.bestOf ?: Int.MAX_VALUE)
        val target = (if (isDecidingSet) config.finalSetPoints else null)
            ?: config.pointsToWinGame ?: 25
        val leader = maxOf(newHomeScore, newAwayScore)
        val diff = kotlin.math.abs(newHomeScore - newAwayScore)
        val hardCapped = config.pointCap != null && leader >= config.pointCap
        val setWon = delta > 0 && leader >= target && (!config.winByTwo || diff >= 2 || hardCapped)

        if (setWon) {
            val homeWonSet = newHomeScore > newAwayScore
            copy(
                homeScore = 0,
                awayScore = 0,
                setsWonHome = setsWonHome + if (homeWonSet) 1 else 0,
                setsWonAway = setsWonAway + if (!homeWonSet) 1 else 0,
                period = (period + 1).coerceAtMost(config.periods),
            )
        } else {
            copy(homeScore = newHomeScore, awayScore = newAwayScore)
        }
    }

    // Extra field updates. perTeam fields use compound keys: "${key}_home" / "${key}_away".
    fun setExtraFieldTeam(fieldKey: String, isHome: Boolean, value: Int) = update {
        val mapKey = "${fieldKey}_${if (isHome) "home" else "away"}"
        copy(extraFields = extraFields + (mapKey to value))
    }

    fun setExtraField(fieldKey: String, value: Int) = update {
        copy(extraFields = extraFields + (fieldKey to value))
    }

    // Player roster helpers.
    fun setPlayerHome(index: Int, name: String) = update {
        copy(playersHome = playersHome.toMutableList().also { it[index] = name })
    }

    fun setPlayerAway(index: Int, name: String) = update {
        copy(playersAway = playersAway.toMutableList().also { it[index] = name })
    }

    fun addPlayerHome() = update { copy(playersHome = playersHome + "") }
    fun addPlayerAway() = update { copy(playersAway = playersAway + "") }

    fun removePlayerHome(index: Int) = update {
        copy(playersHome = playersHome.toMutableList().also { it.removeAt(index) })
    }

    fun removePlayerAway(index: Int) = update {
        copy(playersAway = playersAway.toMutableList().also { it.removeAt(index) })
    }
}
