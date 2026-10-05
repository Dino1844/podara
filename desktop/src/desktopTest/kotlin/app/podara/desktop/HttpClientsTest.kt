package app.podara.desktop

import app.podara.api.HttpClients
import app.podara.api.apple.ApplePodcastClient
import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shared client is a process-wide singleton, and that identity is the whole
 * point: every caller must resolve to the same instance or connection reuse is
 * lost and the original many-pools problem returns.
 *
 * These tests exercise it through a real local server rather than a mock engine,
 * matching [FetchPodcastClientTest], so they cover the actual OkHttp engine and
 * its redirect/compression behaviour.
 */
class HttpClientsTest {

    @Test
    fun testSharedClientIsASingleton() {
        assertSame(
            HttpClients.shared,
            HttpClients.shared,
            "every caller must resolve to the same client or pooling is defeated"
        )
    }

    @Test
    fun testAllConsumersShareTheSameClient() {
        assertSame(HttpClients.shared, ApplePodcastClient().httpClient)
        assertSame(HttpClients.shared, FetchPodcastClient().client)
    }

    @Test
    fun testRepeatedConstructionDoesNotMultiplyPools() {
        // Constructing managers and clients is cheap and must not create new
        // pools — these are the exact call sites that used to each build one.
        val clients = (1..10).map { FetchPodcastClient().client }.toSet()
        assertEquals(1, clients.size, "each FetchPodcastClient must reuse the shared pool")
    }

    @Test
    fun testJsonConfigurationToleratesUnknownFields() {
        // Third-party payloads gain fields without notice; an unknown key must
        // not throw during decoding.
        val payload = """{"resultCount":1,"surpriseField":"ignored","results":[]}"""
        val decoded = HttpClients.json.decodeFromString<SimpleLookup>(payload)
        assertEquals(1L, decoded.resultCount)
        assertEquals(0, decoded.results.size)
    }

    @Serializable
    private data class SimpleLookup(
        val resultCount: Long,
        val results: List<String> = emptyList()
    )

    @Test
    fun testSharedClientFetchesThroughOkHttpEngine() = runBlocking {
        val feed = validFeed("Shared Client Podcast")
        val server = feedServer(feed) { exchange ->
            val bytes = feed.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/rss+xml; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        try {
            // Uses HttpClients.shared, the same instance production code uses.
            val result = FetchPodcastClient().fetchNoCache(
                "http://127.0.0.1:${server.address.port}/feed.xml"
            )

            assertTrue(result is FetchPodcastClientResult.Success)
            assertEquals("Shared Client Podcast", result.rssChannel.title)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testSharedClientAcceptsCompressedFeed() = runBlocking {
        // The OkHttp engine negotiates gzip transparently and inflates the
        // body; the feed parser must still receive plain XML.
        val feed = validFeed("Compressed Feed Podcast")
        val gzipped = ByteArrayOutputStream().let { buffer ->
            GZIPOutputStream(buffer).use { it.write(feed.toByteArray()) }
            buffer.toByteArray()
        }

        val server = feedServer(feed) { exchange ->
            if (exchange.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") == true) {
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, gzipped.size.toLong())
                exchange.responseBody.use { it.write(gzipped) }
            } else {
                val bytes = feed.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        try {
            val result = FetchPodcastClient().fetchNoCache(
                "http://127.0.0.1:${server.address.port}/feed.xml"
            )

            assertTrue(result is FetchPodcastClientResult.Success, "gzip body should be inflated")
            assertEquals("Compressed Feed Podcast", result.rssChannel.title)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchRequestsXmlAcceptHeader() = runBlocking {
        // Some hosts serve feeds as text/html when no type is requested, which
        // downstream parsers mishandle.
        var accept: String? = null
        val feed = validFeed("Accept Header Podcast")
        val server = feedServer(feed) { exchange ->
            accept = exchange.requestHeaders.getFirst("Accept")
            val bytes = feed.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        try {
            FetchPodcastClient().fetchNoCache("http://127.0.0.1:${server.address.port}/feed.xml")
            assertTrue(
                accept?.contains("xml", ignoreCase = true) == true,
                "expected an XML Accept header, got: $accept"
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchFollowsRedirects() = runBlocking {
        val feed = validFeed("Redirected Podcast")
        val target = feedServer(feed) { exchange ->
            val bytes = feed.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val redirector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed.xml") { exchange ->
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${target.address.port}/feed.xml"
                )
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }

        try {
            val result = FetchPodcastClient().fetchNoCache(
                "http://127.0.0.1:${redirector.address.port}/feed.xml"
            )
            assertTrue(result is FetchPodcastClientResult.Success)
            assertEquals("Redirected Podcast", result.rssChannel.title)
        } finally {
            redirector.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun testFetchFailsFastOnUnreachableHostRatherThanHanging() = runBlocking {
        // Port 1 on loopback is closed. Without a connect timeout this would
        // hang rather than return a typed Failure.
        val result = FetchPodcastClient().fetchNoCache("http://127.0.0.1:1/feed.xml")
        assertTrue(
            result is FetchPodcastClientResult.Failure,
            "an unreachable host must surface as Failure, got $result"
        )
    }

    private fun feedServer(
        feed: String,
        handler: (com.sun.net.httpserver.HttpExchange) -> Unit
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/feed.xml", handler)
        start()
    }

    private fun validFeed(title: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>$title</title>
            <link>https://example.com</link>
            <description>desc</description>
            <item>
              <title>Episode 1</title>
              <guid>ep-1</guid>
              <enclosure url="https://example.com/audio.mp3" type="audio/mpeg"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()
}
