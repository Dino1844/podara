package app.podara.manager

import kotlinx.coroutines.delay

/**
 * Per-task transfer rate limiter using cumulative byte tracking.
 *
 * Suspends the caller when actual throughput exceeds [limitBps]. Extracted from
 * [DownloadManager] so the throttling policy is a standalone, testable unit
 * rather than a private detail of the transfer loop.
 *
 * @param limitBps sustained throughput ceiling in bytes per second.
 */
class RateLimiter(private val limitBps: Long) {

    private var accumulatedBytes = 0L
    private var windowStartNanos = System.nanoTime()

    /**
     * Throttle after reading [bytesRead] bytes. Suspends the current coroutine
     * if the average rate exceeds [limitBps] since construction.
     *
     * @param shouldStop called periodically during long waits — return true
     *   to abort the wait early (e.g. when pause/cancel is requested).
     */
    suspend fun throttle(bytesRead: Int, shouldStop: () -> Boolean = { false }) {
        if (bytesRead <= 0) return
        accumulatedBytes += bytesRead

        val expectedNanos = accumulatedBytes * 1_000_000_000L / limitBps
        val elapsedNanos = System.nanoTime() - windowStartNanos
        var waitNanos = expectedNanos - elapsedNanos

        while (waitNanos > 0) {
            if (shouldStop()) return
            // Cap each delay chunk at 200ms so pause/cancel can be checked promptly
            val chunk = minOf(waitNanos, 200_000_000L)
            delay(chunk / 1_000_000)
            // Recompute remaining wait after the chunk
            val newElapsed = System.nanoTime() - windowStartNanos
            waitNanos = expectedNanos - newElapsed
        }
    }
}