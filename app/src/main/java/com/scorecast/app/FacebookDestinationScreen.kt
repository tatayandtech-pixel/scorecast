package com.scorecast.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Phase 8 — Facebook Graph API login flow: log in, pick a Page, create the live video, and fill
 *  [StreamTarget] with the Page's own one-time ingest URL. [onUseManual] falls back to manual paste. */
@Composable
fun FacebookDestinationScreen(
    target: StreamTarget,
    onTargetChange: (StreamTarget) -> Unit,
    onUseManual: () -> Unit,
) {
    val activity = LocalContext.current as ComponentActivity
    val authState by FacebookAuthManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    var pages by remember { mutableStateOf<List<FacebookPage>>(emptyList()) }
    var selectedPage by remember { mutableStateOf<FacebookPage?>(null) }
    var loadingPages by remember { mutableStateOf(false) }
    var creatingLive by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authState) {
        val logged = authState as? FacebookAuthManager.State.LoggedIn ?: return@LaunchedEffect
        loadingPages = true
        errorMessage = null
        runCatching { FacebookGraphApi.listPages(logged.accessToken) }
            .onSuccess { pages = it }
            .onFailure { errorMessage = it.message }
        loadingPages = false
    }

    Column {
        when (authState) {
            is FacebookAuthManager.State.LoggedOut, is FacebookAuthManager.State.Error -> {
                Text("Connect the Facebook Page you'll stream to.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { scope.launch { FacebookAuthManager.login(activity) } }) {
                    Text("Log in with Facebook")
                }
            }

            is FacebookAuthManager.State.LoggingIn -> {
                CircularProgressIndicator()
            }

            is FacebookAuthManager.State.LoggedIn -> {
                if (loadingPages) {
                    CircularProgressIndicator()
                } else {
                    Text("Choose a Page", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    pages.forEach { page ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPage = page
                                    creatingLive = true
                                    errorMessage = null
                                    scope.launch {
                                        runCatching { FacebookGraphApi.createLiveVideo(page, "ScoreCast live") }
                                            .onSuccess { live ->
                                                onTargetChange(target.copy(ingestUrl = live.secureStreamUrl, streamKey = ""))
                                            }
                                            .onFailure { errorMessage = it.message }
                                        creatingLive = false
                                    }
                                },
                        ) {
                            RadioButton(selected = selectedPage == page, onClick = null)
                            Text(page.name)
                        }
                    }
                    if (creatingLive) {
                        Spacer(Modifier.height(6.dp))
                        CircularProgressIndicator()
                    }
                }
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onUseManual) {
            Text("Enter ingest URL manually instead")
        }
    }
}
