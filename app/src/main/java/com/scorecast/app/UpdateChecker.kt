package com.scorecast.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionTag: String, val downloadUrl: String)

/** Checks GitHub Releases for a newer ScoreCast build (Phase 9). The scorecast repo is public,
 *  so this is a plain unauthenticated request — no token embedded client-side. */
object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/tatayandtech-pixel/scorecast/releases/latest"

    /** Returns the latest release's info if its tag differs from [currentVersionName], or null if
     *  already current, offline, or no release/APK asset exists yet. Tag comparison is a plain
     *  inequality check (not semver-aware) — fine for this project's low, owner-driven release cadence. */
    suspend fun checkForUpdate(currentVersionName: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.getString("tag_name")
            if (tag.removePrefix("v") == currentVersionName) return@runCatching null

            val assets = json.getJSONArray("assets")
            val apkUrl = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?.getString("browser_download_url")
                ?: return@runCatching null

            UpdateInfo(versionTag = tag, downloadUrl = apkUrl)
        }.getOrNull()
    }
}
