package app.podara

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import java.awt.Cursor
import coil3.compose.AsyncImage
import androidx.compose.ui.graphics.painter.BitmapPainter
import app.podara.api.apple.ApplePodcastClient
import app.podara.api.model.PodcastPreviewModel
import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import app.podara.component.AddToQueueButton
import app.podara.component.ContinueListeningCard
import app.podara.component.EpisodeActionIconButton
import app.podara.component.EpisodeListItem
import app.podara.component.EpisodeListItemSecondaryTextRole
import app.podara.component.FavoriteEpisodeButton
import app.podara.component.formatEpisodeMetadata
import app.podara.component.PodaraConfirmDialog
import app.podara.component.PodaraDialog
import app.podara.component.PodaraDialogActionButton
import app.podara.component.PodaraDialogActionStyle
import app.podara.component.PodaraDialogBody
import app.podara.component.PodaraDialogTitle
import app.podara.component.PodaraDropdownMenu
import app.podara.component.PodaraDropdownMenuItem
import app.podara.component.PodaraEmptyState
import app.podara.component.ToolbarPillButton
import app.podara.data.AppDatabase
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import app.podara.platform.GlobalMediaKeys
import app.podara.platform.MediaKeyAction
import app.podara.player.FullPlayer
import app.podara.player.MediaPlayerState
import app.podara.player.MiniPlayer
import app.podara.player.QueueDrawer
import app.podara.player.QueueItem
import app.podara.screen.DiscoverScreen
import app.podara.screen.DownloadsScreen
import app.podara.screen.FavoritesScreen
import app.podara.screen.HistoryScreen
import app.podara.screen.PodcastDetailActions
import app.podara.screen.PodcastDetailHeader
import app.podara.screen.PodcastDetailTopBar
import app.podara.screen.SettingsScreen
import app.podara.screen.rememberScrollOffProgress
import app.podara.manager.AddPodcastResult
import app.podara.manager.DownloadManager
import app.podara.manager.PodcastManager
import app.podara.manager.SubscriptionManager
import app.podara.manager.UpdatePodcastResult
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.theme.ThemePreference
import app.podara.util.Logger
import app.podara.util.RssConverter
import app.podara.util.Settings
import app.podara.util.Strings
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.clickableWithoutIndication
import app.podara.util.clickableWithoutIndicationOrFocusRing
import app.podara.util.SystemTrayManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.SwingUtilities

// Resolved per scheme: a translucent white wash is invisible on the light
// sidebar, so the light palette uses a gray wash plus an accent left rule.
private val SidebarActiveBg: Color
    @Composable get() = PodaraTheme.surfaces.pillFillSelected

// Declared at file level: defining the class and rebuilding the list inside the
// composable meant a new class identity and a new list on every recomposition.
private data class NavItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelKey: String, val screen: String)

private val navItems = listOf(
    NavItem(Icons.Default.Explore, "nav_discover", "discover"),
    NavItem(Icons.Default.LibraryMusic, "nav_subscriptions", "home"),
    NavItem(Icons.Default.Favorite, "nav_favorites", "favorites"),
    NavItem(Icons.AutoMirrored.Filled.QueueMusic, "nav_history", "history"),
    NavItem(Icons.Default.Folder, "nav_downloads", "downloads")
)

