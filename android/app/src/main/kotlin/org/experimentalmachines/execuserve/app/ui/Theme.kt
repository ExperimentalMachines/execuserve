package org.experimentalmachines.execuserve.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.host.Mood
import org.experimentalmachines.execuserve.host.ThemeMode

/*
 * The palette comes from the family ExecuServe belongs to. PyTorch's paper and ink make the
 * neutral ground, untinted, so light and dark mode are the same design at two brightnesses;
 * PyTorch's ember (#EE4C2C) is the brand: the mark, the Start button, a selection. A state
 * shows as a light and a coloured word on a neutral panel, never as a panel painted over:
 * green is serving, blue working, amber needs a look, and failure is crimson, kept off
 * ember's hue so the brand never reads as an error.
 *
 * Every pair is checked by tools/design/contrast.py against WCAG 2 AA and APCA (body and
 * labels Lc 75, marks Lc 30); tools/design/palette.py holds the same values. Change both,
 * then run the audit.
 */
private val Light = lightColorScheme(
    primary = Color(0xFFB83012),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE4DE),
    onPrimaryContainer = Color(0xFF5C1405),
    secondary = Color(0xFF5B5F67),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECEDF1),
    onSecondaryContainer = Color(0xFF262626),
    tertiary = Color(0xFF855400),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8C2),
    onTertiaryContainer = Color(0xFF3D2800),
    error = Color(0xFFB01652),
    onError = Color.White,
    errorContainer = Color(0xFFFBDCE7),
    onErrorContainer = Color(0xFF5E0A2B),
    background = Color(0xFFF3F4F7),
    onBackground = Color(0xFF262626),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF262626),
    surfaceVariant = Color(0xFFECEDF1),
    onSurfaceVariant = Color(0xFF5B5F67),
    surfaceContainer = Color(0xFFF3F4F7),
    surfaceContainerHigh = Color(0xFFECEDF1),
    surfaceContainerLow = Color(0xFFFFFFFF),
    outline = Color(0xFF868D9A),
    outlineVariant = Color(0xFFE2E4E9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFAC6BB),
    onPrimary = Color(0xFF2B0A03),
    primaryContainer = Color(0xFF6A2413),
    onPrimaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFFCCCED2),
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF2A2A2D),
    onSecondaryContainer = Color(0xFFECECEC),
    tertiary = Color(0xFFFFC45E),
    onTertiary = Color(0xFF2A1B00),
    tertiaryContainer = Color(0xFF4A3300),
    onTertiaryContainer = Color(0xFFFFE3B0),
    error = Color(0xFFF7BFD5),
    onError = Color(0xFF3E0619),
    errorContainer = Color(0xFF5A1330),
    onErrorContainer = Color(0xFFFFD9E6),
    background = Color(0xFF121212),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF2A2A2D),
    onSurfaceVariant = Color(0xFFCCCED2),
    surfaceContainer = Color(0xFF242427),
    surfaceContainerHigh = Color(0xFF2A2A2D),
    surfaceContainerLow = Color(0xFF1C1C1E),
    outline = Color(0xFF747A84),
    outlineVariant = Color(0xFF3A3A40),
)

/** The state colours, which Material's scheme has no slots for. */
private data class States(val good: Tone, val working: Tone, val attention: Tone, val failed: Tone)

private val LightStates = States(
    good = Tone(Color(0xFF176C3C), Color(0xFFD7F2E1), Color(0xFF0B3D20)),
    working = Tone(Color(0xFF2B57BF), Color(0xFFDDE5FA), Color(0xFF122B66)),
    attention = Tone(Color(0xFF855400), Color(0xFFFFE8C2), Color(0xFF3D2800)),
    failed = Tone(Color(0xFFB01652), Color(0xFFFBDCE7), Color(0xFF5E0A2B)),
)

private val DarkStates = States(
    good = Tone(Color(0xFF79E3A7), Color(0xFF123D26), Color(0xFFB7F0CD)),
    working = Tone(Color(0xFFC0CEF1), Color(0xFF1D2F5C), Color(0xFFDCE5FF)),
    attention = Tone(Color(0xFFFFC45E), Color(0xFF4A3300), Color(0xFFFFE3B0)),
    failed = Tone(Color(0xFFF7BFD5), Color(0xFF5A1330), Color(0xFFFFD9E6)),
)

