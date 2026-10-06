package app.podara.manager

import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import app.podara.util.Logger
import app.podara.util.RssConverter

private const val TAG = "SubscriptionManager"

class SubscriptionManager(
    private val db: AppDatabase,
    private val fetchPodcastClient: FetchPodcastClient = FetchPodcastClient(),
    /**
     * Invoked with the episodes a feed update just added when that subscription
     * has auto-download enabled. Kept as an injectable lambda (instead of a
     * DownloadManager dependency) so tests can observe the trigger; the default
     * does nothing.
     */
    private val autoDownloadStarter: suspend (List<PodcastEpisode>) -> Unit = {}
) {
    suspend fun subscribe(origin: String) {
        db.subscriptions.insert(origin, false, false)
    }

    suspend fun unsubscribe(origin: String) {
        db.episodes.deleteByOrigin(origin)
        db.podcasts.delete(origin)
        db.subscriptions.delete(origin)
        db.itunesLookup.deleteByRssUrl(origin)
    }

    suspend fun isSubscribed(origin: String): Boolean {
        return db.subscriptions.getByOriginSync(origin) != null
    }

    suspend fun updatePodcast(origin: String, seedColor: Int?): UpdatePodcastResult {
        val subscription = db.subscriptions.getByOriginSync(origin) ?: return UpdatePodcastResult.NotSubscribed

        val response = fetchPodcastClient.fetch(origin, subscription.cacheLastModified, subscription.cacheETag)
        return when (response) {
            is FetchPodcastClientResult.Unchanged -> UpdatePodcastResult.Unchanged(response.reason)
            is FetchPodcastClientResult.Failure -> UpdatePodcastResult.Error(response.e)
            is FetchPodcastClientResult.Success -> {
                val podcast = RssConverter.toPodcast(response.rssChannel, origin, response.fileSize, seedColor)
                val episodes = response.rssChannel.items.map { RssConverter.toPodcastEpisode(it, podcast) }

                val newEpisodes = db.transaction {
                    podcasts.insert(podcast)

                    val existingIds = this.episodes.getEpisodeIds(origin).toSet()
                    val addedEpisodes = episodes.filter { it.id !in existingIds }
                    episodes.forEach { this.episodes.insert(it) }

                    subscriptions.updateLastUpdate(origin, System.currentTimeMillis())
                    subscriptions.updateCache(origin, response.eTag, response.lastModified, response.contentLength)
                    addedEpisodes
                }

                // Auto-download the episodes this update added. Runs after the
                // transaction commits, so a failing download can never roll back
                // the feed update; the back catalog is never touched.
                if (subscription.enableAutoDownload && newEpisodes.isNotEmpty()) {
                    try {
                        autoDownloadStarter(newEpisodes)
                    } catch (e: Exception) {
                        Logger.e(TAG, "Auto-download trigger failed for $origin", e)
                    }
                }

                UpdatePodcastResult.Updated(podcast, newEpisodes.size)
            }
        }
    }

    /**
     * Refresh every subscription, one feed at a time, serially. A feed that
     * fails is recorded in the summary and logged; the remaining feeds are
     * still refreshed.
     */
    suspend fun refreshAll(): RefreshAllResult {
        val origins = db.subscriptions.getAllSync().map { it.origin }
        val results = mutableListOf<RefreshOriginResult>()
        for (origin in origins) {
            val result = try {
                updatePodcast(origin, db.podcasts.getByOrigin(origin)?.imageSeedColor)
            } catch (e: Exception) {
                Logger.e(TAG, "refreshAll: failed to refresh $origin", e)
                UpdatePodcastResult.Error(e)
            }
            results.add(RefreshOriginResult(origin, result))
        }
        return RefreshAllResult(results, results.sumOf { it.newEpisodesCount })
    }
}

sealed class UpdatePodcastResult {
    data class Updated(val podcast: app.podara.data.model.Podcast, val newEpisodesCount: Int) : UpdatePodcastResult()
    data class Unchanged(val reason: String) : UpdatePodcastResult()
    data class Error(val exception: Exception) : UpdatePodcastResult()
    data object NotSubscribed : UpdatePodcastResult()
}

/** One feed's outcome within [RefreshAllResult]. */
data class RefreshOriginResult(
    val origin: String,
    val result: UpdatePodcastResult
) {
    /** New episodes this feed delivered; 0 unless [result] is [UpdatePodcastResult.Updated]. */
    val newEpisodesCount: Int
        get() = (result as? UpdatePodcastResult.Updated)?.newEpisodesCount ?: 0
}

data class RefreshAllResult(
    val results: List<RefreshOriginResult>,
    /** Total new episodes across all refreshed feeds. */
    val newEpisodesCount: Int
)
