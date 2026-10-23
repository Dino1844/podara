package app.podara.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podara.component.AddToQueueButton
import app.podara.component.EpisodeListItem
import app.podara.component.PodaraScrollbar
import app.podara.component.formatEpisodeMetadata
import app.podara.component.FavoriteEpisodeButton
import app.podara.component.PodaraEmptyState
import app.podara.component.PodaraConfirmDialog
import app.podara.component.ToolbarPillButton
import app.podara.component.PodaraSearchField
import app.podara.component.PodaraBackButton
import app.podara.data.AppDatabase
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import app.podara.data.model.PodcastFavorite
import app.podara.player.MediaPlayerState
import app.podara.player.QueueItem
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import app.podara.util.clickableWithoutIndication
import app.podara.util.formatRelativeTime
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(
    database: AppDatabase,
    playerState: MediaPlayerState,
    favoriteVersion: Int,
    onBack: () -> Unit,
    onFavoriteChanged: () -> Unit = {},
    onShowPodcastDetail: (Podcast) -> Unit = {}
) {
    val colors = PodaraTheme.colors
    val header = DesignTokens.PageHeader
    val searchToken = DesignTokens.SearchBar
    val scope = rememberCoroutineScope()

    var allFavoriteItems by remember { mutableStateOf(emptyList<Pair<PodcastFavorite, PodcastEpisode?>>()) }
    var podcastMap by remember { mutableStateOf(mapOf<String, Podcast>()) }
    var searchQuery by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(favoriteVersion) {
        allFavoriteItems = database.favorites.getAllWithEpisode()
        podcastMap = database.podcasts.getAllSync().associateBy { it.origin }
    }

    val filteredPairs = remember(allFavoriteItems, searchQuery) {
        if (searchQuery.isBlank()) {
            allFavoriteItems
        } else {
            allFavoriteItems.filter { (favorite, episode) ->
                val title = episode?.title ?: favorite.title
                val podcastTitle = episode?.podcastTitle ?: favorite.podcastTitle
                title.contains(searchQuery, ignoreCase = true) || podcastTitle.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val displayItems = remember(filteredPairs) {
        filteredPairs.mapNotNull { (favorite, episode) ->
            episode?.let { favorite to it }
        }
    }

    val contextItems = remember(displayItems) {
        displayItems.map { (_, episode) ->
            QueueItem(url = episode.audioUrl, title = episode.title, subtitle = episode.podcastTitle, artworkUrl = episode.imageUrl, podcastArtworkUrl = podcastMap[episode.origin]?.imageUrl, episodeId = episode.id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = DesignTokens.Spacing.lg, start = DesignTokens.Spacing.xl, end = DesignTokens.Spacing.xl, bottom = DesignTokens.Spacing.sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(verticalAlignment = Alignment.Top) {
                PodaraBackButton(onClick = onBack, modifier = Modifier.padding(top = DesignTokens.Spacing.xs))
                Spacer(modifier = Modifier.width(DesignTokens.Spacing.sm))
                Column {
                    Text(
                        text = Strings["favorites_title"],
                        fontSize = header.TitleSize,
                        fontWeight = DesignTokens.TypeFamily.PageTitleWeight,
                        fontFamily = DesignTokens.TypeFamily.PageTitle,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(header.Gap))
                    Text(
                        text = Strings["favorites_subtitle"],
                        fontSize = header.SubtitleSize,
                        color = colors.textMuted
                    )
                }
            }

            PodaraSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = Strings["favorites_search_placeholder"]
            )
        }

        Spacer(modifier = Modifier.height(DesignTokens.Spacing.md))

        if (displayItems.isEmpty()) {
            if (searchQuery.isBlank()) {
                PodaraEmptyState(
                    icon = Icons.Default.Favorite,
                    title = Strings["favorites_empty"],
                    subtitle = Strings["favorites_empty_hint"],
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                val glass = DesignTokens.Glass
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(DesignTokens.EmptyState.IconSize),
                            tint = colors.textMuted
                        )
                        Spacer(modifier = Modifier.height(DesignTokens.EmptyState.Gap))
                        Box(
                            modifier = Modifier
                                .width(DesignTokens.EmptyState.PanelWidth)
                                .shadow(
                                    glass.CompactShadowElevation,
                                    RoundedCornerShape(DesignTokens.EmptyState.PanelRadius),
                                    ambientColor = glass.CompactShadowColor,
                                    spotColor = glass.CompactShadowColor
                                )
                                .clip(RoundedCornerShape(DesignTokens.EmptyState.PanelRadius))
                                .background(glass.CompactGradient)
                                .border(DesignTokens.Border.Width, glass.CompactBorderColor, RoundedCornerShape(DesignTokens.EmptyState.PanelRadius))
                                .padding(DesignTokens.EmptyState.PanelPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = Strings["favorites_search_empty"],
                                    color = colors.textPrimary,
                                    fontSize = DesignTokens.EmptyState.TitleSize,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(DesignTokens.Spacing.xs))
                                Text(
                                    text = Strings["favorites_search_empty_hint"],
                                    color = colors.textSecondary,
                                    fontSize = DesignTokens.EmptyState.SubtitleSize
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = Strings.get("favorites_count", displayItems.size),
                    color = colors.textPrimary,
                    fontSize = DesignTokens.TypeScale.Body,
                    fontWeight = FontWeight.Medium,
                    style = LocalTextStyle.current.copy(
                        fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures
                    )
                )

                ToolbarPillButton(
                    icon = Icons.Default.DeleteSweep,
                    label = Strings["toolbar_clear"],
                    contentDescription = Strings["favorites_clear"],
                    onClick = { showClearDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
            HorizontalDivider(color = colors.divider)
            Spacer(modifier = Modifier.height(6.dp))

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = DesignTokens.FavoriteEpisodeList.ListPaddingTop,
                        bottom = DesignTokens.FavoriteEpisodeList.ListPaddingBottom
                    ),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.FavoriteEpisodeList.CardGap)
                ) {
                    items(displayItems, key = { it.first.episodeId }, contentType = { "favorite_episode_row" }) { (favorite, episode) ->
                        val podcast = podcastMap[episode.origin]
                        val favoriteList = DesignTokens.FavoriteEpisodeList
                        EpisodeListItem(
                            episode = episode,
                            podcast = podcast,
                            isPlaying = playerState.currentEpisodeId == episode.id || playerState.currentUrl == episode.audioUrl,
                            secondaryText = episode.podcastTitle,
                            tertiaryText = formatEpisodeMetadata(formatRelativeTime(favorite.timestamp), episode.duration),
                            onPlay = {
                                playerState.playWithContext(
                                    context = contextItems,
                                    targetUrl = episode.audioUrl,
                                    title = episode.title,
                                    subtitle = episode.podcastTitle,
                                    artworkUrl = episode.imageUrl,
                                    podcastArtworkUrl = podcast?.imageUrl,
                                    durationMs = episode.duration * 1000L,
                                    episodeId = episode.id
                                )
                                scope.launch {
                                    database.history.insert(episode.origin, episode.id)
                                }
                            },
                            onPodcastClick = onShowPodcastDetail
                        ) {
                            AddToQueueButton(
                                size = favoriteList.ActionButtonSize,
                                radius = favoriteList.ActionButtonRadius,
                                iconSize = favoriteList.QueueIconSize,
                                hoverBackgroundColor = favoriteList.ActionButtonHoverBackgroundColor,
                                defaultIconColor = favoriteList.QueueIconColor,
                                hoverIconColor = favoriteList.QueueIconHoverColor
                            ) {
                                playerState.addToQueue(
                                    url = episode.audioUrl,
                                    title = episode.title,
                                    artworkUrl = episode.imageUrl,
                                    podcastArtworkUrl = podcast?.imageUrl,
                                    episodeId = episode.id
                                )
                            }
                            FavoriteEpisodeButton(
                                isFavorite = true,
                                size = favoriteList.ActionButtonSize,
                                radius = favoriteList.ActionButtonRadius,
                                iconSize = favoriteList.FavoriteIconSize,
                                hoverBackgroundColor = favoriteList.ActionButtonHoverBackgroundColor,
                                defaultIconColor = favoriteList.FavoriteInactiveColor,
                                hoverIconColor = favoriteList.ActionIconHoverColor,
                                selectedIconColor = favoriteList.FavoriteActiveColor
                            ) {
                                scope.launch {
                                    database.favorites.delete(episode.id)
                                    allFavoriteItems = database.favorites.getAllWithEpisode()
                                    onFavoriteChanged()
                                }
                            }
                        }
                    }
                }
                PodaraScrollbar(adapter = rememberScrollbarAdapter(listState))
            }
        }
    }

    if (showClearDialog) {
        PodaraConfirmDialog(
            title = Strings["favorites_clear"],
            description = androidx.compose.ui.text.AnnotatedString(Strings["favorites_clear_confirm"]),
            confirmLabel = Strings["favorites_clear_action"],
            dismissLabel = Strings["dialog_cancel"],
            onConfirm = {
                scope.launch {
                    database.favorites.deleteAll()
                    allFavoriteItems = emptyList()
                    onFavoriteChanged()
                }
                showClearDialog = false
            },
            onDismissRequest = { showClearDialog = false }
        )
    }
}