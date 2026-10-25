package app.podara.desktop

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Pins the DESIGN-CRAFT.md A1 migration: every icon in `desktopMain` draws from
 * the Outlined family, except the whitelisted play/pause controls that stay
 * filled on purpose.
 *
 * A1's mechanism is ink weight — a filled glyph deposits 3-4x the ink of the
 * outlined drawing at the same size, which is why the UI read heavier than the
 * text it served. The migration is a line-by-line discipline, and nothing stops
 * a new call site (or a revert hiding in a merge) from reintroducing a filled
 * glyph, so this test re-derives the obligation from the source of truth: a
 * plain-text scan of every `.kt` file under `desktopMain`, the same grep the
 * audit used, with the sanctioned exceptions pinned by file + glyph.
 *
 * The whitelist is transport-only. DESIGN-CRAFT.md A1's own risk note keeps
 * PlayArrow / Pause filled: they are small-area shapes whose outlined versions
 * read even smaller. The same argument covers the download rows' pause/resume
 * buttons — they are the identical glyphs acting on the *transfer*, not new
 * shapes. Every other glyph has an Outlined variant on the material-icons
 * classpath (verified during the migration), so the "no variant exists"
 * escape hatch of the migration rule stayed empty and is not modelled here.
 *
 * The scan covers the whole source set, not just one agent's files: App.kt,
 * DiscoverScreen.kt and every future screen age under the same gate. A prose
 * mention of a glyph in a comment counts as a reference and shares that
 * glyph's entry (PlayerUI.kt's PlayPauseGlyph KDoc explains the filled
 * triangle in `Icons.Default.PlayArrow` terms).
 */
class IconMigrationCoverageTest {

    // ── Locating the repo ──
    //
    // The test's working directory is whatever Gradle picked, so the root is
    // found by walking up until Strings.kt appears at its exact repo-relative
    // path — the same anchor BundledFontCoverageTest uses.

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, STRINGS_RELATIVE).isFile) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}: no $STRINGS_RELATIVE in any ancestor")
    }

    /** The package root every whitelist key is relative to. */
    private fun podaraSourceDir(): File {
        val dir = File(repoRoot(), PODARA_SOURCE_RELATIVE)
        assertTrue("podara source dir missing at ${dir.path}", dir.isDirectory)
        return dir
    }

    // ── The whitelist ──

    /**
     * The only allowed filled references, by file and glyph. Transport and
     * transfer play/pause controls only — see the class KDoc for why.
     */
    private val filledWhitelist: Map<String, Set<String>> = mapOf(
        "component/ContinueListeningCard.kt" to setOf("PlayArrow"),
        "player/PlayerUI.kt" to setOf("Pause", "PlayArrow"),
        "screen/PodcastDetailHeader.kt" to setOf("PlayArrow"),
        "screen/DownloadsScreen.kt" to setOf("Pause", "PlayArrow"),
        "screen/DiscoverScreen.kt" to setOf("PlayArrow"),
    )

    /** Filled families the migration bans: `Icons.Default.X` / `Icons.AutoMirrored.Filled.X`. */
    private val filledReference = Regex("""Icons\.(?:Default|AutoMirrored\.Filled)\.(\w+)""")

    /** The family everything must use after A1. */
    private val outlinedReference = Regex("""Icons\.(?:AutoMirrored\.)?Outlined\.(\w+)""")

    private fun scannedSources(): List<Pair<String, String>> {
        val root = podaraSourceDir()
        val sources = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText(Charsets.UTF_8) }
            .toList()
        // A walk that silently returns nothing would make every coverage
        // assertion below vacuous, so pin the parse itself.
        assertTrue(
            "source walk collapsed (${sources.size} files) — scanner or layout drifted",
            sources.size > 40
        )
        return sources
    }

    // ── The assertions ──

    /**
     * The A1 pin: no filled glyph reference outside the whitelist. Failure
     * output names file + glyph so the fix is a one-line lookup.
     */
    @Test
    fun `filled glyphs appear only on the whitelist`() {
        val violations = mutableListOf<String>()
        for ((relative, text) in scannedSources()) {
            for (match in filledReference.findAll(text)) {
                val glyph = match.groupValues[1]
                val allowed = filledWhitelist[relative]
                if (allowed == null || glyph !in allowed) {
                    violations.add(
                        "$relative: filled '$glyph' (Icons.Default / Icons.AutoMirrored.Filled) — " +
                            "use Icons.(AutoMirrored.)Outlined.$glyph or whitelist it as transport"
                    )
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "A1 leak: ${violations.size} filled icon reference(s) outside the whitelist:\n" +
                    violations.joinToString("\n")
            )
        }
    }

    /**
     * The whitelist must not rot in either direction: every entry still has a
     * live filled reference in its file (a satisfied entry is deleted, not
     * kept as noise), and the outlined family still dominates the source set —
     * a count floor catches a scanner that stopped matching altogether.
     */
    @Test
    fun `whitelist entries stay live and outlined remains the house family`() {
        val sources = scannedSources()
        val textByFile = sources.toMap()

        val stale = mutableListOf<String>()
        for ((relative, glyphs) in filledWhitelist) {
            val text = textByFile[relative]
            if (text == null) {
                stale.add("$relative is whitelisted but the file is gone")
                continue
            }
            val liveGlyphs = filledReference.findAll(text).map { it.groupValues[1] }.toSet()
            for (glyph in glyphs) {
                if (glyph !in liveGlyphs) {
                    stale.add("$relative whitelists $glyph but no filled reference remains — drop the entry")
                }
            }
        }
        if (stale.isNotEmpty()) {
            fail("Whitelist drifted (${stale.size}):\n" + stale.joinToString("\n"))
        }

        val outlinedCount = sources.sumOf { (_, text) -> outlinedReference.findAll(text).count() }
        assertTrue(
            "only $outlinedCount outlined references across ${sources.size} files — " +
                "the scanner stopped matching or the migration was reverted",
            outlinedCount > 50
        )
    }

    private companion object {
        const val STRINGS_RELATIVE =
            "desktop/src/desktopMain/kotlin/app/podara/util/Strings.kt"
        const val PODARA_SOURCE_RELATIVE =
            "desktop/src/desktopMain/kotlin/app/podara"
    }
}
