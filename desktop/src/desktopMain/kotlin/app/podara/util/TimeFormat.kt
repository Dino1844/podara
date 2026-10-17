package app.podara.util

/**
 * Formatting for durations and clock positions.
 *
 * The same `h:mm:ss` / `m:ss` logic had been written out four times —
 * `EpisodeListItem.formatDurationCompact`, `PlayerUI.formatDuration`,
 * `PlayerUI.formatTime` and `ContinueListeningCard.formatEpisodeClock` — each
 * character-identical apart from the variable names and whether the input was
 * seconds or milliseconds. Any change to the format (a locale-aware separator,
 * a leading zero, an hour rollover fix) had to be made in four places.
 */
object TimeFormat {

    /**
     * `h:mm:ss` when the duration reaches an hour, `m:ss` otherwise.
     *
     * @param totalSeconds length in whole seconds; negative values clamp to 0.
     */
    fun duration(totalSeconds: Long): String {
        val safe = totalSeconds.coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val seconds = safe % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** As [duration], taking milliseconds. */
    fun clock(millis: Long): String = duration(millis / 1000)

    /**
     * Human-readable time still ahead, rounded up to whole minutes so a 46:59
     * remainder does not read as "0 min left". Empty when [durationMs] is
     * unknown.
     */
    fun remaining(durationMs: Long, positionMs: Long): String {
        if (durationMs <= 0L) return ""
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val totalMinutes = (remainingMs + 59_999L) / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> Strings.get("home_continue_time_left_hours", hours, minutes)
            hours > 0 -> Strings.get("home_continue_time_left_hours_exact", hours)
            else -> Strings.get("home_continue_time_left_minutes", totalMinutes)
        }
    }

    /** Played share of an episode, clamped to 0..1. Zero when either side is unknown. */
    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}
