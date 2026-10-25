package app.podara.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import app.podara.util.Logger

/**
 * The app's own face: Noto Sans CJK SC (SIL OFL, shipped beside the files as
 * `resources/fonts/OFL.txt`), subset to the characters the app can actually
 * render, as three real weights.
 *
 * Before this, Compose-side text only ever named `FontFamily.SansSerif`
 * (`DesignTokens.TypeFamily.PageTitle`) and everything else took the platform
 * default — on Windows that resolves per glyph through system fallback, and a
 * bold request against a CJK fallback face is answered by *algorithmic* bold:
 * the whole stroke is smeared uniformly wider, which destroys the
 * horizontal-to-diagonal stroke contrast. DESIGN-CRAFT.md B1: "我们只有 400 和
 * 700 两档，而且 700 那档是假的。" This object is the fix — 400, 500 and 700
 * are three separate files, so `FontWeight.Medium`, `FontWeight(450)` and
 * `FontWeight.Bold` all land on designs the typeface was actually drawn in.
 *
 * The files are cut by `tools/font-subset/subset.py` from one shared
 * character set (`tools/font-subset/charset.txt`: every string literal in
 * `util/Strings.kt`, the 3500 common-character tables, ASCII printable, CJK
 * punctuation, the fullwidth forms and a small symbol floor). That pipeline
 * is the answer to the one real risk B1 names — a subset missing a glyph
 * falls back to the system font, which is worse than never changing face —
 * and `BundledFontCoverageTest` pins it: every `Strings.kt` literal character
 * and all of printable ASCII must display in all three weights. A new string
 * that introduces an uncovered character fails that test, and the fix is a
 * rerun of the pipeline, not a silent fallback.
 *
 * Any load failure degrades to [FontFamily.SansSerif] — the pre-B1 behaviour
 * — with [Logger.w] saying so. A missing resource must never take the UI
 * down; the font is an upgrade, not a dependency.
 */
internal object BundledFont {

    /**
     * The bundled family, or [FontFamily.SansSerif] if it could not load.
     *
     * Lazy so the ~1.8 MB of font bytes are only read when a composition
     * actually asks for text — plain-JVM tests that poke [DesignTokens] for
     * colours or spacing never pay for it.
     */
    val family: FontFamily by lazy { load() }

    /**
     * The shipped files and the weight each one carries. Three weights, no
     * variable font: a static 500 is what makes [FontWeight.Medium] honest on
     * Windows, where a variable axis would still be synthesised per weight.
     */
    private val files = listOf(
        FontWeight.Normal to "fonts/noto-sc-400.otf",
        FontWeight.Medium to "fonts/noto-sc-500.otf",
        FontWeight.Bold to "fonts/noto-sc-700.otf",
    )

    private fun load(): FontFamily = try {
        val loader = BundledFont::class.java.classLoader
        val fonts = files.map { (weight, resource) ->
            val bytes = loader.getResourceAsStream(resource)
                ?.use { stream -> stream.readBytes() }
                ?: error("resource not found on the classpath")
            // The desktop-only Font(identity, data, weight) overload
            // (androidx.compose.ui.text.platform, verified against
            // ui-text-desktop 1.9.0). The identity only names the font inside
            // Compose's typeface cache — it must differ per weight.
            Font("noto-sc-${weight.weight}", bytes, weight)
        }
        FontFamily(*fonts.toTypedArray())
    } catch (t: Throwable) {
        Logger.w("BundledFont", "bundled Noto Sans SC failed to load ($t); falling back to the system sans-serif")
        FontFamily.SansSerif
    }
}