@Composable
private fun Sidebar(
    currentScreen: String,
    isRefreshingAll: Boolean = false,
    refreshAllFeedback: String? = null,
    onRefreshAll: () -> Unit = {},
    onDiscover: () -> Unit,
    onShows: () -> Unit,
    onFavorites: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onDownloads: () -> Unit = {}
) {
    val colors = PodaraTheme.colors
    val sidebar = DesignTokens.Sidebar

    Surface(
        modifier = Modifier.width(sidebar.Width).fillMaxHeight(),
        color = colors.surface
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(top = sidebar.PaddingVertical, bottom = 0.dp)
        ) {
            Row(
                modifier = Modifier.padding(start = sidebar.PaddingHorizontal + 6.dp, end = sidebar.PaddingHorizontal, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val logoPainter = remember {
                    val loader = object {}::class.java.classLoader
                    val bytes = loader.getResourceAsStream("logo-64.png")!!.readBytes()
                    BitmapPainter(bytes.decodeToImageBitmap())
                }
                Image(
                    painter = logoPainter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(sidebar.LogoSize)
                        .clip(RoundedCornerShape(8.dp))
                )
                Text(
                    text = Strings["app_name"],
                    color = colors.textPrimary,
                    fontSize = sidebar.LogoTextSize,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            navItems.forEach { item ->
                val isActive = currentScreen == item.screen
                val interactionSource = remember(item.screen) { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                val animatedBg by animateHoverBackgroundColor(isActive || isHovered, SidebarActiveBg)
                val activeGlass = DesignTokens.Navigation.ActiveGlass
                val itemShape = RoundedCornerShape(activeGlass.Radius)
                val hoverShape = itemShape
                val iconTint = if (isActive) colors.accent else colors.textSecondary

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(sidebar.NavItemHeight)
                            .padding(horizontal = sidebar.NavItemPadding)
                            .let { mod ->
                                if (isActive) {
                                    mod.shadow(activeGlass.ShadowElevation, itemShape, ambientColor = activeGlass.ShadowColor, spotColor = activeGlass.ShadowColor)
                                        .border(activeGlass.BorderWidth, activeGlass.Border, itemShape)
                                        .clip(itemShape)
                                        .background(activeGlass.BaseColor)
                                        .background(activeGlass.LeftGlow)
                                        .background(activeGlass.TopGlow)
                                        .background(activeGlass.RightGlow)
                                } else {
                                    mod.background(animatedBg, hoverShape)
                                }
                            }
                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                            .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) {
                                when (item.screen) {
                                    "discover" -> onDiscover()
                                    "home" -> onShows()
                                    "favorites" -> onFavorites()
                                    "history" -> onHistory()
                                    "settings" -> onSettings()
                                    "downloads" -> onDownloads()
                                }
                            }
                            .padding(horizontal = activeGlass.InnerPaddingHorizontal),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(item.icon, contentDescription = Strings[item.labelKey], tint = iconTint, modifier = Modifier.size(sidebar.NavIconSize))
                            Text(
                                text = Strings[item.labelKey],
                                color = if (isActive) colors.textPrimary else colors.textSecondary,
                                fontSize = sidebar.NavTextSize
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = sidebar.DividerPadding))

            Spacer(modifier = Modifier.height(8.dp))

            // ── Refresh All ──
            val refreshEnabled = !isRefreshingAll
            val refreshInteractionSource = remember { MutableInteractionSource() }
            val refreshIsHovered by refreshInteractionSource.collectIsHoveredAsState()
            val refreshAnimatedBg by animateHoverBackgroundColor(refreshEnabled && refreshIsHovered, SidebarActiveBg)
            val refreshActiveGlass = DesignTokens.Navigation.ActiveGlass
            val refreshShape = RoundedCornerShape(refreshActiveGlass.Radius)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sidebar.NavItemHeight)
                    .padding(horizontal = sidebar.NavItemPadding)
                    .let { mod ->
                        if (refreshAllFeedback != null && refreshEnabled) {
                            // Transient accent wash so the summary reads as a
                            // confirmation rather than a changed label.
                            mod.background(colors.accent.copy(alpha = 0.10f), refreshShape)
                        } else {
                            mod.background(refreshAnimatedBg, refreshShape)
                        }
                    }
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickableWithoutIndicationOrFocusRing(interactionSource = refreshInteractionSource, enabled = refreshEnabled) { onRefreshAll() }
                    .padding(horizontal = refreshActiveGlass.InnerPaddingHorizontal),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRefreshingAll) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(sidebar.NavIconSize),
                            strokeWidth = 2.dp,
                            color = colors.accent
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = Strings["refresh_all"], tint = colors.textSecondary, modifier = Modifier.size(sidebar.NavIconSize))
                    }
                    Text(
                        text = when {
                            isRefreshingAll -> Strings["refresh_all_running"]
                            refreshAllFeedback != null -> refreshAllFeedback
                            else -> Strings["refresh_all"]
                        },
                        color = if (isRefreshingAll) colors.textPrimary else colors.textSecondary,
                        fontSize = if (refreshAllFeedback != null) 12.sp else sidebar.NavTextSize,
                        maxLines = if (refreshAllFeedback != null) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val settingsActive = currentScreen == "settings"
            val settingsInteractionSource = remember { MutableInteractionSource() }
            val settingsIsHovered by settingsInteractionSource.collectIsHoveredAsState()
            val settingsAnimatedBg by animateHoverBackgroundColor(settingsActive || settingsIsHovered, SidebarActiveBg)
            val settingsActiveGlass = DesignTokens.Navigation.ActiveGlass
            val settingsShape = RoundedCornerShape(settingsActiveGlass.Radius)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sidebar.NavItemHeight)
                    .padding(horizontal = sidebar.NavItemPadding)
                    .let { mod ->
                        if (settingsActive) {
                            mod.shadow(settingsActiveGlass.ShadowElevation, settingsShape, ambientColor = settingsActiveGlass.ShadowColor, spotColor = settingsActiveGlass.ShadowColor)
                                .border(settingsActiveGlass.BorderWidth, settingsActiveGlass.Border, settingsShape)
                                .clip(settingsShape)
                                .background(settingsActiveGlass.BaseColor)
                                .background(settingsActiveGlass.LeftGlow)
                                .background(settingsActiveGlass.TopGlow)
                                .background(settingsActiveGlass.RightGlow)
                        } else {
                            mod.background(settingsAnimatedBg, settingsShape)
                        }
                    }
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickableWithoutIndicationOrFocusRing(interactionSource = settingsInteractionSource) { onSettings() }
                    .padding(horizontal = settingsActiveGlass.InnerPaddingHorizontal),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, contentDescription = Strings["nav_settings"], tint = if (settingsActive) colors.accent else colors.textSecondary, modifier = Modifier.size(sidebar.NavIconSize))
                    Text(
                        text = Strings["nav_settings"],
                        color = if (settingsActive) colors.textPrimary else colors.textSecondary,
                        fontSize = sidebar.NavTextSize
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

private const val TAG = "App"

private fun logError(e: Throwable) {
    Logger.e(TAG, "Uncaught error: ${e.message}", e)
    try {
        val logFile = File(System.getProperty("user.home"), ".podara/crash.log")
        logFile.parentFile?.mkdirs()
        logFile.appendText(
            "[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}] ${e.message}\n${e.stackTraceToString()}\n\n"
        )
    } catch (_: Exception) {}
}

private suspend fun playAndRecordHistory(
    database: AppDatabase,
    playerState: MediaPlayerState,
    episode: PodcastEpisode,
    podcastImageUrl: String? = null
) {
    Logger.i(TAG, "playAndRecordHistory: title=${episode.title}, url=${episode.audioUrl}")
    try {
        val podcast = database.podcasts.getByOrigin(episode.origin)
        database.episodes.insert(episode)
        playerState.play(
            url = episode.audioUrl,
            title = episode.title,
            subtitle = episode.podcastTitle,
            artworkUrl = episode.imageUrl,
            podcastArtworkUrl = podcast?.imageUrl ?: podcastImageUrl,
            durationMs = episode.duration * 1000L,
            episodeId = episode.id
        )
        database.history.insert(episode.origin, episode.id)
        Logger.d(TAG, "History recorded for episode: ${episode.id}")
    } catch (e: Exception) {
        Logger.e(TAG, "Failed to play episode: ${episode.title}", e)
        throw e
    }
}

@Composable
private fun WindowControlButton(
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit,
    isClose: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val colors = PodaraTheme.colors
    // A white hover wash and a white glyph both vanish on the light title bar.
    val closeHover = colors.danger
    val hoverWash = colors.textPrimary.copy(alpha = 0.08f)
    val restGlyph = colors.textMuted
    val hoverGlyph = colors.textPrimary
    val animatedBg by animateHoverBackgroundColor(isHovered, if (isClose) closeHover else hoverWash)
    val iconTint = when {
        isClose && isHovered -> Color.White
        isHovered -> hoverGlyph
        else -> restGlyph
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .background(animatedBg)
            .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        icon(iconTint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowScope.App(
    windowState: androidx.compose.ui.window.WindowState,
    awtWindow: java.awt.Window,
    closeRequestCount: Int = 0
) {
    val database = remember {
        try {
            val userHome = System.getProperty("user.home")
            val dbDir = File(userHome, ".podara")
            dbDir.mkdirs()
            Logger.i(TAG, "Initializing database at ${dbDir.absolutePath}")
            AppDatabase.build(File(dbDir, "podara.db"))
        } catch (e: Exception) {
            logError(e)
            throw e
        }
    }

    // One instance of each, sharing the process-wide HttpClient connection
    // pool. These are hoisted here so screens and managers receive the same
    // objects rather than each building their own clients.
    val appleClient = remember { ApplePodcastClient() }
    val fetchPodcastClient = remember { FetchPodcastClient() }
    val podcastManager = remember { PodcastManager(database, fetchPodcastClient, appleClient) }

    var themePreference by remember { mutableStateOf(ThemePreference.fromSetting(Settings.getTheme())) }

    var downloadPath by remember { mutableStateOf(Settings.getDownloadPath()) }
    var downloadSpeedLimitKbps by remember { mutableStateOf(Settings.getDownloadSpeedLimitKbps()) }
    val downloadManager = remember(downloadPath, downloadSpeedLimitKbps) {
        val downloadsDir = File(downloadPath)
        downloadsDir.mkdirs()
        DownloadManager(database, downloadsDir, downloadSpeedLimitKbps)
    }
    val playerState = remember { MediaPlayerState() }

    // Restore saved queue and playback state on startup
    LaunchedEffect(Unit) {
        playerState.restoreSession(database)
    }

    val trayManager = remember { SystemTrayManager(awtWindow, playerState) }
    // Save session before quitting from tray. Assigning in composition is a side
    // effect that runs on every recomposition; it belongs in SideEffect so it
    // runs once per successful composition, after changes are applied.
    SideEffect {
        trayManager.onBeforeQuit = {
            runBlocking { playerState.saveSession(database) }
        }
    }

    // Periodic heartbeat: save playback position every 30 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            if (playerState.currentUrl != null) {
                playerState.savePosition(database)
            }
        }
    }

    DisposableEffect(Unit) {
        trayManager.setup()
        onDispose { trayManager.remove() }
    }

    // Global media keys (Windows; a no-op elsewhere). Callbacks arrive on the
    // media-keys pump thread and must hop to the UI thread before touching
    // Compose state.
    val mediaKeys = remember { GlobalMediaKeys() }
    DisposableEffect(Unit) {
        mediaKeys.start { action ->
            SwingUtilities.invokeLater {
                when (action) {
                    MediaKeyAction.PLAY_PAUSE -> playerState.togglePlayPause()
                    MediaKeyAction.STOP -> playerState.stop()
                    MediaKeyAction.PREVIOUS -> playerState.playPrevious()
                    MediaKeyAction.NEXT -> playerState.playNext()
                }
            }
        }
        onDispose { mediaKeys.stop() }
    }

    LaunchedEffect(playerState.isPlaying) {
        trayManager.updatePlayPauseLabel(playerState.isPlaying)
    }
    DisposableEffect(Unit) {
        onDispose { playerState.release() }
    }
    val scope = rememberCoroutineScope()

    var podcasts by remember { mutableStateOf(emptyList<Podcast>()) }
    var currentScreen by remember { mutableStateOf("discover") }
    var selectedPodcast by remember { mutableStateOf<Podcast?>(null) }
    var discoverRefreshKey by remember { mutableStateOf(0) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var showQueueFromMini by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showCloseDialog by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var isAddingPodcast by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(mapOf<String, Pair<Long, Long>>()) }
    var downloadingEpisodes by remember { mutableStateOf(setOf<String>()) }
    var downloadVersion by remember { mutableIntStateOf(0) }
    var favoritesVersion by remember { mutableIntStateOf(0) }
    var completedDownloads by remember { mutableStateOf(setOf<String>()) }
    var downloadJobs by remember { mutableStateOf(mapOf<String, Job>()) }
    var activeDownloadMeta by remember { mutableStateOf(mapOf<String, Pair<String, String>>()) } // episodeId -> (podcastTitle, episodeTitle)

    // Subscription refresh. Bumped after every refreshAll run (startup or
    // sidebar button) so HomeScreen reloads its counts even when the podcast
    // rows themselves are unchanged — same idea as discoverRefreshKey.
    var subscriptionRefreshVersion by remember { mutableIntStateOf(0) }
    var isRefreshingAll by remember { mutableStateOf(false) }
    // Localized one-line summary shown by the sidebar after a manual refresh;
    // null while there is nothing to show.
    var refreshAllFeedback by remember { mutableStateOf<String?>(null) }

    val handleCloseRequest = {
        val action = Settings.getCloseAction()
        if (Settings.isCloseActionRemembered()) {
            when (action) {
                "quit" -> {
                    runBlocking { playerState.saveSession(database) }
                    awtWindow.dispose(); System.exit(0)
                }
                "minimize_to_tray" -> {
                    awtWindow.isVisible = false
                    trayManager.updateShowHideLabel(false)
                }
                else -> { showCloseDialog = true }
            }
        } else {
            showCloseDialog = true
        }
    }

    LaunchedEffect(closeRequestCount) {
        if (closeRequestCount > 0) handleCloseRequest()
    }

    LaunchedEffect(Unit) {
        Logger.d(TAG, "Loading podcasts from database")
        podcasts = database.podcasts.getAllSync()
        completedDownloads = database.downloads.getAllValidDownloadedIds()
        Logger.d(TAG, "Loaded ${podcasts.size} podcasts, ${completedDownloads.size} downloads")
    }

    val startDownload: (PodcastEpisode, String) -> Unit = { episode, podcastTitle ->
        // Guard: don't start a new download if already downloading
        if (episode.id !in downloadingEpisodes) {
            downloadingEpisodes = downloadingEpisodes + episode.id
            completedDownloads = completedDownloads - episode.id
            activeDownloadMeta = activeDownloadMeta + (episode.id to (podcastTitle to episode.title))
            val job = scope.launch {
                try {
                    val result = downloadManager.downloadEpisode(
                        episodeId = episode.id,
                        audioUrl = episode.audioUrl,
                        origin = episode.origin,
                        episodeTitle = episode.title,
                        podcastTitle = podcastTitle,
                        onProgress = { current, total ->
                            downloadProgress = downloadProgress + (episode.id to Pair(current, total))
                        }
                    )
                    if (result.isSuccess) {
                        completedDownloads = completedDownloads + episode.id
                    }
                } finally {
                    downloadingEpisodes = downloadingEpisodes - episode.id
                    downloadProgress = downloadProgress - episode.id
                    activeDownloadMeta = activeDownloadMeta - episode.id
                    downloadJobs = downloadJobs - episode.id
                    downloadVersion++
                }
            }
            downloadJobs = downloadJobs + (episode.id to job)
        }
        Unit
    }

    // Subscription auto-download. The manager calls this with the episodes a
    // feed update just added whenever that subscription opted in, so subscribe,
    // page open, manual refresh and Refresh All all funnel through the same
    // download bookkeeping as a manual download. The manager is remembered for
    // the session, so rememberUpdatedState keeps the trigger pointing at the
    // freshest startDownload (and with it the current DownloadManager).
    val currentStartDownload by rememberUpdatedState(startDownload)
    val subscriptionManager = remember {
        SubscriptionManager(database, fetchPodcastClient) { newEpisodes ->
            newEpisodes.forEach { currentStartDownload(it, it.podcastTitle) }
        }
    }

    // Startup subscription refresh. Home and History read the local database,
    // which otherwise only updates when a podcast page is opened, so the library
    // would stay stale until each feed was visited. Let the session restore and
    // the first frame settle, then refresh every feed once in the background and
    // reload the Home screen data.
    LaunchedEffect(Unit) {
        delay(3_000)
        try {
            val result = subscriptionManager.refreshAll()
            if (result.results.isNotEmpty()) {
                Logger.i(TAG, "Startup refresh: ${result.results.size} feeds, ${result.newEpisodesCount} new episodes")
            }
            podcasts = database.podcasts.getAllSync()
            subscriptionRefreshVersion++
        } catch (e: Exception) {
            Logger.e(TAG, "Startup subscription refresh failed", e)
        }
    }

    // ── Download management callbacks ──
    val pauseDownload: (String) -> Unit = { episodeId ->
        downloadManager.pauseDownload(episodeId)
    }

    val resumeDownload: (String) -> Unit = { episodeId ->
        // Guard: don't resume if already downloading
        if (episodeId !in downloadingEpisodes) {
            scope.launch {
                // Track in downloadingEpisodes so DownloadsScreen shows it as in-progress
                downloadingEpisodes = downloadingEpisodes + episodeId
                try {
                    val result = downloadManager.resumeDownload(episodeId) { current, total ->
                        downloadProgress = downloadProgress + (episodeId to Pair(current, total))
                    }
                    if (result.isSuccess) {
                        completedDownloads = completedDownloads + episodeId
                    }
                } finally {
                    downloadingEpisodes = downloadingEpisodes - episodeId
                    downloadProgress = downloadProgress - episodeId
                    downloadVersion++ // trigger UI refresh to show completed / removed
                }
            }
        }
        Unit
    }

    val cancelDownload: (String) -> Unit = { episodeId ->
        downloadManager.cancelDownload(episodeId)
        downloadJobs[episodeId]?.cancel()
        downloadingEpisodes = downloadingEpisodes - episodeId
        downloadProgress = downloadProgress - episodeId
        activeDownloadMeta = activeDownloadMeta - episodeId
        downloadJobs = downloadJobs - episodeId
        downloadVersion++
        // Also clean up any paused/failed task in DB and partial files
        scope.launch {
            downloadManager.cleanupPausedTask(episodeId)
        }
        Unit
    }

    val deleteDownloaded: (String) -> Unit = { episodeId ->
        scope.launch {
            downloadManager.deleteDownloadedEpisode(episodeId)
            completedDownloads = completedDownloads - episodeId
            downloadVersion++
        }
        Unit
    }

    val deleteDownloadedByOrigin: (String) -> Unit = { origin ->
        scope.launch {
            downloadManager.deleteDownloadedByOrigin(origin)
            completedDownloads = database.downloads.getAllValidDownloadedIds()
            downloadVersion++
        }
        Unit
    }

    // ── Subscription refresh (sidebar "Refresh All") ──
    val refreshAllPodcasts: () -> Unit = {
        if (!isRefreshingAll) {
            isRefreshingAll = true
            refreshAllFeedback = null
            scope.launch {
                try {
                    val result = subscriptionManager.refreshAll()
                    podcasts = database.podcasts.getAllSync()
                    subscriptionRefreshVersion++
                    val refreshed = result.results.count { it.result !is UpdatePodcastResult.Error }
                    refreshAllFeedback = Strings.get("refresh_all_done", refreshed, result.newEpisodesCount)
                } catch (e: Exception) {
                    Logger.e(TAG, "Manual refresh all failed", e)
                } finally {
                    isRefreshingAll = false
                }
            }
        }
        Unit
    }

    // The sidebar shows the refresh summary for a few seconds, then reverts.
    LaunchedEffect(refreshAllFeedback) {
        if (refreshAllFeedback != null) {
            delay(5_000)
            refreshAllFeedback = null
        }
    }

    // ── Play latest episode from FeaturedCard without subscribing ──
    // Uses iTunes Lookup API (entity=podcastEpisode) for fast response.
    val onPlayLatestEpisode: (PodcastPreviewModel) -> Unit = { preview ->
        scope.launch {
            try {
                val collectionId = when {
                    preview.fetchUrl.startsWith("itunes-lookup:") ->
                        preview.fetchUrl.removePrefix("itunes-lookup:").toLongOrNull()
                    else -> null
                } ?: return@launch

                val episodes = appleClient.lookup.lookupLatestEpisodes(collectionId, 1)
                val episode = episodes.firstOrNull() ?: return@launch
                val audioUrl = episode.episodeUrl ?: episode.previewUrl ?: return@launch

                Logger.i(TAG, "onPlayLatestEpisode: playing ${episode.trackName} from ${episode.collectionName}")
                playerState.play(
                    url = audioUrl,
                    title = episode.trackName ?: "",
                    subtitle = episode.collectionName,
                    artworkUrl = episode.artworkUrl600,
                    podcastArtworkUrl = preview.imageUrl,
                    durationMs = episode.trackTimeMillis ?: 0L,
                    episodeId = episode.episodeGuid ?: episode.trackId?.toString() ?: ""
                )
                database.history.insert(
                    origin = episode.feedUrl ?: preview.fetchUrl,
                    episodeId = episode.episodeGuid ?: episode.trackId?.toString() ?: ""
                )
            } catch (e: Exception) {
                Logger.e(TAG, "onPlayLatestEpisode failed", e)
            }
        }
    }

    // ── Navigate to podcast detail (RSS fetch only, no subscribe) ──
    val onShowDetail: (PodcastPreviewModel) -> Unit = { preview ->
        scope.launch {
            try {
                // 1. Resolve the RSS feed URL and persist itunes-lookup mapping
                val feedUrl = if (preview.fetchUrl.startsWith("itunes-lookup:")) {
                    val id = preview.fetchUrl.removePrefix("itunes-lookup:").toLongOrNull()
                        ?: return@launch
                    val lookupResult = appleClient.lookup.lookupById(id)
                    val resolvedUrl = lookupResult?.fetchUrl ?: return@launch
                    // Persist mapping so DiscoverScreen can check subscription status later
                    database.itunesLookup.insert(preview.fetchUrl, resolvedUrl)
                    resolvedUrl
                } else {
                    preview.fetchUrl
                }

                // 2. Navigate immediately with a minimal Podcast
                //    PodcastDetailScreen will fetch episodes from RSS on its own
                selectedPodcast = Podcast(
                    origin = feedUrl,
                    link = preview.link,
                    title = preview.title,
                    description = preview.description,
                    author = preview.author,
                    imageUrl = preview.imageUrl,
                    imageSeedColor = 0,
                    languageCode = preview.languageCode,
                    fileSize = 0
                )
            } catch (e: Exception) {
                Logger.e(TAG, "onShowDetail failed", e)
            }
        }
    }

    // Apple Podcasts web is light-first. Set to true to compare against the
    // previous dark glass scheme; both palettes are maintained.
    // Theme preference lives at the root so every screen observes it. Changing it
    // in Settings recomposes the whole app rather than one screen.
    PodaraTheme(preference = themePreference) {
        val titleBarColors = PodaraTheme.colors
        val surfaces = PodaraTheme.surfaces
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Paint the window itself. Without this, anything that unmounts
                // (see the FullPlayer overlay) reveals the bare window background,
                // which reads as a white flash on this undecorated window.
                .background(titleBarColors.background)
        ) {
            // ── Custom Title Bar ──
            WindowDraggableArea {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(titleBarColors.background),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(12.dp))
                    val titleLogoPainter = remember {
                        val loader = object {}::class.java.classLoader
                        val bytes = loader.getResourceAsStream("logo-64.png")!!.readBytes()
                        BitmapPainter(bytes.decodeToImageBitmap())
                    }
                    Image(
                        painter = titleLogoPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Strings["app_name"],
                        color = titleBarColors.textMuted,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Minimize
                    WindowControlButton(
                        onClick = { windowState.isMinimized = true },
                        icon = { tint -> Icon(Icons.Default.Remove, contentDescription = Strings["titlebar_minimize"], tint = tint, modifier = Modifier.size(14.dp)) }
                    )
                    // Maximize
                    WindowControlButton(
                        onClick = {
                            windowState.placement = if (windowState.placement == WindowPlacement.Maximized)
                                WindowPlacement.Floating else WindowPlacement.Maximized
                        },
                        icon = { tint ->
                            Icon(
                                if (windowState.placement == WindowPlacement.Maximized) Icons.Default.FilterNone else Icons.Default.CropSquare,
                                contentDescription = Strings["titlebar_maximize"],
                                tint = tint,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    // Close
                    WindowControlButton(
                        onClick = { handleCloseRequest() },
                        icon = { tint -> Icon(Icons.Default.Close, contentDescription = Strings["titlebar_close"], tint = tint, modifier = Modifier.size(14.dp)) },
                        isClose = true
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(titleBarColors.background)
            ) {
                PlayerOverlaidArea(
                    playerVisible = showFullPlayer,
                    sidebar = {
                    Sidebar(
                        currentScreen = currentScreen,
                        isRefreshingAll = isRefreshingAll,
                        refreshAllFeedback = refreshAllFeedback,
                        onRefreshAll = refreshAllPodcasts,
                        onDiscover = { currentScreen = "discover"; showFullPlayer = false; selectedPodcast = null },
                        onShows = { currentScreen = "home"; showFullPlayer = false; selectedPodcast = null },
                        onFavorites = { currentScreen = "favorites"; showFullPlayer = false; selectedPodcast = null },
                        onHistory = { currentScreen = "history"; showFullPlayer = false; selectedPodcast = null },
                        onSettings = { currentScreen = "settings"; showFullPlayer = false; selectedPodcast = null },
                        onDownloads = { currentScreen = "downloads"; showFullPlayer = false; selectedPodcast = null }
                    )
                    },
                    content = {
                    when {
                        selectedPodcast != null -> PodcastDetailScreen(
                        podcast = selectedPodcast!!,
                        database = database,
                        subscriptionManager = subscriptionManager,
                        fetchPodcastClient = fetchPodcastClient,
                        playerState = playerState,
                        downloadManager = downloadManager,
                        downloadingEpisodes = downloadingEpisodes,
                        downloadProgress = downloadProgress,
                        downloadVersion = downloadVersion,
                        completedDownloads = completedDownloads,
                        favoriteVersion = favoritesVersion,
                        onStartDownload = startDownload,
                        onPauseDownload = pauseDownload,
                        onResumeDownload = resumeDownload,
                        onFavoriteChanged = { favoritesVersion++ },
                        onBack = {
                            selectedPodcast = null
                            discoverRefreshKey++
                        },
                        onUnsubscribed = {
                            podcasts = database.podcasts.getAllSync()
                        },
                        onSubscribed = {
                            podcasts = database.podcasts.getAllSync()
                            discoverRefreshKey++
                        }
                    )
                    currentScreen == "home" -> HomeScreen(
                        podcasts = podcasts,
                        database = database,
                        subscriptionManager = subscriptionManager,
                        scope = scope,
                        refreshVersion = subscriptionRefreshVersion,
                        onPodcastClick = { podcast -> selectedPodcast = podcast },
                        onAddPodcast = { showAddDialog = true },
                        onDiscover = { currentScreen = "discover" },
                        onHistory = { currentScreen = "history" },
                        onSettings = { currentScreen = "settings" },
                        onPodcastsChanged = { newPodcasts -> podcasts = newPodcasts },
                        onResumeLastEpisode = { episode, positionMs ->
                            scope.launch {
                                val downloadRecord = database.downloads.getByEpisodeId(episode.id)
                                val url = if (downloadRecord != null && File(downloadRecord.filePath).exists()) {
                                    downloadRecord.filePath
                                } else {
                                    episode.audioUrl
                                }
                                val epWithUrl = episode.copy(audioUrl = url)
                                if (playerState.currentEpisodeId == epWithUrl.id) {
                                    // Same episode the player still has loaded —
                                    // picking it up again is just a resume.
                                    playerState.resume()
                                    return@launch
                                }
                                val podcast = database.podcasts.getByOrigin(epWithUrl.origin) ?: return@launch
                                val context = database.episodes.getAllByOrigin(epWithUrl.origin).map { ep ->
                                    QueueItem(
                                        url = if (ep.id == epWithUrl.id) url else ep.audioUrl,
                                        title = ep.title,
                                        subtitle = ep.podcastTitle,
                                        artworkUrl = ep.imageUrl,
                                        podcastArtworkUrl = podcast.imageUrl,
                                        episodeId = ep.id
                                    )
                                }
                                playerState.playWithContext(
                                    context = context,
                                    targetUrl = url,
                                    title = epWithUrl.title,
                                    subtitle = epWithUrl.podcastTitle,
                                    artworkUrl = epWithUrl.imageUrl,
                                    podcastArtworkUrl = podcast.imageUrl,
                                    durationMs = epWithUrl.duration * 1000L,
                                    episodeId = epWithUrl.id
                                )
                                if (positionMs > 0L) playerState.seek(positionMs)
                                database.episodes.insert(epWithUrl)
                                database.history.insert(epWithUrl.origin, epWithUrl.id)
                            }
                        }
                    )
                    currentScreen == "discover" -> DiscoverScreen(
                        database = database,
                        subscriptionManager = subscriptionManager,
                        podcastManager = podcastManager,
                        appleClient = appleClient,
                        discoverRefreshKey = discoverRefreshKey,
                        onSubscribed = {
                            scope.launch { podcasts = database.podcasts.getAllSync() }
                        },
                        onBack = {
                            currentScreen = "home"
                            scope.launch { podcasts = database.podcasts.getAllSync() }
                        },
                        onPlayLatestEpisode = onPlayLatestEpisode,
                        onShowDetail = onShowDetail
                    )
                    currentScreen == "settings" -> SettingsScreen(
                        database = database,
                        onBack = { currentScreen = "home" },
                        onDownloadPathChanged = { newPath -> downloadPath = newPath },
                        themePreference = themePreference,
                        onThemeChanged = { preference ->
                            themePreference = preference
                            Settings.setTheme(preference.settingValue)
                        },
                        downloadSpeedLimitKbps = downloadSpeedLimitKbps,
                        onDownloadSpeedLimitChanged = { limit -> downloadSpeedLimitKbps = limit }
                    )
                    currentScreen == "history" -> HistoryScreen(
                        database = database,
                        playerState = playerState,
                        favoriteVersion = favoritesVersion,
                        onBack = { currentScreen = "home" },
                        onFavoriteChanged = { favoritesVersion++ },
                        onShowPodcastDetail = { podcast -> selectedPodcast = podcast }
                    )
                    currentScreen == "favorites" -> FavoritesScreen(
                        database = database,
                        playerState = playerState,
                        favoriteVersion = favoritesVersion,
                        onBack = { currentScreen = "home" },
                        onFavoriteChanged = { favoritesVersion++ },
                        onShowPodcastDetail = { podcast -> selectedPodcast = podcast }
                    )
                    currentScreen == "downloads" -> DownloadsScreen(
                        database = database,
                        downloadManager = downloadManager,
                        downloadPath = downloadPath,
                        downloadingEpisodes = downloadingEpisodes,
                        downloadProgress = downloadProgress,
                        downloadVersion = downloadVersion,
                        completedDownloads = completedDownloads,
                        activeDownloadMeta = activeDownloadMeta,
                        playerState = playerState,
                        favoriteVersion = favoritesVersion,
                        onPauseDownload = pauseDownload,
                        onResumeDownload = resumeDownload,
                        onCancelDownload = cancelDownload,
                        onDeleteDownloaded = deleteDownloaded,
                        onDeleteDownloadedByOrigin = deleteDownloadedByOrigin,
                        onFavoriteChanged = { favoritesVersion++ },
                        onBack = { currentScreen = "home" },
                        onOpenSettings = { currentScreen = "settings" }
                    )   // DownloadsScreen
                    }   // when
                    },
                    player = {
                        FullPlayer(
                            state = playerState,
                            database = database,
                            completedDownloads = completedDownloads,
                            favoriteVersion = favoritesVersion,
                            onFavoriteChanged = { favoritesVersion++ },
                            onStartDownload = { episode -> startDownload(episode, episode.podcastTitle) },
                            onShowQueue = { showQueueFromMini = true },
                            onClose = { showFullPlayer = false }
                        )
                    }
                )
            }   // Box close

            MiniPlayer(
                state = playerState,
                onExpand = { showFullPlayer = true },
                onBodyClick = { showFullPlayer = !showFullPlayer },
                onShowQueue = { showQueueFromMini = true }
            )
        }   // Column close

        // Scrim — appears instantly, separate from panel animation
        if (showQueueFromMini) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(surfaces.scrim)
                    .clickableWithoutIndication { showQueueFromMini = false }
            )
        }

    // Panel — slides in from right
        AnimatedVisibility(
            visible = showQueueFromMini,
            enter = slideInHorizontally(animationSpec = tween(DesignTokens.Animation.NormalMs)) { it },
            exit = slideOutHorizontally(animationSpec = tween(250)) { it }
        ) {
            QueueDrawer(
                state = playerState,
                database = database,
                favoriteVersion = favoritesVersion,
                onFavoriteChanged = { favoritesVersion++ },
                onDismiss = { showQueueFromMini = false }
            )
        }

        if (showAddDialog) {
            AddPodcastDialog(
                isLoading = isAddingPodcast,
                onDismiss = {
                    if (!isAddingPodcast) {
                        showAddDialog = false
                        addError = null
                    }
                },
                onConfirm = { url ->
                    isAddingPodcast = true
                    scope.launch {
                        try {
                            when (val result = podcastManager.addPodcast(url, null)) {
                                is AddPodcastResult.Created -> {
                                    podcasts = database.podcasts.getAllSync()
                                    showAddDialog = false
                                    addError = null
                                }
                                is AddPodcastResult.Duplicate -> {
                                    addError = Strings.get("podcast_already_exists", result.duplicate.title)
                                }
                            }
                        } catch (e: Exception) {
                            addError = Strings.get("error_adding_podcast", e.message ?: "")
                        } finally {
                            isAddingPodcast = false
                        }
                    }
                },
                error = addError
            )
        }

        // ── Close Behavior Dialog ──
        if (showCloseDialog) {
            val dialogColors = PodaraTheme.colors
            var chosenAction by remember { mutableStateOf(Settings.getCloseAction()) }
            var rememberChoice by remember { mutableStateOf(false) }

        PodaraDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { PodaraDialogTitle(Strings["close_dialog_title"], textAlign = androidx.compose.ui.text.style.TextAlign.Start) },
            content = {
                Column {
                    PodaraDialogBody(Strings["close_dialog_message"])
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        "quit" to Strings["close_action_quit"],
                        "minimize_to_tray" to Strings["close_action_minimize"]
                    ).forEach { (action, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(44.dp).clickableWithoutIndication { chosenAction = action },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = chosenAction == action,
                                onClick = { chosenAction = action },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = dialogColors.accent,
                                    unselectedColor = dialogColors.textSecondary
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp, color = dialogColors.textPrimary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(40.dp).clickableWithoutIndication { rememberChoice = !rememberChoice },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = rememberChoice,
                            onCheckedChange = { rememberChoice = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = dialogColors.accent,
                                uncheckedColor = dialogColors.textSecondary,
                                checkmarkColor = dialogColors.surface
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(Strings["close_remember"], fontSize = 13.sp, color = dialogColors.textSecondary)
                    }
                }
            },
            actions = {
                PodaraDialogActionButton(Strings["dialog_cancel"], { showCloseDialog = false }, PodaraDialogActionStyle.Secondary)
                PodaraDialogActionButton(Strings["dialog_ok"], {
                    if (rememberChoice) {
                        Settings.setCloseAction(chosenAction)
                        Settings.setCloseActionRemembered(true)
                    }
                    when (chosenAction) {
                        "quit" -> {
                            runBlocking { playerState.saveSession(database) }
                            awtWindow.dispose(); System.exit(0)
                        }
                        "minimize_to_tray" -> {
                            awtWindow.isVisible = false
                            trayManager.updateShowHideLabel(false)
                        }
                    }
                    showCloseDialog = false
                }, PodaraDialogActionStyle.Primary)
            }
        )
    }
    }   // PodaraTheme close
}

internal fun sortPodcastsByLatestEpisodeDate(
    podcasts: List<Podcast>,
    latestEpisodePubDateMap: Map<String, Long>
): List<Podcast> = podcasts.sortedWith(
    compareByDescending<Podcast> { latestEpisodePubDateMap[it.origin] ?: 0L }
        .thenBy { it.fetchTitle() }
        .thenBy { it.origin }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    podcasts: List<Podcast>,
    database: AppDatabase,
    subscriptionManager: SubscriptionManager,
    scope: kotlinx.coroutines.CoroutineScope,
    refreshVersion: Int = 0,
    onPodcastClick: (Podcast) -> Unit,
    onAddPodcast: () -> Unit,
    onDiscover: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onPodcastsChanged: (List<Podcast>) -> Unit,
    onResumeLastEpisode: (PodcastEpisode, Long) -> Unit
) {
    val colors = PodaraTheme.colors
    var isEditing by remember { mutableStateOf(false) }
    var selectedPodcasts by remember { mutableStateOf(setOf<String>()) }
    var showBatchUnsubscribeDialog by remember { mutableStateOf(false) }
    var showUnsubscribeDialog by remember { mutableStateOf(false) }
    var podcastToUnsubscribe by remember { mutableStateOf<Podcast?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var subscriptionMap by remember { mutableStateOf(mapOf<String, app.podara.data.model.PodcastSubscription>()) }
    var episodeCountMap by remember { mutableStateOf(mapOf<String, Int>()) }
    var latestEpisodePubDateMap by remember { mutableStateOf(mapOf<String, Long>()) }
    var lastListenedMap by remember { mutableStateOf(mapOf<String, Long>()) }
    var sortOption by remember { mutableStateOf("name_asc") }
    var showSortMenu by remember { mutableStateOf(false) }

    // Load subscription data and episode counts. refreshVersion is bumped by
    // subscription refreshes (startup / sidebar) so counts reload even when the
    // podcast rows themselves are unchanged.
    LaunchedEffect(podcasts, refreshVersion) {
        if (podcasts.isEmpty()) return@LaunchedEffect
        val subs = database.subscriptions.getAllSync()
        subscriptionMap = subs.associateBy { it.origin }
        // One aggregate query. This used to load every episode of every podcast
        // — including each episode's full HTML show notes — to compute two
        // numbers, on the single-threaded database dispatcher. With 100 podcasts
        // x 200 episodes that is tens of thousands of large strings per refresh.
        val (counts, latestPubDates) = database.episodes.getCountsAndLatestByOrigin(podcasts.map { it.origin })
        episodeCountMap = counts
        latestEpisodePubDateMap = latestPubDates
        lastListenedMap = database.history.getLatestTimestampPerOrigin()
    }

    // "Continue listening" snapshot: the episode the player session last had
    // open and where it stopped. Reloaded on the same cadence as the counts;
    // hidden when nothing is in progress (never started, already finished, or
    // the episode has left its feed).
    var resumeEpisode by remember { mutableStateOf<PodcastEpisode?>(null) }
    var resumePodcast by remember { mutableStateOf<Podcast?>(null) }
    var resumePositionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(podcasts, refreshVersion) {
        val session = database.playerSession.loadSession()
        val episode = session?.currentEpisodeId?.let { database.episodes.getById(it) }
        resumeEpisode = episode
        resumePodcast = episode?.let { database.podcasts.getByOrigin(it.origin) }
        resumePositionMs = session?.currentPositionMs ?: 0L
    }

    val filteredPodcasts = remember(podcasts, searchQuery) {
        if (searchQuery.isBlank()) podcasts
        else podcasts.filter { it.title.contains(searchQuery, ignoreCase = true) || it.author.contains(searchQuery, ignoreCase = true) }
    }

    val sortedPodcasts = remember(filteredPodcasts, sortOption, latestEpisodePubDateMap, lastListenedMap) {
        when (sortOption) {
            "name_asc" -> filteredPodcasts.sortedBy { it.fetchTitle() }
            "name_desc" -> filteredPodcasts.sortedByDescending { it.fetchTitle() }
            "recent_update" -> sortPodcastsByLatestEpisodeDate(filteredPodcasts, latestEpisodePubDateMap)
            "recent_listen" -> filteredPodcasts.sortedByDescending { lastListenedMap[it.origin] ?: 0L }
            else -> filteredPodcasts
        }
    }

    // ── Empty state ──
    if (podcasts.isEmpty()) {
        PodaraEmptyState(
            icon = Icons.Default.RssFeed,
            title = Strings["home_empty"],
            subtitle = Strings["home_empty_hint"],
            modifier = Modifier.fillMaxSize().background(colors.background),
            iconTint = colors.accent,
            actionText = Strings["home_add_podcast"],
            actionIcon = Icons.Default.Add,
            onActionClick = onAddPodcast
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(
                top = DesignTokens.PageHeader.PaddingTop,
                start = DesignTokens.PageHeader.PaddingHorizontal,
                end = DesignTokens.PageHeader.PaddingHorizontal,
                bottom = DesignTokens.Spacing.sm
            )
    ) {
        // ── Page header ──
        val header = DesignTokens.PageHeader
        val search = DesignTokens.SearchBar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
                // Left: Title + Subtitle
                Column {
                    Text(
                        text = Strings["home_subscriptions"],
                        fontSize = header.TitleSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = DesignTokens.TypeFamily.PageTitle,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(header.Gap))
                    Text(Strings["home_subscriptions_desc"], fontSize = header.SubtitleSize, color = colors.textMuted)
                }

                // Right: Search bar
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
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(search.IconSize)
                        )
                        Spacer(modifier = Modifier.width(search.Gap))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text(Strings["home_search_placeholder"], color = colors.textDisabled, fontSize = search.TextSize)
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = TextStyle(color = colors.textPrimary, fontSize = search.TextSize),
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
                                    .clickableWithoutIndication { searchQuery = "" }
                            )
                        }
                    }
                }
            }

        Spacer(modifier = Modifier.height(16.dp))

        val toolbarButton = DesignTokens.ToolbarButton
        val selectionToolbar = DesignTokens.SubscriptionSelectionToolbar
        // ── List toolbar: count + actions ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(toolbarButton.PillHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isEditing) {
                Row(horizontalArrangement = Arrangement.spacedBy(toolbarButton.Gap)) {
                    ToolbarPillButton(
                        icon = Icons.Default.Close,
                        label = Strings["home_cancel"],
                        contentDescription = Strings["home_cancel"],
                        onClick = {
                            isEditing = false; selectedPodcasts = emptySet()
                        }
                    )
                    Text(
                        text = Strings.get("home_selected_count", selectedPodcasts.size),
                        fontSize = DesignTokens.ToolbarButton.PillTextSize,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            } else {
                Text(
                    text = Strings.get("home_subscription_count", podcasts.size),
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(toolbarButton.Gap)) {
                if (isEditing) {
                    ToolbarPillButton(
                        icon = Icons.Default.SelectAll,
                        label = Strings["home_select_all"],
                        contentDescription = Strings["home_select_all"],
                        onClick = {
                            selectedPodcasts = if (selectedPodcasts.size == filteredPodcasts.size) emptySet()
                            else filteredPodcasts.map { it.origin }.toSet()
                        }
                    )
                    ToolbarPillButton(
                        icon = Icons.Default.Delete,
                        label = Strings["home_delete_selected"],
                        contentDescription = Strings["home_delete_selected"],
                        onClick = { showBatchUnsubscribeDialog = true },
                        iconColor = selectionToolbar.DeleteIconColor,
                        hoverIconColor = selectionToolbar.DeleteIconHoverColor,
                        textColor = selectionToolbar.DeleteIconColor,
                        hoverTextColor = selectionToolbar.DeleteIconHoverColor,
                        hoverBackgroundColor = selectionToolbar.DeleteButtonHoverBackgroundColor,
                        pressedBackgroundColor = selectionToolbar.DeleteButtonPressedBackgroundColor,
                        hoverBorderColor = selectionToolbar.DeleteButtonHoverBorderColor
                    )
                } else {
                    val currentSortLabel = when (sortOption) {
                        "name_asc" -> Strings["home_sort_name_asc"]
                        "name_desc" -> Strings["home_sort_name_desc"]
                        "recent_update" -> Strings["home_sort_recent_update"]
                        "recent_listen" -> Strings["home_sort_recent_listen"]
                        else -> Strings["home_sort"]
                    }

                    ToolbarPillButton(
                        icon = Icons.Default.Add,
                        label = Strings["home_add_podcast"],
                        contentDescription = Strings["home_add_podcast"],
                        onClick = onAddPodcast
                    )

                    // Manage button
                    ToolbarPillButton(
                        icon = Icons.Default.Edit,
                        label = Strings["home_manage"],
                        contentDescription = Strings["home_manage"],
                        onClick = { isEditing = true }
                    )

                    Box {
                        // Sort button
                        val sortInteractionSource = remember { MutableInteractionSource() }
                        val isSortHovered by sortInteractionSource.collectIsHoveredAsState()
                        val isSortPressed by sortInteractionSource.collectIsPressedAsState()
                        val sortShape = RoundedCornerShape(toolbarButton.PillRadius)
                        val sortActive = showSortMenu
                        val sortTextColor = when {
                            sortActive -> toolbarButton.PillSelectedTextColor
                            isSortHovered || isSortPressed -> toolbarButton.PillHoverTextColor
                            else -> toolbarButton.PillTextColor
                        }
                        val sortIconColor = when {
                            sortActive -> toolbarButton.PillSelectedIconColor
                            isSortHovered || isSortPressed -> toolbarButton.PillHoverIconColor
                            else -> toolbarButton.PillIconColor
                        }
                        Box(
                            modifier = Modifier
                                .height(toolbarButton.PillHeight)
                                .widthIn(min = toolbarButton.SortMinWidth)
                                .shadow(
                                    if (isSortHovered) toolbarButton.PillHoverShadowElevation else 0.dp,
                                    sortShape,
                                    ambientColor = toolbarButton.PillHoverShadowColor,
                                    spotColor = toolbarButton.PillHoverShadowColor
                                )
                                .clip(sortShape)
                                .background(
                                    when {
                                        sortActive -> toolbarButton.PillSelectedBackgroundColor
                                        isSortPressed -> toolbarButton.PillPressedBackgroundColor
                                        isSortHovered -> toolbarButton.PillHoverBackgroundColor
                                        else -> toolbarButton.PillSortBackgroundColor
                                    }
                                )
                                .border(
                                    toolbarButton.BorderWidth,
                                    when {
                                        sortActive -> toolbarButton.PillSelectedBorderColor
                                        isSortHovered || isSortPressed -> toolbarButton.PillHoverBorderColor
                                        else -> toolbarButton.PillSortBorderColor
                                    },
                                    sortShape
                                )
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                .clickableWithoutIndicationOrFocusRing(interactionSource = sortInteractionSource) { showSortMenu = true }
                                .padding(horizontal = toolbarButton.PillPaddingHorizontal),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(toolbarButton.PillIconTextGap)
                            ) {
                                Icon(
                                    Icons.Default.UnfoldMore,
                                    contentDescription = null,
                                    tint = sortIconColor,
                                    modifier = Modifier.size(toolbarButton.PillIconSize)
                                )
                                Text(
                                    text = currentSortLabel,
                                    color = sortTextColor,
                                    fontSize = toolbarButton.PillTextSize,
                                    lineHeight = toolbarButton.PillLineHeight,
                                    fontWeight = if (sortActive) toolbarButton.PillActiveTextWeight else toolbarButton.PillTextWeight
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = Strings["home_sort"],
                                    tint = sortIconColor,
                                    modifier = Modifier.size(toolbarButton.PillTrailingIconSize)
                                )
                            }
                        }

                        PodaraDropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            items = listOf(
                                PodaraDropdownMenuItem(
                                    label = Strings["home_sort_name_asc"],
                                    icon = Icons.Default.SortByAlpha,
                                    isSelected = sortOption == "name_asc",
                                    onClick = { sortOption = "name_asc"; showSortMenu = false }
                                ),
                                PodaraDropdownMenuItem(
                                    label = Strings["home_sort_name_desc"],
                                    icon = Icons.Default.SortByAlpha,
                                    isSelected = sortOption == "name_desc",
                                    onClick = { sortOption = "name_desc"; showSortMenu = false }
                                ),
                                PodaraDropdownMenuItem(
                                    label = Strings["home_sort_recent_update"],
                                    icon = Icons.Default.Update,
                                    isSelected = sortOption == "recent_update",
                                    onClick = { sortOption = "recent_update"; showSortMenu = false }
                                ),
                                PodaraDropdownMenuItem(
                                    label = Strings["home_sort_recent_listen"],
                                    icon = Icons.Default.History,
                                    isSelected = sortOption == "recent_listen",
                                    onClick = { sortOption = "recent_listen"; showSortMenu = false }
                                )
                            )
                        )
                    }
                }
            }  // closes inner Row
        }  // closes outer Row

        Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))

        HorizontalDivider(color = colors.divider)

        Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))

        // ── Subscriptions list ──
        if (sortedPodcasts.isEmpty()) {
            val glass = DesignTokens.Glass
            Box(
                modifier = Modifier.fillMaxSize(),
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
                            .shadow(glass.CompactShadowElevation, RoundedCornerShape(DesignTokens.EmptyState.PanelRadius), ambientColor = glass.CompactShadowColor, spotColor = glass.CompactShadowColor)
                            .clip(RoundedCornerShape(DesignTokens.EmptyState.PanelRadius))
                            .background(glass.CompactGradient)
                            .border(DesignTokens.Border.Width, glass.CompactBorderColor, RoundedCornerShape(DesignTokens.EmptyState.PanelRadius))
                            .padding(DesignTokens.EmptyState.PanelPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = Strings["home_search_empty"],
                                color = colors.textPrimary,
                                fontSize = DesignTokens.EmptyState.TitleSize,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(DesignTokens.Spacing.xs))
                            Text(
                                text = Strings["home_search_empty_hint"],
                                color = colors.textSecondary,
                                fontSize = DesignTokens.EmptyState.SubtitleSize
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.FavoriteEpisodeList.CardGap),
                contentPadding = PaddingValues(
                    top = DesignTokens.FavoriteEpisodeList.ListPaddingTop,
                    bottom = DesignTokens.FavoriteEpisodeList.ListPaddingBottom
                )
            ) {
                val resume = resumeEpisode
                if (resume != null && resumePositionMs > 0L &&
                    (resume.duration <= 0 || resumePositionMs < resume.duration * 1000L)
                ) {
                    item(key = "resume-listening") {
                        ContinueListeningCard(
                            episodeTitle = resume.title,
                            podcastTitle = resume.podcastTitle,
                            positionMs = resumePositionMs,
                            durationMs = resume.duration * 1000L,
                            imageUrl = resume.imageUrl?.takeIf { it.isNotBlank() }
                                ?: resumePodcast?.imageUrl?.takeIf { it.isNotBlank() },
                            onClick = { onResumeLastEpisode(resume, resumePositionMs) }
                        )
                    }
                }
                items(sortedPodcasts) { podcast ->
                    val sub = subscriptionMap[podcast.origin]
                    val newCount = sub?.newEpisodes ?: 0
                    val epCount = episodeCountMap[podcast.origin] ?: 0

                    SubscriptionCard(
                        podcast = podcast,
                        newCount = newCount,
                        episodeCount = epCount,
                        isEditing = isEditing,
                        isSelected = podcast.origin in selectedPodcasts,
                        onToggleSelect = { checked ->
                            selectedPodcasts = if (checked) selectedPodcasts + podcast.origin
                            else selectedPodcasts - podcast.origin
                        },
                        onClick = { if (!isEditing) onPodcastClick(podcast) },
                        onMore = {
                            podcastToUnsubscribe = podcast
                            showUnsubscribeDialog = true
                        }
                    )
                }
            }
        }
    }

    // ── Dialogs ──
    if (showUnsubscribeDialog && podcastToUnsubscribe != null) {
        val podcast = podcastToUnsubscribe!!
        val confirmationMessage = Strings.get("unsubscribe_confirm", podcast.title)
        val confirmationText = buildAnnotatedString {
            append(confirmationMessage)
            val titleStart = confirmationMessage.indexOf(podcast.title)
            if (titleStart >= 0) {
                addStyle(
                    SpanStyle(
                        fontWeight = DesignTokens.Dialog.Typography.EmphasisWeight,
                        color = DesignTokens.Dialog.Typography.EmphasisColor
                    ),
                    titleStart,
                    titleStart + podcast.title.length
                )
            }
        }
        PodaraConfirmDialog(
            title = Strings["unsubscribe"],
            description = confirmationText,
            confirmLabel = Strings["unsubscribe"],
            dismissLabel = Strings["dialog_cancel"],
            onConfirm = {
                scope.launch {
                    subscriptionManager.unsubscribe(podcast.origin)
                    onPodcastsChanged(database.podcasts.getAllSync())
                    showUnsubscribeDialog = false
                    podcastToUnsubscribe = null
                }
            },
            onDismissRequest = {
                showUnsubscribeDialog = false
                podcastToUnsubscribe = null
            }
        )
    }

    if (showBatchUnsubscribeDialog) {
        PodaraConfirmDialog(
            title = Strings["batch_unsubscribe"],
            description = AnnotatedString(Strings.get("batch_unsubscribe_confirm", selectedPodcasts.size)),
            confirmLabel = Strings["unsubscribe"],
            dismissLabel = Strings["dialog_cancel"],
            onConfirm = {
                scope.launch {
                    selectedPodcasts.forEach { subscriptionManager.unsubscribe(it) }
                    onPodcastsChanged(database.podcasts.getAllSync())
                    selectedPodcasts = emptySet(); isEditing = false; showBatchUnsubscribeDialog = false
                }
            },
            onDismissRequest = { showBatchUnsubscribeDialog = false }
        )
    }
}

