package app.podara.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podara.component.EpisodeActionIconButton
import app.podara.component.FavoriteEpisodeButton
import app.podara.component.PodaraEmptyState
import app.podara.component.PodaraConfirmDialog
import app.podara.component.PodaraBackButton
import app.podara.data.AppDatabase
import app.podara.data.PodcastDownload
import app.podara.data.model.PodcastFavorite
import app.podara.manager.DownloadManager
import app.podara.player.MediaPlayerState
import app.podara.player.QueueItem
import app.podara.util.DateFormat
import app.podara.util.Strings
import app.podara.util.animateHoverBackgroundColor
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.clickableWithoutIndicationOrFocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private data class PodcastGroup(
    val origin: String,
    val podcastTitle: String,
    val items: List<PodcastDownload>
)

/**
 * One row of the completed-downloads list.
 *
 * The list used to be rendered as one `LazyColumn` item per podcast, with that
 * item's episodes drawn by a plain `forEachIndexed` inside it. Lazy composition
 * only skips work per *item*, so a show with 500 downloaded episodes composed
 * all 500 rows — with their hover sources, artwork loads and file stats — in a
 * single pass, and the list stuttered on scroll. Flattening header and episode
 * rows into one entry list puts every row under the lazy machinery, so only the
 * visible ones compose.
 */
private sealed interface DownloadListEntry {
    /** Stable across list rebuilds so LazyColumn can keep item state. */
    val key: String

    data class GroupHeader(val group: PodcastGroup) : DownloadListEntry {
        override val key: String get() = "header_${group.origin}"
    }

    data class Episode(
        val download: PodcastDownload
    ) : DownloadListEntry {
        override val key: String get() = "ep_${download.episodeId}"
    }
}

private data class ActiveDownloadItem(
    val episodeId: String,
    val task: app.podara.data.DownloadTask?,
    val isDownloading: Boolean
)

/**
 * Cached result of stat-ing one download file.
 *
 * Computed once per [DownloadsScreen]'s `downloadVersion` bump rather than in the
 * row's composition. `exists` is carried separately from `sizeBytes` because a
 * present-but-empty file and a missing file mean different things to the UI.
 */
private data class FileStat(
    val exists: Boolean,
    val sizeBytes: Long
)

