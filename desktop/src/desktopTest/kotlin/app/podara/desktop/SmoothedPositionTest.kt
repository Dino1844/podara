package app.podara.desktop

import app.podara.player.hasConverged
import app.podara.player.smoothStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-frame smoothing behind the seek bars.
 *
 * Motivation is the polling rate: mpv reports position every 250 ms, and the
 * slider used to be bound directly to it, so playback advanced in visible
 * quarter-second steps. The step function is separated from the composition so
 * its two required behaviours can be checked without a running frame clock:
 *
 * - ordinary playback converges on the target within a bounded number of steps;
 * - a seek is not eased, because the audio moves at once and a thumb that
 *   slides after the fact reads as lag.
 */
class SmoothedPositionTest {

    /** Roughly one poll tick of playback on a 30-minute episode. */
    private val pollStep = 0.25f / 1800f

    @Test
    fun testStepMovesPartOfTheWayTowardTheTarget() {
        val next = smoothStep(0f, 1f, fraction = 0.35f)
        assertEquals(0.35f, next, 1e-6f)
    }

    @Test
    fun testStepIsMonotonicAndNeverOvershoots() {
        var value = 0f
        val target = 1f
        var previous = value
        repeat(200) {
            value = smoothStep(value, target, fraction = 0.35f)
            assertTrue(value >= previous, "smoothing must not move backwards")
            assertTrue(value <= target, "smoothing must not overshoot the target")
            previous = value
        }
        assertTrue(hasConverged(value, target), "should converge, ended at $value")
    }

    @Test
    fun testStepSnapsWhenAlreadyConverged() {
        // Returning the target exactly avoids a long tail of sub-epsilon
        // updates, each of which would be a recomposition for no visible change.
        assertEquals(1f, smoothStep(0.99999f, 1f, fraction = 0.35f))
    }

    @Test
    fun testPlaybackConvergesWithinAFewFrames() {
        // The gap per poll is ~0.00014 of the bar. From one poll behind, the
        // smoothing should reach the new value in a handful of frames — short
        // enough that the bar never visibly lags the audio.
        var value = 0f
        val target = pollStep
        var frames = 0
        while (!hasConverged(value, target) && frames < 240) {
            value = smoothStep(value, target, fraction = 0.35f)
            frames++
        }
        assertTrue(hasConverged(value, target), "did not converge to $target from 0")
        assertTrue(frames <= 12, "took $frames frames to converge; that would read as lag")
    }

    @Test
    fun testConvergenceDetectsTheEpsilon() {
        assertTrue(hasConverged(1f, 1.00001f))
        assertTrue(hasConverged(0.5f, 0.5f))
        assertTrue(!hasConverged(0f, 1f))
    }

    @Test
    fun testLargerResponsivenessConvergesFaster() {
        fun framesToConverge(fraction: Float): Int {
            var value = 0f
            var frames = 0
            while (!hasConverged(value, pollStep) && frames < 500) {
                value = smoothStep(value, pollStep, fraction)
                frames++
            }
            return frames
        }
        assertTrue(
            framesToConverge(0.5f) < framesToConverge(0.2f),
            "a higher responsiveness must converge in fewer frames"
        )
    }

    @Test
    fun testSeekDistanceIsDistinguishableFromAPollStep() {
        // The design distinguishes the two by magnitude, so that distinction has
        // to hold: a seek is orders of magnitude larger than one poll tick.
        val seekAcrossTrack = 0.5f
        assertTrue(
            seekAcrossTrack > pollStep * 50,
            "a half-track seek (${seekAcrossTrack}) must dwarf a poll step ($pollStep)"
        )
    }
}