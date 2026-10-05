package app.podara.api.apple

import app.podara.api.HttpClients
import app.podara.api.apple.route.Lookup
import app.podara.api.apple.route.Search
import app.podara.api.apple.route.TopPodcasts
import io.ktor.client.HttpClient

/**
 * Client for Apple's public iTunes Search / Lookup / Top Podcasts APIs.
 *
 * Uses the process-wide [HttpClients.shared] instance so requests share one
 * connection pool. This class no longer owns its client and has no `close()` —
 * closing the shared instance would break every other caller.
 */
class ApplePodcastClient(
    val httpClient: HttpClient = HttpClients.shared
) {
    val lookup = Lookup(this)
    val search = Search(this)
    val topPodcasts = TopPodcasts(this)
}
