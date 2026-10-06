package app.podara.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import app.podara.component.FavoriteEpisodeButton
import app.podara.component.PodaraDropdownMenu
import app.podara.component.PodaraDropdownMenuItem
import app.podara.component.PodaraDialog
import app.podara.component.PodaraDialogActionButton
import app.podara.component.PodaraDialogActionStyle
import app.podara.component.PodaraDialogBody
import app.podara.component.PodaraDialogTitle
import app.podara.component.ToolbarPillButton
import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.clickableWithoutIndication
import app.podara.util.clickableWithoutIndicationOrFocusRing
import coil3.compose.AsyncImage
import java.awt.Cursor
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Design Tokens: button.primary ──
// These mirror DesignTokens.Button but with the mini player's own geometry.
// Resolved per scheme so the gold fill becomes the red-pink accent under the
// light palette.
private val PrimaryButtonGradient: Brush
    @Composable get() = PodaraTheme.surfaces.buttonGradient
private val PrimaryButtonBorder: Color
    @Composable get() = PodaraTheme.surfaces.buttonBorder
private val PrimaryButtonText: Color
    @Composable get() = PodaraTheme.surfaces.buttonText
private val PrimaryButtonIcon: Color
    @Composable get() = PodaraTheme.surfaces.buttonIcon
