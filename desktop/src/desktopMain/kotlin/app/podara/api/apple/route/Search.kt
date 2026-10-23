package app.podara.api.apple.route

import app.podara.api.HttpClients
import app.podara.api.applyUserAgent
import app.podara.api.apple.ApplePodcastClient
import app.podara.api.apple.model.SearchResponse
import app.podara.api.model.PodcastPreviewModel
import io.ktor.client.call.body
import io.ktor.client.request.get

class Search(
    val client: ApplePodcastClient
) {

    suspend fun search(
        query: String,
        countryCode: String = "US"
    ): List<PodcastPreviewModel> {
        val body = client.httpClient.get(buildSearchUrl(query, countryCode)) {
            applyUserAgent()
        }.body<String>()

        val response = HttpClients.json.decodeFromString<SearchResponse>(body)
        return dedupeByFeedUrl(response.results.mapNotNull { it.toPodcastPreview() })
    }

}

/**
 * Drops results that resolve to a feed already present earlier in the list.
 *
 * iTunes legitimately returns several catalogue entries pointing at one feed —
 * usually a re-listed show, where the old and the new `collectionId` both carry
 * the same `feedUrl`. Observed live: `radiolab` returns two "Radio Lab" entries
 * for one SoundCloud feed, `npr` returns two "Planet Money" entries for
 * `feeds.npr.org/510289/podcast.xml`, and `history` returns six such pairs.
 *
 * That matters because the results list is a `LazyColumn` keyed on `fetchUrl`
 * (`DiscoverScreen`), and Compose throws `IllegalArgumentException: Key was
 * already used` when two items share a key. The duplicate therefore did not
 * merely repeat a row — it took the whole Discover screen down, which is why a
 * search for a perfectly ordinary word could appear to "do nothing".
 *
 * Deduplicating on `fetchUrl` is the honest fix: these are genuinely the same
 * podcast as far as this app is concerned, since subscribing resolves to that
 * feed and would produce one row regardless. The first occurrence wins because
 * iTunes orders results by relevance. Comparison is case-insensitive on the
 * scheme and host so a mixture of `HTTP://` and `http://` cannot slip through.
 */
internal fun dedupeByFeedUrl(previews: List<PodcastPreviewModel>): List<PodcastPreviewModel> {
    val seen = HashSet<String>(previews.size)
    return previews.filter { preview -> seen.add(normalizeFeedUrl(preview.fetchUrl)) }
}

/** Comparison key for [dedupeByFeedUrl]; see there for why it is case-insensitive. */
private fun normalizeFeedUrl(url: String): String = url.trim().lowercase()

/**
 * Builds the iTunes search URL with the query term percent-encoded.
 *
 * The term used to be interpolated raw, so `cats & dogs` arrived at the server
 * as `term=cats & dogs=...` — an extra empty parameter and a truncated term,
 * and any query containing `&`, `=`, `#` or `%` broke the same way.
 * [java.net.URLEncoder] is the right encoder here: it is the
 * `application/x-www-form-urlencoded` format query strings are specified in,
 * including `+` for spaces.
 */
internal fun buildSearchUrl(query: String, countryCode: String): String {
    val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8)
    return "https://itunes.apple.com/search?media=podcast&country=$countryCode&term=$encoded"
}
