package com.scorecast.app

data class ExtraFieldConfig(
    val key: String,
    val label: String,
    val perTeam: Boolean,
    val max: Int?,
)

data class SportConfig(
    val sport: String,
    val displayName: String,
    val scoringModel: String = "flat",
    val usesPlayers: Boolean = false,
    val periods: Int = 2,
    val periodLabel: String = "Period",
    val periodLength: Int = 600,
    val clockDirection: String = "down",
    val scoreIncrements: List<Int> = listOf(1),
    val extraFields: List<ExtraFieldConfig> = emptyList(),
    val bestOf: Int? = null,
    val pointsToWinGame: Int? = null,
    val finalSetPoints: Int? = null,
    val winByTwo: Boolean = false,
    val pointCap: Int? = null,
    val shotClockSeconds: Int? = null,
)
