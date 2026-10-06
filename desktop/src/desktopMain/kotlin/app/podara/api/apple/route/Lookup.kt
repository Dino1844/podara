package app.podara.api.apple.route

import app.podara.api.apple.ApplePodcastClient
import app.podara.api.model.PodcastPreviewModel
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import app.podara.api.HttpClients

@Serializable
data class LookupResponse(
    val resultCount: Long,
    val results: List<LookupResult>,
)

@Serializable
data class LookupResult(
    val feedUrl: String? = null,
    val trackViewUrl: String,
    val trackName: String,
    val artistName: String,
    val artworkUrl600: String,
    val releaseDate: String? = null,
    val country: String,
)

// ── iTunes lookup with entity=podcastEpisode ──
// Returns recent episode(s) for a podcast — fast (~10 KB), no full RSS download needed.

@Serializable
data class PodcastLookupEpisodeResponse(
    val resultCount: Long,
    val results: List<PodcastEpisodeLookupResult>,
)

@Serializable
data class PodcastEpisodeLookupResult(
    val kind: String? = null,
    val trackId: Long? = null,
    val trackName: String? = null,
    val trackTimeMillis: Long? = null,
    val episodeUrl: String? = null,
    val previewUrl: String? = null,
    val releaseDate: String? = null,
    val description: String? = null,
    val shortDescription: String? = null,
    val episodeGuid: String? = null,
    val artworkUrl600: String? = null,
    val feedUrl: String? = null,
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val country: String? = null,
)

class Lookup(
    val client: ApplePodcastClient
) {

    suspend fun lookupById(id: Long): PodcastPreviewModel? {
        val body = client.httpClient.get("https://itunes.apple.com/lookup?id=$id&media=podcast").body<String>()
        val response = HttpClients.json.decodeFromString<LookupResponse>(body)
        val result = response.results.firstOrNull() ?: return null
        val feedUrl = result.feedUrl ?: return null

        return PodcastPreviewModel(
            fetchUrl = feedUrl,
            link = result.trackViewUrl,
            title = result.trackName,
            description = result.releaseDate ?: "",
            author = result.artistName,
            imageUrl = result.artworkUrl600,
            languageCode = result.country.lowercase()
        )
    }

    suspend fun batchLookupFeedUrls(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val idParam = ids.joinToString(",")
        val body = client.httpClient.get("https://itunes.apple.com/lookup?id=$idParam&media=podcast").body<String>()
        val response = HttpClients.json.decodeFromString<LookupResponse>(body)
        return response.results.mapNotNull { result ->
            val feedUrl = result.feedUrl ?: return@mapNotNull null
            val id = extractTrackId(result.trackViewUrl) ?: return@mapNotNull null
            id to feedUrl
        }.toMap()
    }

    /**
     * Fetches recent episodes for a podcast via the iTunes Lookup API.
     * Uses the semi-undocumented `entity=podcastEpisode` parameter which returns
     * up to ~200 recent episodes without needing to download the full RSS feed.
     *
     * Response is lightweight (~10 KB) compared to downloading the full RSS XML
     * (which can be multiple MB for podcasts with hundreds of episodes).
     *
     * Results are ordered by releaseDate descending (newest first).
     */
    suspend fun lookupLatestEpisodes(id: Long, limit: Int = 1): List<PodcastEpisodeLookupResult> {
        val body = client.httpClient.get(
            "https://itunes.apple.com/lookup?id=$id&country=US&media=podcast&entity=podcastEpisode&limit=$limit"
        ).body<String>()
        val response = HttpClients.json.decodeFromString<PodcastLookupEpisodeResponse>(body)
        return response.results.filter { it.kind == "podcast-episode" }
    }

}

/**
 * Extracts the numeric track ID from an iTunes trackViewUrl, e.g.
 * `https://podcasts.apple.com/us/podcast/name/id123456` → 123456.
 *
 * The previous implementation (`substringAfterLast("/id").substringBefore("/")`)
 * dropped every URL carrying a query string: `.../id123456?i=789` has no `/`
 * after the id, so the whole `123456?i=789` failed to parse and the podcast was
 * silently left out of the batch map — which made subscribed shows render as
 * unsubscribed. The query string is now cut first.
 */
internal fun extractTrackId(trackViewUrl: String): Long? =
    trackViewUrl
        .substringAfterLast("/id")
        .substringBefore("/")
        .substringBefore("?")
        .toLongOrNull()
