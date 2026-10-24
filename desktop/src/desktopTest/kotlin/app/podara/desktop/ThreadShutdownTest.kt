package app.podara.desktop

import app.podara.player.awaitThreadStop
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression cover for the engine's shutdown escalation.
 *
 * `MpvAudioPlayerEngine.release()` used to `join(3s)` and carry on whether or
 * not the worker actually stopped. A caller then destroyed the process — and
 * with it any native handle the worker was still inside — believing teardown
 * was complete. [awaitThreadStop] answers the only question that matters ("is
 * this thread gone?") instead of assuming a timeout means success.
 */
class ThreadShutdownTest {

    @Test
    fun aThreadThatIgnoresInterruptsIsReportedAsStillAlive() {
        val keepRunning = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val stubborn = Thread({
            started.countDown()
            while (keepRunning.get()) {
                try {
                    Thread.sleep(5L)
                } catch (_: InterruptedException) {
                    // Deliberately ignored: this models a worker wedged in native
                    // code that never checks the interrupt flag.
                }
            }
        }, "stubborn-worker").apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS), "the worker must reach its loop")
            assertFalse(
                awaitThreadStop(stubborn, firstWaitMs = 50L, secondWaitMs = 150L),
                "a thread that ignores interrupts must be reported as still alive"
            )
            assertTrue(stubborn.isAlive, "the helper must not claim a live thread is dead")
        } finally {
            keepRunning.set(false)
            stubborn.join(5_000L)
        }
        assertFalse(stubborn.isAlive, "the test must clean up its worker")
    }

    @Test
    fun aThreadThatCanBeInterruptedIsReportedAsStopped() {
        val sleeping = CountDownLatch(1)
        val polite = Thread({
            try {
                sleeping.countDown()
                Thread.sleep(60_000L)
            } catch (_: InterruptedException) {
                // Normal shutdown: the interrupt is the escalation working.
            }
        }, "polite-worker").apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(sleeping.await(5, TimeUnit.SECONDS), "the worker must reach its sleep")
            assertTrue(
                awaitThreadStop(polite, firstWaitMs = 50L, secondWaitMs = 2_000L),
                "an interruptible thread must be reported as stopped"
            )
            assertFalse(polite.isAlive)
        } finally {
            polite.interrupt()
            polite.join(2_000L)
        }
    }

    @Test
    fun anAlreadyStoppedThreadIsReportedAsStopped() {
        val done = Thread({ }, "already-done").apply { start() }
        done.join(5_000L)

        assertTrue(awaitThreadStop(done, firstWaitMs = 100L, secondWaitMs = 100L))
    }

    @Test
    fun nonPositiveTimeoutsDoNotWaitAtAll() {
        // `Thread.join(0)` waits forever; the helper must treat a zero timeout as
        // a liveness check instead of hanging the shutdown path indefinitely.
        val sleeping = CountDownLatch(1)
        val worker = Thread({
            sleeping.countDown()
            try {
                Thread.sleep(60_000L)
            } catch (_: InterruptedException) {
                // Nothing to do.
            }
        }, "no-wait-worker").apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(sleeping.await(5, TimeUnit.SECONDS), "the worker must reach its sleep")
            val startedAt = System.nanoTime()
            val stopped = awaitThreadStop(worker, firstWaitMs = 0L, secondWaitMs = 0L)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertFalse(stopped, "a sleeping thread is still alive when nothing waits for it")
            assertTrue(elapsedMs < 2_000L, "zero timeouts must not block, took ${elapsedMs}ms")
        } finally {
            worker.interrupt()
            worker.join(2_000L)
        }
    }
}
