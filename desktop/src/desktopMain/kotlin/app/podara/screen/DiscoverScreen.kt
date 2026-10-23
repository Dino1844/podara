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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podara.api.apple.ApplePodcastClient
import app.podara.api.apple.TopChartsCache
import app.podara.api.apple.route.dedupeByFeedUrl
import app.podara.api.model.PodcastPreviewModel
import app.podara.component.PodaraArtwork
import app.podara.component.PodaraScrollbar
import app.podara.component.PodaraSearchField
import app.podara.component.PodaraTooltip
import app.podara.data.AppDatabase
import app.podara.manager.AddPodcastResult
import app.podara.manager.PodcastManager
import app.podara.manager.SubscriptionManager
import app.podara.util.Logger
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.clickableWithoutIndicationOrFocusRing
import app.podara.util.pressScale
import app.podara.util.Settings
import app.podara.util.Strings
import app.podara.util.clickableWithoutIndication
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
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
                    fontWeight = DesignTokens.TypeFamily.PageTitleWeight,
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
            PodaraSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = Strings["discover_search_placeholder"],
                submitOnEnter = true,
                onSearch = doSearch,
                onClear = {
                    searchResults = emptyList()
                    hasSearched = false
                }
            )
        }

        Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))

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
                                fontSize = DesignTokens.TypeScale.Body,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickableWithoutIndication { retryFailed() }
                            )
                            Text(
                                text = Strings["discover_error_close"],
                                color = colors.textSecondary,
                                fontSize = DesignTokens.TypeScale.Body,
                                modifier = Modifier
                                    .clickableWithoutIndication { dismissError() }
                            )
                        }
                    }
                }
            }
            else -> {
                // Defence in depth: the results list is keyed on `fetchUrl`, and
                // Compose throws `Key was already used` when two rows share a key,
                // which blanks the whole screen. `Search` deduplicates its own
                // output already, but the charts list is a second source that this
                // screen does not control, and a crash here is indistinguishable
                // from "search does nothing". Deduplicating at the single point
                // where both lists become `podcasts` means neither source can take
                // the screen down. The same tested helper as the search route, so
                // both call sites agree on what "same feed" means.
                val podcasts = dedupeByFeedUrl(if (hasSearched) searchResults else topPodcasts)
                // The result set changes on every search and refresh, so the
                // cursor is re-clamped or it can point past the end of the new
                // list (or stay short of newly available items after growth).
                //
                // In a LaunchedEffect rather than inline: this writes snapshot
                // state (the carousel's cursor), and writing it from the
                // composition body means the write happens *during* composition,
                // which Compose can neither batch nor apply on the composition's
                // own schedule. The values it reads are unchanged, so the effect
                // key is the size alone.
                LaunchedEffect(podcasts.size) {
                    featuredCarousel.onListChanged(podcasts.size)
                }
                if (podcasts.isEmpty()) {
                    // Two different empty states. A search that matched nothing
                    // used to render "Search podcasts" — the same hint shown
                    // before anyone typed — so a query with no hits looked like
                    // the search had never run.
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (hasSearched) Strings["discover_no_results"] else Strings["discover_search_hint"],
                                color = colors.textSecondary,
                                fontSize = DesignTokens.TypeScale.Body
                            )
                            if (hasSearched) {
                                Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
                                Text(
                                    text = Strings.get("discover_no_results_hint", searchQuery),
                                    color = colors.textMuted,
                                    fontSize = DesignTokens.TypeScale.Caption
                                )
                            }
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(bottom = DesignTokens.Spacing.lg)
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
                                        animationSpec = tween(DesignTokens.Animation.NormalMs, easing = DesignTokens.Animation.StandardEasing),
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

                            // ── New Episodes / All results ──
                            item {
                                Spacer(modifier = Modifier.height(DesignTokens.Spacing.lg))
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
                            items(podcasts, key = { podcast -> podcast.fetchUrl }, contentType = { "discover_podcast_card" }) { podcast ->
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
                        PodaraScrollbar(adapter = rememberScrollbarAdapter(listState))
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
    // Read the wash colours here, not inside drawBehind: drawBehind runs in a
    // DrawScope, which is not a @Composable context.
    val heroWashColors = hero.WashColors
    val surfaces = PodaraTheme.surfaces
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.SectionHeader.PaddingHorizontal, vertical = DesignTokens.Spacing.xs)
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
            // One soft wash behind the artwork. This used to be three layers: a
            // gold radial glow from the left, a second gold radial glow in the
            // corner, and a 61-sample decorative waveform drawn from a hardcoded
            // array. The waveform did not move with playback and did not encode
            // anything; it was there to look busy, and on a dark card it read as
            // a leftover from an audio editor. The artwork is the loudest thing
            // in this card now, which is the point of a featured item.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = heroWashColors,
                                center = Offset(
                                    size.width * hero.WashCenterFactor.x,
                                    size.height * hero.WashCenterFactor.y
                                ),
                                radius = size.width * hero.WashRadiusFactor
                            )
                        )
                    }
            )
            val featuredHoverInteraction = remember { MutableInteractionSource() }
            val isFeaturedHovered by featuredHoverInteraction.collectIsHoveredAsState()
            val animatedFeaturedBg by animateHoverBackgroundColor(
                isFeaturedHovered,
                DesignTokens.Glass.HoverOverlayColor,
                tween(DesignTokens.Animation.NormalMs, easing = DesignTokens.Animation.StandardEasing)
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
                    .clickableWithoutIndicationOrFocusRing(interactionSource = featuredHoverInteraction) { onShowDetail() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cover — one artwork with two echoes behind it.
                //
                // This was five layers: two blurred copies, two accent-tinted
                // panels behind those, and the real cover. The echoes were
                // offset down-left while the card's wash and the title sit to
                // the right, so the eye was pulled in two directions; and the
                // tinted panels put a second accent colour behind artwork that
                // already has its own colours. Two echoes, both up-left by a
                // couple of dp, keep the "a stack of records" idea without
                // adding a competing palette.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    PodaraArtwork(
                        url = podcast.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(186.dp)
                            .offset(x = (-14).dp, y = (-8).dp)
                            .graphicsLayer(rotationZ = -4f)
                            .alpha(0.22f)
                            .blur(2.dp),
                        shape = RoundedCornerShape(card.CoverRadius + DesignTokens.Radius.Small),
                        showPlaceholder = false
                    )
                    PodaraArtwork(
                        url = podcast.imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(188.dp)
                            .offset(x = (-7).dp, y = (-4).dp)
                            .graphicsLayer(rotationZ = 2f)
                            .alpha(0.30f)
                            .blur(1.dp),
                        shape = RoundedCornerShape(card.CoverRadius + DesignTokens.Radius.Small),
                        showPlaceholder = false
                    )
                    PodaraArtwork(
                        url = podcast.imageUrl,
                        contentDescription = podcast.title,
                        modifier = Modifier
                            .size(184.dp)
                            .shadow(DesignTokens.Elevation.Overlay, RoundedCornerShape(card.CoverRadius), ambientColor = surfaces.shadow, spotColor = surfaces.shadow),
                        shape = RoundedCornerShape(card.CoverRadius)
                    )
                }

                Spacer(modifier = Modifier.width(card.ContentGap))

                // Text content
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    // "精选" — an eyebrow, not a badge and not a label. It was a
                    // 12sp accent-coloured word floating above the title with no
                    // container and no consistent size relationship to anything,
                    // so it read as a caption that had drifted up. Drawn at the
                    // micro step in the muted step, tracked out, it is now
                    // clearly a category marker over the title.
                    Text(
                        text = Strings["discover_featured"],
                        color = colors.textMuted,
                        fontSize = DesignTokens.TypeScale.Micro,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
                    Box(modifier = Modifier.height(36.dp)) {
                        Text(
                            text = podcast.title,
                            color = colors.textPrimary,
                            fontSize = DesignTokens.TypeScale.Display,
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
                                fontSize = DesignTokens.TypeScale.BodyStrong,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = DesignTokens.TypeScale.LineHeight.Title,
                                modifier = Modifier.align(Alignment.CenterStart)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(DesignTokens.Spacing.md))

                    // Three action buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(card.ButtonGap)) {
                        // Latest Episode — one flat fill, no sheen. The gradient,
                        // the inner highlight and the specular sheen were three
                        // separate brushes stacked on a 40dp button.
                        Box(
                            modifier = Modifier
                                .height(btn.Height)
                                .shadow(btn.ShadowElevation, RoundedCornerShape(btn.Radius), ambientColor = btn.ShadowColor, spotColor = btn.ShadowColor)
                                .clip(RoundedCornerShape(btn.Radius))
                                .background(btn.Fill)
                                .clickableWithoutIndication { onPlayLatestEpisode() },
                            contentAlignment = Alignment.Center
                        ) {
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
                            animationSpec = tween(DesignTokens.Animation.NormalMs, easing = DesignTokens.Animation.StandardEasing)
                        )
                        PodaraTooltip(
                            text = if (isSubscribed) Strings["discover_added"] else Strings["discover_add"],
                            modifier = Modifier
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(DesignTokens.IconButton.Size)
                                    .pressScale(addInteractionSource)
                                    .clip(CircleShape)
                                    .border(DesignTokens.Border.Width, DesignTokens.Border.SecondaryColor, CircleShape)
                                    .background(animatedAddBg)
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

                        // More / Detail
                        val moreInteractionSource = remember { MutableInteractionSource() }
                        val isMoreHovered by moreInteractionSource.collectIsHoveredAsState()
                        val animatedMoreBg by animateColorAsState(
                            targetValue = if (isMoreHovered) colors.elevated else colors.surface,
                            animationSpec = tween(DesignTokens.Animation.NormalMs, easing = DesignTokens.Animation.StandardEasing)
                        )
                        PodaraTooltip(text = Strings["discover_more"], modifier = Modifier) {
                            Box(
                                modifier = Modifier
                                    .size(DesignTokens.IconButton.Size)
                                    .pressScale(moreInteractionSource)
                                    .clip(CircleShape)
                                    .border(DesignTokens.Border.Width, DesignTokens.Border.SecondaryColor, CircleShape)
                                    .background(animatedMoreBg)
                                    .clickableWithoutIndicationOrFocusRing(interactionSource = moreInteractionSource) { onShowDetail() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MoreHoriz, contentDescription = Strings["discover_more"], tint = colors.textSecondary, modifier = Modifier.size(DesignTokens.IconButton.IconSize))
                            }
                        }
                    }
                }
            }

            // Navigation arrows — top right, layered ON TOP of the content
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(card.Padding),
                horizontalArrangement = Arrangement.spacedBy(card.NavButtonGap)
            ) {
                val prevInteractionSource = remember { MutableInteractionSource() }
                val isPrevHovered by prevInteractionSource.collectIsHoveredAsState()
                val animatedPrevBg by animateColorAsState(
                    targetValue = if (isPrevHovered) colors.elevated else colors.surface.copy(alpha = 0.6f),
                    animationSpec = tween(DesignTokens.Animation.NormalMs, easing = DesignTokens.Animation.StandardEasing)
                )
                PodaraTooltip(text = Strings["discover_previous"], modifier = Modifier) {
                    Box(
                        modifier = Modifier
                            .size(card.NavButtonSize)
                            .pressScale(prevInteractionSource)
                            .clip(CircleShape)
                            .background(animatedPrevBg)
                            .border(DesignTokens.Border.Width, colors.border, CircleShape)
                            .clickableWithoutIndicationOrFocusRing(interactionSource = prevInteractionSource) { onPrevious() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = Strings["discover_previous"],
                            tint = colors.textPrimary,
                            modifier = Modifier.size(card.NavIconSize)
                        )
                    }
                }
                val nextInteractionSource = remember { MutableInteractionSource() }
                val isNextHovered by nextInteractionSource.collectIsHoveredAsState()
                val animatedNextBg by animateColorAsState(
                    targetValue = if (isNextHovered) colors.elevated else colors.surface.copy(alpha = 0.6f),
                    animationSpec = tween(DesignTokens.Animation.NormalMs, easing = DesignTokens.Animation.StandardEasing)
                )
                PodaraTooltip(text = Strings["discover_next"], modifier = Modifier) {
                    Box(
                        modifier = Modifier
                            .size(card.NavButtonSize)
                            .pressScale(nextInteractionSource)
                            .clip(CircleShape)
                            .background(animatedNextBg)
                            .border(DesignTokens.Border.Width, colors.border, CircleShape)
                            .clickableWithoutIndicationOrFocusRing(interactionSource = nextInteractionSource) { onNext() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = Strings["discover_next"],
                            tint = colors.textPrimary,
                            modifier = Modifier.size(card.NavIconSize)
                        )
                    }
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
            PodaraArtwork(
                url = podcast.imageUrl,
                contentDescription = podcast.title,
                modifier = Modifier.size(er.CoverSize),
                shape = RoundedCornerShape(er.CoverRadius)
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
                    lineHeight = DesignTokens.TypeScale.LineHeight.BodyRow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (podcast.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(er.LineGap))
                    Text(
                        text = podcast.description,
                        color = colors.textMuted,
                        fontSize = DesignTokens.TypeScale.Micro,
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
                animationSpec = tween(DesignTokens.Animation.NormalMs, easing = DesignTokens.Animation.StandardEasing)
            )
            PodaraTooltip(
                text = if (isSubscribed) Strings["discover_added"] else Strings["discover_add"],
                modifier = Modifier
            ) {
                Box(
                    modifier = Modifier
                        .size(DesignTokens.IconButton.Size)
                        .pressScale(addInteractionSource)
                        .clip(CircleShape)
                        .border(DesignTokens.Border.Width, DesignTokens.Border.SecondaryColor, CircleShape)
                        .background(animatedAddBg)
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
}