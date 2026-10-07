package com.scorecast.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

/**
 * What the pairing QR encodes (spec §9) — session identity only, never the video URL. The mirror
 * has no video access by design; scanning this just binds it to the same scoring state.
 */
data class SessionCode(
    val sessionId: String,
    val joinToken: String,
) {
    /** A web-mirror link, so any phone's own camera app can open it in a browser and join without
     *  typing anything. The code sits in the fragment, which browsers never send to the server,
     *  so the join token stays out of Firebase Hosting's request logs. */
    fun toQrPayload(): String = "$WEB_MIRROR_URL#join=$sessionId:$joinToken"

    companion object {
        const val WEB_MIRROR_URL = "https://scorecast-app-625c0.web.app/"

        /** Short-lived join token (spec §9) so a stray scan of an old QR can't still bind. */
        fun generate(): SessionCode = SessionCode(
            sessionId = UUID.randomUUID().toString(),
            joinToken = UUID.randomUUID().toString().take(8),
        )

        /** Accepts the web-mirror link the QR now carries, and the JSON payload older builds
         *  showed, so the in-app scanner pairs with either. */
        fun parse(payload: String): SessionCode? {
            val code = payload.substringAfter("#join=", missingDelimiterValue = "")
            if (code.isNotEmpty()) {
                val sessionId = code.substringBefore(':')
                val joinToken = code.substringAfter(':', missingDelimiterValue = "")
                return if (sessionId.isNotBlank() && joinToken.isNotBlank()) SessionCode(sessionId, joinToken) else null
            }
            return runCatching {
                val o = JSONObject(payload)
                SessionCode(sessionId = o.getString("sessionId"), joinToken = o.getString("joinToken"))
            }.getOrNull()
        }
    }
}

/**
 * Holds the current main-device session for QR pairing. Lazily created the first time the
 * operator opens "Pair device" and cleared when the match ends, so a fresh session (and QR)
 * starts with each new broadcast. Creating a session also starts the Firebase sync (spec §4) —
 * the QR itself never carries the video URL, only enough to join scoring.
 */
object SessionHolder {
    private val _session = MutableStateFlow<SessionCode?>(null)
    val session: StateFlow<SessionCode?> = _session.asStateFlow()

    fun ensureSession(): SessionCode {
        _session.value?.let { return it }
        val created = SessionCode.generate()
        _session.value = created
        FirebaseSessionSync.startAsMain(created)
        return created
    }

    fun clear() {
        _session.value = null
        FirebaseSessionSync.stop(deleteSession = true)
    }
}
