package app.podara.desktop

import app.podara.theme.AppleLightPalette
import app.podara.theme.PodiumBackground
import app.podara.theme.PodiumTextDisabled
import app.podara.theme.PodiumTextFaint
import app.podara.theme.PodiumTextMuted
import app.podara.theme.PodiumTextPrimary
import app.podara.theme.PodiumTextSecondary
import app.podara.theme.PodiumTextStrong
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grayscale ladder has to clear a contrast floor on the background it is
 * actually drawn on — and the ladder itself never changes, so the only way this
 * breaks is a background changing underneath it.
 *
 * That is what happened. The ratios recorded next to the colour constants were
 * measured against the page and one sheet colour, and they were right when
 * written. The shell gradient then put a second, *darker* background under the
 * sidebar, and the comment still promised everything cleared 4.5:1.
 *
 * So these numbers are computed here rather than copied from a comment. The
 * floor is per role, not per step: 4.5:1 (WCAG 1.4.3) for anything that is
 * text, 3:1 (1.4.11) for a step that is only ever allowed as a graphic —
 * which is exactly what [PodaraTextMuted] is on the shell, where it tints nav
 * icons and no label. [PodiumTextFaint] is glyph-only by contract.
 */
class ContrastFloorTest {

    /** WCAG 1.4.3: text. */
    private val textFloor = 4.5

    /** WCAG 1.4.3, large text. */
    private val largeTextFloor = 3.0

    /** WCAG 1.4.11: non-text — icons, separators, counters. */
    private val graphicFloor = 3.0

