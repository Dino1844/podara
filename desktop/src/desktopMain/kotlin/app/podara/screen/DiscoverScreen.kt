package app.podara.screen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor
import app.podara.api.apple.ApplePodcastClient
import app.podara.api.apple.TopChartsCache
import app.podara.api.model.PodcastPreviewModel
import app.podara.data.AppDatabase
import app.podara.manager.AddPodcastResult
import app.podara.manager.PodcastManager
import app.podara.manager.SubscriptionManager
import app.podara.util.Logger
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.clickableWithoutIndicationOrFocusRing
import app.podara.util.Settings
import app.podara.util.Strings
import app.podara.util.clickableWithoutIndication
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private const val TAG = "DiscoverScreen"

// Persists itunes-lookup:xxx → RSS feed URL mapping across composition boundaries
// so that subscription status can be checked correctly after re-entering DiscoverScreen.
// A plain mutableMapOf is read during composition (isSubscribed checks below) but
// is not snapshot state, so a write would never invalidate those reads — it only
// worked because a sibling state happened to change in the same effect.
private val itunesToRssCache = androidx.compose.runtime.mutableStateMapOf<String, String>()

// Which Discover operation failed last, so the error banner's Retry re-runs
// that operation instead of always reloading the charts.
private enum class DiscoverErrorSource { Load, Search, Subscribe }

