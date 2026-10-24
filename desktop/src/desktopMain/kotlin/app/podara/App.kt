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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import app.podara.component.PodaraScrollbar
import app.podara.component.PodaraTooltip
import androidx.compose.ui.graphics.painter.BitmapPainter
import app.podara.api.apple.ApplePodcastClient
import app.podara.api.model.PodcastPreviewModel
import app.podara.api.rss.FetchPodcastClient
import app.podara.api.rss.FetchPodcastClientResult
import app.podara.component.AddToQueueButton
import app.podara.component.ContinueListeningCard
import app.podara.component.PodaraArtwork
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
import app.podara.component.PodaraSearchField
import app.podara.data.AppDatabase
import app.podara.data.model.EpisodeProgress
import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import app.podara.platform.GlobalMediaKeys
import app.podara.platform.MediaKeyAction
import app.podara.platform.ShortcutAction
import app.podara.platform.shortcutFor
import app.podara.player.FullPlayer
import app.podara.player.MediaPlayerState
import app.podara.player.MiniPlayer
import app.podara.player.QueueDrawer
import app.podara.player.QueueItem
import app.podara.screen.DiscoverScreen
import app.podara.screen.DiscoverSearchSession
import app.podara.screen.DownloadsScreen
import app.podara.screen.FavoritesScreen
import app.podara.screen.HistoryScreen
import app.podara.screen.PodcastDetailActions
import app.podara.screen.PodcastDetailHeader
import app.podara.screen.PodcastDetailTopBar
import app.podara.screen.SettingsScreen
import app.podara.screen.rememberScrollOffProgress
import app.podara.manager.AddPodcastResult
import app.podara.manager.AlreadyDownloadingException
import app.podara.manager.DownloadManager
import app.podara.manager.PodcastManager
import app.podara.manager.ProgressThrottle
import app.podara.manager.SubscriptionManager
import app.podara.manager.UpdatePodcastResult
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.theme.ThemePreference
import app.podara.util.Logger
import app.podara.util.LocalKeyboardNavigation
import app.podara.util.LocalSetTextFieldFocus
import app.podara.util.LocalTextFieldFocus
import app.podara.util.RssConverter
import app.podara.util.Settings
import app.podara.util.DateFormat
import app.podara.util.Strings
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.artworkSeedFor
import app.podara.util.clickableWithoutIndication
import app.podara.util.clickableWithoutIndicationOrFocusRing
import app.podara.util.pressScale
import app.podara.util.stripHtml
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

// Hover on a sidebar row. Was `pillFillSelected`, an accent tint, which made
// hovering a nav item look like a second kind of selection; the sidebar now sits
// on the shell, where a hover has to lighten toward white rather than darken.
private val SidebarActiveBg: Color
    @Composable get() = PodaraTheme.surfaces.navHoverFill

// Declared at file level: defining the class and rebuilding the list inside the
// composable meant a new class identity and a new list on every recomposition.
private data class NavItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelKey: String, val screen: String)

private val navItems = listOf(
    NavItem(Icons.Default.Explore, "nav_discover", "discover"),
    NavItem(Icons.Default.LibraryMusic, "nav_subscriptions", "home"),
    NavItem(Icons.Default.Favorite, "nav_favorites", "favorites"),
    NavItem(Icons.Default.History, "nav_history", "history"),
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
    val surfaces = PodaraTheme.surfaces
    val sidebar = DesignTokens.Sidebar

    // No fill: the sidebar is the strip of shell with nothing laid over it, not
    // a panel beside the content.
    Surface(
        modifier = Modifier.width(sidebar.Width).fillMaxHeight(),
        color = surfaces.sidebarFill
    ) {
        Column(
            modifier = Modifier.fillMaxHeight().padding(top = sidebar.PaddingVertical, bottom = 0.dp)
        ) {
            Row(
                modifier = Modifier.padding(start = sidebar.PaddingHorizontal + 6.dp, end = sidebar.PaddingHorizontal, top = DesignTokens.Spacing.sm, bottom = DesignTokens.Spacing.sm),
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
                        .clip(RoundedCornerShape(DesignTokens.Radius.Small))
                )
                Text(
                    text = Strings["app_name"],
                    color = colors.textPrimary,
                    fontSize = sidebar.LogoTextSize,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(DesignTokens.Spacing.md))

            navItems.forEach { item ->
                val isActive = currentScreen == item.screen
                val interactionSource = remember(item.screen) { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                val animatedBg by animateHoverBackgroundColor(isActive || isHovered, SidebarActiveBg)
                val activeGlass = DesignTokens.Navigation.ActiveGlass
                val itemShape = RoundedCornerShape(activeGlass.Radius)
                val hoverShape = itemShape
                // One gray step below the nav label, so the glyph does not read
                // heavier than the word beside it.
                val iconTint = if (isActive) DesignTokens.IconInk.Active else DesignTokens.IconInk.Resting

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DesignTokens.Spacing.xs)
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
                                } else {
                                    mod.background(animatedBg, hoverShape)
                                }
                            }
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

            Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))

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
                        Icon(Icons.Default.Refresh, contentDescription = Strings["refresh_all"], tint = DesignTokens.IconInk.Resting, modifier = Modifier.size(sidebar.NavIconSize))
                    }
                    Text(
                        text = when {
                            isRefreshingAll -> Strings["refresh_all_running"]
                            refreshAllFeedback != null -> refreshAllFeedback
                            else -> Strings["refresh_all"]
                        },
                        color = if (isRefreshingAll) colors.textPrimary else colors.textSecondary,
                        fontSize = if (refreshAllFeedback != null) DesignTokens.TypeScale.Caption else sidebar.NavTextSize,
                        maxLines = if (refreshAllFeedback != null) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))

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
                        } else {
                            mod.background(settingsAnimatedBg, settingsShape)
                        }
                    }
                    .clickableWithoutIndicationOrFocusRing(interactionSource = settingsInteractionSource) { onSettings() }
                    .padding(horizontal = settingsActiveGlass.InnerPaddingHorizontal),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Settings, contentDescription = Strings["nav_settings"], tint = if (settingsActive) DesignTokens.IconInk.Active else DesignTokens.IconInk.Resting, modifier = Modifier.size(sidebar.NavIconSize))
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
    contentDescription: String,
    icon: @Composable (tint: Color, contentDescription: String) -> Unit,
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

    // Wrapped here rather than at the three call sites: all three glyphs are the
    // same shape of control, so the helper gives them all a tooltip at once and
    // none of them can end up with two. `contentDescription` is now a parameter
    // and is handed to the icon slot, so the accessible name and the tooltip text
    // are one value instead of two literals that can drift.
    PodaraTooltip(text = contentDescription) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .pressScale(interactionSource)
                .background(animatedBg)
                .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            icon(iconTint, contentDescription)
        }
    }
}

