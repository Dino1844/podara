package app.podara.manager

/**
 * Rate-limits a stream of progress callbacks to a fixed interval.
 *
 * Download progress arrives once per buffer read — 8 KiB at a time, so a
 * 100 MB episode produces about 12,800 updates. Each one used to copy the whole
 * progress map and recompose every screen reading it, on the UI thread, for a
 * number the eye cannot resolve anyway.
 *
 * Emits the first call immediately so the UI shows progress without waiting,
 * then at most one per [minIntervalMillis]. The final update is not special-
 * cased: the download's completion path clears the entry regardless, so a
 * dropped tail costs nothing.
 *
 * Not thread-safe by design — it is used from a single download coroutine.
 */
class ProgressThrottle(private val minIntervalMillis: Long = 250L) {

    private var lastEmitMs = 0L
    private var emitted = false

    /** @return true when the caller should publish this update. */
    fun shouldEmit(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!emitted) {
            emitted = true
            lastEmitMs = nowMillis
            return true
        }
        if (nowMillis - lastEmitMs < minIntervalMillis) return false
        lastEmitMs = nowMillis
        return true
    }
}
