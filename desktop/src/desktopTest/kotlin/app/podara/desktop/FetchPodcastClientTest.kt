package app.podara.desktop

import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchPodcastClientTest {

    @Test
    fun testFetchUsesASingleGetAndNoSpeculativeHead() = runBlocking {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed.xml") { exchange ->
                methods += exchange.requestMethod
                val bytes = validFeed("Single Round Trip Podcast").toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/rss+xml; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            val result = FetchPodcastClient().fetch(
                origin = "http://127.0.0.1:${server.address.port}/feed.xml",
                lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                eTag = "\"cached-etag\"",
            )

            assertEquals(
                listOf("GET"), methods,
                "a refresh must not issue a speculative HEAD; it doubled the round trips"
            )
            assertTrue(result is FetchPodcastClientResult.Success)
            assertEquals("Single Round Trip Podcast", result.rssChannel.title)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchSendsConditionalHeadersOnGet() = runBlocking {
        val xml = validFeed("Conditional Podcast")
        var receivedETag: String? = null
        var receivedLastModified: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed.xml") { exchange ->
                receivedETag = exchange.requestHeaders.getFirst("If-None-Match")
                receivedLastModified = exchange.requestHeaders.getFirst("If-Modified-Since")
                val bytes = xml.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            val result = FetchPodcastClient().fetch(
                origin = "http://127.0.0.1:${server.address.port}/feed.xml",
                lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                eTag = "\"cached-etag\"",
            )

            assertTrue(result is FetchPodcastClientResult.Success)
            assertEquals("Conditional Podcast", result.rssChannel.title)
            assertEquals("\"cached-etag\"", receivedETag)
            assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", receivedLastModified)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchAlwaysParsesTheServedBody() = runBlocking {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Length Changed Podcast</title>
                <link>https://example.com</link>
                <description>Content changed even though length header matched</description>
                <item>
                  <title>Episode 1</title>
                  <guid>ep-1</guid>
                  <enclosure url="https://example.com/audio.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed.xml") { exchange ->
                val bytes = xml.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/rss+xml; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            // Only the server can say the feed changed, so a 200 is always
            // parsed rather than compared against a cached size.
            val result = FetchPodcastClient().fetch(
                origin = "http://127.0.0.1:${server.address.port}/feed.xml",
                lastModified = "",
                eTag = ""
            )

            assertTrue(result is FetchPodcastClientResult.Success)
            assertEquals("Length Changed Podcast", result.rssChannel.title)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchReportsUnchangedOnNotModified() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed.xml") { exchange ->
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
            }
            start()
        }

        try {
            val result = FetchPodcastClient().fetch(
                origin = "http://127.0.0.1:${server.address.port}/feed.xml",
                lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                eTag = "\"cached-etag\"",
            )

            assertTrue(
                result is FetchPodcastClientResult.Unchanged,
                "a 304 means the cached feed is still current"
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchRejectsDeclaredOversizedFeed() = runBlocking {
        val bytes = "x".repeat(256).toByteArray()
        val server = oversizedFeedServer(bytes, chunked = false)

        try {
            val result = FetchPodcastClient(maxFeedBytes = 128).fetchNoCache(
                "http://127.0.0.1:${server.address.port}/feed.xml"
            )

            assertTrue(result is FetchPodcastClientResult.Failure)
            assertTrue(result.e.message.orEmpty().contains("maximum size"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchRejectsChunkedOversizedFeed() = runBlocking {
        val bytes = "x".repeat(256).toByteArray()
        val server = oversizedFeedServer(bytes, chunked = true)

        try {
            val result = FetchPodcastClient(maxFeedBytes = 128).fetchNoCache(
                "http://127.0.0.1:${server.address.port}/feed.xml"
            )

            assertTrue(result is FetchPodcastClientResult.Failure)
            assertTrue(result.e.message.orEmpty().contains("maximum size"))
        } finally {
            server.stop(0)
        }
    }

    private fun oversizedFeedServer(bytes: ByteArray, chunked: Boolean): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed.xml") { exchange ->
                exchange.sendResponseHeaders(200, if (chunked) 0 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun validFeed(title: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>$title</title>
            <link>https://example.com</link>
            <description>Test feed</description>
            <item>
              <title>Episode 1</title>
              <guid>ep-1</guid>
              <enclosure url="https://example.com/audio.mp3" type="audio/mpeg"/>
            </item>
          </channel>
        </rss>
    """.trimIndent()
}
