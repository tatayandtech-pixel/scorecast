package com.scorecast.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Brand semantics Material3's ColorScheme has no built-in role for. `danger` doesn't need an
 * entry here — it maps directly onto the existing `error` role. Values mirror the exact hex
 * tokens verified in the project's DESIGN.md (web-mirror's Sideline Console system) so both
 * platforms share one brand rather than diverging accidentally.
 */
object ScoreCastColors {
    val ok = Color(0xFF66BB6A)
    val pending = Color(0xFFFFB74D)

    val okLight = Color(0xFF256029)
    val pendingLight = Color(0xFF8F5300)
}

// Card's actual default containerColor in this Material3 version is surfaceContainerLow, not
// surface directly — verified on-device: leaving it unset left Card backgrounds on Material3's
// own stock baseline tint (a faint lavender-gray) instead of the brand's Card Surface. Every
// surfaceContainer* tier is pinned to the same Card Surface value so Card lands there regardless
// of which tier it defaults to, consistent with the Flat-Only two-tone (Void/Card Surface) system.
private val ScoreCastDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB39DDB),
    onPrimary = Color(0xFF1A1A1A),
    background = Color(0xFF111318),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF1C1F26),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFF9AA0AB),
    surfaceContainerLowest = Color(0xFF1C1F26),
    surfaceContainerLow = Color(0xFF1C1F26),
    surfaceContainer = Color(0xFF1C1F26),
    surfaceContainerHigh = Color(0xFF1C1F26),
    surfaceContainerHighest = Color(0xFF1C1F26),
    outline = Color(0xFF3A3F4A),
    error = Color(0xFFE57373),
    onError = Color(0xFF1A1A1A),
)

private val ScoreCastLightColorScheme = lightColorScheme(
    primary = Color(0xFF6C4FA8),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F4F6),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFECEEF2),
    onSurfaceVariant = Color(0xFF545B68),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    outline = Color(0xFFD0D3D9),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun ScoreCastStatusOk(): Color = if (isSystemInDarkTheme()) ScoreCastColors.ok else ScoreCastColors.okLight

@Composable
fun ScoreCastStatusPending(): Color = if (isSystemInDarkTheme()) ScoreCastColors.pending else ScoreCastColors.pendingLight

/**
 * Also establishes the root [Surface] the app never had — without one, Compose's
 * `LocalContentColor` never picks up `onBackground`/`onSurface` and every plain `Text()` falls
 * back to Material3's hardcoded ambient default (`Color.Black`), regardless of colorScheme. Pre-
 * existing gap, not introduced by this scheme; fixed here since it's the root wrapper.
 */
@Composable
fun ScoreCastTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) ScoreCastDarkColorScheme else ScoreCastLightColorScheme
    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = colorScheme.background) {
            content()
        }
    }
}
