package app.podara.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

/**
 * Pins the bundled Noto Sans SC subsets to what the app actually renders
 * (DESIGN-CRAFT.md B1).
 *
 * B1's single real risk is a subset missing a glyph: a missing character
 * silently falls back to the system font, which looks *worse* than never
 * having changed face, and nothing at runtime would say so. So this test
 * re-derives the character obligation from the source of truth — the string
 * literals in `util/Strings.kt` themselves, parsed here rather than copied —
 * and checks every character against the three shipped files through AWT's
 * own font machinery (`Font.createFont` + `canDisplay`), the same rasterizer
 * family the platform composites through.
 *
 * The subsets were cut by `tools/font-subset/subset.py` from one shared
 * charset (`tools/font-subset/charset.txt`: Strings.kt literals + the 3500
 * common-character tables + an ASCII/CJK-punctuation/fullwidth floor). The
 * Strings.kt parse here is deliberately independent of that pipeline — if the
 * pipeline or the charset drifts away from what the app displays, this file
 * is the alarm.
 */
class BundledFontCoverageTest {

    // ── Locating the repo ──
    //
    // The test's working directory is whatever Gradle picked (the `desktop/`
    // project dir today, but never trusted), so the root is found by walking
    // up until the anchor — Strings.kt at its exact repo-relative path —
    // appears. That path names three directories no other checkout layout
    // produces, so a false positive match is not a realistic failure mode.

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, STRINGS_RELATIVE).isFile) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}: no $STRINGS_RELATIVE in any ancestor")
    }

    private fun fontsDir(): File = File(repoRoot(), FONTS_DIR_RELATIVE).also {
        assertTrue("missing font resource directory ${it.path}", it.isDirectory)
    }

    // ── Parsing Strings.kt ──

    /**
     * Every character of every string literal inside the `en` and `zh` mapOf
     * blocks, decoded through Kotlin's escape rules — the same obligation
     * `tools/font-subset/build_charset.py` extracts.
     *
     * ISO control characters are excluded: they never render as glyphs (a
     * `\n` in a string is line breaking, not typography), so they are not a
     * font coverage obligation.
     */
    private fun stringsLiteralChars(): Set<Char> {
        val source = File(repoRoot(), STRINGS_RELATIVE).readText(Charsets.UTF_8)
        val chars = LinkedHashSet<Char>()
        for (decl in listOf("en", "zh")) {
            for (literal in mapBlockLiterals(source, decl)) {
                for (c in literal) {
                    if (!c.isISOControl()) chars.add(c)
                }
            }
        }
        // A parse that silently returns nothing would make every coverage
        // assertion below vacuous, so pin the parse itself: the zh map alone
        // carries well over a hundred distinct Han characters.
        assertTrue(
            "Strings.kt parse collapsed (${chars.size} chars) — parser or file drifted",
            chars.size > 300
        )
        assertTrue("Strings.kt parse lost known zh characters", '播' in chars)
        return chars
    }

    /** The decoded string literals inside `private val <decl> = mapOf( … )`. */
    private fun mapBlockLiterals(source: String, decl: String): List<String> {
        val marker = "private val $decl = mapOf("
        val start = source.indexOf(marker)
        assertTrue("Strings.kt no longer declares `$marker`", start >= 0)
        var pos = start + marker.length
        var depth = 1 // mapOf's opening paren is consumed
        val literals = mutableListOf<String>()
        while (pos < source.length) {
            val c = source[pos]
            val next = source.getOrNull(pos + 1)
            when {
                c == '/' && next == '/' -> pos = source.indexOf('\n', pos).let { if (it < 0) source.length else it }
                c == '/' && next == '*' -> pos = source.indexOf("*/", pos).let { if (it < 0) source.length else it + 2 }
                c == '"' -> decodeLiteral(source, pos + 1).let { (text, end) ->
                    literals.add(text)
                    pos = end
                }
                c == '(' -> { depth++; pos++ }
                c == ')' -> {
                    depth--
                    pos++
                    if (depth == 0) return literals
                }
                else -> pos++
            }
        }
        error("mapOf block for '$decl' never closed")
    }

    /**
     * Decodes a Kotlin string literal, given the index just after the opening
     * quote. Returns the decoded text and the index just past the closing
     * quote.
     */
    private fun decodeLiteral(source: String, start: Int): Pair<String, Int> {
        val out = StringBuilder()
        var i = start
        while (i < source.length) {
            when (val c = source[i]) {
                '"' -> return out.toString() to (i + 1)
                '\\' -> {
                    i++
                    when (val e = source.getOrNull(i) ?: error("dangling escape in Strings.kt")) {
                        'u' -> {
                            out.append(source.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 5
                        }
                        else -> {
                            out.append(
                                when (e) {
                                    't' -> '\t'
                                    'b' -> '\b'
                                    'n' -> '\n'
                                    'r' -> '\r'
                                    else -> e // \" \' \\ \$ — the escaped char itself
                                }
                            )
                            i++
                        }
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        error("unterminated string literal in Strings.kt")
    }

    // ── The assertions ──

    /** The shipped files. */
    private val fontFiles = listOf("noto-sc-400.otf", "noto-sc-500.otf", "noto-sc-700.otf")

    /**
     * Existence, size budget (4 MB per weight — B1's own number; the shipped
     * cuts run ~1.8 MB), OpenType container, and that AWT loads them as the
     * face they claim to be.
     */
    @Test
    fun `bundled font files exist within budget and load as the bundled face`() {
        for (name in fontFiles) {
            val file = File(fontsDir(), name)
            assertTrue("missing bundled font ${file.path}", file.isFile)

            val size = file.length()
            assertTrue("$name is only $size bytes — not a real CJK subset", size > 1_000_000)
            assertTrue("$name is $size bytes, over the ${BUDGET_BYTES}B budget", size <= BUDGET_BYTES)

            val magic = file.inputStream().use { stream ->
                val buf = ByteArray(4)
                assertEquals("$name truncated", 4, stream.read(buf))
                buf
            }
            assertEquals("$name is not an OpenType/CFF font", "OTTO", String(magic, Charsets.US_ASCII))

            val awt = Font.createFont(Font.TRUETYPE_FONT, file)
            assertTrue(
                "$name loaded as family '${awt.family}', not the bundled face",
                awt.family.contains("Noto Sans CJK")
            )
        }
    }

    /**
     * B1's actual complaint, asserted directly: the 700 file must be a *drawn*
     * bold design, not the 400 in disguise. A synthetic bold raises ink by
     * smearing one outline; a real bold is a different drawing with
     * meaningfully more ink. Both faces render the same Han glyph (fixed
     * advance, same em box), so the ink their outlines deposit differs by the
     * weight design itself — measured here through the same AWT rasterizer the
     * coverage checks above use. A generous margin keeps it deterministic
     * against anti-aliasing jitter while still failing the substitution this
     * guards against (700 replaced by a copy of 400 puts the two ink counts
     * within a fraction of a percent).
     */
    @Test
    fun `the 700 file carries a drawn bold design, not a rerun of the 400`() {
        val regular = inkRatio("noto-sc-400.otf")
        val bold = inkRatio("noto-sc-700.otf")
        assertTrue(
            "700 ink ($bold) is not meaningfully heavier than 400 ink ($regular) — " +
                "the bold file looks like the regular one",
            bold > regular * 1.05
        )
    }

    /** Fraction of the raster that one Han glyph covers at 96 px. */
    private fun inkRatio(name: String): Double {
        val font = Font.createFont(Font.TRUETYPE_FONT, File(fontsDir(), name)).deriveFont(96f)
        val image = BufferedImage(200, 200, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        // A fresh raster is black; the ink count below treats dark-as-ink.
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.color = java.awt.Color.BLACK
        graphics.font = font
        // 汉 — a dense glyph both weights draw full-width, so the only thing
        // that can move the ink count is the weight design itself.
        graphics.drawString("汉", 40, 140)
        graphics.dispose()
        var dark = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.raster.getSample(x, y, 0) < 128) dark++
            }
        }
        return dark.toDouble() / (image.width * image.height)
    }

    /**
     * The core coverage pin: every character of every en/zh string literal in
     * `Strings.kt` must display in **all three** weights. One weight failing
     * means the weight axis lies about a character, which is the failure mode
     * B1 exists to kill.
     */
    @Test
    fun `every Strings_kt literal character displays in all three weights`() {
        val chars = stringsLiteralChars()
        for (name in fontFiles) {
            val font = Font.createFont(Font.TRUETYPE_FONT, File(fontsDir(), name))
            val sample = String(chars.toCharArray())
            val firstBad = font.canDisplayUpTo(sample)
            if (firstBad >= 0) {
                val missing = chars.filterNot(font::canDisplay)
                fail(
                    "$name cannot display ${missing.size} string-literal character(s): " +
                        missing.joinToString("") { "'$it' U+%04X".format(it.code) }
                )
            }
        }
    }

    /** Printable ASCII — the floor the UI leans on even in the English locale. */
    @Test
    fun `printable ascii displays in all three weights`() {
        val ascii = (0x20..0x7E).map { it.toChar() }
        for (name in fontFiles) {
            val font = Font.createFont(Font.TRUETYPE_FONT, File(fontsDir(), name))
            val missing = ascii.filterNot(font::canDisplay)
            assertTrue(
                "$name cannot display ASCII: ${missing.joinToString("")}",
                missing.isEmpty()
            )
        }
    }

    private companion object {
        const val STRINGS_RELATIVE =
            "desktop/src/desktopMain/kotlin/app/podara/util/Strings.kt"
        const val FONTS_DIR_RELATIVE =
            "desktop/src/desktopMain/resources/fonts"
        const val BUDGET_BYTES = 4L * 1024 * 1024
    }
}
