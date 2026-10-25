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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
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
import app.podara.component.PodaraArtwork
import app.podara.component.PodaraDropdownMenu
import app.podara.component.PodaraDropdownMenuItem
import app.podara.component.PodaraDialog
import app.podara.component.PodaraDialogActionButton
import app.podara.component.PodaraDialogActionStyle
import app.podara.component.PodaraDialogBody
import app.podara.component.PodaraScrollbar
import app.podara.component.PodaraDialogTitle
import app.podara.component.PodaraTooltip
import app.podara.component.ToolbarPillButton
import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.DateFormat
import app.podara.util.Strings
import app.podara.util.TimeFormat
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.clickableWithoutIndication
import app.podara.util.clickableWithoutIndicationOrFocusRing
import app.podara.util.handCursor
import app.podara.util.pressScale
import app.podara.util.stripHtml
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Design Tokens: button.primary ──
// These mirror DesignTokens.Button but with the mini player's own geometry.
// Resolved per scheme: dark is the warm accent, light is the red-pink.
//
// This was a gradient, a sheen, a 1dp white border and an elevation shadow —
// a lit metal dome. The play button in particular read as a plastic token
// sitting in the middle of an otherwise flat transport row, and it was heavier
// than the play *icon* it contains. One flat fill, no sheen, no border.
private val PrimaryButtonFill: Color
    @Composable get() = PodaraTheme.surfaces.buttonFill
private val PrimaryButtonText: Color
    @Composable get() = PodaraTheme.surfaces.buttonText
