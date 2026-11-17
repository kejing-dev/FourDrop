package io.embodit.fourdrop.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.fourdrop.domain.Language
import io.embodit.fourdrop.platform.Graph
import java.util.Locale

/** Light oak rack, vermilion / cream discs, the app's amber (icon #E0A040) darkened for white text; roles measured on the glass (D13). */
object FDColors {
    /** Buttons: deep amber-brown so white labels stay >= 4.5:1. */
    val Primary = Color(0xFF8A5414)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFF6E3C2)
    val Secondary = Color(0xFF7A5A2E)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    val Amber = Color(0xFFE0A040)

    val CardFace = Color(0xFFFBF6EC)
    val Oak = Color(0xFFC49C68)
    val OakDark = Color(0xFFA88255)
    val Red = Color(0xFFC8412E)
    val Yellow = Color(0xFFEBD9A8)
    val YellowEdge = Color(0xFF9C7A2A)
    val Hole = Color(0xFF4A3A2A)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun FourDropTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = FDColors.OnPrimary,
        labelPrimary = FDColors.Ink,
        labelSecondary = FDColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = FDColors.Error,
        alert = FDColors.Amber,
        passable = FDColors.Secondary,
        interaction = FDColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
