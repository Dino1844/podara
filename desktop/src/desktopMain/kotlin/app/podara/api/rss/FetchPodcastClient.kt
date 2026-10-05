package app.podara.api.rss

import app.podara.api.HttpClients
import com.prof18.rssparser.RssParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

sealed interface FetchPodcastClientResult {
    data class Success(
        val rssChannel: com.prof18.rssparser.model.RssChannel,
        val fileSize: Long,
        val lastModified: String,
        val eTag: String,
        val contentLength: String
    ) : FetchPodcastClientResult

    class Unchanged(
        val reason: String
    ) : FetchPodcastClientResult

    data class Failure(val e: Exception) : FetchPodcastClientResult
}

/**
 * Fetches and parses podcast RSS feeds.
 *
 * Shares the process-wide [app.podara.api.HttpClients.shared] client so feed
 * requests reuse connections instead of establishing new ones per feed.
 *
 * Note on request shape: this performs a conditional GET only. It previously
 * issued a speculative HEAD first to read Content-Length, which added a full
 * round trip to every refresh — and when a host rejected or mishandled the
 * HEAD (common enough that the original code had to guard it) that latency was
 * pure waste. A conditional GET already answers the only question the HEAD was
 * asked: whether the feed changed.
 */
open class FetchPodcastClient(
    val client: HttpClient = HttpClients.shared,
    private val maxFeedBytes: Long = DEFAULT_MAX_FEED_BYTES
) {
    companion object {
        internal const val DEFAULT_MAX_FEED_BYTES = 10L * 1024 * 1024
    }

    private val rssParser = RssParser()

    init {
        require(maxFeedBytes in 1 until Long.MAX_VALUE) {
            "maxFeedBytes must be positive and less than Long.MAX_VALUE"
        }
    }

    /**
     * Conditional GET against [origin].
     *
     * [lastModified] and [eTag] are the cached validators; when either is
     * present the server answers 304 if the feed is unchanged.
     *
     * There is deliberately no Content-Length parameter. It once drove a
     * speculative HEAD that never actually short-circuited anything — an equal
     * length says nothing about whether the content changed — so it only added
     * a round trip per refresh.
     */
    open suspend fun fetch(
        origin: String,
        lastModified: String,
        eTag: String
    ): FetchPodcastClientResult = try {
        get(origin = origin, lastModified = lastModified, eTag = eTag)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FetchPodcastClientResult.Failure(e)
    }

    private suspend fun get(
        origin: String,
        lastModified: String? = null,
        eTag: String? = null
    ): FetchPodcastClientResult {
        val response = client.get(origin) {
            addCacheHeaders(lastModified, eTag)
            // Ask for XML explicitly. Some hosts serve feeds as text/html when
            // no type is requested, which downstream parsers then mishandle.
            header(HttpHeaders.Accept, ContentType.Application.Xml.toString())
        }

        when (response.status) {
            HttpStatusCode.OK -> {
                val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declaredLength != null && declaredLength > maxFeedBytes) {
                    val exception = FeedTooLargeException(maxFeedBytes)
                    response.bodyAsChannel().cancel(exception)
                    throw exception
                }

                val channel = response.bodyAsChannel()
                val bytes = channel
                    .readRemaining(maxFeedBytes + 1)
                    .readByteArray()
                if (bytes.size > maxFeedBytes) {
                    val exception = FeedTooLargeException(maxFeedBytes)
                    channel.cancel(exception)
                    throw exception
                }

                return FetchPodcastClientResult.Success(
                    rssChannel = rssParser.parse(bytes.decodeToString()),
                    fileSize = bytes.size.toLong(),
                    eTag = response.headers[HttpHeaders.ETag] ?: "",
                    lastModified = response.headers[HttpHeaders.LastModified] ?: "",
                    contentLength = response.headers[HttpHeaders.ContentLength] ?: ""
                )
            }

            HttpStatusCode.NotModified -> {
                response.bodyAsChannel().cancel(null)
                return FetchPodcastClientResult.Unchanged("ETAG or LAST-MODIFIED get")
            }
        }

        val exception = Exception("UNHANDLED STATUS CODE ${response.status}")
        response.bodyAsChannel().cancel(exception)
        throw exception
    }

    private fun io.ktor.client.request.HttpRequestBuilder.addCacheHeaders(
        lastModified: String?,
        eTag: String?
    ) {
        if (!eTag.isNullOrBlank()) {
            header(HttpHeaders.IfNoneMatch, eTag)
        }
        if (!lastModified.isNullOrBlank()) {
            header(HttpHeaders.IfModifiedSince, lastModified)
        }
    }

    open suspend fun fetchNoCache(
        origin: String
    ): FetchPodcastClientResult {
        return try {
            get(origin = origin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FetchPodcastClientResult.Failure(e)
        }
    }
}

private class FeedTooLargeException(maxBytes: Long) :
    Exception("Podcast feed exceeds the maximum size of $maxBytes bytes")