@Composable
fun DownloadsScreen(
    database: AppDatabase,
    downloadManager: DownloadManager,
    downloadPath: String,
    downloadingEpisodes: Set<String>,
    downloadProgress: Map<String, Pair<Long, Long>>,
    downloadVersion: Int,
    completedDownloads: Set<String>,
    activeDownloadMeta: Map<String, Pair<String, String>>,
    playerState: MediaPlayerState? = null,
    favoriteVersion: Int,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDeleteDownloaded: (String) -> Unit,
    onDeleteDownloadedByOrigin: (String) -> Unit,
    onFavoriteChanged: () -> Unit = {},
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = PodaraTheme.colors
    val header = DesignTokens.PageHeader
    val spacing = DesignTokens.Spacing

    // ── State for completed downloads ──
    var completedList by remember { mutableStateOf(emptyList<PodcastDownload>()) }
    var totalBytes by remember { mutableStateOf(0L) }
    var groupedByPodcast by remember { mutableStateOf(emptyList<PodcastGroup>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Single-episode delete confirmation. It used to live inside the per-podcast
    // group component, which only ever composed the groups; now that every row is
    // its own list entry there is no group scope to hold it, so the screen owns
    // one pending delete for the whole list.
    var pendingDeleteDownload by remember { mutableStateOf<PodcastDownload?>(null) }
    var favoriteIds by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    // ── Episode artwork map for download context playback ──
    var episodeArtworkMap by remember { mutableStateOf(mapOf<String, String?>()) }

    // File stats, keyed by path and invalidated whenever downloadVersion changes.
    // Disk access is not something a composable should do per frame: the row used
    // to call File.exists() and File.length() in its body, which re-stat'ed every
    // visible row on every scroll and hover tick.
    var fileStats by remember { mutableStateOf(mapOf<String, FileStat>()) }

    // Reload completed list when downloadVersion changes
    LaunchedEffect(downloadVersion) {
        val all = database.downloads.getAllValid()
        completedList = all

        // One query for every episode's artwork instead of one per download.
        // With 500 downloads the old loop meant 500 round trips through the
        // single-threaded database dispatcher, on every list refresh.
        val ids = all.map { it.episodeId }
        episodeArtworkMap = database.episodes.getImageUrlsByIds(ids)

        // Stat the files off the UI thread, then publish the whole map at once.
        // Each entry costs a pair of syscalls, so 500 downloads is ~1000 calls
        // that must not block composition.
        fileStats = withContext(Dispatchers.IO) {
            all.associate { download ->
                val file = File(download.filePath)
                val exists = file.exists()
                download.filePath to FileStat(
                    exists = exists,
                    sizeBytes = if (exists) file.length() else 0L
                )
            }
        }

        totalBytes = fileStats.values.sumOf { it.sizeBytes }

        groupedByPodcast = all.groupBy { it.origin }.map { (origin, items) ->
            PodcastGroup(origin, items.first().podcastTitle, items)
        }
    }

    // Flat, lazily-composed view of the completed list.
    val completedEntries = remember(groupedByPodcast) {
        buildList {
            groupedByPodcast.forEach { group ->
                add(DownloadListEntry.GroupHeader(group))
                group.items.forEach { download ->
                    add(DownloadListEntry.Episode(download))
                }
            }
        }
    }

    // ── Determine active download tasks from DB ──
    var activeTasks by remember { mutableStateOf(listOf<app.podara.data.DownloadTask>()) }
    LaunchedEffect(downloadVersion) {
        activeTasks = database.downloadTasks.getAllActive()
    }

    LaunchedEffect(favoriteVersion) {
        favoriteIds = database.favorites.getAllEpisodeIds()
    }

    val activeDownloadItems = remember(downloadingEpisodes, activeTasks) {
        val tasksByEpisodeId = activeTasks.associateBy { it.episodeId }
        val episodeIds = activeTasks.map { it.episodeId } + downloadingEpisodes.filterNot { it in tasksByEpisodeId }
        episodeIds.map { episodeId ->
            ActiveDownloadItem(
                episodeId = episodeId,
                task = tasksByEpisodeId[episodeId],
                isDownloading = episodeId in downloadingEpisodes || tasksByEpisodeId[episodeId]?.state == "DOWNLOADING"
            )
        }
    }
    val hasInProgress = activeDownloadItems.isNotEmpty()
    val hasCompleted = completedList.isNotEmpty()
    val showEmpty = !hasInProgress && !hasCompleted

    val downloadContextItems = remember(completedList, episodeArtworkMap, fileStats) {
        // Reads the cached stat instead of calling File.exists() here: this runs
        // in composition, and a missing file is already reported by `fileStats`
        // from the same downloadVersion bump the list was built on.
        completedList
            .filter { fileStats[it.filePath]?.exists == true }
            .map { dl ->
                QueueItem(url = dl.filePath, title = dl.episodeTitle.ifEmpty { dl.episodeId }, subtitle = dl.podcastTitle, artworkUrl = episodeArtworkMap[dl.episodeId], episodeId = dl.episodeId, isDownloaded = true)
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = DesignTokens.Spacing.lg, start = 32.dp, end = 32.dp, bottom = 8.dp)
    ) {
        // ── Header ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(verticalAlignment = Alignment.Top) {
                PodaraBackButton(onClick = onBack, modifier = Modifier.padding(top = DesignTokens.Spacing.xs))
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = Strings["nav_downloads"],
                        fontSize = header.TitleSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = DesignTokens.TypeFamily.PageTitle,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(header.Gap))
                    Text(
                        text = Strings["downloads_subtitle"],
                        fontSize = header.SubtitleSize,
                        color = colors.textMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.md))

        // ── Summary Card ──
        // Hairline border rather than elevation: a #F5F5F7 fill on a #FFFFFF
        // page has almost no luminance step of its own.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    DesignTokens.Border.Width,
                    PodaraTheme.surfaces.cardBorder,
                    RoundedCornerShape(12.dp)
                ),
            shape = RoundedCornerShape(12.dp),
            color = colors.surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = DesignTokens.Spacing.md, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Location
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = Strings["downloads_summary_path"],
                            color = colors.textMuted,
                            fontSize = DesignTokens.TypeScale.Micro
                        )
                        Text(
                            text = downloadPath,
                            color = colors.textSecondary,
                            fontSize = DesignTokens.TypeScale.Micro,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 280.dp)
                        )
                    }
                }

                // Divider
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(colors.divider))

                // Storage
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = Strings.get("downloads_summary_downloads", completedList.size),
                            color = colors.textPrimary,
                            fontSize = DesignTokens.TypeScale.Micro
                        )
                        Text(
                            text = Strings.get("downloads_summary_storage", formatFileSize(totalBytes)),
                            color = colors.textSecondary,
                            fontSize = DesignTokens.TypeScale.Micro
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.sm))

        if (showEmpty) {
            // ── Empty state ──
            PodaraEmptyState(
                icon = Icons.Default.FileDownload,
                title = Strings["downloads_empty"],
                subtitle = Strings["downloads_empty_hint"],
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.FavoriteEpisodeList.CardGap),
                contentPadding = PaddingValues(bottom = DesignTokens.FavoriteEpisodeList.ListPaddingBottom)
            ) {
                // ── In Progress Section ──
                if (hasInProgress) {
                    item(key = "section_in_progress") {
                        Spacer(modifier = Modifier.height(spacing.md))
                        Text(
                            text = Strings["downloads_in_progress"],
                            fontSize = DesignTokens.SectionHeader.TitleSize,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = DesignTokens.TypeFamily.PageTitle,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(spacing.sm))
                    }

                    items(activeDownloadItems, key = { "task_${it.episodeId}" }, contentType = { "download_in_progress_row" }) { item ->
                        val episodeId = item.episodeId
                        val task = item.task
                        if (item.isDownloading) {
                            val progress = downloadProgress[episodeId]
                            val meta = activeDownloadMeta[episodeId]
                            val current = progress?.first ?: task?.downloadedBytes ?: 0L
                            val total = progress?.second ?: task?.totalBytes ?: 0L

                            InProgressRow(
                                episodeId = episodeId,
                                podcastTitle = meta?.first ?: task?.podcastTitle ?: "",
                                episodeTitle = meta?.second ?: task?.episodeTitle ?: "",
                                currentBytes = current,
                                totalBytes = total,
                                isFavorite = episodeId in favoriteIds,
                                onToggleFavorite = {
                                    val source = task ?: app.podara.data.DownloadTask(
                                        episodeId = episodeId,
                                        origin = "",
                                        audioUrl = "",
                                        podcastTitle = meta?.first ?: "",
                                        episodeTitle = meta?.second ?: episodeId,
                                        createdAt = 0L,
                                        updatedAt = 0L
                                    )
                                    scope.launch {
                                        val favorite = PodcastFavorite(
                                            episodeId = source.episodeId,
                                            origin = source.origin,
                                            timestamp = System.currentTimeMillis(),
                                            title = source.episodeTitle.ifEmpty { source.episodeId },
                                            podcastTitle = source.podcastTitle,
                                            audioUrl = source.audioUrl,
                                            imageUrl = null,
                                            duration = 0,
                                            pubDate = 0L
                                        )
                                        val nowFavorite = if (source.episodeId in favoriteIds) {
                                            database.favorites.delete(source.episodeId)
                                            false
                                        } else {
                                            database.favorites.insert(favorite)
                                            true
                                        }
                                        favoriteIds = if (nowFavorite) favoriteIds + source.episodeId else favoriteIds - source.episodeId
                                        onFavoriteChanged()
                                    }
                                },
                                onPause = onPauseDownload,
                                onResume = onResumeDownload,
                                onCancel = onCancelDownload,
                                colors = colors
                            )
                        } else {
                            PausedTaskRow(
                                task = requireNotNull(task),
                                isFavorite = episodeId in favoriteIds,
                                onToggleFavorite = {
                                    scope.launch {
                                        val favorite = PodcastFavorite(
                                            episodeId = task.episodeId,
                                            origin = task.origin,
                                            timestamp = System.currentTimeMillis(),
                                            title = task.episodeTitle.ifEmpty { task.episodeId },
                                            podcastTitle = task.podcastTitle,
                                            audioUrl = task.audioUrl,
                                            imageUrl = null,
                                            duration = 0,
                                            pubDate = 0L
                                        )
                                        val nowFavorite = if (task.episodeId in favoriteIds) {
                                            database.favorites.delete(task.episodeId)
                                            false
                                        } else {
                                            database.favorites.insert(favorite)
                                            true
                                        }
                                        favoriteIds = if (nowFavorite) favoriteIds + task.episodeId else favoriteIds - task.episodeId
                                        onFavoriteChanged()
                                    }
                                },
                                onResume = onResumeDownload,
                                onCancel = onCancelDownload,
                                colors = colors
                            )
                        }
                    }
                }

                // ── Completed Section ──
                if (hasCompleted) {
                    item(key = "section_completed") {
                        Spacer(modifier = Modifier.height(spacing.md))
                        Text(
                            text = Strings["downloads_completed"],
                            fontSize = DesignTokens.SectionHeader.TitleSize,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = DesignTokens.TypeFamily.PageTitle,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(spacing.sm))
                    }

                    // One entry per row rather than one per podcast: the group
                    // component used to draw all of its episodes in a single
                    // composition pass, which defeated lazy composition for any
                    // show with a large downloaded back catalogue.
                    items(completedEntries, key = { it.key }, contentType = { entry ->
                        when (entry) {
                            is DownloadListEntry.GroupHeader -> "download_group_header"
                            is DownloadListEntry.Episode -> "download_episode"
                        }
                    }) { entry ->
                        when (entry) {
                            is DownloadListEntry.GroupHeader -> DownloadGroupHeader(
                                group = entry.group,
                                onDeleteAll = {
                                    showBatchDeleteConfirm = entry.group.origin to entry.group.podcastTitle
                                },
                                colors = colors
                            )

                            is DownloadListEntry.Episode -> {
                                val download = entry.download
                                val stat = fileStats[download.filePath]
                                CompletedDownloadRow(
                                    download = download,
                                    playerState = playerState,
                                    contextItems = downloadContextItems,
                                    isFavorite = download.episodeId in favoriteIds,
                                    fileExists = stat?.exists == true,
                                    fileSize = stat?.sizeBytes ?: 0L,
                                    onToggleFavorite = {
                                        scope.launch {
                                            val episode = database.episodes.getById(download.episodeId)
                                            val nowFavorite = if (download.episodeId in favoriteIds) {
                                                database.favorites.delete(download.episodeId)
                                                false
                                            } else if (episode != null) {
                                                database.favorites.insert(episode)
                                                true
                                            } else {
                                                database.favorites.insert(PodcastFavorite(
                                                    episodeId = download.episodeId,
                                                    origin = download.origin,
                                                    timestamp = System.currentTimeMillis(),
                                                    title = download.episodeTitle.ifEmpty { download.episodeId },
                                                    podcastTitle = download.podcastTitle,
                                                    imageUrl = null,
                                                    audioUrl = download.filePath,
                                                    duration = 0,
                                                    pubDate = 0L
                                                ))
                                                true
                                            }
                                            favoriteIds = if (nowFavorite) favoriteIds + download.episodeId else favoriteIds - download.episodeId
                                            onFavoriteChanged()
                                        }
                                    },
                                    onDelete = { pendingDeleteDownload = download },
                                    colors = colors
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Single download delete confirm dialog ──
    pendingDeleteDownload?.let { download ->
        PodaraConfirmDialog(
            title = Strings["downloads_delete"],
            description = AnnotatedString(Strings["downloads_delete_confirm"]),
            confirmLabel = Strings["downloads_delete"],
            dismissLabel = Strings["dialog_cancel"],
            onConfirm = {
                onDeleteDownloaded(download.episodeId)
                pendingDeleteDownload = null
            },
            onDismissRequest = { pendingDeleteDownload = null }
        )
    }

    // ── Batch delete confirm dialog ──
    showBatchDeleteConfirm?.let { (origin, title) ->
        PodaraConfirmDialog(
            title = Strings["downloads_delete_all"],
            description = androidx.compose.ui.text.AnnotatedString(
                Strings.get("downloads_delete_podcast_confirm", title)
            ),
            confirmLabel = Strings["downloads_delete"],
            dismissLabel = Strings["dialog_cancel"],
            onConfirm = {
                onDeleteDownloadedByOrigin(origin)
                showBatchDeleteConfirm = null
            },
            onDismissRequest = { showBatchDeleteConfirm = null }
        )
    }
}

// ── In Progress Row ──
@Composable
private fun InProgressRow(
    episodeId: String,
    podcastTitle: String,
    episodeTitle: String,
    currentBytes: Long,
    totalBytes: Long,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    colors: app.podara.theme.PodaraColors
) {
    val progress = if (totalBytes > 0) (currentBytes.toFloat() / totalBytes) else 0f
    val card = DesignTokens.FavoriteEpisodeList
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(card.CardRadius)
    val animatedBg by animateColorAsState(
        targetValue = when {
            isPressed -> card.PressedBackgroundColor
            isHovered -> card.HoverBackgroundColor
            else -> card.BackgroundColor
        },
        animationSpec = tween(durationMillis = DesignTokens.Animation.HoverMs),
        label = "rowBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isHovered) card.HoverBorderColor else card.BorderColor,
        animationSpec = tween(durationMillis = DesignTokens.Animation.HoverMs),
        label = "rowBorder"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(card.CardHeight)
            .clip(shape)
            .background(animatedBg)
            .border(card.BorderWidth, borderColor, shape)
            .padding(horizontal = card.CardPaddingHorizontal, vertical = card.CardPaddingVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon placeholder
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
                .background(colors.elevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.FileDownload,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(card.CoverContentGap))

        // Info + Progress
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episodeTitle.ifEmpty { episodeId },
                color = card.TitleColor,
                fontSize = card.TitleSize,
                lineHeight = card.TitleLineHeight,
                fontWeight = card.TitleWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(card.PodcastNameMarginTop))
            Text(
                text = podcastTitle,
                color = card.PodcastNameColor,
                fontSize = card.PodcastNameSize,
                lineHeight = card.PodcastNameLineHeight,
                fontWeight = card.PodcastNameWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(card.MetadataMarginTop))
            // Progress bar
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = DesignTokens.Spacing.xl)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = colors.accent,
                trackColor = PodaraTheme.surfaces.cardBorder,
            )
            Spacer(modifier = Modifier.height(0.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${formatFileSize(currentBytes)} / ${formatFileSize(totalBytes)}",
                    color = colors.textMuted,
                    fontSize = DesignTokens.TypeScale.Micro
                )
                if (totalBytes > 0) {
                    Text(
                        text = "(${(progress * 100).toInt()}%)",
                        color = colors.textMuted,
                        fontSize = DesignTokens.TypeScale.Micro
                    )
                }
            }
        }

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(card.ActionsGap)) {
            FavoriteEpisodeButton(
                isFavorite = isFavorite,
                enabled = episodeTitle.isNotBlank() || podcastTitle.isNotBlank(),
                size = card.ActionButtonSize,
                radius = card.ActionButtonRadius,
                iconSize = card.FavoriteIconSize,
                hoverBackgroundColor = card.ActionButtonHoverBackgroundColor,
                defaultIconColor = card.FavoriteInactiveColor,
                hoverIconColor = card.ActionIconHoverColor,
                selectedIconColor = card.FavoriteActiveColor
            ) {
                onToggleFavorite()
            }
            // Pause / Resume toggle (resume only if paused state from DB — already handled elsewhere)
            if (currentBytes > 0) {
                ActionIconButton(
                    icon = Icons.Default.Pause,
                    description = Strings["downloads_pause"],
                    onClick = { onPause(episodeId) }
                )
            }
            // Cancel
            ActionIconButton(
                icon = Icons.Default.Close,
                description = Strings["downloads_cancel"],
                onClick = { onCancel(episodeId) }
            )
        }
    }
}

// ── Paused / Failed Task Row ──
@Composable
private fun PausedTaskRow(
    task: app.podara.data.DownloadTask,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    colors: app.podara.theme.PodaraColors
) {
    val isPaused = task.state == "PAUSED"
    val isFailed = task.state == "FAILED"
    val card = DesignTokens.FavoriteEpisodeList
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(card.CardRadius)
    val animatedBg by animateColorAsState(
        targetValue = when {
            isPressed -> card.PressedBackgroundColor
            isHovered -> card.HoverBackgroundColor
            else -> card.BackgroundColor
        },
        animationSpec = tween(durationMillis = DesignTokens.Animation.HoverMs),
        label = "rowBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isHovered) card.HoverBorderColor else card.BorderColor,
        animationSpec = tween(durationMillis = DesignTokens.Animation.HoverMs),
        label = "rowBorder"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(card.CardHeight)
            .clip(shape)
            .background(animatedBg)
            .border(card.BorderWidth, borderColor, shape)
            .padding(horizontal = card.CardPaddingHorizontal, vertical = card.CardPaddingVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                .background(if (isFailed) colors.danger.copy(alpha = 0.15f) else colors.elevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isFailed) Icons.Default.Error else Icons.Default.PauseCircle,
                contentDescription = null,
                tint = if (isFailed) colors.danger else colors.textSecondary,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(card.CoverContentGap))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.episodeTitle.ifEmpty { task.episodeId },
                color = card.TitleColor,
                fontSize = card.TitleSize,
                lineHeight = card.TitleLineHeight,
                fontWeight = card.TitleWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(card.PodcastNameMarginTop))
            Text(
                text = task.podcastTitle,
                color = card.PodcastNameColor,
                fontSize = card.PodcastNameSize,
                lineHeight = card.PodcastNameLineHeight,
                fontWeight = card.PodcastNameWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(card.MetadataMarginTop))
            Text(
                text = if (isPaused) Strings["downloads_status_paused"]
                       else Strings["downloads_status_failed"],
                color = if (isFailed) colors.danger else card.MetadataColor,
                fontSize = card.MetadataSize
            )
            if (task.downloadedBytes > 0) {
                Text(
                    text = "${formatFileSize(task.downloadedBytes)} / ${formatFileSize(task.totalBytes)}",
                    color = card.MetadataColor,
                    fontSize = card.MetadataSize
                )
            }
        }

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(card.ActionsGap)) {
            FavoriteEpisodeButton(
                isFavorite = isFavorite,
                size = card.ActionButtonSize,
                radius = card.ActionButtonRadius,
                iconSize = card.FavoriteIconSize,
                hoverBackgroundColor = card.ActionButtonHoverBackgroundColor,
                defaultIconColor = card.FavoriteInactiveColor,
                hoverIconColor = card.ActionIconHoverColor,
                selectedIconColor = card.FavoriteActiveColor
            ) {
                onToggleFavorite()
            }
            if (isPaused || isFailed) {
                ActionIconButton(
                    icon = Icons.Default.PlayArrow,
                    description = Strings["downloads_resume"],
                    onClick = { onResume(task.episodeId) }
                )
            }
            ActionIconButton(
                icon = Icons.Default.Close,
                description = Strings["downloads_cancel"],
                onClick = { onCancel(task.episodeId) }
            )
        }
    }
}

