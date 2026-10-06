package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import app.podara.manager.SubscriptionManager
import app.podara.manager.UpdatePodcastResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The auto-download chain, end to end minus the real downloader:
 *
 *  - [app.podara.data.SubscriptionDao.setAutoDownload] persists the per-
 *    subscription flag the UI toggle writes.
 *  - [SubscriptionManager.updatePodcast] hands the episodes a feed update just
 *    added to the injected starter, and only when the flag is on. The starter
 *    is a lambda so no network download happens here; what matters is that it
 *    receives exactly the new episodes and nothing else.
 */
class AutoDownloadTest {

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File

    private val origin = "https://example.com/feed.xml"

    @BeforeTest
    fun setup() {
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podara_auto_download_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
    }

    @AfterTest
    fun teardown() {
        database.close()
        testDbFile.delete()
    }

    private fun subscriptionManager(
        autoDownloadStarter: suspend (List<PodcastEpisode>) -> Unit = {}
    ) = SubscriptionManager(
        database,
        fetchPodcastClient = FakeFetchPodcastClient(),
        autoDownloadStarter = autoDownloadStarter
    )

    // ── DAO ──

    @Test
    fun setAutoDownloadPersistsTrueAndFalse() = runBlocking {
        subscriptionManager().subscribe(origin)
        assertFalse(database.subscriptions.getByOriginSync(origin)!!.enableAutoDownload,
            "new subscriptions start with auto-download off")

        database.subscriptions.setAutoDownload(origin, true)
        assertTrue(database.subscriptions.getByOriginSync(origin)!!.enableAutoDownload)

        database.subscriptions.setAutoDownload(origin, false)
        assertFalse(database.subscriptions.getByOriginSync(origin)!!.enableAutoDownload)
    }

    @Test
    fun setAutoDownloadOnlyTouchesItsOwnSubscription() = runBlocking {
        val otherOrigin = "https://example.com/other.xml"
        subscriptionManager().subscribe(origin)
        subscriptionManager().subscribe(otherOrigin)

        database.subscriptions.setAutoDownload(origin, true)

        assertTrue(database.subscriptions.getByOriginSync(origin)!!.enableAutoDownload)
        assertFalse(database.subscriptions.getByOriginSync(otherOrigin)!!.enableAutoDownload,
            "the other subscription's flag must stay untouched")
    }

    // ── Trigger chain ──

    @Test
    fun updatePodcastHandsOnlyTheNewEpisodesToTheStarterWhenEnabled() = runBlocking {
        val manager = subscriptionManager()
        manager.subscribe(origin)
        database.subscriptions.setAutoDownload(origin, true)

        val received = mutableListOf<PodcastEpisode>()
        val result = subscriptionManager { received.addAll(it) }.updatePodcast(origin, null)

        assertTrue(result is UpdatePodcastResult.Updated)
        // The fake feed carries ep-1 and ep-2; nothing existed before, so both
        // are new and both arrive at the starter, in feed order.
        assertEquals(listOf("$origin:ep-1", "$origin:ep-2"), received.map { it.id })
    }

    @Test
    fun updatePodcastDoesNotCallTheStarterWhenDisabled() = runBlocking {
        val manager = subscriptionManager()
        manager.subscribe(origin)

        var calls = 0
        subscriptionManager { calls++ }.updatePodcast(origin, null)

        assertEquals(0, calls, "auto-download must stay a no-op while the flag is off")
    }

    @Test
    fun updatePodcastDoesNotCallTheStarterForExistingEpisodes() = runBlocking {
        // Store the back catalog first, enable the flag afterwards, then refresh
        // an unchanged feed: nothing is added, so the starter must not fire —
        // enabling auto-download must not bulk-download the archive.
        val manager = subscriptionManager()
        manager.subscribe(origin)
        manager.updatePodcast(origin, null)
        database.subscriptions.setAutoDownload(origin, true)

        val received = mutableListOf<PodcastEpisode>()
        subscriptionManager { received.addAll(it) }.updatePodcast(origin, null)

        assertTrue(received.isEmpty())
    }
}
