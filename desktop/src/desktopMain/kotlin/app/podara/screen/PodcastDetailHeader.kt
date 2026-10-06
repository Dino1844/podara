package app.podara.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podara.component.PodaraDropdownMenu
import app.podara.component.PodaraDropdownMenuItem
import app.podara.data.model.Podcast
import app.podara.stripHtml
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.clickableWithoutIndication
import app.podara.util.clickableWithoutIndicationOrFocusRing
import coil3.compose.AsyncImage
import java.awt.Cursor

/**
 * The podcast detail header and the compact bar it collapses into.
 *
 * The header (cover, title, author, description, actions) used to be pinned
 * above the episode list, so it stayed on screen forever and ate a quarter of
 * the window. It now scrolls away as the first item of the episode list, and
 * the top bar takes over its context: the title fades in as the header leaves,
 * and the same three actions (play latest, subscribe, more) appear once the
 * header is mostly gone — hiding the information without hiding the functions.
 */

/**
 * How far the list item at [index] has scrolled off, 0 = fully visible, 1 =
 * fully gone.
 *
 * When the list is not laid out — loading, or the empty state — this reports
 * fully collapsed, so the compact bar shows the title while there is no header
 * to do it.
 */
@Composable
internal fun rememberScrollOffProgress(listState: LazyListState, index: Int = 0): State<Float> =
    remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > index) {
                1f
            } else {
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                when {
                    info == null -> 1f
                    info.size <= 0 -> 0f
                    else -> (listState.firstVisibleItemScrollOffset.toFloat() / info.size).coerceIn(0f, 1f)
                }
            }
        }
    }

/**
 * The bar at the top of the podcast detail screen: back button always, title
 * and [actions] faded in by [collapseProgress].
 *
 * The title is alpha-scrubbed rather than composed conditionally — it is not
 * interactive, so a half-faded title is harmless. The actions are composed
 * conditionally instead: an invisible button still receives clicks, and a
 * subscribe button the user cannot see must not be clickable.
 */
@Composable
internal fun PodcastDetailTopBar(
    title: String,
    collapseProgress: Float,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    val colors = PodaraTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val backInteractionSource = remember { MutableInteractionSource() }
            val isBackHovered by backInteractionSource.collectIsHoveredAsState()
            val backAnimatedBg by animateHoverBackgroundColor(isBackHovered, colors.elevated)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(backAnimatedBg)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                    .clickableWithoutIndicationOrFocusRing(interactionSource = backInteractionSource) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = Strings["nav_back"],
                    tint = colors.textPrimary
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alpha(collapseProgress)
            )
            AnimatedVisibility(
                visible = collapseProgress > COLLAPSED_ACTIONS_THRESHOLD,
                enter = fadeIn(tween(DesignTokens.Animation.HoverMs)),
                exit = fadeOut(tween(DesignTokens.Animation.HoverMs))
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
        }
        // A rule under the bar appears as the page collapses, separating the
        // now-persistent chrome from the content scrolling beneath it.
        HorizontalDivider(color = colors.divider, modifier = Modifier.alpha(collapseProgress))
    }
}

/** The header is mostly gone by the time the compact actions take over. */
private const val COLLAPSED_ACTIONS_THRESHOLD = 0.6f

/**
 * The full podcast header: cover, title, author, two-line description and the
 * action row. Designed to be the first item of the episode list so it scrolls
 * away with the content instead of pinning above it.
 */
