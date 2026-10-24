package app.podara.manager

import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import app.podara.util.Logger
import app.podara.util.RssConverter

private const val TAG = "SubscriptionManager"

/**
 * Whether a stored episode already holds everything a feed refresh would write.
 *
 * Compares exactly the columns [app.podara.data.EpisodeDao.insert] overwrites on
 * conflict — deliberately not `isNew`, which the upsert leaves alone (the "new"
 * flag is cleared by the UI, not by a refresh, so a difference there must not
 * force a write).
 */
private fun PodcastEpisode.hasSameRefreshContentAs(other: PodcastEpisode): Boolean =
    guid == other.guid &&
        origin == other.origin &&
        link == other.link &&
        title == other.title &&
        description == other.description &&
        imageUrl == other.imageUrl &&
        author == other.author &&
        pubDate == other.pubDate &&
        duration == other.duration &&
        audioUrl == other.audioUrl &&
        podcastTitle == other.podcastTitle &&
        imageSeedColor == other.imageSeedColor

class SubscriptionManager(
    private val db: AppDatabase,
    private val fetchPodcastClient: FetchPodcastClient = FetchPodcastClient(),
    /**
     * Invoked with the episodes a feed update just added when that subscription
     * has auto-download enabled. Kept as an injectable lambda (instead of a
     * DownloadManager dependency) so tests can observe the trigger; the default
     * does nothing.
     */
    private val autoDownloadStarter: suspend (List<PodcastEpisode>) -> Unit = {},
    /**
     * Invoked once per feed update that delivered new episodes and whose
     * subscription has notifications enabled. Same rationale as
     * [autoDownloadStarter]: a lambda keeps the desktop-only tray dependency
     * out of this class and lets tests observe the trigger.
     *
     * Receives the podcast title and the newly added episodes.
     */
    private val newEpisodeNotifier: (String, List<PodcastEpisode>) -> Unit = { _, _ -> }
) {
    suspend fun subscribe(origin: String) {
        db.subscriptions.insert(origin, false, false)
    }

    suspend fun unsubscribe(origin: String) {
        // One transaction: these four deletes are a single logical operation.
        // Run separately, a failure partway through (disk full, process killed)
        // left orphaned episodes or a subscription row pointing at a podcast
        // that no longer exists.
        db.transaction {
            episodes.deleteByOrigin(origin)
            progress.deleteByOrigin(origin)
            podcasts.delete(origin)
            subscriptions.delete(origin)
            itunesLookup.deleteByRssUrl(origin)
        }
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

                    // EpisodeDao.insert is an upsert whose ON CONFLICT branch
                    // fires `podcastEpisodeFtsUpdate`, which deletes and
                    // re-inserts the FTS row. Writing the whole feed on every
                    // refresh therefore rebuilt the search index for every
                    // episode — of every show — even when the feed had not
                    // changed. Only genuinely new rows and rows whose stored
                    // content actually differs are written now.
                    val existing = this.episodes.getAllByOrigin(origin).associateBy { it.id }
                    val addedEpisodes = episodes.filter { it.id !in existing }
                    val changedEpisodes = episodes.filter { fetched ->
                        val stored = existing[fetched.id] ?: return@filter false
                        !stored.hasSameRefreshContentAs(fetched)
                    }
                    (addedEpisodes + changedEpisodes).forEach { this.episodes.insert(it) }

                    subscriptions.updateLastUpdate(origin, System.currentTimeMillis())
                    subscriptions.updateCache(origin, response.eTag, response.lastModified, response.contentLength)
                    // Drives the "new" badge on the subscription card.
                    //
                    // Only written when this refresh actually added something.
                    // Writing it unconditionally meant a second refresh for the
                    // same show — the detail screen, a manual Refresh All, the
                    // startup run, all of which can overlap — recomputed zero
                    // additions and wiped the count the first one had just set,
                    // so "5 new" was never seen.
                    //
                    // A refresh that adds nothing leaves the count alone: the
                    // badge means "arrived since you last opened the show", and
                    // only opening it clears that.
                    if (addedEpisodes.isNotEmpty()) {
                        subscriptions.setNewEpisodeCount(origin, addedEpisodes.size)
                    }
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

                // Notify for genuinely new episodes. Same post-commit placement:
                // a failed notification must not undo the feed update.
                if (subscription.enableNotifications && newEpisodes.isNotEmpty()) {
                    try {
                        newEpisodeNotifier(podcast.title, newEpisodes)
                    } catch (e: Exception) {
                        Logger.e(TAG, "Notification trigger failed for $origin", e)
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
