package app.podara.manager

import app.podara.api.apple.ApplePodcastClient
import app.podara.api.model.PodcastPreviewModel
import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import app.podara.data.AppDatabase
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import app.podara.util.Logger
import app.podara.util.RssConverter
import app.podara.util.artworkSeedFor

private const val TAG = "PodcastManager"

sealed class AddPodcastResult {
    data class Duplicate(val duplicate: Podcast) : AddPodcastResult()
    data class Created(val podcast: Podcast) : AddPodcastResult()
}

/**
 * Owns subscription creation: resolving an origin to a feed, fetching it,
 * parsing it, and writing podcasts plus episodes in one transaction.
 *
 * Both clients default to instances backed by the process-wide
 * [app.podara.api.HttpClients.shared] connection pool, so constructing this
 * class is cheap and does not create a new pool. Tests inject fakes instead.
 */
class PodcastManager(
    private val db: AppDatabase,
    private val fetchPodcastClient: FetchPodcastClient = FetchPodcastClient(),
    private val appleClient: ApplePodcastClient = ApplePodcastClient()
) {
    suspend fun addPodcast(origin: String, seedColor: Int?): AddPodcastResult {
        Logger.i(TAG, "addPodcast: origin=$origin")

        val resolvedOrigin: String
        val legacyOrigin: String?

        if (origin.startsWith("itunes-lookup:")) {
            val idStr = origin.removePrefix("itunes-lookup:")
            val id = idStr.toLongOrNull()
            if (id == null) {
                throw IllegalArgumentException("Invalid iTunes ID: $idStr")
            }
            Logger.d(TAG, "Resolving iTunes ID: $id")
            val preview = appleClient.lookup.lookupById(id)
                ?: throw Exception("iTunes lookup failed for ID: $id")
            resolvedOrigin = preview.fetchUrl
            legacyOrigin = origin
            Logger.d(TAG, "Resolved to RSS feed: $resolvedOrigin")
        } else {
            resolvedOrigin = origin
            legacyOrigin = null
        }

        // Canonical origin is the resolved RSS URL, matching addPodcastFromPreview,
        // so a show subscribed from both entries maps to one row. The raw
        // itunes-lookup: key is still checked so subscriptions stored by older
        // versions are recognized instead of duplicated.
        db.podcasts.getByOrigin(resolvedOrigin)?.let {
            Logger.d(TAG, "Podcast already exists: ${it.title}")
            ensureSubscribed(resolvedOrigin)
            return AddPodcastResult.Duplicate(it)
        }
        legacyOrigin?.let { legacy ->
            db.podcasts.getByOrigin(legacy)?.let {
                Logger.d(TAG, "Podcast already exists under legacy origin: ${it.title}")
                ensureSubscribed(legacy)
                return AddPodcastResult.Duplicate(it)
            }
        }

        Logger.d(TAG, "Fetching RSS feed from: $resolvedOrigin")
        val response = fetchPodcastClient.fetchNoCache(resolvedOrigin)
        if (response !is FetchPodcastClientResult.Success) {
            Logger.e(TAG, "Failed to fetch RSS feed: $response")
            throw Exception(response.toString())
        }

        // A caller-supplied colour wins; otherwise derive a stable one from the
        // feed URL. Passing null straight through to `RssConverter.toPodcast`
        // made every podcast fall back to 0, i.e. the same placeholder art, and
        // every add path calls this with a null seed. Hashing the origin keeps
        // the colour identical across restarts and needs no storage.
        val seed = seedColor ?: artworkSeedFor(resolvedOrigin)
        val podcast = RssConverter.toPodcast(response.rssChannel, resolvedOrigin, response.fileSize, seed)
        val episodes = response.rssChannel.items.map { RssConverter.toPodcastEpisode(it, podcast) }
        Logger.i(TAG, "Parsed podcast: ${podcast.title}, ${episodes.size} episodes")

        return addPodcast(podcast, episodes, seedColor, false)
    }

    suspend fun addPodcastFromPreview(preview: PodcastPreviewModel, seedColor: Int?): AddPodcastResult {
        // Resolve iTunes lookup URLs to actual RSS feed URLs
        val origin = if (preview.fetchUrl.startsWith("itunes-lookup:")) {
            val idStr = preview.fetchUrl.removePrefix("itunes-lookup:")
            val id = idStr.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid iTunes ID: $idStr")
            Logger.d(TAG, "Resolving iTunes ID: $id")
            val resolved = appleClient.lookup.lookupById(id)
                ?: throw Exception("iTunes lookup failed for ID: $id")
            Logger.d(TAG, "Resolved to RSS feed: ${resolved.fetchUrl}")
            resolved.fetchUrl
        } else {
            preview.fetchUrl
        }

        Logger.i(TAG, "addPodcastFromPreview: origin=$origin, title=${preview.title}")

        db.podcasts.getByOrigin(origin)?.let {
            Logger.d(TAG, "Podcast already exists: ${it.title}")
            return AddPodcastResult.Duplicate(it)
        }

        val podcast = Podcast(
            origin = origin,
            link = preview.link,
            title = preview.title,
            description = preview.description,
            author = preview.author,
            imageUrl = preview.imageUrl,
            // Same stable derivation as the RSS-add path: Discover never passes
            // a colour, so without this every show added from preview shared
            // the 0 sentinel's placeholder.
            imageSeedColor = seedColor ?: artworkSeedFor(origin),
            languageCode = preview.languageCode,
            fileSize = 0
        )

        // Single write, but kept transactional like every other add path so
        // callers cannot observe a half-added show. The subscription row is
        // written by the caller (DiscoverScreen calls subscribe() right after),
        // which is what makes this the "preview" entry rather than a full add.
        db.transaction {
            podcasts.insert(podcast)
        }
        Logger.i(TAG, "Podcast saved from preview: ${podcast.title}")
        return AddPodcastResult.Created(podcast)
    }

    suspend fun addPodcast(podcast: Podcast, episodes: List<PodcastEpisode>, seedColor: Int?, duplicateCheck: Boolean = true): AddPodcastResult {
        if (duplicateCheck) {
            val existing = db.podcasts.getByOrigin(podcast.origin)
            if (existing != null) {
                // Already known, but "known" and "subscribed" are different
                // rows. A show left behind by the old bug — a `podcast` row
                // with no `podcastSubscription` row — would otherwise be
                // reported Duplicate and skipped, so an OPML import could never
                // repair the library it exists to restore. INSERT OR IGNORE
                // keeps this idempotent, so the ordinary "already there and
                // already subscribed" case costs one cheap no-op insert.
                ensureSubscribed(podcast.origin)
                return AddPodcastResult.Duplicate(existing)
            }
        }

        Logger.d(TAG, "Inserting podcast into database: ${podcast.title}")
        db.transaction {
            podcasts.insert(podcast)
            episodes.forEach { this.episodes.insert(it) }

            // A show that has no `podcastSubscription` row is only *known*, not
            // subscribed: SubscriptionManager.refreshAll() iterates that table,
            // so a podcast added here (OPML import, the "+" paste-URL dialog)
            // would never refresh, and the detail screen looks the row up to
            // decide whether to draw the subscribe button. INSERT OR IGNORE
            // keeps this idempotent.
            subscriptions.insert(podcast.origin, enableNotifications = false, enableAutoDownload = false)
        }
        Logger.i(TAG, "Podcast saved: ${podcast.title} (${episodes.size} episodes)")

        return AddPodcastResult.Created(podcast)
    }

    /**
     * Make sure a show that is only *known* also has a subscription row.
     *
     * `podcast` and `podcastSubscription` are separate tables, and they mean
     * different things: `refreshAll()` iterates the subscription table, and the
     * detail screen reads it to decide whether to draw a Subscribe button. A
     * `podcast` row without one is a show the user has, that never updates and
     * looks unsubscribed.
     *
     * Builds before this fix created exactly that state from OPML import, so
     * every "already exists" path repairs it rather than reporting Duplicate and
     * moving on — otherwise an import could never fix the library it is there to
     * restore. INSERT OR IGNORE keeps the ordinary case a no-op.
     */
    private suspend fun ensureSubscribed(origin: String) {
        db.subscriptions.insert(
            origin,
            enableNotifications = false,
            enableAutoDownload = false
        )
    }
}
