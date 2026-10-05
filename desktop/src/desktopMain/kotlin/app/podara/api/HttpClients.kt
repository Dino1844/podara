package app.podara.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The single [HttpClient] shared by every network call in the app.
 *
 * Previously each collaborator built its own client as a default constructor
 * argument — [app.podara.manager.PodcastManager], [app.podara.manager.SubscriptionManager],
 * [app.podara.api.apple.ApplePodcastClient] and [app.podara.manager.SyncManager]
 * each had one, and `DiscoverScreen` built a second `PodcastManager` plus its own
 * `ApplePodcastClient` on top of the ones in `App.kt`. That came to at least six
 * clients, each with its own connection pool and dispatcher threads, so no
 * connection or TLS handshake could be reused across them and every request paid
 * full connection-setup cost.
 *
 * One shared instance keeps connections warm across feed fetches, iTunes
 * lookups, and sync posts.
 *
 * Do not close this. It lives for the process. The per-client `close()` methods
 * that existed on [app.podara.api.apple.ApplePodcastClient] were removed rather
 * than kept as traps for callers that would close a client they do not own.
 */
object HttpClients {

    /**
     * Shared JSON configuration. Tolerant of unknown fields, nulls, and lenient
     * input, since these are third-party APIs that add fields without notice.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * Connect/socket/request budgets.
     *
     * Without these a stalled host blocks indefinitely — a feed refresh hangs
     * instead of failing, leaving the UI spinning with no way out. The request
     * budget is generous because large feeds legitimately take a while, while
     * connect timeouts stay short so dead hosts fail fast.
     */
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val SOCKET_TIMEOUT_MS = 30_000L
    private const val REQUEST_TIMEOUT_MS = 60_000L

    val shared: HttpClient by lazy {
        HttpClient(OkHttp) {
            // Redirects are followed, but statuses are not turned into
            // exceptions: callers inspect them to map 304 to "unchanged" and
            // other codes to typed failures.
            followRedirects = true
            expectSuccess = false

            engine {
                config {
                    // Recover from a connection the server closed while it sat
                    // idle in the pool, instead of surfacing a stale-socket
                    // failure to the caller.
                    retryOnConnectionFailure(true)
                    followRedirects(true)
                    followSslRedirects(true)
                }
            }

            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }

            // Compression needs no setup here: the OkHttp engine negotiates
            // gzip and deflate transparently and inflates the response. The feed
            // client used to defeat this by sending `Accept-Encoding: identity`,
            // which went away with the speculative HEAD request.

            install(ContentNegotiation) {
                json(json)
            }
        }
    }
}
