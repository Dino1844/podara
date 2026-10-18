package app.podara.desktop

import app.podara.api.apple.TopChartsCache
import app.podara.api.model.PodcastPreviewModel
import java.io.File
import kotlin.test.*

class TopChartsCacheTest {

    private lateinit var cacheDir: File
    private var fakeNow = 0L

    @BeforeTest
    fun setup() {
        cacheDir = File(
            System.getProperty("java.io.tmpdir"),
            "podara_charts_test_${System.currentTimeMillis()}_${(0..9999).random()}"
        )
        cacheDir.mkdirs()
    }

    @AfterTest
    fun teardown() {
        cacheDir.deleteRecursively()
    }

    private fun newCache() = TopChartsCache(cacheDir, nowMillis = { fakeNow })

    private fun samplePodcasts() = listOf(
        PodcastPreviewModel(
            fetchUrl = "itunes-lookup:123",
            link = "https://example.com/1",
            title = "Charts One",
            description = "First chart podcast",
            author = "Author One",
            imageUrl = "https://example.com/1.jpg",
            languageCode = "unknown"
        ),
        PodcastPreviewModel(
            fetchUrl = "itunes-lookup:456",
            link = "https://example.com/2",
            title = "Charts Two",
            description = "Second chart podcast",
            author = "Author Two",
            imageUrl = "https://example.com/2.jpg",
            languageCode = "unknown"
        )
    )

    @Test
    fun testRoundTripSaveAndLoad() {
        val cache = newCache()
        cache.save("US", samplePodcasts())

        assertEquals(samplePodcasts(), cache.load("US"))
    }

    @Test
    fun testLoadReturnsNullWhenNothingCached() {
        assertNull(newCache().load("US"))
    }

    @Test
    fun testLoadReturnsNullForCountryMismatch() {
        val cache = newCache()
        cache.save("US", samplePodcasts())

        assertNull(cache.load("CN"))
        assertEquals(samplePodcasts(), cache.load("US"))
    }

    @Test
    fun testLoadReturnsNullForCorruptFile() {
        val cache = newCache()
        cache.save("US", samplePodcasts())
        cache.cacheFile.writeText("not json at all {{")

        assertNull(cache.load("US"))
    }

    @Test
    fun testLoadReturnsNullWhenCacheExpired() {
        val cache = newCache()
        fakeNow = 1_000_000L
        cache.save("US", samplePodcasts())

        // Still usable well inside the max age...
        fakeNow = 1_000_000L + 24L * 60 * 60 * 1000
        assertEquals(samplePodcasts(), cache.load("US"))

        // ...but not one millisecond past it.
        fakeNow = 1_000_000L + TopChartsCache.DEFAULT_MAX_AGE_MILLIS + 1
        assertNull(cache.load("US"))
    }

    @Test
    fun testSaveOverwritesPreviousContent() {
        val cache = newCache()
        cache.save("US", samplePodcasts())
        val replacement = listOf(
            PodcastPreviewModel(
                fetchUrl = "itunes-lookup:789",
                link = "https://example.com/3",
                title = "Charts Three",
                description = "Replacement podcast",
                author = "Author Three",
                imageUrl = "https://example.com/3.jpg",
                languageCode = "unknown"
            )
        )
        cache.save("US", replacement)

        assertEquals(replacement, cache.load("US"))
    }

    @Test
    fun testLoadReturnsNullForEmptyCachedList() {
        val cache = newCache()
        cache.save("US", emptyList())

        assertNull(cache.load("US"))
    }

    @Test
    fun testSaveGoesThroughATempFileAndLeavesNoLeftovers() {
        // The write is temp-then-rename so a crash mid-write cannot truncate the
        // live cache (a truncated file reads as "no cache", which silently cost
        // the next start its instant render). This asserts the temp file is
        // cleaned up rather than accumulating next to the cache.
        val cache = newCache()
        cache.save("US", samplePodcasts())

        val leftovers = cacheDir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue(leftovers.isEmpty(), "temp files left behind: ${leftovers.map { it.name }}")
        assertEquals(samplePodcasts(), cache.load("US"))
    }

    @Test
    fun testFailedWriteLeavesThePreviousCacheReadable() {
        // Simulate the write step failing after the old cache exists: the rename
        // target must still hold the old, parseable contents.
        val cache = newCache()
        cache.save("US", samplePodcasts())

        // Block the temp path with a directory so `writeText` throws.
        val temp = File(cacheDir, "${cache.cacheFile.name}.tmp")
        temp.mkdirs()
        try {
            cache.save("CN", samplePodcasts().take(1))

            // The old cache is intact and still parses.
            assertEquals(samplePodcasts(), cache.load("US"))
        } finally {
            temp.deleteRecursively()
        }
    }
}