@Composable
internal fun PodcastDetailHeader(
    podcast: Podcast,
    isSubscribed: Boolean,
    autoDownloadEnabled: Boolean,
    onToggleAutoDownload: () -> Unit,
    onPlayLatest: () -> Unit,
    onToggleSubscribe: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PodaraTheme.colors
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 44.dp, top = 16.dp, end = 32.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = podcast.imageUrl,
                contentDescription = podcast.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(180.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(modifier = Modifier.width(20.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = podcast.fetchTitle(),
                    color = colors.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (podcast.author.isNotEmpty()) {
                    Text(
                        text = podcast.author,
                        color = colors.textSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (podcast.description.isNotEmpty()) {
                    Text(
                        text = stripHtml(podcast.description),
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PodcastDetailActions(
                        isSubscribed = isSubscribed,
                        autoDownloadEnabled = autoDownloadEnabled,
                        onToggleAutoDownload = onToggleAutoDownload,
                        onPlayLatest = onPlayLatest,
                        onToggleSubscribe = onToggleSubscribe,
                        rssUrl = podcast.origin
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 32.dp))
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/**
 * Play latest, subscribe, more. Shared by the full header and the collapsed
 * top bar, so both rows always offer the same three actions.
 *
 * Emits three siblings; call it inside a [Row].
 */
@Composable
internal fun RowScope.PodcastDetailActions(
    isSubscribed: Boolean,
    autoDownloadEnabled: Boolean,
    onToggleAutoDownload: () -> Unit,
    onPlayLatest: () -> Unit,
    onToggleSubscribe: () -> Unit,
    rssUrl: String
) {
    PlayLatestPill(onClick = onPlayLatest)
    SubscribeToggleButton(isSubscribed = isSubscribed, onToggle = onToggleSubscribe)
    RssCopyMenuButton(
        rssUrl = rssUrl,
        showAutoDownload = isSubscribed,
        autoDownloadEnabled = autoDownloadEnabled,
        onToggleAutoDownload = onToggleAutoDownload
    )
}

@Composable
private fun PlayLatestPill(onClick: () -> Unit) {
    val btn = DesignTokens.Button
    Box(
        modifier = Modifier
            .height(btn.Height)
            .shadow(btn.ShadowElevation, RoundedCornerShape(btn.Radius), ambientColor = btn.ShadowColor, spotColor = btn.ShadowColor)
            .clip(RoundedCornerShape(btn.Radius))
            .border(DesignTokens.Border.Width, btn.BorderColor, RoundedCornerShape(btn.Radius))
            .background(btn.Gradient)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .clickableWithoutIndication { onClick() },
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
}

@Composable
private fun SubscribeToggleButton(isSubscribed: Boolean, onToggle: () -> Unit) {
    val colors = PodaraTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        isSubscribed -> colors.accent.copy(alpha = 0.15f)
        isHovered -> colors.elevated
        else -> colors.surface
    }
    Box(
        modifier = Modifier
            .size(DesignTokens.IconButton.Size)
            .clip(CircleShape)
            .border(DesignTokens.Border.Width, DesignTokens.Border.SecondaryColor, CircleShape)
            .background(background)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isSubscribed) Icons.Default.Check else Icons.Default.Add,
            contentDescription = if (isSubscribed) Strings["discover_added"] else Strings["discover_add"],
            tint = if (isSubscribed) colors.accent else colors.textSecondary,
            modifier = Modifier.size(DesignTokens.IconButton.IconSize)
        )
    }
}

@Composable
private fun RssCopyMenuButton(
    rssUrl: String,
    showAutoDownload: Boolean,
    autoDownloadEnabled: Boolean,
    onToggleAutoDownload: () -> Unit
) {
    val colors = PodaraTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = if (isHovered) colors.elevated else colors.surface
    var showPopup by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(DesignTokens.IconButton.Size)
            .clip(CircleShape)
            .border(DesignTokens.Border.Width, DesignTokens.Border.SecondaryColor, CircleShape)
            .background(background)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { showPopup = true },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MoreHoriz, contentDescription = Strings["discover_more"], tint = colors.textSecondary, modifier = Modifier.size(DesignTokens.IconButton.IconSize))

        PodaraDropdownMenu(
            expanded = showPopup,
            onDismissRequest = { showPopup = false },
            items = buildList {
                // Auto-download only means something once subscribed; in preview
                // mode the item is omitted rather than writing to a row that
                // does not exist.
                if (showAutoDownload) {
                    add(
                        PodaraDropdownMenuItem(
                            label = Strings["podcast_auto_download"],
                            isSelected = autoDownloadEnabled,
                            onClick = {
                                showPopup = false
                                onToggleAutoDownload()
                            }
                        )
                    )
                }
                add(
                    PodaraDropdownMenuItem(
                        label = Strings["dialog_copy_to_clipboard"],
                        onClick = {
                            showPopup = false
                            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            val selection = java.awt.datatransfer.StringSelection(rssUrl)
                            clipboard.setContents(selection, null)
                        }
                    )
                )
            }
        )
    }
}