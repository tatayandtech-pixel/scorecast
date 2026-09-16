package com.scorecast.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/** A logged-in Facebook user, as returned by the external-browser OAuth flow (Phase 8). */
data class FacebookSession(
    val accessToken: String,
    val userId: String,
    val userName: String,
    /** Epoch ms, or 0 when Facebook returned no `expires_in` (long-lived / never-expiring token). */
    val expiresAtMs: Long,
) {
    /** Treated as expired a minute early so a call can't start on a token that dies mid-flight. */
    val isExpired: Boolean
        get() = expiresAtMs > 0L && System.currentTimeMillis() >= expiresAtMs - 60_000L
}

/** The Page last streamed to. Deliberately id + name only — see the note on [rememberPage]. */
data class RememberedPage(val id: String, val name: String)

/**
 * Persists the Facebook session across launches, matching [RecentTargetsStore]'s file-per-concern
 * JSON-in-filesDir pattern.
 *
 * Storage is app-private internal storage, which is where the Facebook SDK kept this same token
 * before the external-browser flow replaced it, so this is not a downgrade. It is still a bearer
 * credential: never log it, never copy it into a StreamTarget, never let it reach Firebase.
 */
object FacebookSessionStore {
    private const val SESSION_FILE = "facebook_session.json"
    private const val PENDING_FILE = "facebook_pending_auth.json"

    fun load(context: Context): FacebookSession? {
        val file = File(context.filesDir, SESSION_FILE)
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            FacebookSession(
                accessToken = o.getString("accessToken"),
                userId = o.getString("userId"),
                userName = o.optString("userName", ""),
                expiresAtMs = o.optLong("expiresAtMs", 0L),
            )
        }.getOrNull()
    }

    fun save(context: Context, session: FacebookSession) {
        val o = JSONObject().apply {
            put("accessToken", session.accessToken)
            put("userId", session.userId)
            put("userName", session.userName)
            put("expiresAtMs", session.expiresAtMs)
        }
        runCatching { File(context.filesDir, SESSION_FILE).writeText(o.toString()) }
    }

    /** Clears the session and the remembered Page together — signing out should leave nothing. */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, SESSION_FILE).delete() }
        runCatching { File(context.filesDir, PENDING_FILE).delete() }
    }

    /**
     * Remembers which Page was last streamed to, by id and name only.
     *
     * The Page *access token* is deliberately not stored. It is a second bearer credential and it
     * is cheap to re-fetch from /me/accounts with the user token we already hold, so keeping it at
     * rest would add risk for no convenience. This mirrors [RecentTargetsStore], which persists
     * ingest URLs but never the stream key.
     */
    fun rememberPage(context: Context, page: FacebookPage) {
        val o = JSONObject().apply {
            put("id", page.id)
            put("name", page.name)
        }
        runCatching { File(context.filesDir, "facebook_last_page.json").writeText(o.toString()) }
    }

    fun lastPage(context: Context): RememberedPage? {
        val file = File(context.filesDir, "facebook_last_page.json")
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            RememberedPage(o.getString("id"), o.getString("name"))
        }.getOrNull()
    }

    fun forgetPage(context: Context) {
        runCatching { File(context.filesDir, "facebook_last_page.json").delete() }
    }

    /**
     * The CSRF nonce for an in-flight login.
     *
     * This has to survive process death, not just recomposition: the user leaves for a separate
     * browser task, and Android is free to kill ScoreCast while they type their password. Holding
     * the nonce in memory would mean a successful login gets rejected on return.
     */
    fun savePendingState(context: Context, state: String) {
        val o = JSONObject().apply {
            put("state", state)
            put("createdAtMs", System.currentTimeMillis())
        }
        runCatching { File(context.filesDir, PENDING_FILE).writeText(o.toString()) }
    }

    /** Returns the pending nonce and consumes it, so a redirect can never be replayed. */
    fun takePendingState(context: Context): String? {
        val file = File(context.filesDir, PENDING_FILE)
        if (!file.exists()) return null
        val state = runCatching {
            val o = JSONObject(file.readText())
            // A nonce older than 15 minutes is treated as abandoned rather than valid.
            val age = System.currentTimeMillis() - o.optLong("createdAtMs", 0L)
            if (age > 15 * 60_000L) null else o.getString("state")
        }.getOrNull()
        runCatching { file.delete() }
        return state
    }
}