// ── Podcast download group header (Completed) ──
/**
 * The per-podcast heading above its downloaded episodes.
 *
 * This used to be `PodcastDownloadGroup`, which also drew every episode under
 * the heading in a `forEachIndexed`. Since the whole component was a single
 * `LazyColumn` item, no episode row was ever skipped, so a show with a large
 * back catalogue composed everything at once. Rows are now their own list
 * entries and only this header remains grouped.
 */
@Composable
private fun DownloadGroupHeader(
    group: PodcastGroup,
    onDeleteAll: () -> Unit,
    colors: app.podara.theme.PodaraColors
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = DesignTokens.Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Podcasts,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = group.podcastTitle,
                    color = colors.textPrimary,
                    fontSize = DesignTokens.TypeScale.Body,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "(${group.items.size})",
                    color = colors.textMuted,
                    fontSize = DesignTokens.TypeScale.Caption
                )
            }
            // Delete All button
            val daInteractionSource = remember { MutableInteractionSource() }
            val isDaHovered by daInteractionSource.collectIsHoveredAsState()
            val daAnimatedBg by animateHoverBackgroundColor(isDaHovered, PodaraTheme.surfaces.pillFillHover)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(daAnimatedBg)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickableWithoutIndicationOrFocusRing(interactionSource = daInteractionSource) { onDeleteAll() }
                    .padding(horizontal = DesignTokens.Spacing.sm, vertical = 4.dp)
            ) {
                Text(
                    text = Strings["downloads_delete_all"],
                    color = colors.danger,
                    fontSize = DesignTokens.TypeScale.Caption,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

// ── Completed download row ──
@Composable
private fun CompletedDownloadRow(
    download: PodcastDownload,
    playerState: MediaPlayerState?,
    contextItems: List<QueueItem>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    /**
     * Whether the file is still on disk, as of the last `downloadVersion` bump.
     *
     * Passed in rather than stat-ed here. The row used to call
     * `File(filePath).exists()` and `.length()` in its body specifically so a
     * file deleted outside the app would stop showing as present — but that runs
     * on every composition of every visible row, so scrolling a long list meant
     * thousands of `stat` calls. The screen now stats them once per version bump
     * and passes the answer down; a file removed behind the app's back is
     * reflected on the next download event, which is when the list rebuilds.
     */
    fileExists: Boolean,
    fileSize: Long,
    colors: app.podara.theme.PodaraColors
) {
    val card = DesignTokens.FavoriteEpisodeList
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(card.CardRadius)
    val animatedBg by animateColorAsState(
        targetValue = when {
            isPressed -> card.PressedBackgroundColor
            isHovered -> card.HoverBackgroundColor
            else -> card.BackgroundColor
        },
        animationSpec = tween(durationMillis = DesignTokens.Animation.HoverMs),
        label = "rowBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isHovered) card.HoverBorderColor else card.BorderColor,
        animationSpec = tween(durationMillis = DesignTokens.Animation.HoverMs),
        label = "rowBorder"
    )
    val isPlaying = playerState?.currentEpisodeId == download.episodeId || playerState?.currentUrl == download.filePath

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(card.CardHeight)
            .clip(shape)
            .background(if (isPlaying) card.PlayingBackgroundColor else animatedBg)
            .border(card.BorderWidth, if (isPlaying) card.PlayingBorderColor else borderColor, shape)
            .then(
                if (playerState != null && fileExists) {
                    Modifier
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        .clickableWithoutIndicationOrFocusRing(
                            interactionSource = interactionSource
                        ) {
                            val artUrl = contextItems.firstOrNull { it.url == download.filePath }?.artworkUrl
                            playerState.playWithContext(
                                context = contextItems,
                                targetUrl = download.filePath,
                                title = download.episodeTitle.ifEmpty { download.episodeId },
                                subtitle = download.podcastTitle,
                                artworkUrl = artUrl,
                                episodeId = download.episodeId
                            )
                        }
                } else Modifier
            )
            .padding(horizontal = card.CardPaddingHorizontal, vertical = card.CardPaddingVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
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
                .background(if (fileExists) colors.accent.copy(alpha = 0.1f) else colors.danger.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (fileExists) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (fileExists) colors.accent else colors.danger,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(card.CoverContentGap))

        // Info column
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = download.episodeTitle.ifEmpty { download.episodeId },
                color = card.TitleColor,
                fontSize = card.TitleSize,
                lineHeight = card.TitleLineHeight,
                fontWeight = card.TitleWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(card.PodcastNameMarginTop))
            if (!fileExists) {
                Text(
                    text = Strings["downloads_file_missing"],
                    color = colors.danger,
                    fontSize = card.PodcastNameSize,
                    lineHeight = card.PodcastNameLineHeight,
                    fontWeight = card.PodcastNameWeight
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(card.ActionsGap)) {
                    Text(
                        text = formatFileSize(fileSize),
                        color = card.PodcastNameColor,
                        fontSize = card.PodcastNameSize,
                        lineHeight = card.PodcastNameLineHeight,
                        fontWeight = card.PodcastNameWeight
                    )
                    Text(
                        text = formatTimestamp(download.timestamp),
                        color = card.PodcastNameColor,
                        fontSize = card.PodcastNameSize,
                        lineHeight = card.PodcastNameLineHeight,
                        fontWeight = card.PodcastNameWeight
                    )
                }
                Spacer(modifier = Modifier.height(card.MetadataMarginTop))
                Text(
                    text = download.filePath,
                    color = card.MetadataColor,
                    fontSize = card.MetadataSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(card.ActionsGap)) {
            FavoriteEpisodeButton(
                isFavorite = isFavorite,
                size = card.ActionButtonSize,
                radius = card.ActionButtonRadius,
                iconSize = card.FavoriteIconSize,
                hoverBackgroundColor = card.ActionButtonHoverBackgroundColor,
                defaultIconColor = card.FavoriteInactiveColor,
                hoverIconColor = card.ActionIconHoverColor,
                selectedIconColor = card.FavoriteActiveColor
            ) {
                onToggleFavorite()
            }
            // Open folder
            if (fileExists) {
                EpisodeActionIconButton(
                    icon = Icons.Default.FolderOpen,
                    contentDescription = Strings["downloads_open_folder"],
                    size = card.ActionButtonSize,
                    radius = card.ActionButtonRadius,
                    iconSize = card.ActionIconSize,
                    hoverBackgroundColor = card.ActionButtonHoverBackgroundColor,
                    defaultIconColor = card.ActionIconColor,
                    hoverIconColor = card.ActionIconHoverColor
                ) {
                    try {
                        // Only "open containing folder" needs a File here; the
                        // exists/size checks that used to share this variable now
                        // come from the cached stat passed into the row.
                        Desktop.getDesktop().open(File(download.filePath).parentFile)
                    } catch (_: Exception) {}
                }
            }

            // Delete
            ActionIconButton(
                icon = Icons.Default.Delete,
                description = Strings["downloads_delete"],
                onClick = onDelete
            )
        }
    }
}

// ── Reusable action icon button ──
@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val card = DesignTokens.FavoriteEpisodeList
    EpisodeActionIconButton(
        icon = icon,
        contentDescription = description,
        size = card.ActionButtonSize,
        radius = card.ActionButtonRadius,
        iconSize = card.ActionIconSize,
        hoverBackgroundColor = card.ActionButtonHoverBackgroundColor,
        defaultIconColor = card.ActionIconColor,
        hoverIconColor = card.ActionIconHoverColor,
        onClick = onClick
    )
}

// ── Helpers ──
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

// Shares the episode-list format instead of keeping a third `yyyy-MM-dd` copy
// that ignored the selected language.
private fun formatTimestamp(timestamp: Long): String = DateFormat.episode(timestamp)