    private fun luminance(c: Color): Double {
        fun channel(v: Float) = if (v <= 0.03928f) v / 12.92 else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun ratio(foreground: Color, background: Color): Double {
        val (hi, lo) = listOf(luminance(foreground), luminance(background)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertFloor(
        label: String,
        foreground: Color,
        background: Color,
        floor: Double,
        role: String
    ) {
        val measured = ratio(foreground, background)
        assertTrue(
            "$label on $background is ${"%.2f".format(measured)}:1, under the $floor:1 $role floor",
            measured >= floor
        )
    }

    @Test
    fun lightTextStepsClearAaOnPaper() {
        val paper = AppleLightPalette.Background
        assertFloor("light Primary", AppleLightPalette.TextPrimary, paper, textFloor, "text")
        assertFloor("light Strong", AppleLightPalette.TextStrong, paper, textFloor, "text")
        assertFloor("light Secondary", AppleLightPalette.TextSecondary, paper, textFloor, "text")
        assertFloor("light Muted", AppleLightPalette.TextMuted, paper, textFloor, "text")
    }

    @Test
    fun darkTextStepsClearAaOnPage() {
        assertFloor("dark Primary", PodiumTextPrimary, PodiumBackground, textFloor, "text")
        assertFloor("dark Strong", PodiumTextStrong, PodiumBackground, textFloor, "text")
        assertFloor("dark Secondary", PodiumTextSecondary, PodiumBackground, textFloor, "text")
        assertFloor("dark Muted", PodiumTextMuted, PodiumBackground, textFloor, "text")
    }

    @Test
    fun everyTextStepClearsAaAcrossTheShellGradient() {
        // The shell is not one colour: it is three stops, and the top one is the
        // darkest, so a step that passes on the mid tone can still fail there.
        // These are the stops `SurfaceTokens.shellGradient` actually paints.
        val shellStops = listOf(
            "light top" to Color(0xFFEDF0E1),
            "light mid" to Color(0xFFF3F4F0),
            "light bottom" to Color(0xFFF6F6F5),
            "dark top" to Color(0xFF0D1015),
            "dark mid" to Color(0xFF0A0C10),
            "dark bottom" to PodiumBackground
        )
        val lightText = listOf(
            "Primary" to AppleLightPalette.TextPrimary,
            "Strong" to AppleLightPalette.TextStrong,
            "Secondary" to AppleLightPalette.TextSecondary
        )
        val darkText = listOf(
            "Primary" to PodiumTextPrimary,
            "Strong" to PodiumTextStrong,
            "Secondary" to PodiumTextSecondary
        )
        for ((name, stop) in shellStops) {
            val steps = if (name.startsWith("light")) lightText else darkText
            for ((stepName, color) in steps) {
                assertFloor("$name $stepName", color, stop, textFloor, "text")
            }
        }
    }

    @Test
    fun mutedIsLegalOnTheShellOnlyAsAGraphic() {
        // The rule this encodes: the sidebar may go down to Secondary for text,
        // and to Muted only for an icon tint. Measured against the shell's three
        // stops, Muted is 4.38 / 4.59 / 4.69 — so it clears the text floor on the
        // two lighter stops and fails on the darkest one, which is where the
        // sidebar actually sits. A blanket "< 4.5" would be a lie about the whole
        // gradient; the honest version is that the top stop is the binding one.
        val shellStops = listOf(Color(0xFFEDF0E1), Color(0xFFF3F4F0), Color(0xFFF6F6F5))
        for (stop in shellStops) {
            val onShell = ratio(AppleLightPalette.TextMuted, stop)
            assertTrue(
                "Muted on $stop is ${"%.2f".format(onShell)}:1; it must at least clear the graphic floor, since nav icons are tinted with it",
                onShell >= graphicFloor
            )
        }
        val darkest = shellStops.minOf { ratio(AppleLightPalette.TextMuted, it) }
        assertTrue(
            "Muted on the darkest shell stop reached ${"%.2f".format(darkest)}:1, at or above the text floor - the shell has been lightened and the icon-only restriction on Muted can be relaxed. Update this test and the sidebar rule together.",
            darkest < textFloor
        )
    }

    @Test
    fun faintStaysUnderTheTextFloorAndClearsGraphicsWhereUsed() {
        // textFaint is glyph-only by contract: counters, separators, the dot
        // between two metadata items. On paper it clears the graphic floor and
        // stays below the text floor, which is what "never carry a sentence"
        // means in numbers. It must also stay dimmer than the step above it, so
        // the ladder has no flat pair in it.
        assertFloor("light Faint", AppleLightPalette.TextFaint, AppleLightPalette.Background, graphicFloor, "glyph-only")
        assertFloor("dark Faint", PodiumTextFaint, PodiumBackground, graphicFloor, "glyph-only")
        assertTrue(
            "light Faint is not dimmer than Muted; the ladder has a flat pair",
            ratio(AppleLightPalette.TextFaint, AppleLightPalette.Background) <
                ratio(AppleLightPalette.TextMuted, AppleLightPalette.Background)
        )
        assertTrue(
            "dark Faint is not dimmer than Muted; the ladder has a flat pair",
            ratio(PodiumTextFaint, PodiumBackground) < ratio(PodiumTextMuted, PodiumBackground)
        )
    }

    @Test
    fun ladderStepsAreOrderedOnBothSchemes() {
        // Every step has to be a real step, measured against the darkest place it
        // can be drawn: the top shell stop. Two tokens with one visual value is
        // how the earlier muted/secondary attempt ended up.
        val light = listOf(
            AppleLightPalette.TextPrimary,
            AppleLightPalette.TextStrong,
            AppleLightPalette.TextSecondary,
            AppleLightPalette.TextMuted,
            AppleLightPalette.TextFaint,
            AppleLightPalette.TextDisabled
        )
        val dark = listOf(
            PodiumTextPrimary,
            PodiumTextStrong,
            PodiumTextSecondary,
            PodiumTextMuted,
            PodiumTextFaint,
            PodiumTextDisabled
        )
        assertContrastFallsMonotonically("light", light, Color(0xFFEDF0E1))
        assertContrastFallsMonotonically("dark", dark, Color(0xFF0D1015))
    }

    /** Reading dimmer down the ladder means the contrast ratio must fall at every step. */
    private fun assertContrastFallsMonotonically(label: String, steps: List<Color>, on: Color) {
        for (i in 0 until steps.size - 1) {
            val a = ratio(steps[i], on)
            val b = ratio(steps[i + 1], on)
            assertTrue(
                "$label: step ${i + 1} (${"%.2f".format(a)}:1) is not above step ${i + 2} (${"%.2f".format(b)}:1) on $on",
                a > b
            )
        }
    }

    @Test
    fun largeTextFloorIsWhatThePageTitleClears() {
        // Documents the ceiling too: the display-size page title in Secondary
        // would still be legal (large text is 3:1), which is why the ladder, not
        // the floor, is what separates a title from its metadata on this page.
        assertTrue(
            "Secondary at large size should clear 3:1 on the darkest shell stop",
            ratio(AppleLightPalette.TextSecondary, Color(0xFFEDF0E1)) >= largeTextFloor
        )
    }
}
