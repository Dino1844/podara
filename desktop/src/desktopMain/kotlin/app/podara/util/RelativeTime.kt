package app.podara.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Relative time formatting shared by History and Favorites.
 *
 * Day-level comparisons go through [epochDay] instead of `Calendar.DAY_OF_YEAR`,
 * because `DAY_OF_YEAR` restarts at 1 every 1 January and therefore reports the
 * wrong day for every timestamp around new year (e.g. 31 December never counts
 * as "Yesterday" on 1 January).
 */
fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    if (diff < 0) return formatDateAbsolute(timestamp)

    val minutes = diff / 60_000
    val hours = minutes / 60

    val zone = ZoneId.systemDefault()
    return when {
        minutes < 1 -> Strings["time_just_now"]
        minutes < 60 -> Strings.get("time_minutes_ago", minutes)
        hours < 24 -> Strings.get("time_hours_ago", hours)
        else -> {
            val todayEpochDay = LocalDate.now(zone).toEpochDay()
            if (todayEpochDay - epochDay(timestamp, zone) == 1L) Strings["history_yesterday"]
            else formatDateAbsolute(timestamp)
        }
    }
}

/**
 * Whole local calendar days between 1970-01-01 and [timestamp] — monotonic, so
 * unlike `Calendar.DAY_OF_YEAR` it never resets at a year boundary.
 */
fun epochDay(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    return Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toEpochDay()
}

private fun formatDateAbsolute(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}