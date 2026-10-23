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
    fun testLightSchemeShadowsStaySubtle() {
        // Regression test for the dark ring around the active sidebar item.
        // Setting every light-scheme shadow to Color.Black was intended to
        // restore Compose's elevation-derived alpha, but on a white page a
        // 5-20 dp full-black shadow renders as a hard dark halo rather than
        // depth. Light shadows must be explicitly low-alpha instead.
        for ((name, tokens) in listOf("light" to SurfaceTokens.Light)) {
            // Used to assert `== Color.Transparent`, which was the fix for the
            // hard dark halo around the active nav row. The active row is now a
            // white pill on a near-white shell, so it needs *some* lift or it
            // reads as flat — the invariant that actually matters is that the
            // lift stays in the subtle band, not that it is zero.
            assertTrue(
                tokens.navActiveShadow.alpha <= 0.2f,
                "$name: the active nav shadow alpha ${tokens.navActiveShadow.alpha} will read as a dark ring on a light page"
            )
            for ((field, color) in listOf(
                "shadow" to tokens.shadow,
                "pillShadow" to tokens.pillShadow,
                "buttonShadow" to tokens.buttonShadow,
                "rowCoverShadow" to tokens.rowCoverShadow
            )) {
                assertTrue(
                    color != Color.Black,
                    "$name.$field is opaque black; it will read as a dark ring on a light page"
                )
                assertTrue(
                    color.alpha <= 0.2f,
                    "$name.$field alpha ${color.alpha} is too strong for a light surface"
                )
            }
        }
    }

    @Test
    fun testDarkSchemeKeepsItsShadowDepth() {
        // The dark palette relies on real elevation, so it must stay heavy.
        assertTrue(SurfaceTokens.Dark.dialogShadow.alpha >= 0.3f)
        assertTrue(SurfaceTokens.Dark.shadow.alpha >= 0.2f)
        assertTrue(SurfaceTokens.Dark.navActiveShadow.alpha > 0f)
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
        // The primary button is a flat fill in both schemes now. It used to be a
        // four-stop metal gradient plus a white sheen; this pins the replacement
        // so the gradient cannot come back as "just a little depth".
        assertTrue(
            SurfaceTokens.Dark.buttonFill != SurfaceTokens.Light.buttonFill,
            "the two schemes must not share a button fill"
        )
    }

    @Test
    fun testPrimaryButtonIsNotASphere() {
        // The play button, the seek thumb and every primary button shared one
        // recipe: a vertical gradient, a white sheen over the top half, a 1dp
        // white border and an elevation shadow. On a near-black page that is a
        // lit copper dome, and it made the button heavier than its own icon.
        // A flat fill has no vertical variation, so a scheme that reintroduces
        // one fails here.
        for ((name, tokens) in listOf("dark" to SurfaceTokens.Dark, "light" to SurfaceTokens.Light)) {
            assertEquals(
                1f,
                tokens.buttonFill.alpha,
                "$name.buttonFill must be opaque; a translucent fill over a card reads as a tint, not a button"
            )
            assertEquals(
                tokens.buttonFill,
                tokens.buttonFill.copy(red = tokens.buttonFill.red, green = tokens.buttonFill.green, blue = tokens.buttonFill.blue),
                "$name.buttonFill should be one colour"
            )
        }
    }

    @Test
    fun testSliderThumbIsNotADonut() {
        // The full player's thumb used to be a 15dp disc with a 2dp accent
        // border, a 2dp inset and a white core — at track height it read as a
        // shrunken copy of the play button. The ring is now a hairline that
        // exists only to keep a solid dot visible on the filled half of the
        // track, so it must be visibly darker/lighter than the dot, not the
        // accent colour in the dark scheme.
        assertTrue(
            SurfaceTokens.Dark.sliderThumbRing != SurfaceTokens.Dark.sliderThumb,
            "dark thumb ring must differ from the thumb, or the dot has no edge"
        )
        assertTrue(
            SurfaceTokens.Light.sliderThumbRing != SurfaceTokens.Light.sliderThumb,
            "light thumb ring must differ from the thumb"
        )
        assertEquals(
            SurfaceTokens.Light.sliderThumb,
            Color(0xFFFFFFFF),
            "the light thumb should be white on a red track, not red on red"
        )
        // The ring is a hairline that separates the dot from the track, so it
        // must not be the accent: an accent ring around a white core is a
        // donut, which is the shape this replaced.
        assertTrue(
            SurfaceTokens.Light.sliderThumbRing != AppleLightPalette.Accent,
            "the light thumb ring must not be the accent colour"
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
            "sliderThumb" to tokens.sliderThumb,
            "sliderThumbRing" to tokens.sliderThumbRing,
            "buttonFill" to tokens.buttonFill
        )
        for ((field, color) in fields) {
            assertTrue(
                color != Color.Unspecified,
                "$label.$field is unspecified, which renders transparent"
            )
        }
    }
}
