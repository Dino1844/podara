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
    fun testFetchDoesNotTreatEqualContentLengthAsUnchanged() = runBlocking {
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
        var getRequested = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed.xml") { exchange ->
                if (exchange.requestMethod == "HEAD") {
                    exchange.responseHeaders.add("Content-Length", "123")
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    getRequested = true
                    val bytes = xml.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/rss+xml; charset=utf-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            start()
        }

        try {
            val client = FetchPodcastClient()
            val result = client.fetch(
                origin = "http://127.0.0.1:${server.address.port}/feed.xml",
                lastModified = "",
                eTag = "",
                contentLength = "123"
            )

            assertTrue(getRequested, "Fetch should perform GET even when HEAD Content-Length matches cached value")
            assertTrue(result is FetchPodcastClientResult.Success)
            assertEquals("Length Changed Podcast", result.rssChannel.title)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchFallsBackToConditionalGetWhenHeadIsRejected() = runBlocking {
        val xml = validFeed("HEAD Fallback Podcast")
        var receivedETag: String? = null
        var receivedLastModified: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed.xml") { exchange ->
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(405, -1)
                } else {
                    receivedETag = exchange.requestHeaders.getFirst("If-None-Match")
                    receivedLastModified = exchange.requestHeaders.getFirst("If-Modified-Since")
                    val bytes = xml.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            start()
        }

        try {
            val result = FetchPodcastClient().fetch(
                origin = "http://127.0.0.1:${server.address.port}/feed.xml",
                lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                eTag = "\"cached-etag\"",
                contentLength = ""
            )

            assertTrue(result is FetchPodcastClientResult.Success)
            assertEquals("HEAD Fallback Podcast", result.rssChannel.title)
            assertEquals("\"cached-etag\"", receivedETag)
            assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", receivedLastModified)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testFetchFallsBackToGetWhenHeadRequestFails() = runBlocking {
        val xml = validFeed("HEAD Failure Podcast")
        var getRequested = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/feed.xml") { exchange ->
                if (exchange.requestMethod == "HEAD") {
                    exchange.close()
                } else {
                    getRequested = true
                    val bytes = xml.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            start()
        }

        try {
            val result = FetchPodcastClient().fetch(
                origin = "http://127.0.0.1:${server.address.port}/feed.xml",
                lastModified = "",
                eTag = "",
                contentLength = ""
            )

            assertTrue(getRequested)
            assertTrue(result is FetchPodcastClientResult.Success)
            assertEquals("HEAD Failure Podcast", result.rssChannel.title)
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
