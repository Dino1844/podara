package app.podara.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.podara.api.apple.ApplePodcastClient
import app.podara.component.EpisodeCardSkeletonRow
import app.podara.component.EpisodeListItem
import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import app.podara.screen.DiscoverLoadingSkeleton
import app.podara.screen.DiscoverScreen
import app.podara.screen.DiscoverSkeletonRow
import app.podara.screen.EpisodeRow
import app.podara.api.model.PodcastPreviewModel
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * D1 (DESIGN-CRAFT.md §5): the loading state of a list is a skeleton shaped
 * like the rows that are coming, not a spinner — and its rows take the real
 * row's height, so data arriving swaps content in place without a jump.
 *
 * The height assertions compare a skeleton row against a real row composed in
 * the same window: if either drifts from [DesignTokens.FavoriteEpisodeList]
 * / [DesignTokens.EpisodeRow] the two stop being equal and the test fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeListSkeletonTest {

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podium_skeleton_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
    }

    @After
    fun teardown() {
        database.close()
        testDbFile.delete()
        Dispatchers.resetMain()
    }

    private fun testEpisode() = PodcastEpisode(
        id = "ep-skeleton",
        guid = "guid-skeleton",
        origin = "https://example.com/feed.xml",
        link = "https://example.com/ep",
        title = "Skeleton Episode",
        description = "Description",
        imageUrl = null,
        author = "Author",
        pubDate = 1000L,
        duration = 300,
        audioUrl = "https://example.com/audio.mp3",
        podcastTitle = "Skeleton Podcast"
    )

    @Test
    fun skeletonRowHeightMatchesTheRealEpisodeRow() {
        var cardHeightPx = -1
        composeTestRule.setContent {
            val density = LocalDensity.current
            PodaraTheme {
                SideEffect {
                    cardHeightPx = with(density) { DesignTokens.FavoriteEpisodeList.CardHeight.roundToPx() }
                }
                Column {
                    Box(Modifier.testTag("real-row")) {
                        EpisodeListItem(
                            episode = testEpisode(),
                            podcast = null,
                            isPlaying = false,
                            secondaryText = "Skeleton Podcast",
                            tertiaryText = "",
                            onPlay = {},
                            trailingActions = {}
                        )
                    }
                    Box(Modifier.testTag("skeleton-row")) {
                        EpisodeCardSkeletonRow()
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val realHeight = composeTestRule.onNodeWithTag("real-row").fetchSemanticsNode().size.height
        val skeletonHeight = composeTestRule.onNodeWithTag("skeleton-row").fetchSemanticsNode().size.height

        assertTrue(cardHeightPx > 0, "the token height must resolve to a real pixel height")
        assertEquals(
            cardHeightPx, realHeight,
            "the real EpisodeListItem row must be FavoriteEpisodeList.CardHeight tall"
        )
        assertEquals(
            realHeight, skeletonHeight,
            "the skeleton row must take exactly the real row's height so data arriving does not jump the layout"
        )
    }

    @Test
    fun discoverSkeletonRowHeightMatchesTheRealRow() {
        var rowHeightPx = -1
        val preview = PodcastPreviewModel(
            fetchUrl = "https://example.com/feed.xml",
            link = "https://example.com",
            title = "Real Row Podcast",
            description = "Description",
            author = "Author",
            imageUrl = "https://example.com/image.jpg",
            languageCode = "en"
        )
        composeTestRule.setContent {
            val density = LocalDensity.current
            PodaraTheme {
                SideEffect {
                    rowHeightPx = with(density) { DesignTokens.EpisodeRow.Height.roundToPx() }
                }
                Column {
                    Box(Modifier.testTag("real-row")) {
                        EpisodeRow(
                            podcast = preview,
                            isSubscribed = false,
                            isSubscribing = false,
                            onSubscribe = {},
                            onShowDetail = {}
                        )
                    }
                    Box(Modifier.testTag("skeleton-row")) {
                        DiscoverSkeletonRow()
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val realHeight = composeTestRule.onNodeWithTag("real-row").fetchSemanticsNode().size.height
        val skeletonHeight = composeTestRule.onNodeWithTag("skeleton-row").fetchSemanticsNode().size.height

        assertTrue(rowHeightPx > 0, "the token height must resolve to a real pixel height")
        assertEquals(rowHeightPx, realHeight, "the real EpisodeRow must be EpisodeRow.Height tall")
        assertEquals(
            realHeight, skeletonHeight,
            "the Discover skeleton row must take exactly the real row's height"
        )
    }

    @Test
    fun discoverLoadingSkeletonRendersTheHeaderInTheLoadedListsGeometry() {
        composeTestRule.setContent {
            PodaraTheme {
                Box(Modifier.fillMaxSize()) {
                    DiscoverLoadingSkeleton(hasSearched = false)
                }
            }
        }
        composeTestRule.waitForIdle()

        // The skeleton carries the same section header the loaded list shows,
        // so its text is stable across the swap.
        composeTestRule.onNodeWithText(Strings["discover_new_episodes"]).assertExists()
    }

    /**
     * The screen-level loading state. A charts client that never answers keeps
     * `isLoading` true (no cached charts in the test home), so the only state
     * that can be on screen is the loading one: the section header of the
     * skeleton — and neither the pre-search empty hint nor any result rows.
     */
    @Test
    fun discoverScreenShowsTheSkeletonWhileLoading() {
        val hangingClient = ApplePodcastClient(
            HttpClient(MockEngine { awaitCancellation() })
        )
        composeTestRule.setContent {
            PodaraTheme {
                DiscoverScreen(
                    database = database,
                    subscriptionManager = app.podara.manager.SubscriptionManager(
                        database,
                        FakeFetchPodcastClient()
                    ),
                    podcastManager = app.podara.manager.PodcastManager(
                        database,
                        FakeFetchPodcastClient(),
                        hangingClient
                    ),
                    appleClient = hangingClient,
                    onSubscribed = {}, onBack = {}, onPlayLatestEpisode = {}, onShowDetail = {}
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(Strings["discover_new_episodes"]).fetchSemanticsNodes().isNotEmpty()
        }

        // The loading branch renders the skeleton's header…
        composeTestRule.onNodeWithText(Strings["discover_new_episodes"]).assertExists()
        // …not the empty state's hint (exact text — the search field's
        // placeholder starts with the same words), and not results that have
        // not arrived.
        composeTestRule.onNodeWithText(Strings["discover_search_hint"]).assertDoesNotExist()
        composeTestRule.onNodeWithText("Skeleton Podcast").assertDoesNotExist()
    }
}
