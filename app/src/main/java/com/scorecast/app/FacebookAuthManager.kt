package com.scorecast.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Facebook login via the device's own browser, as a separate task (Phase 8).
 *
 * Deliberately does NOT use the Facebook Login SDK's LoginManager. That SDK offers only three
 * surfaces — the Facebook app, a Chrome Custom Tab, or an embedded WebView — and none of them is a
 * fully external browser. The owner asked for the login to leave the app entirely, so this
 * hand-rolls the OAuth flow instead.
 *
 * Known deviation, owner-approved after the trade-offs were laid out: this uses the implicit flow
 * (`response_type=token`), which OAuth 2.1 removes. The alternative, authorization-code + PKCE,
 * requires the app secret at the token-exchange step, which would force the secret-holding backend
 * that Phase 8 exists to avoid. If a backend ever appears, this is the first thing to migrate.
 *
 * Flow:
 *   1. [login] opens facebook.com/dialog/oauth in the browser, in its own task.
 *   2. Facebook redirects to [REDIRECT_URI] — an https page on our Firebase Hosting origin,
 *      because Facebook will not accept a custom scheme as a redirect_uri.
 *   3. web-mirror/fb-auth.html converts the fragment to scorecast://fb-auth?... in JS.
 *   4. [FacebookAuthRedirectActivity] catches that and calls [handleRedirect].
 *
 * The token travels only through the browser's own address bar and the intent — it is never sent
 * to our hosting origin, because a URL fragment is not transmitted to a server.
 */
object FacebookAuthManager {

    private const val TAG = "FacebookAuth"
    private const val GRAPH_VERSION = "v21.0"

    /** Must be listed verbatim in the Meta dashboard under Valid OAuth Redirect URIs. */
    const val REDIRECT_URI = "https://scorecast-app-625c0.web.app/fb-auth.html"

    const val APP_SCHEME = "scorecast"
    const val APP_HOST = "fb-auth"

    private val PERMISSIONS = listOf(
        "pages_show_list",
        "pages_read_engagement",
        "pages_manage_posts",
        "publish_video",
        "business_management",
    )

    sealed interface State {
        data object LoggedOut : State
        /** The browser is open; the app may be backgrounded or killed while this is the state. */
        data object LoggingIn : State
        data class LoggedIn(val session: FacebookSession) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.LoggedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    // A bare scope would take the whole login path down silently on a single throw — the exact
    // failure mode that cost a session in FirebaseSessionSync. Log it and surface it instead.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Login coroutine failed", t)
            _state.value = State.Error(t.message ?: "Facebook login failed")
        }
    )

    /**
     * Rehydrates the persisted session. Safe to call repeatedly.
     *
     * This exists because the old implementation read the token exactly once, when the object was
     * first touched, and never again — so a cold start could race the read and show "Not signed in"
     * despite a perfectly good saved session.
     */
    fun restore(context: Context) {
        if (_state.value is State.LoggingIn) return
        val session = FacebookSessionStore.load(context)
        _state.value = when {
            session == null -> State.LoggedOut
            session.isExpired -> {
                FacebookSessionStore.clear(context)
                State.LoggedOut
            }
            else -> State.LoggedIn(session)
        }
    }

    /** Opens the login page in the user's browser, as a separate task. */
    fun login(context: Context) {
        val nonce = newNonce()
        FacebookSessionStore.savePendingState(context, nonce)

        val authUrl = Uri.parse("https://www.facebook.com/$GRAPH_VERSION/dialog/oauth")
            .buildUpon()
            .appendQueryParameter("client_id", context.getString(R.string.facebook_app_id))
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("scope", PERMISSIONS.joinToString(","))
            .appendQueryParameter("state", nonce)
            .build()

        val intent = Intent(Intent.ACTION_VIEW, authUrl).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            _state.value = State.LoggingIn
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No browser available for the login flow", e)
            _state.value = State.Error("No browser app is installed to sign in with.")
        }
    }

    /**
     * Handles the scorecast://fb-auth redirect. Returns immediately; [state] carries the outcome
     * once the user lookup completes.
     */
    fun handleRedirect(context: Context, uri: Uri) {
        val appContext = context.applicationContext

        val expected = FacebookSessionStore.takePendingState(appContext)
        val returned = uri.getQueryParameter("state")
        if (expected == null || returned == null || expected != returned) {
            // Either a replayed/forged redirect, or one that outlived its 15-minute window.
            Log.w(TAG, "Rejecting redirect: state nonce did not match")
            _state.value = State.Error("Sign-in could not be verified. Please try again.")
            return
        }

        val token = uri.getQueryParameter("access_token")
        if (token.isNullOrBlank()) {
            _state.value = State.Error("Facebook did not return an access token.")
            return
        }

        val expiresAtMs = uri.getQueryParameter("expires_in")?.toLongOrNull()
            ?.let { System.currentTimeMillis() + it * 1000L }
            ?: 0L

        _state.value = State.LoggingIn
        scope.launch {
            runCatching { FacebookGraphApi.getCurrentUser(token) }
                .onSuccess { user ->
                    val session = FacebookSession(token, user.id, user.name, expiresAtMs)
                    FacebookSessionStore.save(appContext, session)
                    _state.value = State.LoggedIn(session)
                }
                .onFailure {
                    Log.e(TAG, "Token came back but /me failed", it)
                    _state.value = State.Error(it.message ?: "Could not read your Facebook account.")
                }
        }
    }

    /** Clears the local session. Does not revoke the token server-side. */
    fun logout(context: Context) {
        FacebookSessionStore.clear(context.applicationContext)
        FacebookSessionStore.forgetPage(context.applicationContext)
        _state.value = State.LoggedOut
    }

    private fun newNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
