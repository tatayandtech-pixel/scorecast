package com.scorecast.app

import android.os.Bundle
import com.facebook.AccessToken
import com.facebook.GraphRequest
import com.facebook.HttpMethod
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FacebookPage(val id: String, val name: String, val accessToken: String)

data class FacebookLiveVideo(val id: String, val secureStreamUrl: String)

/** Thin suspend wrapper around Facebook's Graph API (Phase 8). No appsecret_proof is sent — this
 *  app's own "Require app secret" dashboard setting is off, confirmed live against a real call. */
object FacebookGraphApi {

    /** The logged-in Facebook user's own display name, for showing "Signed in as X" in Settings. */
    suspend fun getCurrentUserName(userAccessToken: AccessToken): String {
        val json = request("me", HttpMethod.GET, userAccessToken = userAccessToken,
            extraParams = Bundle().apply { putString("fields", "name") })
        return json.getString("name")
    }

    suspend fun listPages(userAccessToken: AccessToken): List<FacebookPage> {
        val json = request("me/accounts", HttpMethod.GET, userAccessToken = userAccessToken)
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
            pageAccessToken = page.accessToken, extraParams = params,
        )
        return FacebookLiveVideo(json.getString("id"), json.getString("secure_stream_url"))
    }

    suspend fun endLiveVideo(page: FacebookPage, liveVideoId: String) {
        val params = Bundle().apply { putString("status", "LIVE_STOPPED") }
        request(liveVideoId, HttpMethod.POST, pageAccessToken = page.accessToken, extraParams = params)
    }

    private suspend fun request(
        path: String,
        method: HttpMethod,
        userAccessToken: AccessToken? = null,
        pageAccessToken: String? = null,
        extraParams: Bundle = Bundle(),
    ): JSONObject = suspendCancellableCoroutine { cont ->
        val params = Bundle(extraParams)
        if (pageAccessToken != null) params.putString("access_token", pageAccessToken)
        val callback = GraphRequest.Callback { response ->
            val error = response.error
            if (error != null) {
                cont.resumeWithException(RuntimeException(error.errorMessage ?: "Graph API error"))
            } else {
                cont.resume(response.jsonObject ?: JSONObject())
            }
        }
        GraphRequest(userAccessToken, path, params, method, callback).executeAsync()
    }
}
