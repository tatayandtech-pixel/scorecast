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

    fun startClock() = update {
        if (clockRunning) this
        else copy(clockRunning = true, startedAtMs = System.currentTimeMillis())
    }

    fun stopClock() = update {
        if (!clockRunning) this
        else copy(clockRunning = false, baseRemainingSeconds = clockDisplay(), startedAtMs = 0L)
    }

    fun adjustClock(deltaSeconds: Float) = update {
        val newBase = (clockDisplay() + deltaSeconds).coerceAtLeast(0f)
        copy(
            baseRemainingSeconds = newBase,
            startedAtMs = if (clockRunning) System.currentTimeMillis() else startedAtMs,
        )
    }

    fun resetClock(totalSeconds: Float = 600f) = update {
        copy(clockRunning = false, baseRemainingSeconds = totalSeconds, startedAtMs = 0L)
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
            extraFields = emptyMap(),
            playersHome = if (config.usesPlayers) List(2) { "" } else emptyList(),
            playersAway = if (config.usesPlayers) List(2) { "" } else emptyList(),
        )
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
