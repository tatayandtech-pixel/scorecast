package com.scorecast.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object SportConfigLoader {
    private val cache = ConcurrentHashMap<String, SportConfig>()
    @Volatile private var allSports: List<SportConfig>? = null

    fun loadAll(context: Context): List<SportConfig> {
        allSports?.let { return it }
        synchronized(this) {
            allSports?.let { return it }
            val files = context.assets.list("sports") ?: return emptyList()
            files.filter { it.endsWith(".json") }.forEach { file ->
                val sport = file.removeSuffix(".json")
                if (!cache.containsKey(sport)) {
                    runCatching {
                        val json = context.assets.open("sports/$file").bufferedReader().readText()
                        cache[sport] = parse(json)
                    }
                }
            }
            return cache.values.sortedBy { it.displayName }.also { allSports = it }
        }
    }

    fun getCached(sport: String): SportConfig? = cache[sport]

    private fun parse(json: String): SportConfig {
        val o = JSONObject(json)
        val efArr = o.optJSONArray("extraFields") ?: JSONArray()
        val extraFields = (0 until efArr.length()).map { i ->
            val e = efArr.getJSONObject(i)
            ExtraFieldConfig(
                key = e.getString("key"),
                label = e.getString("label"),
                perTeam = e.optBoolean("perTeam", false),
                max = if (e.isNull("max")) null else e.optInt("max"),
            )
        }
        val incArr = o.optJSONArray("scoreIncrements") ?: JSONArray()
        val increments = (0 until incArr.length()).map { incArr.getInt(it) }.ifEmpty { listOf(1) }
        return SportConfig(
            sport = o.getString("sport"),
            displayName = o.getString("displayName"),
            scoringModel = o.optString("scoringModel", "flat"),
            usesPlayers = o.optBoolean("usesPlayers", false),
            periods = o.optInt("periods", 2),
            periodLabel = o.optString("periodLabel", "Period"),
            periodLength = o.optInt("periodLength", 600),
            clockDirection = o.optString("clockDirection", "down"),
            scoreIncrements = increments,
            extraFields = extraFields,
            bestOf = if (o.has("bestOf") && !o.isNull("bestOf")) o.getInt("bestOf") else null,
            pointsToWinGame = if (o.has("pointsToWinGame") && !o.isNull("pointsToWinGame")) o.getInt("pointsToWinGame") else null,
            finalSetPoints = if (o.has("finalSetPoints") && !o.isNull("finalSetPoints")) o.getInt("finalSetPoints") else null,
            winByTwo = o.optBoolean("winByTwo", false),
            pointCap = if (o.has("pointCap") && !o.isNull("pointCap")) o.getInt("pointCap") else null,
            shotClockSeconds = if (o.has("shotClockSeconds") && !o.isNull("shotClockSeconds")) o.getInt("shotClockSeconds") else null,
        )
    }
}
