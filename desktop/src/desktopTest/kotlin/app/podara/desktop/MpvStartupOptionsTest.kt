package app.podara.desktop

import app.podara.player.MPV_ERROR_GENERIC
import app.podara.player.MPV_ERROR_OPTION_ERROR
import app.podara.player.MPV_ERROR_OPTION_FORMAT
import app.podara.player.MPV_ERROR_OPTION_NOT_FOUND
import app.podara.player.MPV_STARTUP_OPTIONS
import app.podara.player.MpvOptionFailure
import app.podara.player.StartupSelfCheck
import app.podara.player.classifyOptionResult
import app.podara.player.evaluateSelfCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the mpv startup configuration, without loading `mpv-1.dll`.
 *
 * That constraint is the whole design: `MpvApi.INSTANCE` is a hard-coded JNA
 * singleton, so neither `mpv_create()` returning 0 nor a rejected
 * `mpv_set_option_string` can be reached from a plain unit test. Rather than
 * rearchitect the engine around an injectable API for one check, the parts
 * that were wrong in the original code — the option *names*, and what a
 * return code means — are pure data and pure functions here.
 */
class MpvStartupOptionsTest {

    // ── the option table ──

    @Test
    fun startupOptionNamesAreUnique() {
        // Duplicates would mean one setting silently overwrites another, and
        // mpv applies options in order without complaint.
        val names = MPV_STARTUP_OPTIONS.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate option name in MPV_STARTUP_OPTIONS")
    }

    @Test
    fun startupOptionsDoNotContainTheKnownBadNames() {
        // The regression this locks down. `audio-only` is not a libmpv option
        // at all (mpv_set_option_string returns MPV_ERROR_OPTION_NOT_FOUND),
        // and `video` is only an alias of `vid`, which is easy to "fix" back
        // to by accident when editing the table.
        val names = MPV_STARTUP_OPTIONS.map { it.name }
        assertFalse("audio-only" in names, "'audio-only' is not a libmpv option")
        assertFalse("video" in names, "use the canonical 'vid' rather than its 'video' alias")
        assertFalse("reconnect" in names, "libmpv has no 'reconnect' option; it lives in stream-lavf-o")
    }

    @Test
    fun videoTrackIsDisabled() {
        // Audio-only playback is the entire premise of this engine. Disabling
        // the video track is what also drops embedded cover art.
        val vid = MPV_STARTUP_OPTIONS.single { it.name == "vid" }
        assertEquals("no", vid.value)
        assertTrue(vid.essential, "vid=no is essential; without it mpv may render video")
    }

    @Test
    fun keepOpenStaysEssential() {
        // Completion is detected by observing `eof-reached`. If mpv unloads the
        // file at end of stream, the property disappears before the change can be
        // delivered and no TrackCompleted event is ever emitted — the queue stops
        // advancing at the end of an episode. This is a correctness option, not a
        // preference.
        val keepOpen = MPV_STARTUP_OPTIONS.single { it.name == "keep-open" }
        assertEquals("yes", keepOpen.value)
        assertTrue(keepOpen.essential)
    }

    @Test
    fun networkStreamsGetATimeoutAndReconnect() {
        // Both are tuning options: an older libmpv may reject them, and
        // playback must continue regardless, so neither is essential.
        val timeout = MPV_STARTUP_OPTIONS.single { it.name == "network-timeout" }
        assertFalse(timeout.essential)
        assertTrue(timeout.value.toInt() > 0)

        val lavf = MPV_STARTUP_OPTIONS.single { it.name == "stream-lavf-o" }
        assertFalse(lavf.essential)
        assertTrue(lavf.value.contains("reconnect=1"))
    }

    @Test
    fun cacheIsLeftAtItsDefault() {
        // `cache` defaults to `auto`, which already caches HTTP and skips the
        // cache for local files. Setting it explicitly was tried and reverted:
        // `cache=no` to speed up local files also disables buffering for
        // network streams, which is the opposite of what a podcast app wants.
        assertFalse(MPV_STARTUP_OPTIONS.any { it.name == "cache" })
    }

    @Test
    fun everyOptionHasANameAndValue() {
        for (option in MPV_STARTUP_OPTIONS) {
            assertTrue(option.name.isNotBlank(), "blank option name")
            assertTrue(option.value.isNotBlank(), "blank value for ${option.name}")
            // mpv parses option names case-sensitively; an uppercase entry
            // would fail with OPTION_NOT_FOUND just like a typo.
            assertEquals(option.name.lowercase(), option.name, "option name must be lowercase")
        }
    }

    // ── return-code classification ──

    @Test
    fun successIsNotAFailure() {
        assertNull(classifyOptionResult(0))
        // Anything non-negative is success by the mpv_error convention.
        assertNull(classifyOptionResult(1))
    }

    @Test
    fun unknownOptionNameIsItsOwnFailure() {
        // -5 (MPV_ERROR_OPTION_NOT_FOUND) is the only code that *always*
        // means this file is wrong, so it must not be lumped in with the
        // "this build does not support it, carry on" case.
        assertEquals(MpvOptionFailure.UNKNOWN_NAME, classifyOptionResult(MPV_ERROR_OPTION_NOT_FOUND))
    }

    @Test
    fun badValueIsDistinctFromUnknownName() {
        assertEquals(MpvOptionFailure.BAD_VALUE, classifyOptionResult(MPV_ERROR_OPTION_FORMAT))
        assertEquals(MpvOptionFailure.BAD_VALUE, classifyOptionResult(MPV_ERROR_OPTION_ERROR))
        // Any other error is still a "keep going" case, never an unknown name.
        assertEquals(MpvOptionFailure.BAD_VALUE, classifyOptionResult(MPV_ERROR_GENERIC))
        assertEquals(MpvOptionFailure.BAD_VALUE, classifyOptionResult(-1))
    }

    // ── startup self-check ──

    @Test
    fun selfCheckPassesOnAMatchingValue() {
        assertEquals(StartupSelfCheck.Ok, evaluateSelfCheck("vid", "no", "no"))
    }

    @Test
    fun selfCheckReportsAnUnexpectedValue() {
        val result = evaluateSelfCheck("vid", "no", "auto")
        assertTrue(result is StartupSelfCheck.UnexpectedValue)
        assertEquals("vid", result.property)
        assertEquals("no", result.expected)
        assertEquals("auto", result.actual)
    }

    @Test
    fun selfCheckReportsAnUnreadableProperty() {
        // A NULL from mpv_get_property_string after a successful initialize is
        // the signal that the handle is not in the state we assume.
        val result = evaluateSelfCheck("vid", "no", null)
        assertTrue(result is StartupSelfCheck.Unreadable)
        assertEquals("vid", result.property)
    }

    @Test
    fun selfCheckValueComparisonIsExact() {
        // mpv renders `vid` as the same string it was given, so any difference
        // at all is worth a log line — no trimming, no case folding.
        assertTrue(evaluateSelfCheck("vid", "no", "no ") is StartupSelfCheck.UnexpectedValue)
        assertTrue(evaluateSelfCheck("vid", "no", "NO") is StartupSelfCheck.UnexpectedValue)
        assertTrue(evaluateSelfCheck("vid", "no", "") is StartupSelfCheck.UnexpectedValue)
    }
}