private val PrimaryButtonIcon: Color
    @Composable get() = PodaraTheme.surfaces.buttonIcon

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

    // Hoisted because shadow, border and clip round the same corners; three
    // constructions let them drift apart.
    val cardShape = RoundedCornerShape(DesignTokens.Radius.Large)

    Surface(
        modifier = modifier.fillMaxWidth(),
        // Transparent, so the window's shell wash runs behind the mini player
        // instead of stopping at the content area's bottom edge. The bar used to
        // paint the page colour and then lay a sidebar-width block of surface
        // over the left of it, which was the old two-pane model showing through:
        // with the sidebar no longer a panel, that block was a stray rectangle.
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DesignTokens.Shell.Gutter,
                        end = DesignTokens.Shell.Gutter,
                        bottom = DesignTokens.Shell.Gutter
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DesignTokens.MiniPlayer.Height)
                        .shadow(
                        DesignTokens.Elevation.Raised,
                        cardShape,
                        ambientColor = surfaces.shadow,
                        spotColor = surfaces.shadow
                    )
                        .border(DesignTokens.Border.Width, surfaces.sheetBorder, cardShape)
                        .clip(cardShape)
                        .background(surfaces.sheetFill)
                    .let { mod ->
                        if (state.currentUrl != null) {
                            val bodyInteractionSource = remember { MutableInteractionSource() }
                            mod.clickableWithoutIndicationOrFocusRing(
                                interactionSource = bodyInteractionSource
                            ) { onBodyClick() }
                        } else mod
                    }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = DesignTokens.Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ── Left: Cover + Title + Podcast ──
                    Row(
                        modifier = Modifier.weight(0.9f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box {
                            // Visible loading cue while mpv is opening the source.
                            // Overlaid rather than swapping the play icon so the
                            // play/pause semantics stay put.
                            PodaraArtwork(
                                url = state.currentArtworkUrl,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(DesignTokens.Radius.Small),
                                loadingSpinnerSize = 20.dp,
                                busy = state.isLoading
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.currentTitle ?: Strings["player_no_playback"],
                                    color = colors.textPrimary,
                                    fontSize = DesignTokens.TypeScale.Body,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                // Restrained failure indicator: the mini bar has no
                                // room for a retry button — the details (message +
                                // retry) live in the full player.
                                if (state.error != null) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Outlined.ErrorOutline,
                                        contentDescription = Strings["player_error"],
                                        tint = colors.danger,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            if (state.currentSubtitle != null) {
                                Text(
                                    text = state.currentSubtitle!!,
                                    color = colors.textMuted,
                                    fontSize = DesignTokens.TypeScale.Micro,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // ── Center: Speed + Rewind + Play + Forward ──
                    // Bounded rather than fixed. The four controls inside are
                    // fixed size and add up to MiniPlayerTransport.MinWidth, so
                    // that is the floor; the old `width(260.dp)` was a *floor*
                    // too, which reserved the 28dp of slack against the title
                    // even when the window had none to spare. As a max it is
                    // given back first, while the weighted text columns either
                    // side keep their share of the rest.
                    Row(
                        modifier = Modifier.widthIn(
                            min = MiniPlayerTransport.MinWidth,
                            max = MiniPlayerTransport.MaxWidth
                        ),
                        horizontalArrangement = Arrangement.spacedBy(
                            MiniPlayerTransport.ControlGap,
                            Alignment.CenterHorizontally
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .height(MiniPlayerTransport.SpeedHeight)
                                    .width(MiniPlayerTransport.SpeedWidth)
                                    .clip(RoundedCornerShape(DesignTokens.Radius.Small))
                                    .clickableWithoutIndication { showSpeedMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${state.playbackSpeed}x",
                                    color = colors.textMuted,
                                    fontSize = DesignTokens.TypeScale.Body
                                )
                            }
                            // The themed menu, matching the FullPlayer speed menu below.
                            // Material's DropdownMenu painted surfaceContainer
                            // from the placeholder colour scheme, so it was the
                            // one control that ignored the token system.
                            PodaraDropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false },
                                items = MediaPlayerState.SPEED_PRESETS.map { speed ->
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

                        // The tooltip wrapper carries the control's incoming layout
                        // chain (size, cursor) so the transport row keeps the exact
                        // geometry and spacing it had before; the button inside
                        // restarts from a plain Modifier and is still constrained
                        // to the same box by the wrapper.
                        PodaraTooltip(
                            text = Strings["player_seek_back"],
                            modifier = Modifier.size(MiniPlayerTransport.SeekBackSize).handCursor()
                        ) {
                            IconButton(onClick = { state.seekBack() }) {
                                Icon(
                                    Icons.Outlined.Replay10,
                                    contentDescription = Strings["player_seek_back"],
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // Disabled until something is loaded, and it has to look
                        // disabled: the gradient, icon and hand cursor used to be
                        // identical either way, so the button simply ignored
                        // clicks with no explanation.
                        val canPlay = state.currentUrl != null
                        val playInteractionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .pressScale(playInteractionSource)
                                .size(MiniPlayerTransport.PlayPauseSize)
                                .alpha(if (canPlay) 1f else DisabledControlAlpha)
                                .shadow(DesignTokens.Elevation.Raised, CircleShape, ambientColor = PodaraTheme.surfaces.buttonShadow, spotColor = PodaraTheme.surfaces.buttonShadow)
                                .clip(CircleShape)
                                .background(PrimaryButtonFill)
                                .then(
                                    if (canPlay) {
                                        Modifier
                                            .clickableWithoutIndication(interactionSource = playInteractionSource) { state.togglePlayPause() }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            // 26dp, not 40dp. At 40dp inside a 56dp disc the
                            // triangle filled 71% of the circle and optically
                            // drifted right, because a triangle's visual centre
                            // is not its bounding-box centre. The seek buttons
                            // either side use ~46%; the main control was the
                            // densest thing in the row.
                            PlayPauseGlyph(
                                isPlaying = state.isPlaying,
                                contentDescription = if (state.isPlaying) Strings["player_pause"] else Strings["player_play"],
                                tint = PrimaryButtonIcon,
                                size = MiniPlayerTransport.PlayPauseIconSize
                            )
                        }

                        PodaraTooltip(
                            text = Strings["player_seek_forward"],
                            modifier = Modifier.size(MiniPlayerTransport.SeekForwardSize).handCursor()
                        ) {
                            IconButton(onClick = { state.seekForward() }) {
                                Icon(
                                    Icons.Outlined.Forward10,
                                    contentDescription = Strings["player_seek_forward"],
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }

                    // ── Right: Time + Slider + Volume + Queue + Fullscreen ──
                    Row(
                        modifier = Modifier.weight(1f).padding(start = DesignTokens.Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)
                    ) {
                        Text(
                            text = TimeFormat.clock(if (isDragged) (sliderPosition * state.duration).toLong() else state.currentPosition),
                            color = colors.textMuted,
                            fontSize = DesignTokens.TypeScale.Caption,
                            style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures)
                        )

                        Slider(
                            value = sliderPosition,
                            onValueChange = { dragPosition = it },
                            onValueChangeFinished = {
                                state.seek((dragPosition * state.duration).toLong())
                            },
                            modifier = Modifier.weight(1f).height(20.dp),
                            thumb = {
                                // Same solid dot as the full player's timeline,
                                // one size down.
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(PodaraTheme.surfaces.sliderThumb)
                                        .border(2.dp, PodaraTheme.surfaces.sliderThumbRing, CircleShape)
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
                            text = TimeFormat.clock(state.duration),
                            color = colors.textMuted,
                            fontSize = DesignTokens.TypeScale.Caption,
                            style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures)
                        )

                        // One string for both the accessible name and the tooltip,
                        // so the two cannot drift apart.
                        val muteDescription =
                            if (state.volume > 0) Strings["player_mute"] else Strings["player_unmute"]
                        PodaraTooltip(
                            text = muteDescription,
                            modifier = Modifier.size(32.dp).handCursor()
                        ) {
                            IconButton(onClick = { state.toggleMute() }) {
                                Icon(
                                    if (state.volume > 0) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                                    contentDescription = muteDescription,
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        PodaraTooltip(
                            text = Strings["player_queue"],
                            modifier = Modifier.size(32.dp).handCursor()
                        ) {
                            IconButton(onClick = onShowQueue) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.QueueMusic,
                                    contentDescription = Strings["player_queue"],
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        PodaraTooltip(
                            text = Strings["player_expand"],
                            modifier = Modifier.size(32.dp).handCursor(enabled = state.currentUrl != null)
                        ) {
                            IconButton(
                                onClick = { if (state.currentUrl != null) onExpand() },
                                enabled = state.currentUrl != null
                            ) {
                                Icon(
                                    Icons.Outlined.Fullscreen,
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

    // The timeline slider's interaction source, shared between the Slider and
    // the drag flag below — the same arrangement the mini player uses.
    val timelineInteractionSource = remember { MutableInteractionSource() }

    // Derived from the slider's drag interactions rather than hand-maintained.
    //
    // `isDragging` used to be a boolean set true in onValueChange and false in
    // onValueChangeFinished, which is fine only if every drag ends in a release.
    // They do not: cancelling the gesture (alt-tab away mid-drag, a parent
    // stealing the pointer) emits DragInteraction.Cancel and never calls
    // onValueChangeFinished, so the flag stuck on and the slider sat frozen at
    // the last dragged position while the episode played on. Deriving it means
    // Stop and Cancel both clear it, and the mini player and this one agree.
    val isDragging by timelineInteractionSource.collectIsDraggedAsState()

    var showSleepTimer by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }

    // ── Episode data from DB ──
    var currentEpisode by remember { mutableStateOf<PodcastEpisode?>(null) }
    var currentOrigin by remember { mutableStateOf<String?>(null) }
    var favoriteIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(favoriteVersion) {
        favoriteIds = database.favorites.getAllEpisodeIds()
    }

    LaunchedEffect(state.currentEpisodeId) {
        // Clear first. When playback stops the id becomes null, and when it moves
        // to an episode the database does not know (an ad-hoc stream, an id from
        // a build that stored them differently) the lookup returns null — either
        // way the previous episode's notes, download and favourite controls must
        // not stay on screen describing a different track.
        currentEpisode = null
        currentOrigin = null
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
                .padding(top = DesignTokens.Spacing.lg, start = 28.dp, end = 28.dp, bottom = DesignTokens.Spacing.lg)
        ) {
            // ── Top bar: Close (left) + Queue (right) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarPillButton(
                    icon = Icons.Outlined.KeyboardArrowDown,
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
                // The chapter entry sits beside the queue one and only exists
                // when the loaded file actually has chapters — a button that
                // opens an empty list is noise.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.chapters.isNotEmpty()) {
                        ToolbarPillButton(
                            icon = Icons.Outlined.FormatListNumbered,
                            label = "",
                            contentDescription = Strings["player_chapters"],
                            onClick = { showChapters = true },
                            iconColor = colors.textPrimary,
                            hoverIconColor = PodaraTheme.surfaces.pillIconHover,
                            defaultBackgroundColor = PodaraTheme.surfaces.iconButtonFill,
                            defaultBorderColor = PodaraTheme.surfaces.iconButtonBorder,
                            hoverBackgroundColor = PodaraTheme.surfaces.iconButtonFillHover,
                            hoverBorderColor = PodaraTheme.surfaces.pillBorderHover
                        )
                    }
                    ToolbarPillButton(
                        icon = Icons.AutoMirrored.Outlined.QueueMusic,
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
            }

            Spacer(modifier = Modifier.height(DesignTokens.Spacing.lg))

            // ══════════════════════════════════════
            // 1. HEADER: Cover + Episode Info
            // ══════════════════════════════════════
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = DesignTokens.Spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.lg),
                verticalAlignment = Alignment.Top
            ) {
                // Cover 208dp radius:12
                Box(modifier = Modifier.width(208.dp)) {
                    // Same loading cue as the mini player: overlaid on the
                    // artwork so nothing in the control row shifts.
                    PodaraArtwork(
                        url = state.currentArtworkUrl,
                        contentDescription = null,
                        modifier = Modifier.size(208.dp),
                        shape = RoundedCornerShape(DesignTokens.Radius.Medium),
                        loadingSpinnerSize = 32.dp,
                        loadingStrokeWidth = 3.dp,
                        busy = state.isLoading
                    )
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
                            fontSize = DesignTokens.TypeScale.BodyStrong,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Episode Title
                    Text(
                        text = state.currentTitle ?: "",
                        fontSize = DesignTokens.TypeScale.Heading,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = DesignTokens.TypeScale.LineHeight.Heading
                    )

                    // Episode Description
                    val episodeDesc = currentEpisode?.description
                    if (!episodeDesc.isNullOrBlank()) {
                        Text(
                            text = stripHtml(episodeDesc),
                            fontSize = DesignTokens.TypeScale.Body,
                            lineHeight = DesignTokens.TypeScale.LineHeight.BodyText,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 500.dp)
                        )
                    }

                    // Metadata Row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val pubDate = currentEpisode?.pubDate ?: 0L
                        if (pubDate > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                                Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp), tint = DesignTokens.IconInk.Resting)
                                Text(text = formatDate(pubDate), fontSize = DesignTokens.TypeScale.Body, color = colors.textMuted, style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures))
                            }
                        }

                        val dur = currentEpisode?.duration ?: 0
                        if (dur > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)) {
                                Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(14.dp), tint = DesignTokens.IconInk.Resting)
                                Text(text = TimeFormat.duration(dur.toLong()), fontSize = DesignTokens.TypeScale.Body, color = colors.textMuted, style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures))
                            }
                        }
                    }

                    // Action Buttons
                    val origin = currentOrigin
                    // Keyed on the episode, not on position: the "more" menu
                    // holds a snapshot of the episode it was opened for (its
                    // origin, its audio URL), so leaving it remembered across a
                    // track change meant the menu stayed open and its "copy link"
                    // action handed back the *new* episode's URL.
                    var showMore by remember(state.currentEpisodeId) { mutableStateOf(false) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
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
                                radius = DesignTokens.Radius.Medium,
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
                            icon = Icons.Outlined.MoreHoriz,
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
                                    icon = Icons.Outlined.ContentCopy,
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

            Spacer(modifier = Modifier.height(DesignTokens.Spacing.lg))

            // ══════════════════════════════════════
            // 2. TIMELINE
            // ══════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)
            ) {
                Slider(
                    value = sliderPosition,
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = { state.seek((dragPosition * state.duration).toLong()) },
                    modifier = Modifier.fillMaxWidth().height(20.dp),
                    // The drag flag is observed from this source; if it is not
                    // wired to the Slider itself the flag never moves at all.
                    interactionSource = timelineInteractionSource,
                    thumb = {
                        // One solid dot with a hairline ring, not a 15dp disc
                        // holding a 2dp accent border, a 2dp inset and a white
                        // core. At track height the donut read as a shrunken
                        // copy of the play button.
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(PodaraTheme.surfaces.sliderThumb)
                                .border(2.dp, PodaraTheme.surfaces.sliderThumbRing, CircleShape)
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
                    Text(text = TimeFormat.clock(if (isDragging) (sliderPosition * state.duration).toLong() else state.currentPosition), color = colors.textMuted, fontSize = DesignTokens.TypeScale.Caption, style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures))
                    Text(text = TimeFormat.clock(state.duration), color = colors.textMuted, fontSize = DesignTokens.TypeScale.Caption, style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures))
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
                        radius = DesignTokens.Radius.Small,
                        minWidth = 40.dp,
                        horizontalPadding = DesignTokens.Spacing.sm,
                        textSize = DesignTokens.TypeScale.Body,
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
                        items = MediaPlayerState.SPEED_PRESETS.map { speed ->
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

                // The two audio enhancement toggles, beside the speed control
                // they share the row with. Preferences, not transport state:
                // they stay clickable with nothing loaded.
                Spacer(modifier = Modifier.width(DesignTokens.Spacing.sm))

                EnhancementTogglePill(
                    icon = Icons.Outlined.ContentCut,
                    label = Strings["player_skip_silence"],
                    active = state.skipSilence,
                    onClick = { state.changeSkipSilence(!state.skipSilence) }
                )

                Spacer(modifier = Modifier.width(DesignTokens.Spacing.sm))

                EnhancementTogglePill(
                    icon = Icons.Outlined.Equalizer,
                    label = Strings["player_loudness_boost"],
                    active = state.loudnessBoost,
                    onClick = { state.changeLoudnessBoost(!state.loudnessBoost) }
                )

                Spacer(modifier = Modifier.width(DesignTokens.Spacing.md))

                CircleControlButton(size = 48.dp, icon = Icons.Outlined.Replay10, contentDescription = Strings["player_seek_back"], tint = colors.textMuted, onClick = { state.seekBack() })

                Spacer(modifier = Modifier.width(DesignTokens.Spacing.md))

                // Play/Pause 56dp (matching MiniPlayer). Flat fill, no sheen,
                // no border — see the note on PrimaryButtonFill.
                val fullPlayInteractionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .pressScale(fullPlayInteractionSource)
                        .size(56.dp)
                        .shadow(DesignTokens.Elevation.Raised, CircleShape, ambientColor = PodaraTheme.surfaces.buttonShadow, spotColor = PodaraTheme.surfaces.buttonShadow)
                        .clip(CircleShape)
                        .background(PrimaryButtonFill)
                        .clickableWithoutIndication(
                            interactionSource = fullPlayInteractionSource,
                            enabled = state.currentUrl != null
                        ) { state.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    PlayPauseGlyph(
                        isPlaying = state.isPlaying,
                        contentDescription = if (state.isPlaying) Strings["player_pause"] else Strings["player_play"],
                        tint = PrimaryButtonIcon,
                        size = DesignTokens.MiniPlayer.PlayPause.IconSize
                    )
                }

                Spacer(modifier = Modifier.width(DesignTokens.Spacing.md))

                CircleControlButton(size = 40.dp, icon = Icons.Outlined.Forward10, contentDescription = Strings["player_seek_forward"], tint = colors.textMuted, onClick = { state.seekForward() })

                Spacer(modifier = Modifier.width(DesignTokens.Spacing.md))

                CircleControlButton(size = 40.dp, icon = Icons.Outlined.Timer, contentDescription = Strings["player_sleep_timer"], tint = colors.textMuted, onClick = { showSleepTimer = true })
            }

            Spacer(modifier = Modifier.height(DesignTokens.Spacing.lg))

            // ══════════════════════════════════════
            // 4. PLAYBACK FAILURE
            // Inline error bar — a plain Row inside the player's own scrollable
            // column, not an overlay, so the full-screen coverage invariants the
            // overlay tests pin down are untouched.
            // ══════════════════════════════════════
            val playbackError = playbackErrorMessage(state.error)
            if (playbackError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DesignTokens.Radius.Medium))
                        .background(colors.surface)
                        .border(DesignTokens.Border.Width, colors.border, RoundedCornerShape(DesignTokens.Radius.Medium))
                        .padding(horizontal = DesignTokens.Spacing.md, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = colors.danger,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = playbackError,
                        color = colors.textSecondary,
                        fontSize = DesignTokens.TypeScale.Body,
                        lineHeight = DesignTokens.TypeScale.LineHeight.BodyRow,
                        modifier = Modifier.weight(1f)
                    )
                    ToolbarPillButton(
                        icon = Icons.Outlined.Refresh,
                        label = Strings["player_error_retry"],
                        contentDescription = Strings["player_error_retry"],
                        onClick = { state.retry() },
                        height = 30.dp,
                        radius = DesignTokens.Radius.Small,
                        iconColor = colors.accent,
                        hoverIconColor = colors.accentHover,
                        textColor = colors.accent,
                        hoverTextColor = colors.accentHover,
                        defaultBackgroundColor = PodaraTheme.surfaces.iconButtonFill,
                        defaultBorderColor = colors.accent,
                        hoverBackgroundColor = PodaraTheme.surfaces.iconButtonFillHover,
                        hoverBorderColor = colors.accentHover
                    )
                }
                Spacer(modifier = Modifier.height(DesignTokens.Spacing.lg))
            }

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
                            .clip(RoundedCornerShape(DesignTokens.Radius.Medium))
                            .background(colors.surface)
                            .border(DesignTokens.Border.Width, colors.border, RoundedCornerShape(DesignTokens.Radius.Medium))
                            .padding(horizontal = DesignTokens.Spacing.lg, vertical = DesignTokens.Spacing.lg)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            Text(
                                text = Strings["player_episode_notes"],
                                fontSize = DesignTokens.TypeScale.Heading,
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

                Spacer(modifier = Modifier.height(DesignTokens.Spacing.lg))
                HorizontalDivider(color = colors.divider)
                Spacer(modifier = Modifier.height(DesignTokens.Spacing.lg))
            }
        }

        // Chapter drawer, hosted inside the full player the way the queue
        // drawer is hosted by the app: it covers the player and dismisses on
        // its own scrim. Gated on the list twice — the flag AND the data — so
        // a track change to a chapterless file closes an open panel.
        if (showChapters && state.chapters.isNotEmpty()) {
            ChaptersPanel(
                chapters = state.chapters,
                currentIndex = state.currentChapterIndex,
                onSeek = { positionMs -> state.seek(positionMs) },
                onDismiss = { showChapters = false }
            )
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
    val shape = RoundedCornerShape(DesignTokens.Radius.Medium)

    Box(
        modifier = Modifier
            // widthIn rather than a fixed 108.dp: the button holds an icon plus
            // "Download" / "Downloaded", and 108.dp only fit the English labels.
            // A longer translation ("Heruntergeladen") was clipped mid-glyph.
            // The min keeps a stable target size; the max stops a short label
            // from stretching the control row. Horizontal padding lives on the
            // content row instead of here, because this Box also paints the
            // gradient and would otherwise paint it inset.
            .widthIn(min = 108.dp, max = 180.dp)
            .height(40.dp)
            .shadow(
                if (isHovered) 10.dp else 6.dp,
                shape,
                ambientColor = PodaraTheme.surfaces.buttonShadow,
                spotColor = PodaraTheme.surfaces.buttonShadow
            )
            .clip(shape)
            .background(if (isDownloaded) SolidColor(colors.success) else SolidColor(PrimaryButtonFill))
            .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { if (!isDownloaded) onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (isDownloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                contentDescription = null,
                tint = PrimaryButtonIcon,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (isDownloaded) Strings["episode_downloaded"] else Strings["episode_download"],
                fontSize = DesignTokens.TypeScale.Body,
                fontWeight = FontWeight.Medium,
                color = PrimaryButtonText
            )
        }
    }
}

/**
 * The glyph inside a round play control.
 *
 * `Icons.Default.PlayArrow` is a Material icon whose triangle sits inside a
 * 24x24 viewport with asymmetric padding — drawn dead-centre it reads as
 * shifted left, because a triangle's visual mass is left of its bounding box.
 * The seek icons either side are circular arrows, which are optically centred
 * already, so the play button was the one control in the row that looked
 * off-centre.
 *
 * The fix is a small rightward nudge, applied only to the play triangle (pause
 * is two symmetric bars and needs none).
 */
@Composable
private fun PlayPauseGlyph(
    isPlaying: Boolean,
    contentDescription: String,
    tint: Color,
    size: androidx.compose.ui.unit.Dp
) {
    // Deliberately not wrapped in a PodaraTooltip: play and pause are the two
    // glyphs in this app that need no translation, and a bubble over the largest
    // control in the window would only be noise. It is also the one control whose
    // hover target is the disc around the glyph, not the glyph itself.
    Icon(
        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(size)
            .offset(x = if (isPlaying) 0.dp else size * 0.06f)
    )
}

@Composable
private fun CircleControlButton(
    size: androidx.compose.ui.unit.Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val surfaces = PodaraTheme.surfaces
    val backgroundColor by animateHoverBackgroundColor(isHovered, surfaces.cardFillHover)
    val iconColor by animateColorAsState(
        targetValue = if (isHovered) PodaraTheme.colors.textPrimary else tint,
        animationSpec = tween(DesignTokens.Animation.HoverMs, easing = DesignTokens.Animation.StandardEasing),
        label = "playerControlIcon"
    )

    // Wrapped here rather than at the call sites: the seek and sleep-timer
    // controls are the same primitive, so one wrap gives every use a tooltip and
    // a per-call-site wrap would nest them. The description is now non-null
    // precisely because it doubles as the tooltip text — a control with no name
    // has nothing to show.
    PodaraTooltip(text = contentDescription) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(backgroundColor)
                .handCursor(),
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
}

/**
 * One of the two audio enhancement toggles (Skip Silence, Loudness Boost) in
 * the full player's control row.
 *
 * A pill matching the speed selector beside it; the active state is carried by
 * the accent colours rather than a different shape, the same way the retry
 * button marks itself out. The label doubles as the contentDescription —
 * there is no icon-only state to name separately, and a labelled pill gets no
 * tooltip from ToolbarPillButton, which is the pattern every text pill in the
 * player already follows.
 */
@Composable
private fun EnhancementTogglePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val colors = PodaraTheme.colors
    ToolbarPillButton(
        icon = icon,
        label = label,
        contentDescription = label,
        onClick = onClick,
        height = 30.dp,
        radius = DesignTokens.Radius.Small,
        horizontalPadding = DesignTokens.Spacing.sm,
        textSize = DesignTokens.TypeScale.Body,
        iconColor = if (active) colors.accent else colors.textPrimary,
        hoverIconColor = if (active) colors.accentHover else PodaraTheme.surfaces.pillIconHover,
        textColor = if (active) colors.accent else colors.textPrimary,
        hoverTextColor = if (active) colors.accentHover else PodaraTheme.surfaces.pillTextHover,
        defaultBackgroundColor = PodaraTheme.surfaces.iconButtonFill,
        defaultBorderColor = if (active) colors.accent else PodaraTheme.surfaces.iconButtonBorder,
        hoverBackgroundColor = PodaraTheme.surfaces.iconButtonFillHover,
        hoverBorderColor = if (active) colors.accentHover else PodaraTheme.surfaces.pillBorderHover
    )
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
 * Opacity of a control that exists but cannot be used yet.
 *
 * The play button was already correctly disabled when nothing was loaded, but it
 * looked identical to the enabled state — same gradient, same hand cursor — so a
 * click that did nothing was indistinguishable from a bug. 0.45 reads as
 * unavailable without making the icon illegible.
 */
private const val DisabledControlAlpha = 0.45f

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
        // A time readout, so it gets the tabular figures the rest of the
        // player's clocks use; the prose around it stays proportional.
        pushStyle(
            SpanStyle(
                color = timestampColor,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures
            )
        )
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

// Local alias so the call site above reads unchanged; the implementation is
// shared with the episode list, which previously formatted the same date as
// `yyyy-MM-dd` here and `MMM dd, yyyy` in this file.
private fun formatDate(timestamp: Long): String = DateFormat.episode(timestamp)

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
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)
                    ) {
                        ToolbarPillButton(
                            icon = if (isSelectionMode) Icons.Outlined.Close else Icons.Outlined.CheckCircle,
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
                            iconColor = DesignTokens.IconInk.Resting,
                            hoverIconColor = colors.textPrimary,
                            defaultBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassBackgroundColor,
                            hoverBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassHoverBackgroundColor,
                            // A hairline, not the accent. An accent-coloured
                            // 1dp border on a 28dp pill reads as an unfinished
                            // skeleton screen, and it put the accent on a
                            // control that is not the primary action. The
                            // accent is left to the icon only.
                            defaultBorderColor = PodaraTheme.surfaces.pillBorder,
                            hoverBorderColor = PodaraTheme.surfaces.pillBorderHover
                        )
                        ToolbarPillButton(
                            icon = Icons.Outlined.ClearAll,
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
                            iconColor = DesignTokens.IconInk.Resting,
                            hoverIconColor = colors.textPrimary,
                            defaultBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassBackgroundColor,
                            hoverBackgroundColor = DesignTokens.QueuePanel.HeaderActionGlassHoverBackgroundColor,
                            // A hairline, not the accent. An accent-coloured
                            // 1dp border on a 28dp pill reads as an unfinished
                            // skeleton screen, and it put the accent on a
                            // control that is not the primary action. The
                            // accent is left to the icon only.
                            defaultBorderColor = PodaraTheme.surfaces.pillBorder,
                            hoverBorderColor = PodaraTheme.surfaces.pillBorderHover
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Queue list ──
                // LazyColumn rather than Column + verticalScroll: a 500-episode
                // queue composed every card up front, drag detectors included,
                // for the handful actually visible.
                val queueScrollState = rememberLazyListState()
                if (state.queue.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = Strings["player_queue_empty"],
                            color = colors.textMuted,
                            fontSize = DesignTokens.TypeScale.Body
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                    LazyColumn(
                        state = queueScrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = DesignTokens.QueuePanel.PaddingHorizontal,
                                end = DesignTokens.QueuePanel.PaddingHorizontal,
                                top = favoriteList.ListPaddingTop,
                                bottom = favoriteList.ListPaddingBottom
                            ),
                        verticalArrangement = Arrangement.spacedBy(queuePanel.CardGap)
                    ) {
                        itemsIndexed(state.queue, key = { _, item -> item.episodeId ?: item.url }, contentType = { _, _ -> "queue_card" }) { index, item ->
                            val isActive = index == state.queueIndex && state.currentUrl != null
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
                                    // The row's `align` and the handle's optical
                                    // nudge are layout, so they move onto the
                                    // tooltip wrapper; the icon keeps its size and
                                    // its drag detector. The icon's own
                                    // contentDescription stays null — the string is
                                    // the tooltip's, not a new accessible name.
                                    PodaraTooltip(
                                        text = Strings["player_queue_drag"],
                                        modifier = Modifier
                                            .align(Alignment.CenterVertically)
                                            .offset(y = queuePanel.DragHandleVerticalOffset)
                                    ) {
                                        Icon(
                                            Icons.Outlined.DragHandle,
                                            contentDescription = null,
                                            tint = favoriteList.ActionIconColor,
                                            modifier = Modifier
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
                                                            // Guard the empty queue: `coerceIn(0, lastIndex)` throws
                                                            // "Cannot coerce value to an empty range" when lastIndex is
                                                            // -1. The queue can empty out between composition and a drag
                                                            // that is already in flight (a context switch, "clear queue",
                                                            // or the engine finishing the last item).
                                                            val lastIndex = state.queue.lastIndex
                                                            dragTargetIndex = if (lastIndex < 0) {
                                                                index
                                                            } else {
                                                                (index + offsetRows).coerceIn(0, lastIndex)
                                                            }
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
                                }
                            )
                        }
                    }
                    PodaraScrollbar(
                        adapter = rememberScrollbarAdapter(queueScrollState)
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
                                fontSize = DesignTokens.TypeScale.Body,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
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
                            Spacer(Modifier.width(DesignTokens.Spacing.md))
                        }
                        ToolbarPillButton(
                            icon = Icons.Outlined.Delete,
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
        // Icon-only dismiss: its position in the overlay (`align` + the margin
        // that keeps it off the corner) is layout and moves to the wrapper, while
        // the disc keeps its own size and visual chain so the painted circle is
        // exactly where it was.
        PodaraTooltip(
            text = Strings["home_cancel"],
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(DesignTokens.QueuePanel.HeaderCloseButtonMargin)
        ) {
            Box(
                modifier = Modifier
                    .size(DesignTokens.QueuePanel.HeaderCloseButtonSize)
                    .clip(CircleShape)
                    .background(closeBackground)
                    .clickableWithoutIndicationOrFocusRing(
                        interactionSource = closeInteractionSource,
                        onClick = onDismiss
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = Strings["home_cancel"],
                    tint = colors.textSecondary,
                    modifier = Modifier.size(DesignTokens.QueuePanel.HeaderCloseIconSize)
                )
            }
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
        animationSpec = tween(DesignTokens.Animation.HoverMs, easing = DesignTokens.Animation.StandardEasing),
        label = "queueCardBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isHovered -> favoriteList.HoverBorderColor
            else -> favoriteList.BorderColor
        },
        animationSpec = tween(DesignTokens.Animation.HoverMs, easing = DesignTokens.Animation.StandardEasing),
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
                        Icons.Outlined.Check,
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
            PodaraArtwork(
                url = item.artworkUrl?.takeIf { it.isNotBlank() } ?: item.podcastArtworkUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(queuePanel.CoverRadius)
            )

            if (!isSelectionMode && isCoverHovered) {
                // Icon-only remove: the badge's corner placement (`align`) and its
                // inset `offset` are layout and move onto the tooltip wrapper, so
                // it still sits exactly where it did. The disc keeps size, fill and
                // click. Its visible hover state is the cover's own, not a
                // tooltip's, so both fire together and say the same thing.
                PodaraTooltip(
                    text = Strings["player_remove"],
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = -queuePanel.RemoveButtonInset, y = queuePanel.RemoveButtonInset)
                ) {
                    Box(
                        modifier = Modifier
                            .size(queuePanel.ActiveCoverBadge)
                            .background(PodaraTheme.surfaces.scrim.copy(alpha = 0.55f), CircleShape)
                            .clickableWithoutIndicationOrFocusRing(
                                interactionSource = removeInteractionSource,
                                onClick = onRemove
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = Strings["player_remove"],
                            tint = PodaraTheme.surfaces.buttonIcon,
                            modifier = Modifier.size(10.dp)
                        )
                    }
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
                    Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
                    PodaraDialogActionButton(
                        label = Strings["player_cancel_timer"],
                        onClick = { state.cancelSleepTimer(); onDismiss() },
                        style = PodaraDialogActionStyle.Destructive,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
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

/**
 * Maps a PlaybackErrorCategory reported by the engine to user-facing copy.
 * null (no failure) maps to null so callers can guard with a plain `if`.
 */
@Composable
private fun playbackErrorMessage(category: String?): String? = when (category) {
    null -> null
    PlaybackErrorCategory.NETWORK -> Strings["player_error_network"]
    PlaybackErrorCategory.FILE -> Strings["player_error_file"]
    PlaybackErrorCategory.UNSUPPORTED -> Strings["player_error_unsupported"]
    else -> Strings["player_error_generic"]
}