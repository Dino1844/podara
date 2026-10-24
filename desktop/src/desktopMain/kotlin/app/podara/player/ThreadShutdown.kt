package app.podara.player

/**
 * Stops a worker thread, escalating from "ask politely" to "interrupt", and
 * reports whether it actually died.
 *
 * The engine used to call `thread.join(timeout)` and carry on regardless: the
 * caller believed the native handle was destroyed while `mpv_wait_event` could
 * still be running on the worker, which is a use-after-free waiting to happen.
 * A timeout alone cannot answer "did it stop?", so the caller gets the answer
 * back and can fail loudly.
 *
 * [firstWaitMs] is the polite wait: the worker is expected to leave its loop
 * once it sees the quit command, so no interrupt is needed in a healthy
 * shutdown. If it is still alive after that, the thread is interrupted and
 * given [secondWaitMs] more. Waiting is bounded by the two timeouts combined;
 * a non-positive timeout is treated as "check without waiting" so this can
 * never turn into an unbounded join.
 *
 * Never throws. An [InterruptedException] on the *calling* thread restores the
 * interrupt flag and stops waiting — the caller is being shut down too, and
 * swallowing that flag would hide it from the code that has to act on it.
 *
 * @return true when the thread is no longer alive.
 */
internal fun awaitThreadStop(thread: Thread, firstWaitMs: Long, secondWaitMs: Long): Boolean {
    joinQuietly(thread, firstWaitMs)
    if (thread.isAlive) {
        thread.interrupt()
        joinQuietly(thread, secondWaitMs)
    }
    return !thread.isAlive
}

/**
 * Joins [thread] for at most [timeoutMs], never throwing.
 *
 * `Thread.join(0)` waits forever, which is the opposite of what a shutdown
 * helper wants, so zero and negative values are a liveness check only.
 */
private fun joinQuietly(thread: Thread, timeoutMs: Long) {
    if (timeoutMs <= 0L) return
    try {
        thread.join(timeoutMs)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