@Composable
private fun SubscriptionCard(
    podcast: Podcast,
    newCount: Int,
    episodeCount: Int,
    isEditing: Boolean,
    isSelected: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    val colors = PodaraTheme.colors
    val card = DesignTokens.FavoriteEpisodeList
    val row = DesignTokens.SubscriptionRow
    val badge = DesignTokens.Badge
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(card.CardRadius)
    val animatedBg by animateColorAsState(
        when {
            isSelected -> card.PlayingBackgroundColor
            isPressed && !isEditing -> card.PressedBackgroundColor
            isHovered && !isEditing -> card.HoverBackgroundColor
            else -> card.BackgroundColor
        },
        tween(DesignTokens.Animation.HoverMs)
    )
    val borderColor by animateColorAsState(
        when {
            isSelected -> card.PlayingBorderColor
            isHovered && !isEditing -> card.HoverBorderColor
            else -> card.BorderColor
        },
        tween(DesignTokens.Animation.HoverMs)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(card.CardHeight)
            .clip(shape)
            .background(animatedBg)
            .border(card.BorderWidth, borderColor, shape)
            .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) {
                if (isEditing) onToggleSelect(!isSelected) else onClick()
            }
            .padding(horizontal = card.CardPaddingHorizontal, vertical = card.CardPaddingVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isEditing) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = onToggleSelect,
                modifier = Modifier.size(row.CheckboxSize)
            )
            Spacer(modifier = Modifier.width(card.CoverContentGap))
        }

        // Cover
        Box(
            modifier = Modifier
                .size(card.CoverSize)
                .shadow(
                    card.CoverShadowElevation,
                    RoundedCornerShape(card.CoverRadius),
                    ambientColor = card.CoverShadowColor,
                    spotColor = card.CoverShadowColor
                )
                .clip(RoundedCornerShape(card.CoverRadius))
                .background(colors.elevated)
        ) {
            AsyncImage(model = podcast.imageUrl, contentDescription = podcast.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }

        Spacer(modifier = Modifier.width(card.CoverContentGap))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = podcast.fetchTitle(),
                color = card.TitleColor,
                fontSize = card.TitleSize,
                lineHeight = card.TitleLineHeight,
                fontWeight = card.TitleWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(card.PodcastNameMarginTop))
            Text(
                text = podcast.author,
                color = card.PodcastNameColor,
                fontSize = card.PodcastNameSize,
                lineHeight = card.PodcastNameLineHeight,
                fontWeight = card.PodcastNameWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (podcast.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(card.MetadataMarginTop))
                Text(
                    text = stripHtml(podcast.description),
                    color = card.MetadataColor,
                    fontSize = card.MetadataSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = row.DescriptionMaxWidth)
                )
            }
        }

        Spacer(modifier = Modifier.width(card.ContentActionsGap))

        // Meta: episode count + New badge (horizontal row)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
            modifier = Modifier.padding(end = row.MetaEndPadding)
        ) {
            if (episodeCount > 0) {
                Text(text = Strings.get("home_episode_count", episodeCount), color = card.MetadataColor, fontSize = card.MetadataSize)
            }
            if (newCount > 0) {
                Box(
                    modifier = Modifier
                        .wrapContentSize()
                        .clip(RoundedCornerShape(badge.Radius))
                        .background(badge.AccentBackgroundColor)
                        .padding(horizontal = badge.PaddingHorizontal, vertical = badge.PaddingVertical),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (newCount == 1) Strings["home_new_badge"] else Strings.get("home_new_count", newCount),
                        color = badge.AccentTextColor, fontSize = badge.TextSize, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(card.ActionsGap))

        // More button
        if (!isEditing) {
            EpisodeActionIconButton(
                icon = Icons.Default.Delete,
                contentDescription = Strings["unsubscribe"],
                size = card.ActionButtonSize,
                radius = card.ActionButtonRadius,
                iconSize = card.ActionIconSize,
                hoverBackgroundColor = card.ActionButtonHoverBackgroundColor,
                defaultIconColor = card.ActionIconColor,
                hoverIconColor = card.ActionIconHoverColor,
                onClick = onMore
            )
        }
    }
}

