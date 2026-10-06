package app.podara.api.apple.route

import app.podara.api.HttpClients
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
        val body = client.httpClient.get(buildSearchUrl(query, countryCode)).body<String>()

        val response = HttpClients.json.decodeFromString<SearchResponse>(body)
        return response.results.mapNotNull { it.toPodcastPreview() }
    }

}

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
