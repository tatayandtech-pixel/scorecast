package com.scorecast.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
 * pattern — except that the session, a bearer credential, is encrypted at rest with an Android
 * Keystore key (spec §11). The key is generated inside the Keystore and can't be exported, so the
 * file is useless off this phone. Spec §11 names EncryptedSharedPreferences; its library
 * (androidx.security:security-crypto) has since been deprecated by Google in favour of using the
 * Keystore directly, which is what this does, with no new dependency.
 *
 * Still: never log the token, never copy it into a StreamTarget, never let it reach Firebase.
 */
object FacebookSessionStore {
    private const val SESSION_FILE = "facebook_session.bin"
    /** The plaintext file builds before 2026-10-15 wrote; deleted on sight, never read. */
    private const val LEGACY_SESSION_FILE = "facebook_session.json"
    private const val PENDING_FILE = "facebook_pending_auth.json"
    private const val KEY_ALIAS = "scorecast_facebook_session"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    fun load(context: Context): FacebookSession? {
        runCatching { File(context.filesDir, LEGACY_SESSION_FILE).delete() }
        val file = File(context.filesDir, SESSION_FILE)
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(decrypt(file.readBytes()))
            FacebookSession(
                accessToken = o.getString("accessToken"),
                userId = o.getString("userId"),
                userName = o.optString("userName", ""),
                expiresAtMs = o.optLong("expiresAtMs", 0L),
            )
        }.getOrElse {
            // Unreadable (e.g. the key is gone): signed out, rather than stuck on a dead file.
            file.delete()
            null
        }
    }

    fun save(context: Context, session: FacebookSession) {
        val o = JSONObject().apply {
            put("accessToken", session.accessToken)
            put("userId", session.userId)
            put("userName", session.userName)
            put("expiresAtMs", session.expiresAtMs)
        }
        runCatching { File(context.filesDir, SESSION_FILE).writeBytes(encrypt(o.toString())) }
    }

    /** Clears the session and the remembered Page together — signing out should leave nothing. */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, SESSION_FILE).delete() }
        runCatching { File(context.filesDir, LEGACY_SESSION_FILE).delete() }
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

    /** IV followed by the AES-GCM ciphertext (which carries its own authentication tag). */
    private fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(blob: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        }
        return String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