@Composable
fun DiscoverScreen(
    database: AppDatabase,
    subscriptionManager: SubscriptionManager,
    podcastManager: PodcastManager,
    appleClient: ApplePodcastClient,
    discoverRefreshKey: Int = 0,
    onSubscribed: () -> Unit,
    onBack: () -> Unit,
    onPlayLatestEpisode: (PodcastPreviewModel) -> Unit,
    onShowDetail: (PodcastPreviewModel) -> Unit
) {
    val colors = PodaraTheme.colors
    val header = DesignTokens.PageHeader
    val search = DesignTokens.SearchBar
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<PodcastPreviewModel>()) }
    var hasSearched by remember { mutableStateOf(false) }
    var topPodcasts by remember { mutableStateOf(emptyList<PodcastPreviewModel>()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorSource by remember { mutableStateOf<DiscoverErrorSource?>(null) }
    // The podcast whose subscription failed, so Retry can re-attempt it.
    var failedSubscribePreview by remember { mutableStateOf<PodcastPreviewModel?>(null) }
    // Bumped by the error banner's Retry to re-run the load effect.
    var loadRetryKey by remember { mutableStateOf(0) }
    var subscribedOrigins by remember { mutableStateOf(setOf<String>()) }
    var subscribingOrigins by remember { mutableStateOf(setOf<String>()) }
    val topChartsCache = remember { TopChartsCache() }

// Owned here rather than inside the list item so its index survives the
    // carousel item being scrolled away and back.
    val featuredCarousel = remember { FeaturedCarousel(1) }
    

    LaunchedEffect(discoverRefreshKey, loadRetryKey) {
        Logger.i(TAG, "Loading top podcasts and subscriptions")
        val countryCode = if (Settings.getLanguage() == "zh") "CN" else "US"
        // Stale-while-revalidate: render the cached charts immediately and
        // refresh in the background. When the refresh fails with cached charts
        // on screen, keep the stale list — the error UI is only for when there
        // is nothing to show at all.
        val cachedCharts = topChartsCache.load(countryCode)
        val hasCachedCharts = cachedCharts != null
        if (hasCachedCharts) {
            topPodcasts = cachedCharts
        } else {
            isLoading = true
        }
        try {
            val dbOrigins = database.podcasts.getAllOrigins()
            // Restore persisted itunes-lookup → RSS URL mappings from database
            itunesToRssCache.clear()
            itunesToRssCache.putAll(database.itunesLookup.getAll())
            if (hasCachedCharts) {
                // Background refresh — swap in the fresh list only when the
                // network load succeeds, so a failed refresh keeps the cache.
                val fresh = appleClient.topPodcasts.load(countryCode = countryCode)
                topChartsCache.save(countryCode, fresh)
                topPodcasts = fresh
            } else {
                topPodcasts = appleClient.topPodcasts.load(countryCode = countryCode).also {
                    topChartsCache.save(countryCode, it)
                }
            }
            val itunesIds = topPodcasts
                .filter { it.fetchUrl.startsWith("itunes-lookup:") }
                .mapNotNull { it.fetchUrl.removePrefix("itunes-lookup:").toLongOrNull() }
            val resolvedMap = appleClient.lookup.batchLookupFeedUrls(itunesIds)
            // Cache itunes-lookup:xxx → RSS URL mapping for subscription status checks
            resolvedMap.forEach { (id, rssUrl) ->
                itunesToRssCache["itunes-lookup:$id"] = rssUrl
            }
            // The subscribed set is the database's own origins, nothing else. The
            // resolved chart URLs go into itunesToRssCache only — the isSubscribed
            // checks match a card's itunes-lookup: fetchUrl through it. Folding
            // them into this set marked every chart entry as subscribed; that
            // stayed invisible only while batchLookupFeedUrls still silently
            // dropped every entry, so the folded-in sets were always empty.
            subscribedOrigins = dbOrigins
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load data", e)
            if (!hasCachedCharts) {
                errorMessage = Strings.get("error_loading", e.message ?: "")
                errorSource = DiscoverErrorSource.Load
            }
        }
        isLoading = false
    }

    val doSearch: () -> Unit = {
        if (searchQuery.isNotBlank()) {
            hasSearched = true
            scope.launch {
                isLoading = true
                errorMessage = null
                try {
                    searchResults = appleClient.search.search(searchQuery)
                    // Resolve search hits' itunes-lookup ids to feed URLs so the
                    // isSubscribed checks can match RSS-URL subscription rows.
                    // Cache only — never the subscribedOrigins set. A failure here
                    // must not discard the search results already on screen; the
                    // checks simply fall back to "unsubscribed" for unmapped ids.
                    try {
                        val searchIds = searchResults
                            .filter { it.fetchUrl.startsWith("itunes-lookup:") }
                            .mapNotNull { it.fetchUrl.removePrefix("itunes-lookup:").toLongOrNull() }
                        appleClient.lookup.batchLookupFeedUrls(searchIds).forEach { (id, rssUrl) ->
                            itunesToRssCache["itunes-lookup:$id"] = rssUrl
                        }
                    } catch (e: Exception) {
                        Logger.w(TAG, "Resolving search result feed URLs failed: ${e.message}")
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Search failed", e)
                    errorMessage = Strings.get("search_failed", e.message ?: "")
                    errorSource = DiscoverErrorSource.Search
                }
                isLoading = false
            }
        }
    }

    val subscribe: (PodcastPreviewModel) -> Unit = { preview ->
        scope.launch {
            subscribingOrigins = subscribingOrigins + preview.fetchUrl
            try {
                val result = podcastManager.addPodcastFromPreview(preview, null)
                if (result is AddPodcastResult.Created || result is AddPodcastResult.Duplicate) {
                    // Get the podcast's actual origin (itunes-lookup: is resolved to real RSS URL)
                    val podcastOrigin = when (result) {
                        is AddPodcastResult.Created -> result.podcast.origin
                        is AddPodcastResult.Duplicate -> result.duplicate.origin
                    }
                    subscriptionManager.subscribe(podcastOrigin)
                    // Persist itunes-lookup → RSS URL mapping in database
                    database.itunesLookup.insert(preview.fetchUrl, podcastOrigin)
                    // Also cache in memory for the current session
                    itunesToRssCache[preview.fetchUrl] = podcastOrigin
                    subscribedOrigins = subscribedOrigins + preview.fetchUrl + podcastOrigin
                    onSubscribed()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add podcast ${preview.title}", e)
                errorMessage = Strings.get("error_adding_podcast", e.message ?: "")
                errorSource = DiscoverErrorSource.Subscribe
                failedSubscribePreview = preview
            } finally {
                subscribingOrigins = subscribingOrigins - preview.fetchUrl
            }
        }
    }

    val dismissError: () -> Unit = {
        errorMessage = null
        errorSource = null
        failedSubscribePreview = null
    }

    val retryFailed: () -> Unit = {
        when (errorSource) {
            DiscoverErrorSource.Load -> {
                // Show the spinner on this recomposition instead of an empty
                // state for one frame; the restarted effect below re-runs the load.
                isLoading = true
                dismissError()
                loadRetryKey++
            }
            DiscoverErrorSource.Search -> {
                dismissError()
                doSearch()
            }
            DiscoverErrorSource.Subscribe -> {
                val preview = failedSubscribePreview
                dismissError()
                preview?.let { subscribe(it) }
            }
            null -> dismissError()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(colors.background)
    ) {
        // ── Header: Title + Search ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = header.PaddingHorizontal, end = header.PaddingHorizontal, top = header.PaddingTop, bottom = DesignTokens.Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = Strings["nav_discover"],
                    color = colors.textPrimary,
                    fontSize = header.TitleSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = DesignTokens.TypeFamily.PageTitle
                )
                Spacer(modifier = Modifier.height(header.Gap))
                Text(
                    text = Strings["discover_subtitle"],
                    color = colors.textMuted,
                    fontSize = header.SubtitleSize
                )
            }

            // Search bar
            Surface(
                modifier = Modifier
                    .width(search.Width)
                    .height(search.Height)
                    .border(DesignTokens.Border.Width, DesignTokens.Border.SecondaryColor, RoundedCornerShape(search.Radius)),
                shape = RoundedCornerShape(search.Radius),
                color = colors.surface
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = search.PaddingHorizontal),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = Strings["discover_search"],
                        tint = colors.accent,
                        modifier = Modifier
                            .size(search.IconSize)
                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                            .clickableWithoutIndication { doSearch() }
                    )
                    Spacer(modifier = Modifier.width(search.Gap))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = Strings["discover_search_placeholder"],
                                color = colors.textDisabled,
                                fontSize = search.TextSize
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onKeyEvent { event ->
                                    if (event.key == Key.Enter && event.type == KeyEventType.KeyUp && searchQuery.isNotBlank()) {
                                        doSearch()
                                        true
                                    } else false
                                },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = colors.textPrimary,
                                fontSize = search.TextSize
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(colors.accent)
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = Strings["discover_search_clear"],
                            tint = colors.textMuted,
                            modifier = Modifier
                                .size(search.ClearIconSize)
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                .clickableWithoutIndication {
                                    searchQuery = ""
                                    searchResults = emptyList()
                                    hasSearched = false
                                }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Content ──
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
            errorMessage != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = errorMessage!!, color = colors.danger)
                        Spacer(modifier = Modifier.height(DesignTokens.Spacing.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.lg)) {
                            Text(
                                text = Strings["discover_retry"],
                                color = colors.accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                    .clickableWithoutIndication { retryFailed() }
                            )
                            Text(
                                text = Strings["discover_error_close"],
                                color = colors.textSecondary,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                    .clickableWithoutIndication { dismissError() }
                            )
                        }
                    }
                }
            }
            else -> {
                val podcasts = if (hasSearched) searchResults else topPodcasts
                // The result set changes on every search and refresh, so the
                // cursor is re-clamped or it can point past the end of the new
                // list (or stay short of newly available items after growth).
                featuredCarousel.onListChanged(podcasts.size)
                if (podcasts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(Strings["discover_search_hint"], color = colors.textMuted)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        // ── Featured card (first podcast) ──
                        if (!hasSearched && podcasts.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // arrows are inside FeaturedCard
                                }
                                // The full index, not index % FEATURED_SLOTS. The modulo meant the
                                // carousel could only ever show the first five podcasts: any
                                // index 5..size-1 rendered as podcasts[0..4], so "next" from the
                                // fifth went backwards to the first and the rest were never
                                // featured at all. It also made the crossfade appear to run in
                                // the wrong direction, because two different indices produced the
                                // same target.
                                Crossfade(
                                    targetState = featuredCarousel.current,
                                    animationSpec = tween(DesignTokens.Animation.NormalMs),
                                    label = "featuredPodcast"
                                ) { idx ->
                                    val podcast = podcasts[idx]
                                    FeaturedCard(
                                        podcast = podcast,
                                        isSubscribed = podcast.fetchUrl in subscribedOrigins
                                            || itunesToRssCache[podcast.fetchUrl] in subscribedOrigins,
                                        isSubscribing = podcast.fetchUrl in subscribingOrigins,
                                        onSubscribe = { subscribe(podcast) },
                                        onPlayLatestEpisode = { onPlayLatestEpisode(podcast) },
                                        onShowDetail = { onShowDetail(podcast) },
                                        onPrevious = { featuredCarousel.previous() },
                                        onNext = { featuredCarousel.next() }
                                    )
                                }
                            }
                        }

                        // ── Trending This Week ──
                        // NOTE: commented out because no public API provides real trending data
                        // See .claude/plans/discover-trending-this-week-api-exa-cont-radiant-dragon.md
