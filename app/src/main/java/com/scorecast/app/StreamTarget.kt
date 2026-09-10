package com.scorecast.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Named platform tiles (spec Appendix B5/§10). Facebook's Graph API login stays deferred — every
 *  platform here uses manual ingest URL + key paste for now. */
enum class Platform(val displayName: String, val defaultIngestUrl: String) {
    FACEBOOK("Facebook", StreamConfig.DEFAULT_INGEST_URL),
    YOUTUBE("YouTube", "rtmp://a.rtmp.youtube.com/live2"),
    TWITCH("Twitch", "rtmp://live.twitch.tv/app"),
    CUSTOM_RTMP("Custom RTMP", ""),
    SAVE_IN_MEMORY("Save in memory", ""),
}

/** The operator's chosen destination + delivery mode, threaded from the wizard into the in-game screen. */
data class StreamTarget(
    val platform: Platform = Platform.FACEBOOK,
    val ingestUrl: String = Platform.FACEBOOK.defaultIngestUrl,
    val streamKey: String = "",
    val mode: RecordingMode = RecordingMode.STREAM_ONLY,
)

/** A previously-used ingest URL, kept for quick re-selection (spec §10). Never stores the key —
 *  stream keys are runtime-only per CLAUDE.md's security rule. */
data class RecentTarget(val platform: Platform, val ingestUrl: String, val lastUsedMs: Long)

object RecentTargetsStore {
    private const val FILE_NAME = "recent_targets.json"
    private const val MAX_ENTRIES = 5

    fun list(context: Context): List<RecentTarget> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                runCatching {
                    RecentTarget(
                        platform = Platform.valueOf(o.getString("platform")),
                        ingestUrl = o.getString("ingestUrl"),
                        lastUsedMs = o.getLong("lastUsedMs"),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun record(context: Context, platform: Platform, ingestUrl: String) {
        if (ingestUrl.isBlank()) return
        val updated = (listOf(RecentTarget(platform, ingestUrl, System.currentTimeMillis())) +
            list(context).filterNot { it.platform == platform && it.ingestUrl == ingestUrl })
            .take(MAX_ENTRIES)
        val arr = JSONArray()
        updated.forEach { t ->
            arr.put(JSONObject().apply {
                put("platform", t.platform.name)
                put("ingestUrl", t.ingestUrl)
                put("lastUsedMs", t.lastUsedMs)
            })
        }
        File(context.filesDir, FILE_NAME).writeText(arr.toString())
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
