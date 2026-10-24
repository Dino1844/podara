package app.podara.util

import app.podara.api.rss.FetchPodcastClientResult
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import com.prof18.rssparser.model.RssChannel
import com.prof18.rssparser.model.RssItem
import java.security.MessageDigest

object RssConverter {

    fun toPodcast(channel: RssChannel, origin: String, fileSize: Long, seedColor: Int?): Podcast {
        val ownerName = channel.itunesChannelData?.owner?.name
        return Podcast(
            origin = origin,
            link = channel.link ?: "",
            title = channel.title ?: "",
            description = channel.description ?: "",
            author = ownerName ?: "",
            imageUrl = channel.itunesChannelData?.image ?: channel.image?.url ?: "",
            imageSeedColor = seedColor ?: 0,
            languageCode = "",
            fileSize = fileSize
        )
    }

    fun toPodcastEpisode(item: RssItem, podcast: Podcast): PodcastEpisode {
        // Identity, in order of trustworthiness.
        //
        // `guid` is the only field the spec says is stable. When a feed omits it,
        // falling back to link or title is usually fine, but a feed that omits
        // both *and* reuses its title — daily briefings do exactly this — gave
        // every item the same id, and the whole feed collapsed onto one row.
        // The enclosure URL is the last resort: two episodes of one show can
        // share a title but never a file.
        val audioUrl = item.audio ?: ""
        val guid = item.guid
            ?: item.link
            ?: item.title?.takeIf { it.isNotBlank() }?.let { title ->
                if (audioUrl.isNotBlank()) "$title#$audioUrl" else title
            }
            ?: audioUrl
        val id = "${podcast.origin}:${guid}"

        return PodcastEpisode(
            id = id,
            guid = guid,
            origin = podcast.origin,
            link = item.link ?: "",
            title = item.title ?: "",
            description = item.description ?: item.content ?: "",
            imageUrl = item.itunesItemData?.image ?: item.image,
            author = item.author ?: podcast.author,
            pubDate = parseDate(item.pubDate),
            duration = parseDuration(item.itunesItemData?.duration),
            audioUrl = item.audio ?: "",
            podcastTitle = podcast.title,
            imageSeedColor = podcast.imageSeedColor
        )
    }

    /**
     * Parses the many shapes RSS pubDates actually come in.
     *
     * The previous two SimpleDateFormats silently failed on RFC 822 dates with a
     * named zone (`...GMT`), ISO-8601 with a numeric offset (`+08:00`),
     * fractional seconds, and bare dates — yielding pubDate = 0, which quietly
     * broke "recent update" sorting for the whole podcast.
     *
     * Three shapes needed more than another formatter, because java.time's
     * parsers reject or misread them at the field level:
     *
     * - `2024-01-01 00:00:00` — a space instead of the ISO `T`, so
     *   `LocalDateTime`'s built-in parser refuses it.
     * - `Mon, 01 Jan 24 00:00:00 GMT` — a two-digit year. `RFC_1123_DATE_TIME`
     *   reads it as year 24, silently sorting the episode ~2000 years back.
     * - `Mon, 01 Jan 2024 00:00:00 EST` — a named zone. `RFC_1123_DATE_TIME`
     *   only accepts `+HHMM` or `GMT`, and resolving names through java.time's
     *   zone database is wrong for exactly the abbreviations feeds use (`EST`
     *   becomes America/New_York, so a July date is parsed as EDT; `IST` becomes
     *   Africa/Abidjan). [NAMED_ZONE_OFFSETS] pins the RFC 822 meanings instead.
     *
     * Results outside `[1970, now + 10 years]` are treated as failures: they are
     * either a misparse (the two-digit-year bug) or a feed bug, and both sort
     * worse than the 0 that means "unknown".
     *
     * Returns 0 when nothing parses, and says so in the log rather than failing
     * silently — a feed with a new date shape should be visible somewhere.
     */
    internal fun parseDate(dateString: String?): Long {
        val trimmed = dateString?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0

        val normalized = normalizeRfc822(trimmed)
        val epochMillis = parseEpochMillis(normalized)
        if (epochMillis == null) {
            Logger.w(TAG, "Unparseable pubDate: $trimmed")
            return 0
        }
        if (epochMillis < MIN_PLAUSIBLE_EPOCH_MILLIS || epochMillis > maxPlausibleEpochMillis()) {
            Logger.w(TAG, "Ignoring implausible pubDate: $trimmed (parsed as $epochMillis)")
            return 0
        }
        return epochMillis
    }

