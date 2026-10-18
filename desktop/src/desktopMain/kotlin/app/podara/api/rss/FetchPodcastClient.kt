package app.podara.api.rss

import app.podara.api.HttpClients
import app.podara.api.applyUserAgent
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
import java.nio.charset.Charset

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
            // Identify the client: Acast, Megaphone and Art19 answer 403 to a
            // request that only looks like a bare HTTP library.
            applyUserAgent()
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

                val charset = detectCharset(response.headers[HttpHeaders.ContentType], bytes)
                return FetchPodcastClientResult.Success(
                    // Feed bytes are not always UTF-8: GBK and ISO-8859-1 feeds
                    // are common outside the English-speaking world, and
                    // `decodeToString()` assumed UTF-8, so their titles and
                    // descriptions arrived as mojibake.
                    rssChannel = rssParser.parse(String(bytes, charset)),
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

/**
 * Picks the charset a feed body should be decoded with.
 *
 * Precedence is HTTP first, then the XML declaration, then UTF-8:
 * 1. The `charset` parameter of the response `Content-Type` is authoritative —
 *    an HTTP header overrides in-document declarations (RFC 7231 §3.1.1.1), and
 *    it is what the server actually labelled the bytes it produced.
 * 2. `encoding="..."` in the XML declaration. The scanner stops at the end of
 *    the declaration or after the first 200 bytes, matching the XML spec's
 *    requirement that the declaration, if present, must open the document — so
 *    a byte order mark or BOM-less ASCII prolog still resolves, while an
 *    `encoding=` string deep in some episode description never wins.
 * 3. UTF-8, the XML default, and the only safe fallback for a body that says
 *    nothing about its encoding.
 *
 * The declared name is resolved leniently: feeds in the wild carry `utf8`,
 * `gb2312` and other spellings a strict lookup rejects, and `Charset.forName`
 * throws on unknown names. A bad label must degrade to UTF-8 rather than take
 * the whole feed down with an exception.
 */
internal fun detectCharset(contentType: String?, bytes: ByteArray): Charset {
    charsetFromContentType(contentType)?.let { return it }
    charsetFromXmlDeclaration(bytes)?.let { return it }
    return Charsets.UTF_8
}

/**
 * Reads the `charset=` parameter out of a `Content-Type` header value.
 *
 * Parsed by hand rather than with [io.ktor.http.ContentType.parse] because a
 * stray quoting or spacing quirk should not throw on the fetch path; anything
 * unparseable simply falls through to the next signal.
 */
private fun charsetFromContentType(contentType: String?): Charset? {
    if (contentType.isNullOrBlank()) return null
    // Every segment is examined rather than only those after the media type:
    // the media type itself cannot contain '=', so this costs nothing and still
    // reads a malformed header that is just `charset=gbk`.
    val parameter = contentType
        .split(';')
        .firstOrNull { it.substringBefore('=').trim().equals("charset", ignoreCase = true) }
        ?: return null
    val name = parameter.substringAfter('=', "").trim().trim('"', '\'')
    return charsetOrNull(name)
}

/**
 * Extracts `encoding="..."` from the XML declaration within the first 200 bytes.
 *
 * The bytes are peeked as ISO-8859-1 rather than decoded with a guessed
 * charset: every encoding a declaration can name is ASCII-compatible for the
 * `<?xml ... encoding="..."?>` prolog, so a byte-per-character view reads the
 * declaration correctly without having to know the answer beforehand.
 *
 * The declaration may open with a BOM the byte view renders as junk, so the
 * scan looks for `<?xml` anywhere in the window rather than only at offset 0.
 */
private fun charsetFromXmlDeclaration(bytes: ByteArray): Charset? {
    if (bytes.isEmpty()) return null
    val head = String(bytes, 0, minOf(bytes.size, XML_DECLARATION_SCAN_BYTES), Charsets.ISO_8859_1)
    val declarationStart = head.indexOf("<?xml", ignoreCase = true)
    if (declarationStart < 0) return null
    val declaration = head.substring(declarationStart).substringBefore("?>", "")
    val encodingAttribute = XML_ENCODING_ATTRIBUTE.find(declaration) ?: return null
    return charsetOrNull(encodingAttribute.groupValues[1])
}

/**
 * Resolves a declared charset name, or null when it is blank or unknown.
 *
 * `runCatching` is doing real work here: [Charset.forName] throws on illegal or
 * unsupported names, and a feed must still be parsed with the UTF-8 fallback
 * instead of failing the refresh.
 */
private fun charsetOrNull(name: String): Charset? =
    runCatching { Charset.forName(name.trim()) }.getOrNull()

/** The XML declaration, if present at all, has to open the document. */
private const val XML_DECLARATION_SCAN_BYTES = 200

/** Matches `encoding="utf-8"` inside a declaration, in either quote style. */
private val XML_ENCODING_ATTRIBUTE = Regex(
    """encoding\s*=\s*["']([^"']+)["']""",
    RegexOption.IGNORE_CASE
)
