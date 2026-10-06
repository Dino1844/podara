package app.podara.api.apple

import app.podara.api.HttpClients
import app.podara.api.model.PodcastPreviewModel
import app.podara.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Disk cache for the Discover top-charts list, so re-entering the screen does
 * not always pay a network round trip before anything renders.
 *
 * Only the fields the Discover UI renders are persisted. The apple route hands
 * out mapped [PodcastPreviewModel]s (not @Serializable), and although the raw
 * [TopPodcastsResponse] is @Serializable, the screen never sees it — the route
 * maps it to previews internally.
 */
class TopChartsCache(
    cacheDir: File = File(System.getProperty("user.home"), ".podara/cache"),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    val cacheFile = File(cacheDir, FILE_NAME)

    /**
     * Returns the cached previews for [countryCode], or null when there is no
     * usable cache (missing, corrupt, for another country, empty, or older
     * than [maxAgeMillis]). Never throws — a broken cache must not break the
     * screen; it just falls back to the network path.
     */
    fun load(countryCode: String, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): List<PodcastPreviewModel>? {
        return try {
            if (!cacheFile.exists()) return null
            val cached = HttpClients.json.decodeFromString<CachedTopCharts>(cacheFile.readText())
            val expired = nowMillis() - cached.savedAtMillis > maxAgeMillis
            if (cached.countryCode != countryCode || expired || cached.podcasts.isEmpty()) {
                null
            } else {
                cached.podcasts.map { it.toPreviewModel() }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to read top-charts cache: $e")
            null
        }
    }

    /**
     * Best-effort write. A failed cache write only costs the next cold start
     * its instant render, so failures are logged and swallowed.
     */
    fun save(countryCode: String, podcasts: List<PodcastPreviewModel>) {
        try {
            val cached = CachedTopCharts(
                countryCode = countryCode,
                savedAtMillis = nowMillis(),
                podcasts = podcasts.map {
                    CachedPodcast(
                        fetchUrl = it.fetchUrl,
                        link = it.link,
                        title = it.title,
                        description = it.description,
                        author = it.author,
                        imageUrl = it.imageUrl,
                        languageCode = it.languageCode
                    )
                }
            )
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(HttpClients.json.encodeToString(cached))
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to write top-charts cache: $e")
        }
    }

    private fun CachedPodcast.toPreviewModel() = PodcastPreviewModel(
        fetchUrl = fetchUrl,
        link = link,
        title = title,
        description = description,
        author = author,
        imageUrl = imageUrl,
        languageCode = languageCode
    )

    @Serializable
    private data class CachedPodcast(
        val fetchUrl: String,
        val link: String,
        val title: String,
        val description: String,
        val author: String,
        val imageUrl: String,
        val languageCode: String
    )

    @Serializable
    private data class CachedTopCharts(
        val countryCode: String,
        val savedAtMillis: Long,
        val podcasts: List<CachedPodcast>
    )

    companion object {
        private const val TAG = "TopChartsCache"
        private const val FILE_NAME = "top-charts.json"

        // Charts change daily, but on a cold start with no network any cache is
        // better than an error screen — only give up on one that is weeks old.
        const val DEFAULT_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