/**
 * Which screen [App] is showing, as a single value.
 *
 * `AnimatedContent` needs one comparable target to key its content slots on, and
 * it hands that value back to the content lambda rather than letting the lambda
 * keep reading the live state — the outgoing copy composes for as long as it
 * takes to fade, and by then `selectedPodcast` has already moved on.
 */
private data class ScreenTarget(val screen: String, val podcast: Podcast?)

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
    // Created once for the process lifetime, then retargeted via reconfigure().
    // Keying this remember on the settings rebuilt the manager whenever the user
    // changed the download folder or speed limit, and since the pause/cancel
    // flags live on the instance, every transfer already running became
    // unstoppable — the UI's pause went to the new object while the old one
    // kept writing.
    val downloadManager = remember {
        val downloadsDir = File(downloadPath)
        downloadsDir.mkdirs()
        DownloadManager(database, downloadsDir, downloadSpeedLimitKbps)
    }
    SideEffect {
        downloadManager.reconfigure(File(downloadPath), downloadSpeedLimitKbps)
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
            // Hide first, then write. `runBlocking` here cannot be avoided (the
            // process exits on the next line, so nothing may outlive it), and a
            // queue of a few hundred entries makes it take long enough for
            // Windows to paint "Not Responding" over the window. Concealing the
            // window means the user sees an immediate response instead.
            awtWindow.isVisible = false
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

    // ── Per-screen search queries ──
    //
    // Filter text for the three screens that filter a list they already have in
    // memory. It lives here for the same reason the list states do: the screen
    // is disposed when a podcast detail opens, and a filter the user typed was
    // thrown away, so returning showed the unfiltered list at a scroll position
    // that only made sense while filtered.
    //
    // Discover is not in this map. Its search fetches from the network, so it
    // needs the results and the "a search has run" flag kept alongside the text;
    // `discoverSearchSession` holds all three.
    //
    // Snapshot-backed, not a plain `mutableMapOf`. The field is controlled: its
    // displayed text is whatever this map says, so a write that does not
    // invalidate composition means the box never shows what was typed.
    val searchQueries = remember { mutableStateMapOf<String, String>() }
    fun searchQueryFor(screen: String): String = searchQueries[screen].orEmpty()
    fun setSearchQuery(screen: String, query: String) {
        searchQueries[screen] = query
    }

    // Kept for the process, like the list states: a trip into a podcast detail
    // must not cost the user their search, and must not make the app re-fetch
    // results it already had.
    val discoverSearchSession = remember { DiscoverSearchSession() }

    /**
     * Forget the filters, for a move that leaves the screen behind.
     *
     * The sidebar is a set of destinations, not a trail, so arriving at one of
     * them should look like arriving at it fresh. Returning to a screen through
     * "back" keeps its filters, because that is going back to where you were.
     */
    fun clearSearchQueries() {
        searchQueries.clear()
        discoverSearchSession.clear()
    }

    // ── Navigation history ──
    //
    // One entry per screen the user has been on, current one last. "Back" pops
    // it rather than hardcoding a destination, so leaving a podcast detail
    // returns to whichever list opened it instead of always landing on Home.
    //
    // Screen names only. The stack deliberately does not carry each list's
    // scroll position: that is the list state's own job (see `listStates`), and
    // copying it into shell-owned observable state meant recomposing the shell
    // on every scroll step.
    //
    // The stack is not saved across restarts and does not need to be: a fresh
    // window has nothing to go back to.
    var backStack by remember { mutableStateOf(listOf("discover")) }

    // ── Per-screen list scroll state ──
    //
    // Hoisted above the `AnimatedContent` that swaps the screens, because that
    // swap disposes the whole subtree: a `rememberLazyListState()` declared
    // inside a screen dies with it, so returning from a podcast detail put the
    // user back at the top of the list they had scrolled down.
    //
    // The state object is the whole fix. A `LazyListState` keeps its cursor when
    // it is unmounted and re-attached, so nothing has to put the position back —
    // and nothing should try, because recording it into shell state to restore
    // it later is what recomposed the shell on every scroll step.
    //
    // Keyed by screen name so each screen keeps its own cursor, and created
    // lazily so a screen never visited costs nothing.
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    fun listStateFor(screen: String): LazyListState =
        listStates.getOrPut(screen) { LazyListState() }

    /**
     * Move to [screen], remembering where we came from.
     *
     * Re-selecting the screen already on top is a no-op: pushing the same name
     * again would grow the stack with a duplicate that sends the next "back" to
     * a screen the user is still standing on.
     */
    fun navigateTo(screen: String) {
        if (backStack.last() == screen) return
        backStack = backStack + screen
    }

    /**
     * Pop one entry, revealing the screen under it. Returns false when there is
     * nowhere to go, which lets a caller decide what "back at the root" means —
     * the sidebar switch is a deliberate move, not a pop, so it resets the stack.
     */
    fun navigateBack(): Boolean {
        if (backStack.size <= 1) return false
        backStack = backStack.dropLast(1)
        return true
    }

    /**
     * Switch screens from the sidebar, discarding the history.
     *
     * The sidebar is a set of destinations, not a trail: tapping "Downloads"
     * while on a podcast detail should leave the detail, not leave a "back"
     * arrow that walks the user through wherever they happened to be before.
     *
     * The lists' own scroll positions survive the reset — they live in
     * `listStates`, keyed by screen name and independent of the stack — so
     * arriving at a list through the sidebar still shows where the user was.
     * Only the "back" chain is dropped.
     */
    fun switchScreen(screen: String) {
        selectedPodcast = null
        showFullPlayer = false
        // A destination, not a place to return to: arriving at a screen through
        // the sidebar should look like arriving at it fresh, so its old filter
        // goes. "Back" keeps filters, because that is returning to where the
        // user was.
        clearSearchQueries()
        backStack = listOf(screen)
    }

    /**
     * Leave the podcast detail, whichever way the user asked.
     *
     * The back button and the Escape key both land here, because they are the
     * same gesture and used to do different amounts of work: the button bumped
     * `discoverRefreshKey` so a subscription taken on the detail page showed up
     * in the list coming back, and Escape did not, so the same escape left a
     * stale list. One function, so the two cannot drift apart again.
     */
    fun closePodcastDetail() {
        selectedPodcast = null
        // Discover reloads its charts when this key moves, so a subscription
        // taken from the detail page is reflected in the list coming back.
        discoverRefreshKey++
    }

    val handleCloseRequest = {
        val action = Settings.getCloseAction()
        if (Settings.isCloseActionRemembered()) {
            when (action) {
                "quit" -> {
                    // Same ordering as the tray quit path: conceal the window
                    // before the synchronous session write so closing never looks
                    // like a hang on a large queue.
                    awtWindow.isVisible = false
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
            // Progress callbacks arrive once per 8 KiB read — roughly 12,800
            // times for a 100 MB episode, each one copying the whole map and
            // recomposing every screen that reads it. Coalesce them to ~4 Hz;
            // the bar cannot show more than that anyway.
            val progressThrottle = ProgressThrottle()
            val job = scope.launch {
                try {
                    val result = downloadManager.downloadEpisode(
                        episodeId = episode.id,
                        audioUrl = episode.audioUrl,
                        origin = episode.origin,
                        episodeTitle = episode.title,
                        podcastTitle = podcastTitle,
                        onProgress = { current, total ->
                            if (progressThrottle.shouldEmit()) {
                                downloadProgress = downloadProgress + (episode.id to Pair(current, total))
                            }
                        }
                    )
                    if (result.isSuccess) {
                        completedDownloads = completedDownloads + episode.id
                    } else if (result.exceptionOrNull() is AlreadyDownloadingException) {
                        // Another call already owns this episode; leave its
                        // bookkeeping alone instead of tearing it down here.
                        return@launch
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
    val currentNotifyNewEpisodes by rememberUpdatedState(
        { podcastTitle: String, newEpisodes: List<PodcastEpisode> ->
            // One new episode is worth naming; several are only worth counting.
            val headline = if (newEpisodes.size == 1) {
                newEpisodes.first().title
            } else {
                Strings.get("notification_new_episodes", newEpisodes.size)
            }
            trayManager.showNotification(podcastTitle, headline)
            Logger.i(TAG, "Notified: $podcastTitle — ${newEpisodes.size} new episode(s)")
        }
    )
    val subscriptionManager = remember {
        SubscriptionManager(
            db = database,
            fetchPodcastClient = fetchPodcastClient,
            autoDownloadStarter = { newEpisodes ->
                newEpisodes.forEach { currentStartDownload(it, it.podcastTitle) }
            },
            newEpisodeNotifier = { title, episodes ->
                currentNotifyNewEpisodes(title, episodes)
            }
        )
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
                // Same coalescing as startDownload: a resumed episode is usually
                // a large one, so this path sees at least as many 8 KiB callbacks
                // as a fresh download, and each one used to copy the whole map.
                val progressThrottle = ProgressThrottle()
                try {
                    val result = downloadManager.resumeDownload(episodeId) { current, total ->
                        if (progressThrottle.shouldEmit()) {
                            downloadProgress = downloadProgress + (episodeId to Pair(current, total))
                        }
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
            // Drop it from the in-memory set only when the file is really gone.
            // On Windows a file being played cannot be deleted, and the old code
            // cleared the flag anyway — so the UI reported "deleted" while the
            // downloads page still listed it, and the stale file became
            // unreachable because the list only renders database rows.
            if (downloadManager.deleteDownloadedEpisode(episodeId)) {
                completedDownloads = completedDownloads - episodeId
            }
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
                    // Stable per-show colour for the generated placeholder art.
                    // 0 is the "no seed recorded" sentinel, which made every
                    // podcast added from Discover look identical.
                    imageSeedColor = artworkSeedFor(feedUrl),
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

        // Whether the last input was the keyboard. Drives the focus ring: every
        // control is focusable again (they used to set canFocus = false, which
        // made Tab reach nothing at all), but painting a ring after a mouse click
        // reads as a glitch, so the indicator only appears once Tab has been
        // pressed. See LocalKeyboardNavigation.
        var keyboardNavigation by remember { mutableStateOf(false) }

        // Keyboard control for the player.
        //
        // `onPreviewKeyEvent` on the root means the handler sees a key before a
        // focused child does, which is what makes Escape close the player and
        // Space toggle playback from anywhere — but it also means text fields
        // must be excluded, or typing a space into the search box would pause
        // the episode instead. [isTypingInTextField] is published to the tree as
        // a composition local; every text field reports its focus into it.
        var isTypingInTextField by remember { mutableStateOf(false) }
        val shortcutHandler: (KeyEvent) -> Boolean = { event ->
            // Tab switches the app into keyboard mode before the shortcut table
            // is consulted, because Tab itself is not a player shortcut.
            if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                keyboardNavigation = true
            }
            if (event.type == KeyEventType.KeyDown && !isTypingInTextField) {
                when (shortcutFor(event.key)) {
                    ShortcutAction.PLAY_PAUSE -> { playerState.togglePlayPause(); true }
                    ShortcutAction.NEXT -> { playerState.playNext(); true }
                    ShortcutAction.PREVIOUS -> { playerState.playPrevious(); true }
                    ShortcutAction.SEEK_FORWARD -> { playerState.seekForward(); true }
                    ShortcutAction.SEEK_BACK -> { playerState.seekBack(); true }
                    ShortcutAction.VOLUME_UP -> {
                        playerState.changeVolume((playerState.volume + 5).coerceAtMost(100)); true
                    }
                    ShortcutAction.VOLUME_DOWN -> {
                        playerState.changeVolume((playerState.volume - 5).coerceAtLeast(0)); true
                    }
                    ShortcutAction.TOGGLE_MUTE -> { playerState.toggleMute(); true }
                    ShortcutAction.TOGGLE_FULL_PLAYER -> {
                        // Only meaningful when something is loaded; otherwise the
                        // full player would open onto an empty screen.
                        if (playerState.currentUrl != null) {
                            showFullPlayer = !showFullPlayer; true
                        } else {
                            false
                        }
                    }
                    ShortcutAction.TOGGLE_QUEUE -> {
                        showQueueFromMini = !showQueueFromMini; true
                    }
                    ShortcutAction.SPEED_UP -> { playerState.stepPlaybackSpeed(up = true); true }
                    ShortcutAction.SPEED_DOWN -> { playerState.stepPlaybackSpeed(up = false); true }
                    ShortcutAction.CLOSE_OVERLAY -> {
                        // Escape backs out of the most modal thing first, and
                        // reports "not handled" when there is nothing to close
                        // so it does not swallow the key.
                        if (showQueueFromMini) {
                            showQueueFromMini = false; true
                        } else if (showFullPlayer) {
                            showFullPlayer = false; true
                        } else if (showAddDialog) {
                            showAddDialog = false; true
                        } else if (selectedPodcast != null) {
                            closePodcastDetail(); true
                        } else {
                            false
                        }
                    }
                    null -> false
                }
            } else {
                false
            }
        }

        CompositionLocalProvider(
            LocalTextFieldFocus provides isTypingInTextField,
            LocalSetTextFieldFocus provides { isTypingInTextField = it },
            LocalKeyboardNavigation provides keyboardNavigation
        ) {
            Column(
                modifier = Modifier
                .fillMaxSize()
                // Paint the window itself. Without this, anything that unmounts
                // (see the FullPlayer overlay) reveals the bare window background,
                // which reads as a white flash on this undecorated window.
                //
                // The shell, not a flat page: the wash starts under the title bar
                // so the top of the window is the only tinted part of it, and the
                // sheet and the mini player both float on top of this rather than
                // beside it.
                .background(surfaces.shellGradient)
                // The queue drawer's scrim covers this whole column, so while it
                // is open none of this content is reachable by a screen reader
                // either. Marked here on the covered layer rather than on the
                // scrim: putting it on the scrim would only hide the scrim, which
                // has nothing to read anyway. FullPlayer does the same thing via
                // PlayerOverlaidArea.
                .semantics {
                    if (showQueueFromMini) invisibleToUser()
                }
                .onPreviewKeyEvent(shortcutHandler)
                // Any pointer press hands control back to the mouse, so the next
                // focus change came from a click and must not paint a ring.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            if (keyboardNavigation) keyboardNavigation = false
                        }
                    }
                }
        ) {
            // ── Custom Title Bar ──
            WindowDraggableArea {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
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
                    Spacer(modifier = Modifier.width(DesignTokens.Spacing.sm))
                    Text(
                        text = Strings["app_name"],
                        color = titleBarColors.textMuted,
                        fontSize = DesignTokens.TypeScale.Caption
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Minimize
                    WindowControlButton(
                        onClick = { windowState.isMinimized = true },
                        contentDescription = Strings["titlebar_minimize"],
                        icon = { tint, desc -> Icon(Icons.Default.Remove, contentDescription = desc, tint = tint, modifier = Modifier.size(14.dp)) }
                    )
                    // Maximize
                    WindowControlButton(
                        onClick = {
                            windowState.placement = if (windowState.placement == WindowPlacement.Maximized)
                                WindowPlacement.Floating else WindowPlacement.Maximized
                        },
                        contentDescription = Strings["titlebar_maximize"],
                        icon = { tint, desc ->
                            Icon(
                                if (windowState.placement == WindowPlacement.Maximized) Icons.Default.FilterNone else Icons.Default.CropSquare,
                                contentDescription = desc,
                                tint = tint,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    // Close
                    WindowControlButton(
                        onClick = { handleCloseRequest() },
                        contentDescription = Strings["titlebar_close"],
                        icon = { tint, desc -> Icon(Icons.Default.Close, contentDescription = desc, tint = tint, modifier = Modifier.size(14.dp)) },
                        isClose = true
                    )
                }
            }

            // No fill: this is the shell's own area, so the window gradient above
            // is what shows in the gutter around the content sheet.
            Box(modifier = Modifier.weight(1f)) {
                PlayerOverlaidArea(
                    playerVisible = showFullPlayer,
                    sidebar = {
                    Sidebar(
                        currentScreen = backStack.last(),
                        isRefreshingAll = isRefreshingAll,
                        refreshAllFeedback = refreshAllFeedback,
                        onRefreshAll = refreshAllPodcasts,
                        onDiscover = { switchScreen("discover") },
                        onShows = { switchScreen("home") },
                        onFavorites = { switchScreen("favorites") },
                        onHistory = { switchScreen("history") },
                        onSettings = { switchScreen("settings") },
                        onDownloads = { switchScreen("downloads") }
                    )
                    },
                    content = {
                    val animation = DesignTokens.Animation
                    val screenRisePx = with(LocalDensity.current) { animation.ScreenEnterRise.roundToPx() }
                    val pushFarPx = with(LocalDensity.current) { animation.ScreenPushFar.roundToPx() }
                    val pushNearPx = with(LocalDensity.current) { animation.ScreenPushNear.roundToPx() }
                    AnimatedContent(
                        targetState = ScreenTarget(backStack.last(), selectedPodcast),
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            // Both ends are needed to know the direction: entering
                            // a podcast and leaving one are the same pair of
                            // states seen from opposite sides.
                            val toDetail = targetState.podcast != null
                            val fromDetail = initialState.podcast != null
                            val motion = when {
                                toDetail && !fromDetail -> ScreenMotion.Forward
                                fromDetail && !toDetail -> ScreenMotion.Backward
                                else -> ScreenMotion.Sibling
                            }
                            screenTransitionSpec(motion, screenRisePx, pushFarPx, pushNearPx)
                        }
                    ) { target ->
                    // Read off the target, never off `selectedPodcast`: the
                    // outgoing copy keeps composing while it fades, and by then
                    // the podcast it was built for is already null.
                    val podcast = target.podcast
                    when {
                        podcast != null -> PodcastDetailScreen(
                        podcast = podcast,
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
                            // The detail is not its own entry on the stack: it is
                            // the list screen the user was on with a podcast
                            // layered over it. So leaving it only clears the
                            // overlay — the list underneath comes back exactly
                            // as it was, at the position it was left at.
                            //
                            // The old handler also set the screen to Home, which
                            // is why a search result felt like it vanished: the
                            // user was returned to a different screen entirely.
                            closePodcastDetail()
                        },
                        onUnsubscribed = {
                            podcasts = database.podcasts.getAllSync()
                        },
                        onSubscribed = {
                            podcasts = database.podcasts.getAllSync()
                            discoverRefreshKey++
                        }
                    )
                    target.screen == "home" -> HomeScreen(
                        podcasts = podcasts,
                        database = database,
                        subscriptionManager = subscriptionManager,
                        scope = scope,
                        refreshVersion = subscriptionRefreshVersion,
                        listState = listStateFor("home"),
                        searchQuery = searchQueryFor("home"),
                        onSearchQueryChange = { setSearchQuery("home", it) },
                        onPodcastClick = { podcast ->
                            selectedPodcast = podcast
                            // Opening a show clears its "new episodes" badge: the
                            // badge means "arrived since you last looked", so
                            // looking is the acknowledgement. Bumping the refresh
                            // version reloads the subscription map behind the
                            // sidebar so the badge disappears immediately rather
                            // than on the next startup refresh.
                            scope.launch {
                                database.subscriptions.clearNewEpisodeCount(podcast.origin)
                                subscriptionRefreshVersion++
                            }
                        },
                        onAddPodcast = { showAddDialog = true },
                        onDiscover = { navigateTo("discover") },
                        onHistory = { navigateTo("history") },
                        onSettings = { navigateTo("settings") },
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
                                        episodeId = ep.id,
                                        // The target's `url` is a local download
                                        // path; remember the stream it came from
                                        // so a later deleted download can fall
                                        // back instead of failing with a FILE
                                        // error.
                                        fallbackUrl = if (ep.id == epWithUrl.id) ep.audioUrl else null
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
                                    episodeId = epWithUrl.id,
                                    fallbackUrl = episode.audioUrl
                                )
                                if (positionMs > 0L) playerState.seek(positionMs)
                                // Insert the unmodified episode: it is here only to
                                // make sure the row exists for history.insert. The
                                // copy carries the resolved *local* download path,
                                // and persisting that would replace the stream URL
                                // every other reader relies on (and leave no
                                // fallback once the download is deleted).
                                database.episodes.insert(episode)
                                database.history.insert(epWithUrl.origin, epWithUrl.id)
                            }
                        }
                    )
                    target.screen == "discover" -> DiscoverScreen(
                        database = database,
                        subscriptionManager = subscriptionManager,
                        podcastManager = podcastManager,
                        appleClient = appleClient,
                        discoverRefreshKey = discoverRefreshKey,
                        listState = listStateFor("discover"),
                        searchSession = discoverSearchSession,
                        onSubscribed = {
                            scope.launch { podcasts = database.podcasts.getAllSync() }
                        },
                        onBack = {
                            // Same pop as the detail screen: these pages are
                            // reached from Home or from the sidebar, and "back"
                            // means whichever of those the user came from —
                            // not Home by default.
                            if (!navigateBack()) {
                                // At the root there is nothing behind it. Fall
                                // back to the subscriptions list rather than
                                // leaving the user on a dead page.
                                switchScreen("home")
                            }
                            scope.launch { podcasts = database.podcasts.getAllSync() }
                        },
                        onPlayLatestEpisode = onPlayLatestEpisode,
                        onShowDetail = onShowDetail
                    )
                    target.screen == "settings" -> SettingsScreen(
                        database = database,
                        onBack = { if (!navigateBack()) switchScreen("home") },
                        onDownloadPathChanged = { newPath -> downloadPath = newPath },
                        themePreference = themePreference,
                        onThemeChanged = { preference ->
                            themePreference = preference
                            Settings.setTheme(preference.settingValue)
                        },
                        downloadSpeedLimitKbps = downloadSpeedLimitKbps,
                        onDownloadSpeedLimitChanged = { limit -> downloadSpeedLimitKbps = limit },
                        onPodcastsImported = {
                            // Imported shows are new rows the shell has never
                            // read. Reload them here, and nudge the counters so
                            // Home's episode counts and Discover's subscribed
                            // markers pick the import up too.
                            scope.launch {
                                podcasts = database.podcasts.getAllSync()
                                subscriptionRefreshVersion++
                                discoverRefreshKey++
                            }
                        }
                    )
                    target.screen == "history" -> HistoryScreen(
                        database = database,
                        playerState = playerState,
                        favoriteVersion = favoritesVersion,
                        onBack = { if (!navigateBack()) switchScreen("home") },
                        onFavoriteChanged = { favoritesVersion++ },
                        onShowPodcastDetail = { podcast -> selectedPodcast = podcast },
                        listState = listStateFor("history"),
                        searchQuery = searchQueryFor("history"),
                        onSearchQueryChange = { setSearchQuery("history", it) }
                    )
                    target.screen == "favorites" -> FavoritesScreen(
                        database = database,
                        playerState = playerState,
                        favoriteVersion = favoritesVersion,
                        onBack = { if (!navigateBack()) switchScreen("home") },
                        onFavoriteChanged = { favoritesVersion++ },
                        onShowPodcastDetail = { podcast -> selectedPodcast = podcast },
                        listState = listStateFor("favorites"),
                        searchQuery = searchQueryFor("favorites"),
                        onSearchQueryChange = { setSearchQuery("favorites", it) }
                    )
                    target.screen == "downloads" -> DownloadsScreen(
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
                        onBack = { if (!navigateBack()) switchScreen("home") },
                        onOpenSettings = { navigateTo("settings") },
                        listState = listStateFor("downloads"),
                    )   // DownloadsScreen
                    }   // when
                    }   // AnimatedContent
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
        //
        // The offset is the panel's own width, not `it`: the drawer's root is a
        // fillMaxSize Box with a [DesignTokens.QueuePanel.Width] Surface docked
        // to its trailing edge, so `it` is the whole content area and the old
        // spec spent most of its duration travelling across empty space to the
        // left of where the panel actually is. Same distance in, same distance
        // out, so the edge that moves is the one the user is watching.
        val queueSlidePx = with(LocalDensity.current) { DesignTokens.QueuePanel.Width.roundToPx() }
        AnimatedVisibility(
            visible = showQueueFromMini,
            enter = slideInHorizontally(animationSpec = DesignTokens.Animation.DrawerSpring) { queueSlidePx },
            exit = slideOutHorizontally(animationSpec = tween(DesignTokens.Animation.DrawerExitMs, easing = DesignTokens.Animation.ExitEasing)) { queueSlidePx }
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
                            Spacer(Modifier.width(DesignTokens.Spacing.sm))
                            Text(label, fontSize = DesignTokens.TypeScale.Body, color = dialogColors.textPrimary)
                        }
                    }
                    Spacer(Modifier.height(DesignTokens.Spacing.sm))
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
                        Spacer(Modifier.width(DesignTokens.Spacing.xs))
                        Text(Strings["close_remember"], fontSize = DesignTokens.TypeScale.Body, color = dialogColors.textSecondary)
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
                            awtWindow.isVisible = false
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
    listState: LazyListState = rememberLazyListState(),
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
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
    // The filter is owned here unless the shell hoists it, which it does so a
    // trip into a podcast detail does not silently clear it. Clearing the text
    // while keeping the list's scroll position would leave the user at an
    // arbitrary row of a longer list.
    var localSearchQuery by remember { mutableStateOf("") }
    val searchQueryText = searchQuery ?: localSearchQuery
    val onSearchQueryTextChange: (String) -> Unit =
        onSearchQueryChange ?: { localSearchQuery = it }
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

    // "Continue listening" snapshot. Reads the per-episode progress table
    // rather than playerSession: the session row only describes whatever the
    // player currently holds, so switching episodes used to wipe the card's
    // memory of what was actually in progress. Hidden when nothing is
    // unfinished (never started, already completed, or the episode has left
    // its feed).
    var resumeEpisode by remember { mutableStateOf<PodcastEpisode?>(null) }
    var resumePodcast by remember { mutableStateOf<Podcast?>(null) }
    var resumePositionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(podcasts, refreshVersion) {
        val progress = database.progress.getMostRecentResumable()
        val episode = progress?.episodeId?.let { database.episodes.getById(it) }
        resumeEpisode = episode
        resumePodcast = episode?.let { database.podcasts.getByOrigin(it.origin) }
        // Fall back to the session position for installs that predate the
        // progress table and have not written a row yet.
        resumePositionMs = progress?.positionMs
            ?: database.playerSession.loadSession()?.currentPositionMs
            ?: 0L
        if (progress != null && episode == null) {
            // The episode is gone from the feed; the row is unreachable.
            database.progress.delete(progress.episodeId)
        }
    }

    val filteredPodcasts = remember(podcasts, searchQueryText) {
        if (searchQueryText.isBlank()) podcasts
        else podcasts.filter { it.title.contains(searchQueryText, ignoreCase = true) || it.author.contains(searchQueryText, ignoreCase = true) }
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
                        fontWeight = DesignTokens.TypeFamily.PageTitleWeight,
                        fontFamily = DesignTokens.TypeFamily.PageTitle,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(header.Gap))
                    Text(Strings["home_subscriptions_desc"], fontSize = header.SubtitleSize, color = colors.textMuted)
                }

                // Right: Search bar
                PodaraSearchField(
                    value = searchQueryText,
                    onValueChange = { onSearchQueryTextChange(it) },
                    placeholder = Strings["home_search_placeholder"]
                )
            }

        Spacer(modifier = Modifier.height(DesignTokens.Spacing.md))

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
                        style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            } else {
                Text(
                    text = Strings.get("home_subscription_count", podcasts.size),
                    color = colors.textPrimary,
                    fontSize = DesignTokens.TypeScale.Body,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(toolbarButton.Gap)) {
                if (isEditing) {
                    ToolbarPillButton(
                        icon = Icons.Default.SelectAll,
                        label = Strings["home_select_all"],
                        contentDescription = Strings["home_select_all"],
                        onClick = {
                            // Compare against what the user can actually see: the
                            // selection can hold podcasts that the current search
                            // filter hides, and counting those made "select all"
                            // toggle off while visible rows were still unselected.
                            val visibleOrigins = filteredPodcasts.map { it.origin }.toSet()
                            selectedPodcasts = if (visibleOrigins.isNotEmpty() &&
                                visibleOrigins.all { it in selectedPodcasts }
                            ) {
                                emptySet()
                            } else {
                                visibleOrigins
                            }
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
                                .pressScale(sortInteractionSource)
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
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
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
                    // Keyed by origin: the list is re-sorted and refiltered in place,
                    // and without a key Compose reuses item slots by position, so
                    // selection and hover state jumped to whichever show took the
                    // old row's index.
                    items(sortedPodcasts, key = { it.origin }, contentType = { "subscription_card" }) { podcast ->
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
                PodaraScrollbar(adapter = rememberScrollbarAdapter(listState))
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
        // Only the podcasts the user can still see are affected. The selection is
        // a persistent set, so a search filter applied after selecting left hidden
        // origins in it, and the old code unsubscribed those too: the dialog said
        // "unsubscribe 3" while 5 were removed, with no way to undo.
        val visibleOrigins = filteredPodcasts.map { it.origin }.toSet()
        val toUnsubscribe = selectedPodcasts.filter { it in visibleOrigins }
        PodaraConfirmDialog(
            title = Strings["batch_unsubscribe"],
            description = AnnotatedString(Strings.get("batch_unsubscribe_confirm", toUnsubscribe.size)),
            confirmLabel = Strings["unsubscribe"],
            dismissLabel = Strings["dialog_cancel"],
            onConfirm = {
                scope.launch {
                    toUnsubscribe.forEach { subscriptionManager.unsubscribe(it) }
                    onPodcastsChanged(database.podcasts.getAllSync())
                    selectedPodcasts = selectedPodcasts - toUnsubscribe.toSet()
                    isEditing = false; showBatchUnsubscribeDialog = false
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
        tween(DesignTokens.Animation.HoverMs, easing = DesignTokens.Animation.StandardEasing)
    )
    val borderColor by animateColorAsState(
        when {
            isSelected -> card.PlayingBorderColor
            isHovered && !isEditing -> card.HoverBorderColor
            else -> card.BorderColor
        },
        tween(DesignTokens.Animation.HoverMs, easing = DesignTokens.Animation.StandardEasing)
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
        PodaraArtwork(
            url = podcast.imageUrl,
            contentDescription = podcast.title,
            seedColor = podcast.imageSeedColor,
            modifier = Modifier
                .size(card.CoverSize)
                .shadow(
                    card.CoverShadowElevation,
                    RoundedCornerShape(card.CoverRadius),
                    ambientColor = card.CoverShadowColor,
                    spotColor = card.CoverShadowColor
                ),
            shape = RoundedCornerShape(card.CoverRadius)
        )

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
                Text(text = Strings.get("home_episode_count", episodeCount), color = card.MetadataColor, fontSize = card.MetadataSize, style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures))
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
                        color = badge.AccentTextColor, fontSize = badge.TextSize, fontWeight = FontWeight.SemiBold,
                        style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures)
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
    var notificationsEnabled by remember { mutableStateOf(false) }
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

    // Same optimistic pattern: the subscription row is the source of truth and
    // is re-read on the next page load.
    val toggleNotifications: () -> Unit = {
        if (isSubscribed) {
            val newValue = !notificationsEnabled
            notificationsEnabled = newValue
            scope.launch {
                database.subscriptions.setNotificationsEnabled(podcast.origin, newValue)
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

    // Per-episode resume points, so each row can show its own progress bar and
    // a finished episode is visibly done. Keyed on the episode list and the
    // download/favorite counters, which is when the underlying rows change.
    var episodeProgress by remember { mutableStateOf(mapOf<String, EpisodeProgress>()) }
    LaunchedEffect(episodes, downloadVersion) {
        episodeProgress = if (episodes.isEmpty()) emptyMap()
        else database.progress.getByIds(episodes.map { it.id })
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
        notificationsEnabled = subscription?.enableNotifications ?: false

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

    // Reloading has to match how the screen originally loaded. It used to
    // always call subscriptionManager.updatePodcast, which returns NotSubscribed
    // on its first line for a show the user has not subscribed to — so the
    // retry button on a preview-mode podcast silently did nothing.
    val retryLoadEpisodes: () -> Unit = {
        scope.launch {
            isLoading = true
            try {
                if (isSubscribed) {
                    val result = subscriptionManager.updatePodcast(podcast.origin, podcast.imageSeedColor)
                    if (result is UpdatePodcastResult.Updated) {
                        episodes = database.episodes.getAllByOrigin(podcast.origin)
                    }
                } else {
                    val result = fetchPodcastClient.fetchNoCache(podcast.origin)
                    if (result is FetchPodcastClientResult.Success) {
                        val (_, parsedEpisodes) = RssConverter.parseFetchResult(result, podcast.origin)
                        episodes = parsedEpisodes
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Retrying episode load failed", e)
            }
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
                notificationsEnabled = notificationsEnabled,
                onToggleAutoDownload = toggleAutoDownload,
                onToggleNotifications = toggleNotifications,
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
                        Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
                        TextButton(onClick = { retryLoadEpisodes() }) {
                            Text(Strings["podcast_retry"])
                        }
                    }
                }
            }
            else -> {
                // ── Episode list. The podcast header is its first item, so it
                // scrolls away with the content instead of pinning above it;
                // the top bar crossfades in to take over its context. ──
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
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
                                notificationsEnabled = notificationsEnabled,
                                onToggleAutoDownload = toggleAutoDownload,
                                onToggleNotifications = toggleNotifications,
                                onPlayLatest = playLatest,
                                onToggleSubscribe = toggleSubscribe
                            )
                        }
                        // Keyed by episode id: refreshing a feed reorders and can
                        // insert episodes mid-list, and a positional key made row
                        // state (download progress, favourite) follow the slot
                        // rather than the episode.
                        items(episodes, key = { it.id }, contentType = { "episode_row" }) { episode ->
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
                                progress = episodeProgress[episode.id],
                                modifier = Modifier.padding(horizontal = DesignTokens.Spacing.xl),
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
                                                if (item.episodeId == epWithUrl.id) {
                                                    // Same story as the resume path:
                                                    // the resolved url may be a local
                                                    // download, so keep the stream for
                                                    // the "download deleted" case.
                                                    item.copy(url = url, fallbackUrl = episode.audioUrl)
                                                } else {
                                                    item
                                                }
                                            },
                                            targetUrl = url,
                                            title = epWithUrl.title,
                                            subtitle = epWithUrl.podcastTitle,
                                            artworkUrl = epWithUrl.imageUrl,
                                            podcastArtworkUrl = podcast.imageUrl,
                                            durationMs = epWithUrl.duration * 1000L,
                                            episodeId = epWithUrl.id,
                                            fallbackUrl = episode.audioUrl
                                        )
                                        // Same reason as the resume path above:
                                        // never persist `epWithUrl`, whose
                                        // audioUrl is the local download path.
                                        database.episodes.insert(episode)
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
                                            isDownloaded = episode.id in completedDownloads,
                                            // `url` is the local file when the
                                            // episode is downloaded; keep the
                                            // stream as the fallback.
                                            fallbackUrl = episode.audioUrl
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
                                        // A progress ring is the one state in this slot that
                                        // draws no glyph, so nothing about it says it is a
                                        // button. It is: the click pauses a live transfer and
                                        // resumes a stopped one, which is the opposite of what
                                        // the ring looks like it is doing.
                                        val ringLabel =
                                            if (isDbTask) Strings["downloads_resume"] else Strings["downloads_pause"]
                                        PodaraTooltip(text = ringLabel) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(ringAnimatedBg)
                                                    .clickableWithoutIndicationOrFocusRing(
                                                        interactionSource = ringInteractionSource,
                                                        onClickLabel = ringLabel
                                                    ) {
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
                                        }
                                    } else {
                                        val dInteractionSource = remember { MutableInteractionSource() }
                                        val isDHovered by dInteractionSource.collectIsHoveredAsState()
                                        val dAnimatedBg by animateHoverBackgroundColor(isDHovered, colors.elevated)
                                        // The row's only label is the arrow glyph, and the same
                                        // slot holds a check or a progress ring in the other
                                        // states, so the tooltip text is the string this branch's
                                        // contentDescription already uses.
                                        PodaraTooltip(text = Strings["episode_download"]) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(dAnimatedBg)
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
                    PodaraScrollbar(adapter = rememberScrollbarAdapter(listState))
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
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = DesignTokens.Spacing.xl),
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
                            fontSize = DesignTokens.TypeScale.Body
                        )
                    }
                }
            } else {
                Column {
                    PodaraDialogBody(Strings["add_podcast_hint"])
                    Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
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
                        Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
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

/**
 * Formats an episode's publication date for the list rows.
 *
 * Delegates to [DateFormat.episode] so the row and the full player agree on the
 * format and both follow the selected language. This used to be a private
 * `yyyy-MM-dd` copy that ignored the language entirely.
 */
internal fun formatDate(timestamp: Long): String = DateFormat.episode(timestamp)