//                        if (!hasSearched && podcasts.size > 1) {
//                            item {
//                                Spacer(modifier = Modifier.height(24.dp))
//                                SectionHeader(title = Strings["discover_trending"])
//                                Spacer(modifier = Modifier.height(12.dp))
//                            }
//                            item {
//                                Row(
//                                    modifier = Modifier
//                                        .fillMaxWidth()
//                                        .padding(horizontal = DesignTokens.SectionHeader.PaddingHorizontal)
//                                        .horizontalScroll(rememberScrollState()),
//                                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.PodcastCard.Gap)
//                                ) {
//                                    podcasts.drop(1).take(10).forEach { podcast ->
//                                        PodcastCard(
//                                            podcast = podcast,
//                                            onClick = { /* TODO */ }
//                                        )
//                                    }
//                                }
//                            }
//                        }

                        // ── New Episodes / All results ──
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            SectionHeader(
                                title = if (hasSearched) Strings["discover_results"] else Strings["discover_new_episodes"]
                            )
                            Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
                        }

                        // The list below is the browse list in its own right. It used to be
                        // `podcasts.drop(5)` to avoid repeating the five podcasts the
                        // carousel showed, but the carousel now cycles the whole
                        // list, so "already featured" has no fixed set to exclude —
                        // and dropping a slice made the first visible row change
                        // depending on which card happened to be showing.
                        items(podcasts, key = { podcast -> podcast.fetchUrl }) { podcast ->
                            Box(modifier = Modifier.padding(horizontal = DesignTokens.SectionHeader.PaddingHorizontal, vertical = 5.dp)) {
                                EpisodeRow(
                                    podcast = podcast,
                                    isSubscribed = podcast.fetchUrl in subscribedOrigins
                                        || itunesToRssCache[podcast.fetchUrl] in subscribedOrigins,
                                    isSubscribing = podcast.fetchUrl in subscribingOrigins,
                                    onSubscribe = { subscribe(podcast) },
                                    onShowDetail = { onShowDetail(podcast) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Featured Card ──
@Composable
private fun FeaturedCard(
    podcast: PodcastPreviewModel,
    isSubscribed: Boolean,
    isSubscribing: Boolean,
    onSubscribe: () -> Unit,
    onPlayLatestEpisode: () -> Unit,
    onShowDetail: () -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {}
) {
    val colors = PodaraTheme.colors
    val card = DesignTokens.FeaturedCard
    val btn = DesignTokens.Button
    val hero = DesignTokens.Hero
    // Read the hero glow colors here, not inside drawBehind: drawBehind runs in
    // a DrawScope, which is not a @Composable context.
    val heroAmbientGlowColors = hero.LeftAmbientGlowColors
    val heroCornerGlowColors = hero.CornerGlowColors
    val heroWaveformGold = hero.WaveformGold
    val surfaces = PodaraTheme.surfaces
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.SectionHeader.PaddingHorizontal, vertical = 4.dp)
            .height(card.Height),
        shape = RoundedCornerShape(card.Radius),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(card.ShadowElevation, RoundedCornerShape(card.Radius), ambientColor = card.ShadowColor, spotColor = card.ShadowColor)
                .clip(RoundedCornerShape(card.Radius))
                .background(DesignTokens.Card.Gradient)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = heroAmbientGlowColors,
                                center = Offset(
                                    size.width * hero.LeftAmbientCenterFactor.x,
                                    size.height * hero.LeftAmbientCenterFactor.y
                                ),
                                radius = size.width * hero.LeftAmbientRadiusFactor
                            )
                        )
                    }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = heroCornerGlowColors,
                                center = Offset(
                                    size.width * hero.CornerGlowCenterFactor.x,
                                    size.height * hero.CornerGlowCenterFactor.y
                                ),
                                radius = size.width * hero.CornerGlowRadiusFactor
                            )
                        )
                    }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val gold = heroWaveformGold
                        val centerY = size.height * 0.50f
                        val samples = listOf(
                            0.08f, 0.10f, 0.06f, 0.14f, 0.09f, 0.24f, 0.12f, 0.42f,
                            0.18f, 0.72f, 0.36f, 0.96f, 0.54f, 0.86f, 0.28f, 0.64f,
                            0.20f, 0.48f, 0.14f, 0.32f, 0.12f, 0.22f, 0.10f, 0.18f,
                            0.26f, 0.12f, 0.44f, 0.16f, 0.58f, 0.22f, 0.50f, 0.14f,
                            0.38f, 0.18f, 0.30f, 0.10f, 0.24f, 0.16f, 0.42f, 0.12f,
                            0.56f, 0.20f, 0.34f, 0.10f, 0.26f, 0.08f, 0.20f, 0.12f,
                            0.48f, 0.14f, 0.70f, 0.18f, 0.40f, 0.10f, 0.24f, 0.08f,
                            0.18f, 0.06f, 0.14f, 0.08f, 0.12f, 0.05f
                        )
                        fun drawWaveform(startX: Float, waveWidth: Float, alphaScale: Float = 1f) {
                            val step = waveWidth / (samples.size - 1)
                            drawLine(
                                color = gold.copy(alpha = 0.025f * alphaScale),
                                start = Offset(startX, centerY),
                                end = Offset(startX + waveWidth, centerY),
                                strokeWidth = 9f
                            )
                            drawLine(
                                color = gold.copy(alpha = 0.08f * alphaScale),
                                start = Offset(startX, centerY),
                                end = Offset(startX + waveWidth, centerY),
                                strokeWidth = 1.4f
                            )
                            samples.forEachIndexed { index, amplitude ->
                                val x = startX + index * step
                                val height = 5f + amplitude * 46f
                                val alpha = (0.035f + amplitude * 0.09f) * alphaScale
                                drawLine(
                                    color = gold.copy(alpha = alpha * 0.38f),
                                    start = Offset(x, centerY - height * 1.25f),
                                    end = Offset(x, centerY + height * 1.25f),
                                    strokeWidth = 4.2f
                                )
                                drawLine(
                                    color = gold.copy(alpha = alpha),
                                    start = Offset(x, centerY - height),
                                    end = Offset(x, centerY + height),
                                    strokeWidth = 1.25f
                                )
                            }
                        }
                        val leftWaveWidth = size.width * 0.25f
                        val rightWaveWidth = size.width * 0.33f
                        drawWaveform(startX = 0f, waveWidth = leftWaveWidth, alphaScale = 0.86f)
                        drawWaveform(startX = size.width - rightWaveWidth, waveWidth = rightWaveWidth)
                    }
            )
            val featuredHoverInteraction = remember { MutableInteractionSource() }
            val isFeaturedHovered by featuredHoverInteraction.collectIsHoveredAsState()
            val animatedFeaturedBg by animateHoverBackgroundColor(
                isFeaturedHovered,
                DesignTokens.Glass.HoverOverlayColor,
                tween(DesignTokens.Animation.NormalMs)
            )

            // Full-card hover highlight
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(animatedFeaturedBg)
            )

            // Content: Cover + Text (whole area clickable → navigate to detail)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(card.Padding)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickableWithoutIndicationOrFocusRing(interactionSource = featuredHoverInteraction) { onShowDetail() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cover
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = podcast.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(184.dp)
                            .offset(x = (-20).dp, y = (-10).dp)
                            .graphicsLayer(rotationZ = -3f)
                            .alpha(0.26f)
                            .blur(3.dp)
                            .shadow(18.dp, RoundedCornerShape(card.CoverRadius + 8.dp), ambientColor = surfaces.shadow, spotColor = surfaces.shadow)
                            .clip(RoundedCornerShape(card.CoverRadius + 8.dp))
                    )
                    Box(
                        modifier = Modifier
                            .size(184.dp)
                            .offset(x = (-20).dp, y = (-10).dp)
                            .graphicsLayer(rotationZ = -3f)
                            .clip(RoundedCornerShape(card.CoverRadius + 8.dp))
                            .background(colors.accent.copy(alpha = 0.40f))
                    )
                    AsyncImage(
                        model = podcast.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(190.dp)
                            .offset(x = (-8).dp, y = (-4).dp)
                            .graphicsLayer(rotationZ = 2f)
                            .alpha(0.34f)
                            .blur(2.dp)
                            .shadow(18.dp, RoundedCornerShape(card.CoverRadius + 7.dp), ambientColor = surfaces.shadow, spotColor = surfaces.shadow)
                            .clip(RoundedCornerShape(card.CoverRadius + 7.dp))
                    )
                    Box(
                        modifier = Modifier
                            .size(190.dp)
                            .offset(x = (-8).dp, y = (-4).dp)
                            .graphicsLayer(rotationZ = 2f)
                            .clip(RoundedCornerShape(card.CoverRadius + 7.dp))
                            .background(colors.accent.copy(alpha = 0.30f))
                    )
                    AsyncImage(
                        model = podcast.imageUrl,
                        contentDescription = podcast.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(184.dp)
                            .shadow(20.dp, RoundedCornerShape(card.CoverRadius + 4.dp), ambientColor = surfaces.shadow, spotColor = surfaces.shadow)
                            .clip(RoundedCornerShape(card.CoverRadius + 4.dp))
                    )
                }

                Spacer(modifier = Modifier.width(card.ContentGap))

                // Text content
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = Strings["discover_featured"],
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(card.TextGap))
                    Box(modifier = Modifier.height(36.dp)) {
                        Text(
                            text = podcast.title,
                            color = colors.textPrimary,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = DesignTokens.TypeFamily.PageTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                    }
                    Spacer(modifier = Modifier.height(card.TextGap))
                    if (podcast.description.isNotEmpty()) {
                        Box(modifier = Modifier.height(40.dp)) {
                            Text(
                                text = podcast.description,
                                color = colors.textSecondary,
                                fontSize = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 20.sp,
                                modifier = Modifier.align(Alignment.CenterStart)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    Spacer(modifier = Modifier.height(card.ButtonGap))

                    // Three action buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(card.ButtonGap)) {
                        // Latest Episode — design token button
                        Box(
                            modifier = Modifier
                                .height(btn.Height)
                                .shadow(btn.ShadowElevation, RoundedCornerShape(btn.Radius), ambientColor = btn.ShadowColor, spotColor = btn.ShadowColor)
                                .clip(RoundedCornerShape(btn.Radius))
                                .border(DesignTokens.Border.Width, btn.BorderColor, RoundedCornerShape(btn.Radius))
                                .background(btn.Gradient)
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                .clickableWithoutIndication { onPlayLatestEpisode() },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.matchParentSize().background(btn.InnerHighlight))
                            Box(modifier = Modifier.matchParentSize().background(btn.SpecularSheen))
                            Row(
                                modifier = Modifier.padding(horizontal = btn.PaddingHorizontal),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = btn.IconColor, modifier = Modifier.size(btn.IconSize))
                                Spacer(Modifier.width(DesignTokens.Spacing.sm))
                                Text(
                                    text = Strings["discover_latest_episode"],
                                    color = btn.TextColor,
                                    fontSize = btn.TextSize,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Add to playlist / Subscribe
                        val addInteractionSource = remember { MutableInteractionSource() }
                        val isAddHovered by addInteractionSource.collectIsHoveredAsState()
                        val animatedAddBg by animateColorAsState(
                            targetValue = when {
                                isSubscribed -> colors.accent.copy(alpha = 0.15f)
                                isAddHovered -> colors.elevated
                                else -> colors.surface
                            },
                            animationSpec = tween(DesignTokens.Animation.NormalMs)
                        )
                        Box(
                            modifier = Modifier
                                .size(DesignTokens.IconButton.Size)
                                .clip(CircleShape)
                                .border(DesignTokens.Border.Width, DesignTokens.Border.SecondaryColor, CircleShape)
                                .background(animatedAddBg)
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                .clickableWithoutIndicationOrFocusRing(interactionSource = addInteractionSource) {
                                    if (!isSubscribed) onSubscribe()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isSubscribing -> CircularProgressIndicator(
                                    modifier = Modifier.size(DesignTokens.IconButton.IconSize),
                                    strokeWidth = 2.dp,
                                    color = colors.accent
                                )
                                isSubscribed -> Icon(
                                    Icons.Default.Check,
                                    contentDescription = Strings["discover_added"],
                                    tint = colors.accent,
                                    modifier = Modifier.size(DesignTokens.IconButton.IconSize)
                                )
                                else -> Icon(
                                    Icons.Default.Add,
                                    contentDescription = Strings["discover_add"],
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(DesignTokens.IconButton.IconSize)
                                )
                            }
                        }

                        // More / Detail
                        val moreInteractionSource = remember { MutableInteractionSource() }
                        val isMoreHovered by moreInteractionSource.collectIsHoveredAsState()
                        val animatedMoreBg by animateColorAsState(
                            targetValue = if (isMoreHovered) colors.elevated else colors.surface,
                            animationSpec = tween(DesignTokens.Animation.NormalMs)
                        )
                        Box(
                            modifier = Modifier
                                .size(DesignTokens.IconButton.Size)
                                .clip(CircleShape)
                                .border(DesignTokens.Border.Width, DesignTokens.Border.SecondaryColor, CircleShape)
                                .background(animatedMoreBg)
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                .clickableWithoutIndicationOrFocusRing(interactionSource = moreInteractionSource) { onShowDetail() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MoreHoriz, contentDescription = Strings["discover_more"], tint = colors.textSecondary, modifier = Modifier.size(DesignTokens.IconButton.IconSize))
                        }
                    }
                }
            }

            // Navigation arrows — top right, layered ON TOP of the content
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(card.Padding),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val prevInteractionSource = remember { MutableInteractionSource() }
                val isPrevHovered by prevInteractionSource.collectIsHoveredAsState()
                val animatedPrevBg by animateColorAsState(
                    targetValue = if (isPrevHovered) colors.elevated else colors.surface.copy(alpha = 0.6f),
                    animationSpec = tween(DesignTokens.Animation.NormalMs)
                )
                Box(
                    modifier = Modifier
                        .size(card.NavButtonSize)
                        .clip(CircleShape)
                        .background(animatedPrevBg)
                        .border(1.dp, colors.border, CircleShape)
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        .clickableWithoutIndicationOrFocusRing(interactionSource = prevInteractionSource) { onPrevious() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = Strings["discover_previous"],
                        tint = colors.textPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                val nextInteractionSource = remember { MutableInteractionSource() }
                val isNextHovered by nextInteractionSource.collectIsHoveredAsState()
                val animatedNextBg by animateColorAsState(
                    targetValue = if (isNextHovered) colors.elevated else colors.surface.copy(alpha = 0.6f),
                    animationSpec = tween(DesignTokens.Animation.NormalMs)
                )
                Box(
                    modifier = Modifier
                        .size(card.NavButtonSize)
                        .clip(CircleShape)
                        .background(animatedNextBg)
                        .border(1.dp, colors.border, CircleShape)
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        .clickableWithoutIndicationOrFocusRing(interactionSource = nextInteractionSource) { onNext() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = Strings["discover_next"],
                        tint = colors.textPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ── Section Header ──
// No "Show All" link: the list under the header already shows every podcast,
// so there is nothing for it to reveal. The link used to be rendered with a
// hand cursor but no click handler — a dead affordance.
@Composable
internal fun SectionHeader(title: String) {
    val colors = PodaraTheme.colors
    val sh = DesignTokens.SectionHeader
    Text(
        text = title,
        color = colors.textPrimary,
        fontSize = sh.TitleSize,
        fontWeight = FontWeight.SemiBold,
        fontFamily = DesignTokens.TypeFamily.PageTitle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = sh.PaddingHorizontal)
    )
}

// ── Podcast Card (horizontal scroll) ──
// NOTE: commented out with Trending This Week — kept for reference in case the
// section is re-enabled with a real trending API.
//@Composable
//private fun PodcastCard(
//    podcast: PodcastPreviewModel,
//    onClick: () -> Unit
//) {
//    val colors = PodaraTheme.colors
//    val pc = DesignTokens.PodcastCard
//    Surface(
//        modifier = Modifier
//            .width(pc.Width)
//            .shadow(8.dp, RoundedCornerShape(pc.ImageRadius), ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
//            .clip(RoundedCornerShape(pc.ImageRadius))
//            .background(DesignTokens.Card.Gradient)
//            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
//            .clickable(onClick = onClick),
//        shape = RoundedCornerShape(pc.ImageRadius),
//        color = Color.Transparent
//    ) {
//        Column(modifier = Modifier.padding(8.dp)) {
//            AsyncImage(
//                model = podcast.imageUrl,
//                contentDescription = podcast.title,
//                contentScale = ContentScale.Crop,
//                modifier = Modifier
//                    .size(pc.ImageSize)
//                    .clip(RoundedCornerShape(pc.ImageRadius))
//            )
//            Spacer(modifier = Modifier.height(pc.Spacing))
//            Text(
//                text = podcast.title,
//                color = colors.textPrimary,
//                fontSize = pc.TitleSize,
//                maxLines = 1,
//                overflow = TextOverflow.Ellipsis
//            )
//            Text(
//                text = podcast.author,
//                color = colors.textMuted,
//                fontSize = pc.AuthorSize,
//                maxLines = 1,
//                overflow = TextOverflow.Ellipsis
//            )
//        }
//    }
//}

// ── Episode Row ──
@Composable
internal fun EpisodeRow(
    podcast: PodcastPreviewModel,
    isSubscribed: Boolean,
    isSubscribing: Boolean,
    onSubscribe: () -> Unit,
    onShowDetail: () -> Unit = {}
) {
    val colors = PodaraTheme.colors
    val er = DesignTokens.EpisodeRow
    val glass = DesignTokens.Glass
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(glass.CompactShadowElevation, RoundedCornerShape(glass.CompactRadius), ambientColor = glass.CompactShadowColor, spotColor = glass.CompactShadowColor)
            .clip(RoundedCornerShape(glass.CompactRadius))
            .background(glass.CompactGradient)
            .border(DesignTokens.Border.Width, glass.CompactBorderColor, RoundedCornerShape(glass.CompactRadius))
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .clickableWithoutIndication { onShowDetail() },
        shape = RoundedCornerShape(er.CoverRadius),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(er.Height)
                .padding(horizontal = er.PaddingHorizontal, vertical = er.PaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(er.Spacing)
        ) {
            AsyncImage(
                model = podcast.imageUrl,
                contentDescription = podcast.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(er.CoverSize)
                    .clip(RoundedCornerShape(er.CoverRadius))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = podcast.title,
                    color = colors.textPrimary,
                    fontSize = er.TitleSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(er.LineGap))
                Text(
                    text = podcast.author,
                    color = colors.textSecondary,
                    fontSize = er.AuthorSize,
                    lineHeight = 14.sp,
                    maxLines = 1
                )
                if (podcast.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(er.LineGap))
                    Text(
                        text = podcast.description,
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val addInteractionSource = remember { MutableInteractionSource() }
            val isAddHovered by addInteractionSource.collectIsHoveredAsState()
            val animatedAddBg by animateColorAsState(
                targetValue = when {
                    isSubscribed -> colors.accent.copy(alpha = 0.15f)
                    isAddHovered -> colors.elevated
                    else -> colors.surface
                },
                animationSpec = tween(DesignTokens.Animation.NormalMs)
            )
            Box(
                modifier = Modifier
                    .size(DesignTokens.IconButton.Size)
                    .clip(CircleShape)
                    .border(DesignTokens.Border.Width, DesignTokens.Border.SecondaryColor, CircleShape)
                    .background(animatedAddBg)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickableWithoutIndicationOrFocusRing(interactionSource = addInteractionSource) {
                        if (!isSubscribed) onSubscribe()
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    isSubscribing -> CircularProgressIndicator(
                        modifier = Modifier.size(DesignTokens.IconButton.IconSize),
                        strokeWidth = 2.dp,
                        color = colors.accent
                    )
                    isSubscribed -> Icon(
                        Icons.Default.Check,
                        contentDescription = Strings["discover_added"],
                        tint = colors.accent,
                        modifier = Modifier.size(DesignTokens.IconButton.IconSize)
                    )
                    else -> Icon(
                        Icons.Default.Add,
                        contentDescription = Strings["discover_add"],
                        tint = colors.textSecondary,
                        modifier = Modifier.size(DesignTokens.IconButton.IconSize)
                    )
                }
            }
        }
    }
}
