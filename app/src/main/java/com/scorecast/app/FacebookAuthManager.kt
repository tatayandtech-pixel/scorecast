package com.scorecast.app

import androidx.activity.result.ActivityResultRegistryOwner
import com.facebook.AccessToken
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Raw Facebook Login SDK wrapper (Phase 8) — deliberately not Firebase Auth's Facebook provider,
 *  since Graph API calls need a Page access token, not just an identity link. */
object FacebookAuthManager {

    private val PERMISSIONS = listOf(
        "pages_show_list",
        "pages_read_engagement",
        "pages_manage_posts",
        "publish_video",
        "business_management",
    )

    sealed interface State {
        data object LoggedOut : State
        data object LoggingIn : State
        data class LoggedIn(val accessToken: AccessToken) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(
        AccessToken.getCurrentAccessToken()?.let { State.LoggedIn(it) } ?: State.LoggedOut
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private val callbackManager = CallbackManager.Factory.create()

    /** Suspends until the login flow completes; null on cancel or error ([state] carries the detail). */
    suspend fun login(owner: ActivityResultRegistryOwner): State.LoggedIn? {
        _state.value = State.LoggingIn
        return suspendCancellableCoroutine { cont ->
            val loginManager = LoginManager.getInstance()
            loginManager.registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    val logged = State.LoggedIn(result.accessToken)
                    _state.value = logged
                    if (cont.isActive) cont.resume(logged)
                }

                override fun onCancel() {
                    _state.value = State.LoggedOut
                    if (cont.isActive) cont.resume(null)
                }

                override fun onError(error: FacebookException) {
                    _state.value = State.Error(error.message ?: "Facebook login failed")
                    if (cont.isActive) cont.resume(null)
                }
            })
            loginManager.logIn(owner, callbackManager, PERMISSIONS)
        }
    }

    fun logout() {
        LoginManager.getInstance().logOut()
        _state.value = State.LoggedOut
    }
}
