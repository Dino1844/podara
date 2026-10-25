package app.podara.desktop

import app.podara.player.buildAudioFilterChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `af` chain the two audio enhancement toggles produce, as a pure function.
 *
 * This is where the toggle idempotence actually lives: the engine *overwrites*
 * the `af` property with this canonical string rather than appending to it, so
 * "no sequence of flips can stack a second filter" reduces to "the builder is
 * deterministic and every output carries each filter at most once". Both
 * properties are cheap to pin down without touching mpv.
 */
class AudioFilterChainTest {

    @Test
    fun bothTogglesOffIsTheEmptyChain() {
        // mpv's own "no filters" value; also what makes turning both toggles
        // off restore plain playback without leaving anything behind.
        assertEquals("", buildAudioFilterChain(skipSilence = false, loudnessBoost = false))
    }

    @Test
    fun skipSilenceAloneIsTheNamedSilenceremoveFilter() {
        val chain = buildAudioFilterChain(skipSilence = true, loudnessBoost = false)
        // The @label prefix is what makes the entry addressable and keeps a
        // future append-style edit from adding a second unnamed copy.
        assertTrue(chain.startsWith("@silence:"), "expected the @silence label, saw $chain")
        assertTrue(chain.contains("silenceremove="), "expected silenceremove, saw $chain")
        assertEquals(1, chain.split("silenceremove").size - 1, "exactly one silenceremove, saw $chain")
    }

    @Test
    fun loudnessBoostAloneIsTheNamedVolumeFilter() {
        val chain = buildAudioFilterChain(skipSilence = false, loudnessBoost = true)
        assertTrue(chain.startsWith("@boost:"), "expected the @boost label, saw $chain")
        assertTrue(chain.contains("volume=6dB"), "expected a fixed volume lift, saw $chain")
        assertEquals(1, chain.split("volume=").size - 1, "exactly one volume filter, saw $chain")
    }

    @Test
    fun bothTogglesJoinInChainOrder() {
        val chain = buildAudioFilterChain(skipSilence = true, loudnessBoost = true)
        // Silence removal runs first on purpose: "what counts as silence" must
        // be judged on the original signal, not on the boosted one.
        assertTrue(
            chain.indexOf("silenceremove") < chain.indexOf("volume="),
            "silenceremove must precede the gain, saw $chain"
        )
    }

    @Test
    fun silenceremoveUsesParameterNamesTheShippedLibavfilterKnows() {
        // The plausible-but-wrong short form `threshold=` does not exist in
        // af_silenceremove; the real option is `stop_threshold`. A regression
        // to it would fail filter init at playback time, not here — which is
        // why the real-dll probe in RealPlaybackIntegrationTest also reads the
        // chain back from mpv.
        val chain = buildAudioFilterChain(skipSilence = true, loudnessBoost = false)
        assertTrue(chain.contains("stop_periods=-1"), "middle-of-stream trimming, saw $chain")
        assertTrue(chain.contains("stop_duration=0.05"), "keep pauses under 50ms, saw $chain")
        assertTrue(chain.contains("stop_threshold=-30dB"), "silence cutoff, saw $chain")
        // The bare form would sit behind an option separator; `stop_threshold=`
        // itself contains the substring, so this is not a plain `contains`.
        assertTrue(!chain.contains(":threshold="), "the bare `threshold=` option does not exist")
    }

    // ── idempotence: every input has exactly one canonical output ──

    @Test
    fun repeatedBuildsAreDeterministic() {
        val first = buildAudioFilterChain(skipSilence = true, loudnessBoost = true)
        repeat(16) {
            assertEquals(first, buildAudioFilterChain(skipSilence = true, loudnessBoost = true))
        }
    }

    @Test
    fun everyCombinationCarriesEachFilterAtMostOnce() {
        for (skipSilence in listOf(false, true)) {
            for (loudnessBoost in listOf(false, true)) {
                val chain = buildAudioFilterChain(skipSilence, loudnessBoost)
                assertEquals(
                    if (skipSilence) 1 else 0,
                    chain.split("silenceremove").size - 1,
                    "silenceremove count for skip=$skipSilence boost=$loudnessBoost, saw $chain"
                )
                assertEquals(
                    if (loudnessBoost) 1 else 0,
                    chain.split("volume=").size - 1,
                    "volume count for skip=$skipSilence boost=$loudnessBoost, saw $chain"
                )
            }
        }
    }

    @Test
    fun anyFlipSequenceLandsOnTheSameChainAsADirectBuild() {
        // Overwrite semantics mean the chain is a pure function of the current
        // toggle pair, not of the path taken to get there — the property that
        // makes "toggle A on, B on, A off, A on…" identical to a direct build.
        val paths = listOf(
            listOf(true to false, true to true, false to true, true to true),
            listOf(false to true, true to true, true to false, false to false, true to true)
        )
        val direct = buildAudioFilterChain(skipSilence = true, loudnessBoost = true)
        for (path in paths) {
            var skipSilence = false
            var loudnessBoost = false
            var chain = ""
            for ((skip, boost) in path) {
                skipSilence = skip
                loudnessBoost = boost
                chain = buildAudioFilterChain(skipSilence, loudnessBoost)
            }
            assertEquals(direct, chain, "the last state of the path must equal the direct build")
        }
    }
}