/** A state's colour, and the tint behind it where one is used (pills, warnings). */
@Immutable
data class Tone(val color: Color, val container: Color, val onContainer: Color)

@Immutable
data class Tones(val good: Tone, val working: Tone, val attention: Tone, val failed: Tone, val idle: Tone) {
    fun of(mood: Mood): Tone = when (mood) {
        Mood.GOOD -> good
        Mood.WORKING -> working
        Mood.ATTENTION -> attention
        Mood.FAILED -> failed
        Mood.IDLE -> idle
    }
}

private fun tonesOf(dark: Boolean): Tones {
    val states = if (dark) DarkStates else LightStates
    val scheme = if (dark) Dark else Light
    return Tones(
        good = states.good,
        working = states.working,
        attention = states.attention,
        failed = states.failed,
        idle = Tone(scheme.onSurfaceVariant, scheme.surfaceVariant, scheme.onSurface),
    )
}

val LocalTones = staticCompositionLocalOf { tonesOf(dark = false) }

/*
 * Type: IBM Plex, one superfamily. Plex Sans for everything read, measurements included, with
 * tabular digits so figures line up and update in place; Plex Mono (the face pytorch.org sets
 * code in) only for what is copied or compared character by character: addresses, keys,
 * commands, ids. Both ship with the app, cut to Latin, Greek and Cyrillic; any other script
 * falls back to the system's fonts glyph by glyph. Nothing is smaller than 12 sp.
 */
@OptIn(ExperimentalTextApi::class)
private fun plexSans(weight: Int) = Font(R.font.ibm_plex_sans, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val PlexSans = FontFamily(plexSans(REGULAR), plexSans(MEDIUM), plexSans(SEMIBOLD))

private val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private const val REGULAR = 400
private const val MEDIUM = 500
private const val SEMIBOLD = 600
private const val TABULAR = "tnum"

private val Type = Typography().let { base ->
    fun TextStyle.sans(weight: FontWeight = FontWeight.Normal) = copy(fontFamily = PlexSans, fontWeight = weight, fontFeatureSettings = TABULAR)
    base.copy(
        headlineSmall = base.headlineSmall.sans(FontWeight.SemiBold).copy(fontSize = 24.sp, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.sans(FontWeight.SemiBold),
        titleMedium = base.titleMedium.sans(FontWeight.SemiBold).copy(fontSize = 17.sp),
        titleSmall = base.titleSmall.sans(FontWeight.SemiBold).copy(fontSize = 15.sp),
        bodyLarge = base.bodyLarge.sans(),
        bodyMedium = base.bodyMedium.sans(),
        bodySmall = base.bodySmall.sans(),
        labelLarge = base.labelLarge.sans(FontWeight.Medium),
        labelMedium = base.labelMedium.sans(FontWeight.Medium).copy(fontSize = 12.sp),
        labelSmall = base.labelSmall.sans(FontWeight.SemiBold).copy(fontSize = 12.sp),
    )
}

/** Addresses, keys, commands and ids: what a person copies or compares character by character. */
val Mono = TextStyle(fontFamily = PlexMono, fontSize = 13.sp, lineHeight = 19.sp)

/** A figure's value: a reading, large, with tabular digits so it does not jitter as it updates. */
val FigureStyle = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, fontFeatureSettings = TABULAR)

/** The spacing and sizes every screen uses, in one place. */
object Dimens {
    /** Page edge, and the inside of a panel. */
    val gutter = 16.dp

    /** Between panels. */
    val gap = 12.dp

    /** Between rows inside a panel. */
    val row = 8.dp
    val corner = 16.dp

    /** The smallest thing a finger is asked to hit. */
    val touch = 48.dp

    /** From this safe window width the navigation moves to a rail. */
    val wide = 600.dp

    /** Minimum width of each panel column, increased with the font scale. */
    val minColumn = 320.dp

    /** The widest a single column of panels reads well at. */
    val column = 760.dp

    /** The widest the two-column Server tab grows. */
    val columns = 1200.dp
}

/** Whether the app is drawn dark: its own answer, which can differ from the system's. */
val LocalDark = staticCompositionLocalOf { false }

@Composable
fun ExecuServeTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) Dark else Light
    // Edge to edge: the bars are transparent, so their icons must follow the app's theme,
    // which can differ from the system's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalTones provides tonesOf(dark), LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = Type, content = content)
    }
}