private val PrimaryButtonInnerHighlight: Brush
    @Composable get() = PodaraTheme.surfaces.buttonSheen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    state: MediaPlayerState,
    onExpand: () -> Unit,
    onBodyClick: () -> Unit,
    onShowQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PodaraTheme.colors
    val progress by remember { derivedStateOf { state.getProgress() } }
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    var showSpeedMenu by remember { mutableStateOf(false) }
    val surfaces = PodaraTheme.surfaces

    // mpv reports position every 250 ms. Writing that straight to the slider
    // makes playback advance in visible quarter-second steps, so the reported
    // position is chased per frame instead. While the user drags, their pointer
    // owns the value outright.
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val reportedProgress =
        if (state.duration > 0L) state.currentPosition.toFloat() / state.duration else 0f
    val smoothed by rememberSmoothedFloat(target = reportedProgress)
    val sliderPosition = if (isDragged) dragPosition else smoothed

    Surface(
        modifier = modifier.fillMaxWidth(),
        // Was hardcoded to PodiumBackground, which pinned the mini player's
        // backdrop to the dark scheme regardless of the active theme.
        color = PodaraTheme.colors.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            Box(
                modifier = Modifier
                    .width(DesignTokens.Sidebar.Width)
                    .fillMaxHeight()
                    .background(colors.surface)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                    .height(88.dp)
                    .shadow(
                        16.dp,
                        RoundedCornerShape(18.dp),
                        ambientColor = surfaces.shadow,
                        spotColor = surfaces.shadow
                    )
                    .border(DesignTokens.Border.Width, colors.border, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(DesignTokens.Card.Gradient)
                    .let { mod ->
                        if (state.currentUrl != null) {
                            val bodyInteractionSource = remember { MutableInteractionSource() }
                            mod.pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                .clickableWithoutIndicationOrFocusRing(
                                    interactionSource = bodyInteractionSource
                                ) { onBodyClick() }
                        } else mod
                    }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ── Left: Cover + Title + Podcast ──
                    Row(
                        modifier = Modifier.weight(0.9f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AsyncImage(
                            model = state.currentArtworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Column {
                            Text(
                                text = state.currentTitle ?: Strings["player_no_playback"],
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.currentSubtitle != null) {
                                Text(
                                    text = state.currentSubtitle!!,
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // ── Center: Speed + Rewind + Play + Forward ──
                    Row(
                        modifier = Modifier.width(260.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .height(30.dp)
                                    .width(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                    .clickableWithoutIndication { showSpeedMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${state.playbackSpeed}x",
                                    color = colors.textMuted,
                                    fontSize = 14.sp
                                )
                            }
                            // The themed menu, matching the FullPlayer speed menu below.
                            // Material's DropdownMenu painted surfaceContainer
                            // from the placeholder colour scheme, so it was the
                            // one control that ignored the token system.
                            PodaraDropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false },
                                items = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).map { speed ->
                                    PodaraDropdownMenuItem(
                                        label = "${speed}x",
                                        isSelected = speed == state.playbackSpeed,
                                        onClick = {
                                            state.changePlaybackSpeed(speed)
                                            showSpeedMenu = false
                                        }
                                    )
                                }
                            )
                        }

                        IconButton(
                            onClick = { state.seekBack() },
                            modifier = Modifier.size(48.dp).pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        ) {
                            Icon(
                                Icons.Default.Replay10,
                                contentDescription = Strings["player_seek_back"],
                                tint = colors.textMuted,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .shadow(10.dp, CircleShape, ambientColor = PodaraTheme.surfaces.buttonShadow, spotColor = PodaraTheme.surfaces.buttonShadow)
                                .clip(CircleShape)
                                .border(1.dp, PrimaryButtonBorder, CircleShape)
                                .background(PrimaryButtonGradient)
                                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                .clickableWithoutIndication(enabled = state.currentUrl != null) { state.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.matchParentSize().background(PrimaryButtonInnerHighlight))
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) Strings["player_pause"] else Strings["player_play"],
                                tint = PrimaryButtonIcon,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        IconButton(
                            onClick = { state.seekForward() },
                            modifier = Modifier.size(40.dp).pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        ) {
                            Icon(
                                Icons.Default.Forward10,
                                contentDescription = Strings["player_seek_forward"],
                                tint = colors.textMuted,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // ── Right: Time + Slider + Volume + Queue + Fullscreen ──
                    Row(
                        modifier = Modifier.weight(1f).padding(start = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatTime(if (isDragged) (sliderPosition * state.duration).toLong() else state.currentPosition),
                            color = colors.textMuted,
                            fontSize = 12.sp
                        )

                        Slider(
                            value = sliderPosition,
                            onValueChange = { dragPosition = it },
                            onValueChangeFinished = {
                                state.seek((dragPosition * state.duration).toLong())
                            },
                            modifier = Modifier.weight(1f).height(20.dp),
                            thumb = {
                                SliderDefaults.Thumb(
                                    interactionSource = interactionSource,
                                    colors = SliderDefaults.colors(thumbColor = PodaraTheme.surfaces.sliderThumb),
                                    thumbSize = DpSize(12.dp, 12.dp),
                                    modifier = Modifier.offset(y = 2.dp)
                                )
                            },
                            track = { sliderState ->
                                SliderDefaults.Track(
                                    sliderState = sliderState,
                                    modifier = Modifier.height(6.dp),
                                    thumbTrackGapSize = 0.dp,
                                    colors = SliderDefaults.colors(
                                        activeTrackColor = colors.accent,
                                        inactiveTrackColor = PodaraTheme.surfaces.sliderTrackInactive
                                    )
                                )
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = PodaraTheme.surfaces.sliderThumb,
                                activeTrackColor = colors.accent,
                                inactiveTrackColor = PodaraTheme.surfaces.sliderTrackInactive
                            ),
                            interactionSource = interactionSource
                        )

                        Text(
                            text = formatTime(state.duration),
                            color = colors.textMuted,
                            fontSize = 12.sp
                        )

                        IconButton(
                            onClick = { state.toggleMute() },
                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        ) {
                            Icon(
                                if (state.volume > 0) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = if (state.volume > 0) Strings["player_mute"] else Strings["player_unmute"],
                                tint = colors.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onShowQueue,
                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        ) {
                            Icon(
                                Icons.Default.QueueMusic,
                                contentDescription = Strings["player_queue"],
                                tint = colors.textMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { if (state.currentUrl != null) onExpand() },
                            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR))),
                            enabled = state.currentUrl != null
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = Strings["player_expand"],
                                tint = colors.textMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(
    state: MediaPlayerState,
    database: AppDatabase,
    completedDownloads: Set<String> = emptySet(),
    favoriteVersion: Int = 0,
    onFavoriteChanged: () -> Unit = {},
    onClose: () -> Unit,
    onShowQueue: () -> Unit = {},
    onStartDownload: ((PodcastEpisode) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val colors = PodaraTheme.colors

    var isDragging by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    // ── Episode data from DB ──
    var currentEpisode by remember { mutableStateOf<PodcastEpisode?>(null) }
    var currentOrigin by remember { mutableStateOf<String?>(null) }
    var favoriteIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(favoriteVersion) {
        favoriteIds = database.favorites.getAllEpisodeIds()
    }

    LaunchedEffect(state.currentEpisodeId) {
        val episodeId = state.currentEpisodeId ?: return@LaunchedEffect
        val ep = database.episodes.getById(episodeId)
        currentEpisode = ep
        if (ep != null) {
            currentOrigin = ep.origin
        }
    }

    // Download state comes from the app's source of truth, not from a one-shot
    // DB check that a click handler then flipped optimistically — the button
    // used to turn "downloaded" green the moment it was pressed, even if the
    // download never started or failed.
    val isDownloaded = state.currentEpisodeId != null && state.currentEpisodeId in completedDownloads

    // Same 250 ms polling as the mini player, so the same per-frame smoothing.
    // isDragging means the pointer owns the value until release.
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val reportedProgress =
        if (state.duration > 0L) state.currentPosition.toFloat() / state.duration else 0f
    val smoothed by rememberSmoothedFloat(target = reportedProgress)
    val sliderPosition = if (isDragging) dragPosition else smoothed

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, start = 28.dp, end = 28.dp, bottom = 24.dp)
        ) {
            // ── Top bar: Close (left) + Queue (right) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarPillButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    label = "",
                    contentDescription = Strings["player_close"],
                    onClick = onClose,
                    iconColor = colors.textPrimary,
                    hoverIconColor = PodaraTheme.surfaces.pillIconHover,
                    defaultBackgroundColor = PodaraTheme.surfaces.iconButtonFill,
                    defaultBorderColor = PodaraTheme.surfaces.iconButtonBorder,
                    hoverBackgroundColor = PodaraTheme.surfaces.iconButtonFillHover,
                    hoverBorderColor = PodaraTheme.surfaces.pillBorderHover
                )
                ToolbarPillButton(
                    icon = Icons.Default.QueueMusic,
                    label = "",
                    contentDescription = Strings["player_queue"],
                    onClick = onShowQueue,
                    iconColor = colors.textPrimary,
                    hoverIconColor = PodaraTheme.surfaces.pillIconHover,
                    defaultBackgroundColor = PodaraTheme.surfaces.iconButtonFill,
                    defaultBorderColor = PodaraTheme.surfaces.iconButtonBorder,
                    hoverBackgroundColor = PodaraTheme.surfaces.iconButtonFillHover,
                    hoverBorderColor = PodaraTheme.surfaces.pillBorderHover
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ══════════════════════════════════════
            // 1. HEADER: Cover + Episode Info
            // ══════════════════════════════════════
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 43.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Cover 208dp radius:12
                Box(modifier = Modifier.width(208.dp)) {
                    Box(
                        modifier = Modifier
                            .size(208.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.elevated)
                    ) {
                        AsyncImage(
                            model = state.currentArtworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Episode Info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Podcast Name
                    if (state.currentSubtitle != null) {
                        Text(
                            text = state.currentSubtitle!!,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Episode Title
                    Text(
                        text = state.currentTitle ?: "",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 30.sp
                    )

                    // Episode Description
                    val episodeDesc = currentEpisode?.description
                    if (!episodeDesc.isNullOrBlank()) {
                        Text(
                            text = stripHtml(episodeDesc),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 500.dp)
                        )
                    }

                    // Metadata Row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val pubDate = currentEpisode?.pubDate ?: 0L
                        if (pubDate > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.textMuted)
                                Text(text = formatDate(pubDate), fontSize = 13.sp, color = colors.textMuted)
                            }
                        }

                        val dur = currentEpisode?.duration ?: 0
                        if (dur > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.textMuted)
                                Text(text = formatDuration(dur), fontSize = 13.sp, color = colors.textMuted)
                            }
                        }
                    }

                    // Action Buttons
                    val origin = currentOrigin
                    var showMore by remember { mutableStateOf(false) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val epToDownload = currentEpisode
                        if (epToDownload != null) {
                            DownloadActionButton(
                                isDownloaded = isDownloaded,
                                onClick = { onStartDownload?.invoke(epToDownload) }
                            )
                            FavoriteEpisodeButton(
                                isFavorite = epToDownload.id in favoriteIds,
                                size = 40.dp,
                                radius = 10.dp,
                                iconSize = 20.dp,
                                hoverBackgroundColor = PodaraTheme.surfaces.iconButtonFillHover,
                                defaultIconColor = colors.textPrimary,
                                hoverIconColor = PodaraTheme.surfaces.pillIconHover,
                                selectedIconColor = colors.accent
                            ) {
                                scope.launch {
                                    database.episodes.insert(epToDownload)
                                    val isFavorite = database.favorites.toggle(epToDownload)
                                    favoriteIds = if (isFavorite) favoriteIds + epToDownload.id else favoriteIds - epToDownload.id
                                    onFavoriteChanged()
                                }
                            }
                        }
                        ToolbarPillButton(
                            icon = Icons.Default.MoreHoriz,
                            label = "",
                            contentDescription = Strings["discover_more"],
                            onClick = { showMore = true },
                            iconColor = colors.textPrimary,
                            hoverIconColor = PodaraTheme.surfaces.pillIconHover,
                            defaultBackgroundColor = PodaraTheme.surfaces.iconButtonFill,
                            defaultBorderColor = PodaraTheme.surfaces.iconButtonBorder,
                            hoverBackgroundColor = PodaraTheme.surfaces.iconButtonFillHover,
                            hoverBorderColor = PodaraTheme.surfaces.pillBorderHover
                        )
                        PodaraDropdownMenu(
                            expanded = showMore,
                            onDismissRequest = { showMore = false },
                            items = listOf(
                                PodaraDropdownMenuItem(
                                    label = Strings["dialog_copy_to_clipboard"],
                                    icon = Icons.Default.ContentCopy,
                                    onClick = {
                                        showMore = false
                                        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                        val selection = java.awt.datatransfer.StringSelection(origin ?: "")
                                        clipboard.setContents(selection, null)
                                    }
                                )
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ══════════════════════════════════════
            // 2. TIMELINE
            // ══════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 38.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Slider(
                    value = sliderPosition,
                    onValueChange = { dragPosition = it; isDragging = true },
                    onValueChangeFinished = { isDragging = false; state.seek((dragPosition * state.duration).toLong()) },
                    modifier = Modifier.fillMaxWidth().height(20.dp),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(PrimaryButtonGradient)
                                .border(2.dp, PrimaryButtonBorder, CircleShape)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(6.dp),
                            thumbTrackGapSize = 0.dp,
                            colors = SliderDefaults.colors(
                                activeTrackColor = colors.accent,
                                inactiveTrackColor = PodaraTheme.surfaces.sliderTrackInactive
                            )
                        )
                    },
                    colors = SliderDefaults.colors(
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = PodaraTheme.surfaces.sliderTrackInactive
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = formatTime(if (isDragging) (sliderPosition * state.duration).toLong() else state.currentPosition), color = colors.textMuted, fontSize = 12.sp)
                    Text(text = formatTime(state.duration), color = colors.textMuted, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ══════════════════════════════════════
            // 3. PLAYBACK CONTROLS
            // ══════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed selector
                Box {
                    ToolbarPillButton(
                        label = "${state.playbackSpeed}x",
                        contentDescription = Strings["player_speed"],
                        onClick = { showSpeedMenu = true },
                        height = 30.dp,
                        radius = 8.dp,
                        minWidth = 40.dp,
                        horizontalPadding = 8.dp,
                        textSize = 14.sp,
                        iconColor = colors.textPrimary,
                        textColor = colors.textPrimary,
                        hoverTextColor = PodaraTheme.surfaces.pillTextHover,
                        defaultBackgroundColor = PodaraTheme.surfaces.iconButtonFill,
                        defaultBorderColor = PodaraTheme.surfaces.iconButtonBorder,
                        hoverBackgroundColor = PodaraTheme.surfaces.iconButtonFillHover,
                        hoverBorderColor = PodaraTheme.surfaces.pillBorderHover
                    )
                    PodaraDropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false },
                        items = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).map { speed ->
                            PodaraDropdownMenuItem(
                                label = "${speed}x",
                                isSelected = speed == state.playbackSpeed,
                                onClick = {
                                    state.changePlaybackSpeed(speed)
                                    showSpeedMenu = false
                                }
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                CircleControlButton(size = 48.dp, icon = Icons.Default.Replay10, contentDescription = Strings["player_seek_back"], tint = colors.textMuted, onClick = { state.seekBack() })

                Spacer(modifier = Modifier.width(16.dp))

                // Play/Pause 56dp (matching MiniPlayer)
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .shadow(10.dp, CircleShape, ambientColor = PodaraTheme.surfaces.buttonShadow, spotColor = PodaraTheme.surfaces.buttonShadow)
                        .clip(CircleShape)
                        .border(1.dp, PrimaryButtonBorder, CircleShape)
                        .background(PrimaryButtonGradient)
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        .clickableWithoutIndication(enabled = state.currentUrl != null) { state.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.matchParentSize().background(PrimaryButtonInnerHighlight))
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) Strings["player_pause"] else Strings["player_play"],
                        tint = PrimaryButtonIcon,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                CircleControlButton(size = 40.dp, icon = Icons.Default.Forward10, contentDescription = Strings["player_seek_forward"], tint = colors.textMuted, onClick = { state.seekForward() })

                Spacer(modifier = Modifier.width(16.dp))

                CircleControlButton(size = 40.dp, icon = Icons.Default.Timer, contentDescription = Strings["player_sleep_timer"], tint = colors.textMuted, onClick = { showSleepTimer = true })
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ══════════════════════════════════════
            // 5. EPISODE NOTES
            // ══════════════════════════════════════
            val rawDesc = currentEpisode?.description
            if (!rawDesc.isNullOrBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surface)
                            .border(DesignTokens.Border.Width, colors.border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 28.dp, vertical = 26.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            Text(
                                text = Strings["player_episode_notes"],
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary
                            )
                            EpisodeNotesText(
                                html = rawDesc,
                                onSeek = { offsetMs -> state.seek(offsetMs) },
                                onOpenLink = ::openExternalLink
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = colors.divider)
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showSleepTimer) {
        SleepTimerSheet(state = state, onDismiss = { showSleepTimer = false })
    }
}

@Composable
private fun DownloadActionButton(isDownloaded: Boolean, onClick: () -> Unit) {
    val colors = PodaraTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .width(108.dp)
            .height(40.dp)
            .shadow(
                if (isHovered) 10.dp else 6.dp,
                shape,
                ambientColor = PodaraTheme.surfaces.buttonShadow,
                spotColor = PodaraTheme.surfaces.buttonShadow
            )
            .clip(shape)
            .border(1.dp, PrimaryButtonBorder, shape)
            .background(if (isDownloaded) Brush.verticalGradient(listOf(colors.success, colors.success.copy(alpha = 0.72f))) else PrimaryButtonGradient)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { if (!isDownloaded) onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.matchParentSize().background(PrimaryButtonInnerHighlight))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                if (isDownloaded) Icons.Default.CheckCircle else Icons.Default.Download,
                contentDescription = null,
                tint = PrimaryButtonIcon,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (isDownloaded) Strings["episode_downloaded"] else Strings["episode_download"],
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = PrimaryButtonText
            )
        }
    }
}

@Composable
private fun CircleControlButton(
    size: androidx.compose.ui.unit.Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val surfaces = PodaraTheme.surfaces
    val backgroundColor by animateHoverBackgroundColor(isHovered, surfaces.cardFillHover)
    val iconColor by animateColorAsState(
        targetValue = if (isHovered) PodaraTheme.colors.textPrimary else tint,
        animationSpec = tween(DesignTokens.Animation.HoverMs),
        label = "playerControlIcon"
    )

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR))),
        interactionSource = interactionSource
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/**
 * Simple HTML-to-AnnotatedString parser for RSS episode descriptions.
 *
 * Supports `<p> <br> <b>/<strong> <i>/<em> <ul>/<li> <a href="...">`, and
 * annotates both hyperlinks and bare `h:mm:ss` timestamps so the UI can make
 * them clickable.
 *
 * Every pushed style/annotation is popped unconditionally. An earlier version
 * only popped the link annotation when an href was present, which unbalanced
 * the stack and leaked the link styling into the rest of the notes.
 */
internal fun parseSimpleHtml(html: String, linkColor: Color, timestampColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var pos = 0
        val text = html.trim()

        while (pos < text.length) {
            val tagStart = text.indexOf('<', pos)
            if (tagStart < 0) {
                appendTextWithTimestamps(
                    text.substring(pos),
                    linkColor = linkColor,
                    timestampColor = timestampColor
                )
                break
            }

            if (tagStart > pos) {
                appendTextWithTimestamps(
                    text.substring(pos, tagStart),
                    linkColor = linkColor,
                    timestampColor = timestampColor
                )
            }

            val tagEnd = text.indexOf('>', tagStart)
            if (tagEnd < 0) {
                // Malformed — rest is plain text
                append(text.substring(tagStart).replace("&amp;", "&"))
                break
            }

            val tagContent = text.substring(tagStart + 1, tagEnd).trim().lowercase()
            pos = tagEnd + 1

            when {
                // Line breaks
                tagContent == "br" || tagContent == "br/" || tagContent == "/br" -> append("\n")

                // Paragraph — opening adds double newline, closing is ignored
                tagContent == "p" -> { /* opening p — text will follow */ }
                tagContent == "/p" -> append("\n\n")

                // Bold. The close tag must be matched by the full tag name:
                // taking the first character would search for "</s" and fail to
                // find "</strong>".
                tagContent == "b" || tagContent == "strong" -> {
                    val inner = extractTagContent(text, pos, tagContent)
                    if (inner != null) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(parseSimpleHtml(inner.content, linkColor, timestampColor))
                        pop()
                        pos = inner.endPos
                    }
                }
                tagContent == "/b" || tagContent == "/strong" -> { /* handled */ }

                // Italic
                tagContent == "i" || tagContent == "em" -> {
                    val inner = extractTagContent(text, pos, tagContent)
                    if (inner != null) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(parseSimpleHtml(inner.content, linkColor, timestampColor))
                        pop()
                        pos = inner.endPos
                    }
                }
                tagContent == "/i" || tagContent == "/em" -> { /* handled */ }

                // Links
                tagContent.startsWith("a ") || tagContent == "a" -> {
                    val href = extractAttribute(tagContent, "href")
                    val inner = extractTagContent(text, pos, "a")
                    if (inner != null) {
                        pushStyle(SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        ))
                        if (href != null) {
                            pushStringAnnotation(URL_ANNOTATION, href)
                        }
                        append(parseSimpleHtml(inner.content, linkColor, timestampColor))
                        // Balanced: the style and the optional annotation each
                        // get exactly one pop.
                        if (href != null) pop()
                        pop()
                        pos = inner.endPos
                    }
                }
                tagContent == "/a" -> { /* handled by extractTagContent */ }

                // Unordered list / list items
                tagContent == "ul" -> { /* just let text flow */ }
                tagContent == "/ul" -> append("\n")
                tagContent == "li" -> append("\n  • ")
                tagContent == "/li" -> { /* handled inline */ }

                // Skip everything else (headings, divs, spans, images, etc.)
            }
        }
    }
}

internal const val URL_ANNOTATION = "URL"
internal const val TIMESTAMP_ANNOTATION = "TIMESTAMP"

/**
 * Matches episode-notes chapter markers: `12:34`, `1:02:03`, optionally wrapped
 * in brackets or parentheses, as used by most podcast hosts
 * (`[00:00] Intro`, `1:23:45 - Topic`).
 *
 * Two guards keep ordinary prose out. The trailing lookahead rejects a match
 * followed by a letter, digit, colon or slash, so `10:30am`, `16:9`, `2:1` and
 * `12:34:56.7` are not chapter markers. The bracketed alternative is listed
 * first so `[1:02:03]` is consumed whole rather than starting at the first
 * digit and matching only the tail.
 */
internal val TimestampPattern = Regex(
    """(?:[\[(]\d{1,2}:\d{2}(?::\d{2})?[\])]|\d{1,2}:\d{2}(?::\d{2})?)(?![\dA-Za-z/:.])"""
)

/** Converts an `h:mm:ss` / `mm:ss` marker to milliseconds. */
internal fun parseTimestampMarker(marker: String): Long? {
    val cleaned = marker.trim().trim('[', ']', '(', ')')
    val parts = cleaned.split(':')
    if (parts.size !in 2..3) return null
    if (parts.any { it.isBlank() || it.toIntOrNull() == null }) return null
    val numbers = parts.map { it.toInt() }
    val (hours, minutes, seconds) = when (numbers.size) {
        3 -> Triple(numbers[0], numbers[1], numbers[2])
        else -> Triple(0, numbers[0], numbers[1])
    }
    // Reject nonsense rather than seeking to a surprising offset.
    if (minutes > 59 || seconds > 59 || hours < 0) return null
    return ((hours * 60L + minutes) * 60L + seconds) * 1000L
}

/**
 * Appends [text], annotating any timestamp markers found so they can be made
 * clickable and seek the player.
 */
private fun AnnotatedString.Builder.appendTextWithTimestamps(
    text: String,
    linkColor: Color,
    timestampColor: Color
) {
    val decoded = decodeHtmlEntities(text)

    var cursor = 0
    for (match in TimestampPattern.findAll(decoded)) {
        val offset = parseTimestampMarker(match.value) ?: continue
        if (match.range.first > cursor) {
            append(decoded.substring(cursor, match.range.first))
        }
        pushStyle(SpanStyle(color = timestampColor, fontWeight = FontWeight.Medium))
        pushStringAnnotation(TIMESTAMP_ANNOTATION, offset.toString())
        append(match.value)
        pop()
        pop()
        cursor = match.range.last + 1
    }
    if (cursor < decoded.length) {
        append(decoded.substring(cursor))
    }
}

private data class TagInner(val content: String, val endPos: Int)

private fun extractTagContent(text: String, startPos: Int, tagName: String): TagInner? {
    val closeTag = "</$tagName>"
    val closeIdx = text.indexOf(closeTag, startPos, ignoreCase = true)
    if (closeIdx < 0) return null
    return TagInner(text.substring(startPos, closeIdx), closeIdx + closeTag.length)
}

/**
 * Reads [attrName] out of a raw tag's content.
 *
 * Returns the value with HTML entities decoded, so an href containing
 * `&amp;` yields a usable URL rather than a literal `&amp;` that the browser
 * would treat as a parameter name.
 */
private fun extractAttribute(tagContent: String, attrName: String): String? {
    val regex = Regex("""${attrName}\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    val raw = regex.find(tagContent)?.groupValues?.getOrNull(1) ?: return null
    return decodeHtmlEntities(raw)
}

private fun decodeHtmlEntities(text: String): String = text
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")

private fun stripHtml(html: String): String {
    return html.replace(Regex("<[^>]*>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueDrawer(
    state: MediaPlayerState,
    onDismiss: () -> Unit,
    database: AppDatabase? = null,
    favoriteVersion: Int = 0,
    onFavoriteChanged: () -> Unit = {},
    onDownload: ((QueueItem) -> Unit)? = null,
    onDeleteDownload: ((QueueItem) -> Unit)? = null
) {
    val colors = PodaraTheme.colors
    val favoriteList = DesignTokens.FavoriteEpisodeList
    val queuePanel = DesignTokens.QueuePanel
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    val density = LocalDensity.current
    val rowStepPx = with(density) { (queuePanel.CardHeight + queuePanel.CardGap).toPx() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var favoriteIds by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    val closeInteractionSource = remember { MutableInteractionSource() }
    val isCloseHovered by closeInteractionSource.collectIsHoveredAsState()
    val closeBackground by animateHoverBackgroundColor(
        isCloseHovered,
        DesignTokens.QueuePanel.HeaderCloseHoverBackgroundColor
    )

    LaunchedEffect(database, favoriteVersion) {
        favoriteIds = database?.favorites?.getAllEpisodeIds() ?: emptySet()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(DesignTokens.QueuePanel.Width)
                .align(Alignment.CenterEnd)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            color = colors.surface
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = DesignTokens.QueuePanel.PaddingTop)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                // ── Header: "Up Next" + Select/Clear ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DesignTokens.QueuePanel.HeaderHeight)
                        .padding(
                            start = DesignTokens.QueuePanel.PaddingHorizontal,
                            top = DesignTokens.QueuePanel.HeaderContentTopOffset,
                            end = DesignTokens.QueuePanel.PaddingHorizontal
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = Strings["queue_title"],
                        color = colors.textPrimary,
                        fontSize = DesignTokens.QueuePanel.HeaderTitleSize,
                        lineHeight = DesignTokens.QueuePanel.HeaderTitleLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .offset(y = DesignTokens.QueuePanel.HeaderTitleBottomOffset)
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .offset(y = DesignTokens.QueuePanel.HeaderActionVerticalOffset),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToolbarPillButton(
                            icon = if (isSelectionMode) Icons.Default.Close else Icons.Default.CheckCircle,
                            label = if (isSelectionMode) Strings["home_cancel"] else Strings["player_select"],
                            contentDescription = if (isSelectionMode) Strings["home_cancel"] else Strings["player_select"],
                            onClick = {
                                isSelectionMode = !isSelectionMode
                                if (!isSelectionMode) selectedIndices = emptySet()
                            },
                            height = DesignTokens.QueuePanel.HeaderActionHeight,
                            radius = DesignTokens.QueuePanel.HeaderActionRadius,
                            borderWidth = DesignTokens.QueuePanel.HeaderActionBorderWidth,
                            horizontalPadding = DesignTokens.QueuePanel.HeaderActionPaddingHorizontal,
                            iconSize = DesignTokens.QueuePanel.HeaderActionIconSize,
                            iconTextGap = DesignTokens.QueuePanel.HeaderActionIconTextGap,
                            textSize = DesignTokens.QueuePanel.HeaderActionTextSize,
                            lineHeight = DesignTokens.QueuePanel.HeaderActionLineHeight,
                            minWidth = DesignTokens.QueuePanel.HeaderActionMinWidth,
                            iconColor = colors.accent,
                            hoverIconColor = colors.accentHover,
                            defaultBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassBackgroundColor,
                            hoverBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassHoverBackgroundColor,
                            defaultBorderColor = colors.accent,
                            hoverBorderColor = colors.accentHover
                        )
                        ToolbarPillButton(
                            icon = Icons.Default.ClearAll,
                            label = Strings["queue_clear"],
                            contentDescription = Strings["queue_clear"],
                            onClick = { state.removeSelectedFromQueue(state.queue.indices.toSet()) },
                            height = DesignTokens.QueuePanel.HeaderActionHeight,
                            radius = DesignTokens.QueuePanel.HeaderActionRadius,
                            borderWidth = DesignTokens.QueuePanel.HeaderActionBorderWidth,
                            horizontalPadding = DesignTokens.QueuePanel.HeaderActionPaddingHorizontal,
                            iconSize = DesignTokens.QueuePanel.HeaderActionIconSize,
                            iconTextGap = DesignTokens.QueuePanel.HeaderActionIconTextGap,
                            textSize = DesignTokens.QueuePanel.HeaderActionTextSize,
                            lineHeight = DesignTokens.QueuePanel.HeaderActionLineHeight,
                            minWidth = DesignTokens.QueuePanel.HeaderActionMinWidth,
                            iconColor = colors.accent,
                            hoverIconColor = colors.accentHover,
                            defaultBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassBackgroundColor,
                            hoverBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassHoverBackgroundColor,
                            defaultBorderColor = colors.accent,
                            hoverBorderColor = colors.accentHover
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Queue list ──
                val queueScrollState = rememberScrollState()
                if (state.queue.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = Strings["player_queue_empty"],
                            color = colors.textMuted,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(queueScrollState)
                            .padding(
                                start = DesignTokens.QueuePanel.PaddingHorizontal,
                                end = DesignTokens.QueuePanel.PaddingHorizontal,
                                top = favoriteList.ListPaddingTop,
                                bottom = favoriteList.ListPaddingBottom
                            ),
                        verticalArrangement = Arrangement.spacedBy(queuePanel.CardGap)
                    ) {
                        state.queue.forEachIndexed { index, item ->
                            val isActive = index == state.queueIndex
                            val isDraggingThis = draggingIndex == index
                            val itemOffsetPx = when {
                                isDraggingThis -> dragAccumulated.roundToInt()
                                draggingIndex >= 0 && dragTargetIndex >= 0 && draggingIndex != dragTargetIndex -> {
                                    val rowStep = rowStepPx.roundToInt()
                                    if (draggingIndex < dragTargetIndex && index > draggingIndex && index <= dragTargetIndex) -rowStep
                                    else if (draggingIndex > dragTargetIndex && index < draggingIndex && index >= dragTargetIndex) rowStep
                                    else 0
                                }
                                else -> 0
                            }

                            QueueEpisodeCard(
                                item = item,
                                isPlaying = isActive,
                                isSelectionMode = isSelectionMode,
                                isSelected = index in selectedIndices,
                                onPlay = {
                                    if (isSelectionMode) {
                                        selectedIndices = if (index in selectedIndices) selectedIndices - index
                                        else selectedIndices + index
                                    } else {
                                        state.playFromQueue(index)
                                        onDismiss()
                                    }
                                },
                                onSelectionToggle = {
                                    selectedIndices = if (index in selectedIndices) selectedIndices - index
                                    else selectedIndices + index
                                },
                                onRemove = { state.removeFromQueue(index) },
                                modifier = Modifier.then(
                                    if (itemOffsetPx != 0) Modifier.offset { IntOffset(0, itemOffsetPx) }
                                    else Modifier
                                ),
                                trailingActions = {
                                    val episodeId = item.episodeId
                                    if (database != null && episodeId != null) {
                                        FavoriteEpisodeButton(
                                            isFavorite = episodeId in favoriteIds,
                                            size = favoriteList.ActionButtonSize,
                                            radius = favoriteList.ActionButtonRadius,
                                            iconSize = favoriteList.FavoriteIconSize,
                                            hoverBackgroundColor = favoriteList.ActionButtonHoverBackgroundColor,
                                            defaultIconColor = favoriteList.FavoriteInactiveColor,
                                            hoverIconColor = favoriteList.ActionIconHoverColor,
                                            selectedIconColor = favoriteList.FavoriteActiveColor
                                        ) {
                                            scope.launch {
                                                val episode = database.episodes.getById(episodeId)
                                                if (episode != null) {
                                                    val isFavorite = database.favorites.toggle(episode)
                                                    favoriteIds = if (isFavorite) favoriteIds + episodeId else favoriteIds - episodeId
                                                    onFavoriteChanged()
                                                }
                                            }
                                        }
                                    }
                                    Icon(
                                        Icons.Default.DragHandle,
                                        contentDescription = null,
                                        tint = favoriteList.ActionIconColor,
                                        modifier = Modifier
                                            .align(Alignment.CenterVertically)
                                            .offset(y = queuePanel.DragHandleVerticalOffset)
                                            .size(favoriteList.FavoriteIconSize)
                                            .pointerInput(index, rowStepPx) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        draggingIndex = index
                                                        dragAccumulated = 0f
                                                        dragTargetIndex = index
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragAccumulated += dragAmount.y
                                                        val offsetRows = (dragAccumulated / rowStepPx).roundToInt()
                                                        dragTargetIndex = (index + offsetRows)
                                                            .coerceIn(0, state.queue.size - 1)
                                                    },
                                                    onDragEnd = {
                                                        if (draggingIndex >= 0 && dragTargetIndex >= 0 && dragTargetIndex != draggingIndex) {
                                                            state.moveQueueItem(draggingIndex, dragTargetIndex)
                                                        }
                                                        draggingIndex = -1
                                                        dragAccumulated = 0f
                                                        dragTargetIndex = -1
                                                    },
                                                    onDragCancel = {
                                                        draggingIndex = -1
                                                        dragAccumulated = 0f
                                                        dragTargetIndex = -1
                                                    }
                                                )
                                            }
                                    )
                                }
                            )
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(queueScrollState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                        style = ScrollbarStyle(
                            minimalHeight = 16.dp,
                            thickness = 6.dp,
                            shape = CircleShape,
                            hoverDurationMillis = DesignTokens.Animation.HoverMs,
                            unhoverColor = PodaraTheme.surfaces.cardFillHover,
                            hoverColor = PodaraTheme.surfaces.cardBorderHover
                        )
                    )
                    }
                }

                // ── Selection actions bar ──
                if (isSelectionMode && selectedIndices.isNotEmpty()) {
                    HorizontalDivider(color = colors.divider)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.QueuePanel.PaddingHorizontal, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onDownload != null) {
                            Text(
                                text = Strings["episode_download"],
                                color = colors.accent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                                    .clickableWithoutIndicationOrFocusRing(
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) {
                                        selectedIndices.forEach { idx ->
                                            if (idx in state.queue.indices) onDownload(state.queue[idx])
                                        }
                                        selectedIndices = setOf()
                                        isSelectionMode = false
                                    }
                            )
                            Spacer(Modifier.width(16.dp))
                        }
                        ToolbarPillButton(
                            icon = Icons.Default.Delete,
                            label = Strings["player_delete"],
                            contentDescription = Strings["player_delete"],
                            onClick = {
                                state.removeSelectedFromQueue(selectedIndices)
                                selectedIndices = setOf()
                                isSelectionMode = false
                            },
                            height = DesignTokens.QueuePanel.HeaderActionHeight,
                            radius = DesignTokens.QueuePanel.HeaderActionRadius,
                            borderWidth = DesignTokens.QueuePanel.HeaderActionBorderWidth,
                            horizontalPadding = DesignTokens.QueuePanel.HeaderActionPaddingHorizontal,
                            iconSize = DesignTokens.QueuePanel.HeaderActionIconSize,
                            iconTextGap = DesignTokens.QueuePanel.HeaderActionIconTextGap,
                            textSize = DesignTokens.QueuePanel.HeaderActionTextSize,
                            lineHeight = DesignTokens.QueuePanel.HeaderActionLineHeight,
                            minWidth = DesignTokens.QueuePanel.HeaderActionMinWidth,
                            iconColor = colors.danger,
                            hoverIconColor = colors.danger,
                            textColor = colors.danger,
                            hoverTextColor = colors.danger,
                            defaultBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassBackgroundColor,
                            hoverBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassHoverBackgroundColor,
                            defaultBorderColor = colors.danger,
                            hoverBorderColor = colors.danger
                        )
                    }
                }
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(DesignTokens.QueuePanel.HeaderCloseButtonMargin)
                .size(DesignTokens.QueuePanel.HeaderCloseButtonSize)
                .clip(CircleShape)
                .background(closeBackground)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                .clickableWithoutIndicationOrFocusRing(
                    interactionSource = closeInteractionSource,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = Strings["home_cancel"],
                tint = colors.textSecondary,
                modifier = Modifier.size(DesignTokens.QueuePanel.HeaderCloseIconSize)
            )
        }
    }
}

@Composable
private fun QueueEpisodeCard(
    item: QueueItem,
    isPlaying: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onSelectionToggle: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    trailingActions: @Composable RowScope.() -> Unit
) {
    val colors = PodaraTheme.colors
    val favoriteList = DesignTokens.FavoriteEpisodeList
    val queuePanel = DesignTokens.QueuePanel
    val interactionSource = remember { MutableInteractionSource() }
    val coverInteractionSource = remember { MutableInteractionSource() }
    val removeInteractionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isCoverHovered by coverInteractionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(favoriteList.CardRadius)
    val background by animateColorAsState(
        targetValue = when {
            isPlaying -> favoriteList.PlayingBackgroundColor
            isPressed -> favoriteList.PressedBackgroundColor
            isHovered -> favoriteList.HoverBackgroundColor
            else -> favoriteList.BackgroundColor
        },
        animationSpec = tween(DesignTokens.Animation.HoverMs),
        label = "queueCardBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isHovered -> favoriteList.HoverBorderColor
            else -> favoriteList.BorderColor
        },
        animationSpec = tween(DesignTokens.Animation.HoverMs),
        label = "queueCardBorder"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DesignTokens.QueuePanel.CardHeight)
            .then(
                if (isPlaying) {
                    Modifier.drawBehind {
                        drawLine(
                            color = colors.accent,
                            start = Offset(
                                queuePanel.PlayingIndicatorWidth.toPx() / 2f,
                                queuePanel.PlayingIndicatorInset.toPx()
                            ),
                            end = Offset(
                                queuePanel.PlayingIndicatorWidth.toPx() / 2f,
                                size.height - queuePanel.PlayingIndicatorInset.toPx()
                            ),
                            strokeWidth = queuePanel.PlayingIndicatorWidth.toPx()
                        )
                    }
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(background)
            .border(favoriteList.BorderWidth, borderColor, shape)
            .clickableWithoutIndicationOrFocusRing(
                interactionSource = interactionSource,
                onClick = onPlay
            )
            .padding(
                horizontal = queuePanel.CardPaddingHorizontal,
                vertical = queuePanel.CardPaddingVertical
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.accent else Color.Transparent)
                    .border(1.dp, if (isSelected) colors.accent else colors.border, CircleShape)
                    .clickableWithoutIndicationOrFocusRing(
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onSelectionToggle
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    // White on the accent disc, not the surface colour — a #F5F5F7
                    // tick on #FA2D48 was barely legible.
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = PodaraTheme.surfaces.buttonIcon,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(queuePanel.CoverContentGap))
        }

        Box(
            modifier = Modifier
                .size(queuePanel.CoverSize)
                .shadow(
                    elevation = favoriteList.CoverShadowElevation,
                    shape = RoundedCornerShape(queuePanel.CoverRadius),
                    ambientColor = favoriteList.CoverShadowColor,
                    spotColor = favoriteList.CoverShadowColor
                )
                .clip(RoundedCornerShape(queuePanel.CoverRadius))
                .hoverable(coverInteractionSource)
        ) {
            val displayArtworkUrl = item.artworkUrl?.takeIf { it.isNotBlank() }
                ?: item.podcastArtworkUrl?.takeIf { it.isNotBlank() }
            if (displayArtworkUrl != null) {
                AsyncImage(
                    model = displayArtworkUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Podcasts,
                    contentDescription = null,
                    tint = favoriteList.MetadataColor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(queuePanel.CoverSize * 0.45f)
                )
            }

            if (!isSelectionMode && isCoverHovered) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = -queuePanel.RemoveButtonInset, y = queuePanel.RemoveButtonInset)
                        .size(queuePanel.ActiveCoverBadge)
                        .background(PodaraTheme.surfaces.scrim.copy(alpha = 0.55f), CircleShape)
                        .clickableWithoutIndicationOrFocusRing(
                        interactionSource = removeInteractionSource,
                        onClick = onRemove
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = Strings["player_remove"],
                        tint = PodaraTheme.surfaces.buttonIcon,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(queuePanel.CoverContentGap))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = favoriteList.TitleColor,
                fontSize = queuePanel.TitleSize,
                lineHeight = queuePanel.TitleLineHeight,
                fontWeight = queuePanel.TitleWeight,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(queuePanel.SubtitleMarginTop))
                Text(
                    text = item.subtitle,
                    color = favoriteList.PodcastNameColor,
                    fontSize = queuePanel.SubtitleSize,
                    lineHeight = queuePanel.SubtitleLineHeight,
                    fontWeight = favoriteList.PodcastNameWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!isSelectionMode) {
            Spacer(modifier = Modifier.width(favoriteList.ContentActionsGap))
            Row(
                horizontalArrangement = Arrangement.spacedBy(favoriteList.ActionsGap),
                content = trailingActions
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    state: MediaPlayerState,
    onDismiss: () -> Unit
) {
    val options = listOf(5, 10, 15, 20, 30, 45, 60, 90)

    val sleepMinutes = state.sleepTimerMinutes

    PodaraDialog(
        onDismissRequest = onDismiss,
        title = { PodaraDialogTitle(Strings["player_sleep_timer"], textAlign = androidx.compose.ui.text.style.TextAlign.Start) },
        content = {
            Column {
                sleepMinutes?.let {
                    PodaraDialogBody(
                        text = Strings.get("player_sleep_timer_active", it),
                        color = PodaraTheme.colors.accent
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PodaraDialogActionButton(
                        label = Strings["player_cancel_timer"],
                        onClick = { state.cancelSleepTimer(); onDismiss() },
                        style = PodaraDialogActionStyle.Destructive,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                options.forEach { minutes ->
                    PodaraDialogActionButton(
                        label = Strings.get("player_minutes", minutes),
                        onClick = { state.setSleepTimer(minutes); onDismiss() },
                        style = PodaraDialogActionStyle.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        },
        actions = {
            PodaraDialogActionButton(Strings["dialog_close"], onDismiss, PodaraDialogActionStyle.Primary)
        }
    )
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