@Composable
private fun PodcastDetailScreen(
    podcast: Podcast,
    database: AppDatabase,
    subscriptionManager: SubscriptionManager,
    fetchPodcastClient: FetchPodcastClient,
    playerState: MediaPlayerState,
    downloadManager: DownloadManager,
    downloadingEpisodes: Set<String>,
    downloadProgress: Map<String, Pair<Long, Long>>,
    downloadVersion: Int,
    completedDownloads: Set<String>,
    favoriteVersion: Int,
    onStartDownload: (PodcastEpisode, String) -> Unit,
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String) -> Unit = {},
    onFavoriteChanged: () -> Unit = {},
    onBack: () -> Unit,
    onUnsubscribed: suspend () -> Unit = { },
    onSubscribed: suspend () -> Unit = { }
) {
    val colors = PodaraTheme.colors
    var episodes by remember { mutableStateOf(emptyList<PodcastEpisode>()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSubscribed by remember { mutableStateOf(false) }
    var autoDownloadEnabled by remember { mutableStateOf(false) }
    var showUnsubscribeDialog by remember { mutableStateOf(false) }
    var favoriteIds by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    // The big header is the first item of the episode list, so it scrolls away
    // with the content. This tracks how far gone it is, and the top bar uses it
    // to crossfade its compact title and actions in.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val headerCollapseProgress by rememberScrollOffProgress(listState)

    // Shared by the full header and the compact top-bar row, so both offer the
    // same three actions and neither can drift from the other.
    val playLatest: () -> Unit = {
        scope.launch {
            val latest = episodes.maxByOrNull { it.pubDate }
            if (latest != null) {
                playAndRecordHistory(database, playerState, latest, podcast.imageUrl)
            }
        }
    }
    val toggleSubscribe: () -> Unit = {
        if (isSubscribed) {
            showUnsubscribeDialog = true
        } else {
            scope.launch {
                database.podcasts.insert(podcast)
                subscriptionManager.subscribe(podcast.origin)
                isSubscribed = true
                onSubscribed()
                try {
                    when (val result = subscriptionManager.updatePodcast(podcast.origin, podcast.imageSeedColor)) {
                        is UpdatePodcastResult.Updated -> {
                            episodes = database.episodes.getAllByOrigin(podcast.origin)
                        }
                        else -> { }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // Optimistic toggle; the subscription row is the source of truth and is
    // rewritten on the next page load. No-op before subscribing — the menu
    // item is hidden then anyway.
    val toggleAutoDownload: () -> Unit = {
        if (isSubscribed) {
            val newValue = !autoDownloadEnabled
            autoDownloadEnabled = newValue
            scope.launch {
                database.subscriptions.setAutoDownload(podcast.origin, newValue)
            }
        }
        Unit
    }

    // Build context queue items for playWithContext
    val episodeContextItems: List<QueueItem> = remember(episodes) {
        episodes.map { ep ->
            QueueItem(url = ep.audioUrl, title = ep.title, subtitle = ep.podcastTitle, artworkUrl = ep.imageUrl, podcastArtworkUrl = podcast.imageUrl, episodeId = ep.id)
        }
    }

    // ── Active download progress from DB (for robustness across page navigation) ──
    var activeTaskProgress by remember { mutableStateOf(mapOf<String, Pair<Long, Long>>()) }
    LaunchedEffect(downloadVersion) {
        try {
            val activeTasks = database.downloadTasks.getAllActive()
            // Include all non-completed tasks (DOWNLOADING, PAUSED, FAILED)
            // so the detail page never shows a download button for a task that exists
            activeTaskProgress = activeTasks.associate { t ->
                t.episodeId to (t.downloadedBytes to t.totalBytes)
            }
        } catch (_: Exception) { }
    }

    LaunchedEffect(favoriteVersion) {
        favoriteIds = database.favorites.getAllEpisodeIds()
    }

    // Combined check: memory state OR DB task.
    // Keyed on downloadVersion so DB changes force recalculation.
    val allDownloading = remember(downloadingEpisodes, activeTaskProgress, downloadVersion) {
        downloadingEpisodes + activeTaskProgress.keys
    }

    LaunchedEffect(podcast.origin) {
        val subscription = database.subscriptions.getByOriginSync(podcast.origin)
        isSubscribed = subscription != null
        autoDownloadEnabled = subscription?.enableAutoDownload ?: false

        if (subscription != null) {
            // Subscribed: load from DB, then refresh via RSS
            episodes = database.episodes.getAllByOrigin(podcast.origin)
            try {
                when (val result = subscriptionManager.updatePodcast(podcast.origin, podcast.imageSeedColor)) {
                    is UpdatePodcastResult.Updated -> {
                        episodes = database.episodes.getAllByOrigin(podcast.origin)
                    }
                    else -> { }
                }
            } catch (_: Exception) { }
            isLoading = false
        } else {
            // Not subscribed: preview mode — fetch RSS directly, no DB writes
            try {
                val result = fetchPodcastClient.fetchNoCache(podcast.origin)
                if (result is FetchPodcastClientResult.Success) {
                    val (_, parsedEpisodes) = RssConverter.parseFetchResult(result, podcast.origin)
                    episodes = parsedEpisodes
                }
            } catch (_: Exception) { }
            isLoading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(colors.background)
    ) {
        // ── Top bar: back always, title and actions crossfade in as the header
        // scrolls off ──
        PodcastDetailTopBar(
            title = podcast.fetchTitle(),
            collapseProgress = headerCollapseProgress,
            onBack = onBack
        ) {
            PodcastDetailActions(
                isSubscribed = isSubscribed,
                autoDownloadEnabled = autoDownloadEnabled,
                onToggleAutoDownload = toggleAutoDownload,
                onPlayLatest = playLatest,
                onToggleSubscribe = toggleSubscribe,
                rssUrl = podcast.origin
            )
        }

        // ── Content ──
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
            episodes.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = Strings["podcast_no_episodes"],
                            color = PodaraTheme.colors.textMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            scope.launch {
                                isLoading = true
                                try {
                                    val result = subscriptionManager.updatePodcast(podcast.origin, podcast.imageSeedColor)
                                    if (result is UpdatePodcastResult.Updated) {
                                        episodes = database.episodes.getAllByOrigin(podcast.origin)
                                    }
                                } catch (_: Exception) { }
                                isLoading = false
                            }
                        }) {
                            Text(Strings["podcast_retry"])
                        }
                    }
                }
            }
            else -> {
                // ── Episode list. The podcast header is its first item, so it
                // scrolls away with the content instead of pinning above it;
                // the top bar crossfades in to take over its context. ──
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        top = DesignTokens.FavoriteEpisodeList.ListPaddingTop,
                        bottom = DesignTokens.FavoriteEpisodeList.ListPaddingBottom
                    ),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.FavoriteEpisodeList.CardGap)
                ) {
                    item {
                        PodcastDetailHeader(
                            podcast = podcast,
                            isSubscribed = isSubscribed,
                            autoDownloadEnabled = autoDownloadEnabled,
                            onToggleAutoDownload = toggleAutoDownload,
                            onPlayLatest = playLatest,
                            onToggleSubscribe = toggleSubscribe
                        )
                    }
                    items(episodes) { episode ->
                        val isDownloading = episode.id in allDownloading
                        val progress = downloadProgress[episode.id] ?: activeTaskProgress[episode.id]
                        val isDbTask = episode.id in activeTaskProgress && episode.id !in downloadProgress

                        EpisodeListItem(
                            episode = episode,
                            podcast = podcast,
                            isPlaying = playerState.currentEpisodeId == episode.id || playerState.currentUrl == episode.audioUrl,
                            secondaryText = formatEpisodeMetadata(formatDate(episode.pubDate), episode.duration),
                            tertiaryText = stripHtml(episode.description),
                            secondaryTextRole = EpisodeListItemSecondaryTextRole.Metadata,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            onPlay = {
                                scope.launch {
                                    val downloadRecord = database.downloads.getByEpisodeId(episode.id)
                                    val url = if (downloadRecord != null && File(downloadRecord.filePath).exists()) {
                                        downloadRecord.filePath
                                    } else {
                                        episode.audioUrl
                                    }
                                    val epWithUrl = episode.copy(audioUrl = url)
                                    playerState.playWithContext(
                                        // Match by episodeId, not URL: the resolved URL
                                        // is a local file path when downloaded, which no
                                        // context item carries, so URL matching replaced
                                        // nothing and playWithContext appended a duplicate.
                                        context = episodeContextItems.map { item ->
                                            if (item.episodeId == epWithUrl.id) item.copy(url = url) else item
                                        },
                                        targetUrl = url,
                                        title = epWithUrl.title,
                                        subtitle = epWithUrl.podcastTitle,
                                        artworkUrl = epWithUrl.imageUrl,
                                        podcastArtworkUrl = podcast.imageUrl,
                                        durationMs = epWithUrl.duration * 1000L,
                                        episodeId = epWithUrl.id
                                    )
                                    database.episodes.insert(epWithUrl)
                                    database.history.insert(epWithUrl.origin, epWithUrl.id)
                                }
                            },
                        ) {
                            FavoriteEpisodeButton(isFavorite = episode.id in favoriteIds) {
                                scope.launch {
                                    database.episodes.insert(episode)
                                    val isFavorite = database.favorites.toggle(episode)
                                    favoriteIds = if (isFavorite) favoriteIds + episode.id else favoriteIds - episode.id
                                    onFavoriteChanged()
                                }
                            }

                            AddToQueueButton {
                                scope.launch {
                                    val downloadRecord = database.downloads.getByEpisodeId(episode.id)
                                    val url = if (downloadRecord != null && File(downloadRecord.filePath).exists()) {
                                        downloadRecord.filePath
                                    } else {
                                        episode.audioUrl
                                    }
                                    playerState.addToQueue(
                                        url = url,
                                        title = episode.title,
                                        artworkUrl = episode.imageUrl,
                                        podcastArtworkUrl = podcast.imageUrl,
                                        episodeId = episode.id,
                                        isDownloaded = episode.id in completedDownloads
                                    )
                                }
                            }

                            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                if (episode.id in completedDownloads) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = Strings["episode_downloaded"],
                                        tint = colors.success
                                    )
                                } else if (isDownloading) {
                                    val fraction = if (progress != null && progress.second > 0) {
                                        progress.first.toFloat() / progress.second
                                    } else 0f
                                    val ringInteractionSource = remember { MutableInteractionSource() }
                                    val isRingHovered by ringInteractionSource.collectIsHoveredAsState()
                                    val ringAnimatedBg by animateHoverBackgroundColor(isRingHovered, colors.elevated)
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(ringAnimatedBg)
                                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                            .clickableWithoutIndicationOrFocusRing(interactionSource = ringInteractionSource) {
                                                if (isDbTask) {
                                                    onResumeDownload(episode.id)
                                                } else {
                                                    onPauseDownload(episode.id)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = colors.accent
                                        )
                                    }
                                } else {
                                    val dInteractionSource = remember { MutableInteractionSource() }
                                    val isDHovered by dInteractionSource.collectIsHoveredAsState()
                                    val dAnimatedBg by animateHoverBackgroundColor(isDHovered, colors.elevated)
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(dAnimatedBg)
                                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                            .clickableWithoutIndicationOrFocusRing(interactionSource = dInteractionSource) {
                                                onStartDownload(episode, podcast.title)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = Strings["episode_download"], tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUnsubscribeDialog) {
        PodaraConfirmDialog(
            title = Strings["unsubscribe"],
            description = AnnotatedString(Strings.get("unsubscribe_confirm", podcast.title)),
            confirmLabel = Strings["unsubscribe"],
            dismissLabel = Strings["dialog_cancel"],
            onConfirm = {
                scope.launch {
                    subscriptionManager.unsubscribe(podcast.origin)
                    onUnsubscribed()
                    onBack()
                    showUnsubscribeDialog = false
                }
            },
            onDismissRequest = { showUnsubscribeDialog = false }
        )
    }
}

@Composable
private fun AddPodcastDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    isLoading: Boolean = false,
    error: String? = null
) {
    var url by remember { mutableStateOf("") }
    val colors = PodaraTheme.colors

    PodaraDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { PodaraDialogTitle(Strings["home_add_podcast"], textAlign = androidx.compose.ui.text.style.TextAlign.Start) },
        content = {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent
                        )
                        Text(
                            text = Strings["add_podcast_adding"],
                            color = colors.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                Column {
                    PodaraDialogBody(Strings["add_podcast_hint"])
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { if (!isLoading) url = it },
                        label = { Text(Strings["add_podcast_rss_label"]) },
                        placeholder = { Text(Strings["add_podcast_rss_placeholder"]) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isLoading,
                        isError = error != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor = colors.accent,
                            focusedLabelColor = colors.accent,
                            unfocusedLabelColor = colors.textSecondary,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            errorTextColor = colors.danger,
                            errorBorderColor = colors.danger,
                            errorLabelColor = colors.danger
                        )
                    )
                    error?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = it, color = colors.danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        actions = {
            PodaraDialogActionButton(
                label = Strings["dialog_cancel"],
                onClick = onDismiss,
                style = PodaraDialogActionStyle.Secondary,
                enabled = !isLoading
            )
            PodaraDialogActionButton(
                label = if (isLoading) Strings["add_podcast_adding"] else Strings["dialog_ok"],
                onClick = { if (url.isNotBlank()) onConfirm(url) },
                style = PodaraDialogActionStyle.Primary,
                enabled = url.isNotBlank() && !isLoading
            )
        }
    )
}

// ── DownloadsScreen is in DownloadsScreen.kt ──

internal fun stripHtml(html: String): String {
    return html.replace(Regex("<[^>]*>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

internal fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
