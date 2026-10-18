package app.podara.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Date formatting shared by the episode lists, the player and the downloads page.
 *
 * Three private copies of this existed, and they disagreed: the episode list and
 * the downloads page used `yyyy-MM-dd` while the full player used
 * `MMM dd, yyyy`, so the same episode showed two different dates in two places.
 * They also always formatted with the *system* locale, which meant a Chinese UI
 * still printed English month names.
 *
 * Locale follows [Strings.currentLanguage] rather than the JVM default, so the
 * date switches with the language picker the same way every other string does.
 */
object DateFormat {

    /**
     * Medium-length absolute date, e.g. `2024-05-01` or `2024年5月1日`.
     *
     * Deliberately not `DateFormat.MEDIUM` from `java.text`: that adds zero
     * padding differences across locales that look wrong next to the fixed-width
     * metadata columns in the episode rows.
     */
    fun episode(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return formatter(patternForLanguage(), stringsLocale()).format(Date(timestamp))
    }

    /**
     * Short month-and-day, used where the year is obvious from context (the
     * relative-time formatter falls back to this for more than a day ago).
     */
    fun shortDate(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val pattern = if (Strings.currentLanguage == "zh") "M月d日" else "MMM dd"
        return formatter(pattern, stringsLocale()).format(Date(timestamp))
    }

    private fun patternForLanguage(): String =
        if (Strings.currentLanguage == "zh") "yyyy年M月d日" else "yyyy-MM-dd"

    private fun stringsLocale(): Locale =
        if (Strings.currentLanguage == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH

    // SimpleDateFormat is not thread-safe, so each call builds its own. These
    // run for visible rows only and are cheap relative to the text layout that
    // follows; sharing one instance would need a lock on a UI hot path.
    private fun formatter(pattern: String, locale: Locale) =
        SimpleDateFormat(pattern, locale)
}