    private fun parseEpochMillis(text: String): Long? {
        parseWithOffset(text)?.let { return it }
        // java.time validates a leading day-of-week against the resolved date
        // and rejects the whole string when they disagree. Feeds get that wrong
        // often enough that it is not worth failing the date over, so retry
        // without the optional "Mon, " prefix.
        DAY_OF_WEEK_PREFIX.find(text)?.let { prefix ->
            parseWithOffset(text.substring(prefix.range.last + 1))?.let { return it }
        }
        // No zone at all: assume UTC. Covers 2024-01-01T00:00:00, the
        // space-separated variant, and bare dates.
        for (formatter in LOCAL_DATE_TIME_FORMATTERS) {
            try {
                return java.time.LocalDateTime.parse(text, formatter)
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            } catch (_: Exception) { }
        }
        return try {
            java.time.LocalDate.parse(text)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseWithOffset(text: String): Long? {
        for (formatter in OFFSET_DATE_FORMATTERS) {
            try {
                return java.time.Instant.from(formatter.parse(text)).toEpochMilli()
            } catch (_: Exception) { }
        }
        return null
    }

    /**
     * Rewrites the two RFC 822 quirks java.time cannot handle into the numeric
     * form it can: a two-digit year expanded per RFC 2822, and a trailing named
     * zone replaced with its `+HHMM` offset.
     *
     * Everything else (ISO-8601, four-digit years, numeric offsets) passes
     * through untouched.
     */
    private fun normalizeRfc822(text: String): String {
        var result = text

        // RFC 2822 §4.3: a two-digit year is 1950..2049. Fewer than 50 is 20xx,
        // otherwise 19xx. DateTimeFormatter's own `yy` uses a 2000 base, which
        // turns a 1999 episode into 2099.
        TWO_DIGIT_YEAR_RFC822.find(result)?.let { match ->
            val year = match.groupValues[2].toInt()
            val fullYear = if (year < 50) 2000 + year else 1900 + year
            result = result.replaceRange(match.range, match.groupValues[1] + fullYear)
        }

        // Only 2-4 letter names: a single "Z" is an ISO designator that
        // ISO_OFFSET_DATE_TIME already handles, and rewriting it would break it.
        TRAILING_NAMED_ZONE.find(result)?.let { match ->
            NAMED_ZONE_OFFSETS[match.groupValues[1].uppercase()]?.let { offset ->
                result = result.replaceRange(match.range, " $offset")
            }
        }

        return result
    }

    private const val TAG = "RssConverter"

    /** 1970-01-01T00:00:00Z; anything earlier is a misparse, not a publish date. */
    private const val MIN_PLAUSIBLE_EPOCH_MILLIS = 0L

    private fun maxPlausibleEpochMillis(): Long =
        System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000

    /** Day/Month/Year followed by a time, i.e. the RFC 822 slot for the year. */
    private val TWO_DIGIT_YEAR_RFC822 = Regex(
        """^(\s*(?:[A-Za-z]{3,9},\s+)?\d{1,2}\s+[A-Za-z]{3,9}\s+)(\d{2})(?=\s+\d{1,2}:\d{2})""",
        RegexOption.IGNORE_CASE
    )

    /** A trailing zone abbreviation such as ` GMT` or ` EST`. */
    private val TRAILING_NAMED_ZONE = Regex("""\s([A-Za-z]{2,4})$""")

    /** The optional `Mon, ` prefix of an RFC 822 date. */
    private val DAY_OF_WEEK_PREFIX = Regex("""^[A-Za-z]{3,9},\s*""")

    /**
     * RFC 822/2822 zone abbreviations, pinned to their standard offsets.
     *
     * Deliberately not delegated to `ZoneId.of`/java.time's zone text: those
     * resolve `EST` to a DST-observing region and `IST` to Africa/Abidjan,
     * which is not what the feed meant.
     */
    private val NAMED_ZONE_OFFSETS = buildMap {
        // Universal time, in its several spellings.
        for (name in listOf("UT", "UTC", "GMT")) put(name, "+0000")
        // North America.
        put("EST", "-0500"); put("EDT", "-0400")
        put("CST", "-0600"); put("CDT", "-0500")
        put("MST", "-0700"); put("MDT", "-0600")
        put("PST", "-0800"); put("PDT", "-0700")
        put("AKST", "-0900"); put("AKDT", "-0800")
        put("HST", "-1000")
        // Europe.
        put("BST", "+0100"); put("CET", "+0100"); put("CEST", "+0200")
        put("EET", "+0200"); put("EEST", "+0300"); put("MSK", "+0300")
        // Asia and Oceania.
        put("IST", "+0530"); put("SGT", "+0800"); put("HKT", "+0800")
        put("CCT", "+0800"); put("JST", "+0900"); put("KST", "+0900")
        put("AEST", "+1000"); put("AEDT", "+1100")
        put("NZST", "+1200"); put("NZDT", "+1300")
    }

    private val OFFSET_DATE_FORMATTERS = listOf(
        // Mon, 01 Jan 2024 00:00:00 GMT — also +0000 and other numeric zones.
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
        // 2024-01-01T00:00:00Z, +08:00 offsets, optional fractional seconds.
        java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    )

    private val LOCAL_DATE_TIME_FORMATTERS = listOf(
        // 2024-01-01T00:00:00 / 2024-01-01T00:00
        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        // 2024-01-01 00:00:00 — the space-separated shape some feeds emit,
        // with optional seconds and optional fractional seconds.
        java.time.format.DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .optionalStart()
            .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter(java.util.Locale.ROOT),
    )

    private fun parseDuration(durationString: String?): Int {
        if (durationString == null) return 0
        return try {
            val parts = durationString.split(":").map { it.trim().toIntOrNull() ?: 0 }
            when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                1 -> parts[0]
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Parse a FetchPodcastClientResult.Success into a Podcast and its episodes.
     * Used by desktop module to preview episodes without subscribing (no DB writes).
     */
    fun parseFetchResult(
        result: FetchPodcastClientResult.Success,
        origin: String,
        seedColor: Int? = null
    ): Pair<Podcast, List<PodcastEpisode>> {
        val podcast = toPodcast(result.rssChannel, origin, result.fileSize, seedColor)
        val episodes = result.rssChannel.items.map { toPodcastEpisode(it, podcast) }
        return podcast to episodes
    }
}

fun String.sha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}
