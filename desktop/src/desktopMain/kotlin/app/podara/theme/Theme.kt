package app.podara.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class PodaraColors(
    val background: androidx.compose.ui.graphics.Color,
    val surface: androidx.compose.ui.graphics.Color,
    val elevated: androidx.compose.ui.graphics.Color,
    val border: androidx.compose.ui.graphics.Color,
    val divider: androidx.compose.ui.graphics.Color,
    val textPrimary: androidx.compose.ui.graphics.Color,
    /** Card and panel headings, one step under [textPrimary]. */
    val textStrong: androidx.compose.ui.graphics.Color,
    val textSecondary: androidx.compose.ui.graphics.Color,
    val textMuted: androidx.compose.ui.graphics.Color,
    /**
     * Glyph-only step below [textMuted]: counters, separators, the dot between
     * two metadata items. Intentionally under the AA contrast floor, so it must
     * never carry a sentence.
     */
    val textFaint: androidx.compose.ui.graphics.Color,
    val textDisabled: androidx.compose.ui.graphics.Color,
    val accent: androidx.compose.ui.graphics.Color,
    val accentHover: androidx.compose.ui.graphics.Color,
    val accentPressed: androidx.compose.ui.graphics.Color,
    val success: androidx.compose.ui.graphics.Color,
    val warning: androidx.compose.ui.graphics.Color,
    val danger: androidx.compose.ui.graphics.Color,
    val info: androidx.compose.ui.graphics.Color
)

private val DarkPodaraColors = PodaraColors(
    background = PodiumBackground,
    surface = PodiumSurface,
    elevated = PodiumElevated,
    border = PodiumBorder,
    divider = PodiumDivider,
    textPrimary = PodiumTextPrimary,
    textStrong = PodiumTextStrong,
    textSecondary = PodiumTextSecondary,
    textMuted = PodiumTextMuted,
    textFaint = PodiumTextFaint,
    textDisabled = PodiumTextDisabled,
    accent = PodiumAccent,
    accentHover = PodiumAccentHover,
    accentPressed = PodiumAccentPressed,
    success = PodiumSuccess,
    warning = PodiumWarning,
    danger = PodiumDanger,
    info = PodiumInfo
)

/**
 * Apple Podcasts web palette. Replaces the placeholder Material light scheme
 * (which was violet `0xFF6750A4` and unused in practice, since the app was
 * hardcoded to dark) with the neutral grays and red-pink accent that
 * podcasts.apple.com actually uses.
 */
private val LightPodaraColors = PodaraColors(
    background = AppleLightPalette.Background,
    surface = AppleLightPalette.Surface,
    elevated = AppleLightPalette.Elevated,
    border = AppleLightPalette.Border,
    divider = AppleLightPalette.Divider,
    textPrimary = AppleLightPalette.TextPrimary,
    textStrong = AppleLightPalette.TextStrong,
    textSecondary = AppleLightPalette.TextSecondary,
    textMuted = AppleLightPalette.TextMuted,
    textFaint = AppleLightPalette.TextFaint,
    textDisabled = AppleLightPalette.TextDisabled,
    accent = AppleLightPalette.Accent,
    accentHover = AppleLightPalette.AccentHover,
    accentPressed = AppleLightPalette.AccentPressed,
    success = AppleLightPalette.Success,
    warning = AppleLightPalette.Warning,
    danger = AppleLightPalette.Danger,
    info = AppleLightPalette.Info
)

val LocalPodaraColors = staticCompositionLocalOf { DarkPodaraColors }

/**
 * Scheme-dependent fills that `DesignTokens` cannot hold, because it is a
 * plain object and its colours resolve before a theme is known.
 */
val LocalSurfaceTokens = staticCompositionLocalOf { SurfaceTokens.Dark }

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

/**
 * The Material type styles re-pointed at the bundled face.
 *
 * `PodaraTheme` never passed a `typography`, so every `Text` without an
 * explicit family took Material's default — the system sans with per-glyph CJK
 * fallback, where `FontWeight.Bold` on Chinese is algorithmic synthetic bold
 * (DESIGN-CRAFT.md B1). Re-pointing only `DesignTokens.TypeFamily.PageTitle`
 * would have face-lifted the page titles and left the other ~90% of the app in
 * the fallback; the whole point of B1 is the weight axis, so every style rides
 * the bundled family and each weight resolves to a font actually drawn at that
 * weight.
 *
 * Each style keeps Material's own size, leading and tracking — only the family
 * moves. `BundledFont.family` degrades to `FontFamily.SansSerif` if the font
 * files are unreadable, so this cannot throw.
 */
private val BundledTypography: Typography = run {
    val base = Typography()
    val family = BundledFont.family
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family)
    )
}

/**
 * Provides the active palette to the whole app.
 *
 * [preference] wins when it is [ThemePreference.Light] or [ThemePreference.Dark];
 * [ThemePreference.System] defers to [isSystemDarkTheme]. Passing neither falls
 * back to the preference alone, which keeps the parameter optional for tests and
 * previews.
 */
@Composable
fun PodaraTheme(
    preference: ThemePreference = ThemePreference.Light,
    isSystemDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val darkTheme = preference.resolvesToDark(isSystemDarkTheme)
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val podaraColors = if (darkTheme) DarkPodaraColors else LightPodaraColors
    val surfaceTokens = if (darkTheme) SurfaceTokens.Dark else SurfaceTokens.Light

    CompositionLocalProvider(
        LocalPodaraColors provides podaraColors,
        LocalSurfaceTokens provides surfaceTokens,
        LocalThemePreference provides preference
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BundledTypography,
            content = content
        )
    }
}

/**
 * The active preference, so a Settings control can render its own selected state
 * without re-reading Settings.
 */
val LocalThemePreference = staticCompositionLocalOf { ThemePreference.Light }

/** Reads the OS desktop appearance. Java reports this via `Desktop.isDarkTheme` on some look and feels. */
private fun isSystemInDarkTheme(): Boolean = try {
    val desktopClass = Class.forName("java.awt.Desktop")
    val isSupported = desktopClass.getMethod("isDesktopSupported").invoke(null) as Boolean
    if (!isSupported) {
        false
    } else {
        val desktop = desktopClass.getMethod("getDesktop").invoke(null)
        // Present on Windows and macOS; absent elsewhere, which falls back to light.
        runCatching {
            desktopClass.getMethod("isDarkTheme").invoke(desktop) as Boolean
        }.getOrDefault(false)
    }
} catch (_: Throwable) {
    false
}

object PodaraTheme {
    val colors: PodaraColors
        @Composable
        get() = LocalPodaraColors.current

    /**
     * Scheme-dependent surface fills. Prefer these over the colour constants in
     * [DesignTokens] for anything that is a translucent wash, a hairline
     * border, or a shadow — those read as white-on-white or dirt-on-paper
     * under the wrong scheme.
     */
    val surfaces: SurfaceTokens
        @Composable
        get() = LocalSurfaceTokens.current
}
