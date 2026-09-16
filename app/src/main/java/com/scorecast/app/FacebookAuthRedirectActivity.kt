package com.scorecast.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Catches `scorecast://fb-auth?...` from the browser and hands it to [FacebookAuthManager].
 *
 * A dedicated trampoline rather than an intent-filter on MainActivity: MainActivity is the
 * landscape streaming screen with its own launch semantics and a live foreground service behind
 * it, and adding a BROWSABLE filter there would let any app or web page start it directly.
 *
 * Draws nothing and finishes immediately — the user sees the browser hand back to ScoreCast.
 */
class FacebookAuthRedirectActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consume(intent)
    }

    // The activity is singleTask, so a second redirect arrives here rather than in a new instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        intent?.data?.let { FacebookAuthManager.handleRedirect(this, it) }

        // Bring the existing ScoreCast task forward. CLEAR_TOP together with SINGLE_TOP delivers
        // to the running MainActivity instead of recreating it, which matters because a recreate
        // would drop the wizard state the user was midway through.
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}
