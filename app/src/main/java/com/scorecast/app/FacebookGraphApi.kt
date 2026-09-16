package com.scorecast.app

import android.os.Bundle
import com.facebook.GraphRequest
import com.facebook.HttpMethod
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FacebookPage(val id: String, val name: String, val accessToken: String)

data class FacebookLiveVideo(val id: String, val secureStreamUrl: String)

data class FacebookUser(val id: String, val name: String)

/**
 * Thin suspend wrapper around Facebook's Graph API (Phase 8). No appsecret_proof is sent — this
 * app's own "Require app secret" dashboard setting is off, confirmed live against a real call.
 *
 * Tokens are passed as plain strings rather than the SDK's AccessToken type: the external-browser
 * login flow never constructs one, since it does not use the SDK's LoginManager. Every call sets
 * `access_token` explicitly as a parameter, which is what the Page calls already did.
 */
object FacebookGraphApi {

    /** The logged-in user's id and display name, for "Signed in as X" and the stored session. */
    suspend fun getCurrentUser(userToken: String): FacebookUser {
        val json = request("me", HttpMethod.GET, token = userToken,
            extraParams = Bundle().apply { putString("fields", "id,name") })
        return FacebookUser(json.getString("id"), json.getString("name"))
    }

    suspend fun listPages(userToken: String): List<FacebookPage> {
        val json = request("me/accounts", HttpMethod.GET, token = userToken)
        val data = json.getJSONArray("data")
        return (0 until data.length()).map { i ->
            val o = data.getJSONObject(i)
            FacebookPage(o.getString("id"), o.getString("name"), o.getString("access_token"))
        }
    }

    suspend fun createLiveVideo(page: FacebookPage, title: String): FacebookLiveVideo {
        val params = Bundle().apply {
            putString("status", "LIVE_NOW")
            putString("title", title)
        }
        val json = request(
            "${page.id}/live_videos", HttpMethod.POST,
            token = page.accessToken, extraParams = params,
        )
        return FacebookLiveVideo(json.getString("id"), json.getString("secure_stream_url"))
    }

    suspend fun endLiveVideo(page: FacebookPage, liveVideoId: String) {
        val params = Bundle().apply { putString("status", "LIVE_STOPPED") }
        request(liveVideoId, HttpMethod.POST, token = page.accessToken, extraParams = params)
    }

    private suspend fun request(
        path: String,
        method: HttpMethod,
        token: String,
        extraParams: Bundle = Bundle(),
    ): JSONObject = suspendCancellableCoroutine { cont ->
        val params = Bundle(extraParams).apply { putString("access_token", token) }
        val callback = GraphRequest.Callback { response ->
            val error = response.error
            if (error != null) {
                cont.resumeWithException(RuntimeException(error.errorMessage ?: "Graph API error"))
            } else {
                cont.resume(response.jsonObject ?: JSONObject())
            }
        }
        GraphRequest(null, path, params, method, callback).executeAsync()
    }
}
