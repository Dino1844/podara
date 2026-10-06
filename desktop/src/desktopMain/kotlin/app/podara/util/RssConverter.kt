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
        val guid = item.guid ?: item.link ?: item.title ?: ""
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
     * Returns 0 when nothing parses, and says so in the log rather than failing
     * silently — a feed with a new date shape should be visible somewhere.
     */
    internal fun parseDate(dateString: String?): Long {
        val trimmed = dateString?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0
        for (formatter in DATE_FORMATTERS) {
            try {
                return java.time.Instant.from(formatter.parse(trimmed)).toEpochMilli()
            } catch (_: Exception) { }
        }
        // No zone at all: assume UTC. Covers 2024-01-01T00:00:00 and bare dates.
        return try {
            java.time.LocalDateTime.parse(trimmed)
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDate.parse(trimmed)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) {
                Logger.w(TAG, "Unparseable pubDate: $trimmed")
                0
            }
        }
    }

    private const val TAG = "RssConverter"

    private val DATE_FORMATTERS = listOf(
        // Mon, 01 Jan 2024 00:00:00 GMT — also +0000 and other named/numeric zones.
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
        // 2024-01-01T00:00:00Z, +08:00 offsets, optional fractional seconds.
        java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME,
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
