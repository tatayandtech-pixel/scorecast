package com.scorecast.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A completed (or in-progress-and-abandoned) session, listed on the Matches screen (spec Appendix B2). */
data class MatchRecord(
    val id: String = UUID.randomUUID().toString(),
    val sport: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeColorHex: String,
    val awayColorHex: String,
    val playedAtMs: Long,
) {
    companion object {
        fun from(state: GameState): MatchRecord = MatchRecord(
            sport = state.sport,
            homeTeam = state.homeTeam,
            awayTeam = state.awayTeam,
            homeColorHex = state.homeColorHex,
            awayColorHex = state.awayColorHex,
            playedAtMs = System.currentTimeMillis(),
        )
    }
}

/**
 * Local match history (spec Appendix B2) — newest first, JSON file in app-internal storage.
 * No Firebase yet (that's Phase 5); this is purely device-local, matching "Stored locally on the
 * main device" in the spec.
 */
object MatchHistoryStore {
    private const val FILE_NAME = "match_history.json"

    fun list(context: Context): List<MatchRecord> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MatchRecord(
                    id = o.getString("id"),
                    sport = o.getString("sport"),
                    homeTeam = o.getString("homeTeam"),
                    awayTeam = o.getString("awayTeam"),
                    homeColorHex = o.getString("homeColorHex"),
                    awayColorHex = o.getString("awayColorHex"),
                    playedAtMs = o.getLong("playedAtMs"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun delete(context: Context, id: String) {
        writeAll(context, list(context).filterNot { it.id == id })
    }

    fun save(context: Context, record: MatchRecord) {
        writeAll(context, listOf(record) + list(context))
    }

    private fun writeAll(context: Context, records: List<MatchRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("sport", r.sport)
                put("homeTeam", r.homeTeam)
                put("awayTeam", r.awayTeam)
                put("homeColorHex", r.homeColorHex)
                put("awayColorHex", r.awayColorHex)
                put("playedAtMs", r.playedAtMs)
            })
        }
        File(context.filesDir, FILE_NAME).writeText(arr.toString())
    }
}
