package app.podara.api.rss

import com.prof18.rssparser.RssParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
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

open class FetchPodcastClient(
    val client: HttpClient = HttpClient { },
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

    open suspend fun fetch(
        origin: String,
        lastModified: String,
        eTag: String,
        contentLength: String
    ): FetchPodcastClientResult {
        try {
            var newContentLength: String? = null
            try {
                val headResponse = client.head(origin) {
                    header(HttpHeaders.AcceptEncoding, "identity")
                    addCacheHeaders(lastModified, eTag)
                }

                if (headResponse.status == HttpStatusCode.NotModified) {
                    headResponse.bodyAsChannel().cancel(null)
                    return FetchPodcastClientResult.Unchanged("ETAG or LAST-MODIFIED head")
                }
                if (headResponse.status == HttpStatusCode.OK) {
                    newContentLength = headResponse.headers[HttpHeaders.ContentLength]
                }
                headResponse.bodyAsChannel().cancel(null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // HEAD is only an optimization. Many podcast hosts reject or mishandle it.
            }

            return get(
                origin = origin,
                lastModified = lastModified,
                eTag = eTag,
                newContentLength = newContentLength
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FetchPodcastClientResult.Failure(e)
        }
    }

    private suspend fun get(
        origin: String,
        lastModified: String? = null,
        eTag: String? = null,
        newContentLength: String? = null
    ): FetchPodcastClientResult {
        val response = client.get(origin) {
            addCacheHeaders(lastModified, eTag)
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
                    contentLength = newContentLength
                        ?: response.headers[HttpHeaders.ContentLength]
                        ?: ""
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
