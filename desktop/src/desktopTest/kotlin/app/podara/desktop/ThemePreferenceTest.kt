package app.podara.desktop

import androidx.compose.ui.graphics.Color
import app.podara.theme.AppleLightPalette
import app.podara.theme.SurfaceTokens
import app.podara.theme.ThemePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Theme selection.
 *
 * Both palettes were already complete, so the risk here is not a missing colour
 * but a resolution bug: a preference that resolves to the wrong scheme, or a
 * settings value that no longer maps.
 */
class ThemePreferenceTest {

    @Test
    fun testSystemFollowsTheDesktopSetting() {
        assertTrue(ThemePreference.System.resolvesToDark(isSystemDark = true))
        assertTrue(!ThemePreference.System.resolvesToDark(isSystemDark = false))
    }

    @Test
    fun testExplicitChoicesIgnoreTheDesktopSetting() {
        for (systemDark in listOf(true, false)) {
            assertTrue(!ThemePreference.Light.resolvesToDark(systemDark), "light must never be dark")
            assertTrue(ThemePreference.Dark.resolvesToDark(systemDark), "dark must always be dark")
        }
    }

    @Test
    fun testPersistedValuesRoundTrip() {
        for (preference in ThemePreference.entries) {
            assertEquals(preference, ThemePreference.fromSetting(preference.settingValue))
        }
    }

    @Test
    fun testUnknownPersistedValueFallsBackToSystem() {
        assertEquals(ThemePreference.System, ThemePreference.fromSetting("solarized"))
        assertEquals(ThemePreference.System, ThemePreference.fromSetting(""))
    }

    @Test
    fun testBothPalettesAreFullyDefined() {
        // A colour left as Color.Unspecified renders transparent, which is the
        // class of bug that made parts of the first light-scheme attempt
        // invisible. Translucent values are legitimate for hover washes and
        // fills layered over a parent, so only "specified" is asserted here.
        assertNoUnspecified(SurfaceTokens.Dark, "dark")
        assertNoUnspecified(SurfaceTokens.Light, "light")
    }

    @Test
    fun testBaseFillsAreOpaqueInBothSchemes() {
        // The fills that replace a page must themselves be opaque, otherwise
        // content shows through the card.
        for ((name, tokens) in listOf("dark" to SurfaceTokens.Dark, "light" to SurfaceTokens.Light)) {
            for ((field, color) in listOf(
                "cardFill" to tokens.cardFill,
                "menuFill" to tokens.menuFill,
                "dialogFill" to tokens.dialogFill,
                "buttonText" to tokens.buttonText
            )) {
                assertEquals(1f, color.alpha, "$name.$field must be opaque, was $color")
            }
        }
    }

    @Test
    fun testPalettesAreActuallyDistinct() {
        assertTrue(
            SurfaceTokens.Dark.cardFill != SurfaceTokens.Light.cardFill,
            "the two schemes must not share a card fill"
        )
        assertTrue(SurfaceTokens.Light.cardFill == AppleLightPalette.Surface)
        assertTrue(
            SurfaceTokens.Light.buttonGradient.toString().isNotEmpty(),
            "light button gradient should resolve"
        )
    }

    private fun assertNoUnspecified(tokens: SurfaceTokens, label: String) {
        val fields = listOf<Pair<String, Color>>(
            "menuFill" to tokens.menuFill,
            "menuFillHover" to tokens.menuFillHover,
            "menuBorder" to tokens.menuBorder,
            "menuText" to tokens.menuText,
            "pillFill" to tokens.pillFill,
            "pillText" to tokens.pillText,
            "cardFill" to tokens.cardFill,
            "cardBorder" to tokens.cardBorder,
            "buttonText" to tokens.buttonText,
            "dialogFill" to tokens.dialogFill,
            "dialogTitle" to tokens.dialogTitle,
            "rowTitle" to tokens.rowTitle,
            "badgeFill" to tokens.badgeFill,
            "shadow" to tokens.shadow,
            "scrim" to tokens.scrim,
            "sliderTrackInactive" to tokens.sliderTrackInactive,
            "sliderThumb" to tokens.sliderThumb
        )
        for ((field, color) in fields) {
            assertTrue(
                color != Color.Unspecified,
                "$label.$field is unspecified, which renders transparent"
            )
        }
    }
}
