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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podara.component.PodaraArtwork
import app.podara.component.PodaraDropdownMenu
import app.podara.component.PodaraDropdownMenuItem
import app.podara.component.PodaraTooltip
import app.podara.data.model.Podcast
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.clickableWithoutIndication
import app.podara.util.clickableWithoutIndicationOrFocusRing
import app.podara.util.stripHtml

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
            modifier = Modifier.fillMaxWidth().padding(horizontal = DesignTokens.Spacing.sm, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val backInteractionSource = remember { MutableInteractionSource() }
            val isBackHovered by backInteractionSource.collectIsHoveredAsState()
            val backAnimatedBg by animateHoverBackgroundColor(isBackHovered, colors.elevated)
            PodaraTooltip(text = Strings["nav_back"], modifier = Modifier) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(backAnimatedBg)
                        .clickableWithoutIndicationOrFocusRing(interactionSource = backInteractionSource) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = Strings["nav_back"],
                        tint = colors.textPrimary
                    )
                }
            }
            Spacer(Modifier.width(DesignTokens.Spacing.sm))
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = DesignTokens.TypeScale.BodyStrong,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alpha(collapseProgress)
            )
            AnimatedVisibility(
                visible = collapseProgress > COLLAPSED_ACTIONS_THRESHOLD,
                enter = fadeIn(tween(DesignTokens.Animation.HoverMs, easing = DesignTokens.Animation.EmphasisEasing)),
                exit = fadeOut(tween(DesignTokens.Animation.HoverMs, easing = DesignTokens.Animation.ExitEasing))
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm),
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
    notificationsEnabled: Boolean,
    onToggleAutoDownload: () -> Unit,
    onToggleNotifications: () -> Unit,
    onPlayLatest: () -> Unit,
    onToggleSubscribe: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PodaraTheme.colors
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = DesignTokens.Spacing.xl, top = DesignTokens.Spacing.md, end = DesignTokens.Spacing.xl, bottom = DesignTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PodaraArtwork(
                url = podcast.imageUrl,
                contentDescription = podcast.title,
                modifier = Modifier.size(180.dp),
                shape = RoundedCornerShape(DesignTokens.Radius.Medium)
            )

            Spacer(modifier = Modifier.width(20.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = podcast.fetchTitle(),
                    color = colors.textPrimary,
                    fontSize = DesignTokens.TypeScale.Heading,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(DesignTokens.Spacing.xs))

                if (podcast.author.isNotEmpty()) {
                    Text(
                        text = podcast.author,
                        color = colors.textSecondary,
                        fontSize = DesignTokens.TypeScale.Body
                    )
                    Spacer(modifier = Modifier.height(DesignTokens.Spacing.xs))
                }

                if (podcast.description.isNotEmpty()) {
                    Text(
                        text = stripHtml(podcast.description),
                        color = colors.textMuted,
                        fontSize = DesignTokens.TypeScale.Caption,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)) {
                    PodcastDetailActions(
                        isSubscribed = isSubscribed,
                        autoDownloadEnabled = autoDownloadEnabled,
                        notificationsEnabled = notificationsEnabled,
                        onToggleAutoDownload = onToggleAutoDownload,
                        onToggleNotifications = onToggleNotifications,
                        onPlayLatest = onPlayLatest,
                        onToggleSubscribe = onToggleSubscribe,
                        rssUrl = podcast.origin
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = DesignTokens.Spacing.xl))
        Spacer(modifier = Modifier.height(DesignTokens.Spacing.xs))
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
    notificationsEnabled: Boolean,
    onToggleAutoDownload: () -> Unit,
    onToggleNotifications: () -> Unit,
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
        notificationsEnabled = notificationsEnabled,
        onToggleAutoDownload = onToggleAutoDownload,
        onToggleNotifications = onToggleNotifications
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
            .background(btn.Fill)
            .clickableWithoutIndication { onClick() },
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
}

@Composable
private fun SubscribeToggleButton(isSubscribed: Boolean, onToggle: () -> Unit) {
    val colors = PodaraTheme.colors
    val surfaces = PodaraTheme.surfaces
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    // A square-rounded secondary control, not a 40dp ring. Two rings next to a
    // 40dp pill read as three unrelated objects; the pill's own radius and
    // height carry the row, and these two now match it. The fill is a real
    // surface rather than `colors.surface`, which on the dark scheme is one
    // step from the page — unhovered, the button was a hole.
    val background = when {
        isSubscribed -> colors.accent.copy(alpha = 0.16f)
        isHovered -> surfaces.iconButtonFillHover
        else -> surfaces.iconButtonFill
    }
    val shape = RoundedCornerShape(DesignTokens.Button.Radius)
    PodaraTooltip(
        text = if (isSubscribed) Strings["discover_added"] else Strings["discover_add"],
        modifier = Modifier
    ) {
        Box(
            modifier = Modifier
                .height(DesignTokens.Button.Height)
                .clip(shape)
                .border(DesignTokens.Border.Width, surfaces.pillBorder, shape)
                .background(background)
                .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { onToggle() }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSubscribed) Icons.Default.Check else Icons.Default.Add,
                contentDescription = if (isSubscribed) Strings["discover_added"] else Strings["discover_add"],
                tint = if (isSubscribed) colors.accent else colors.textSecondary,
                modifier = Modifier.size(DesignTokens.Button.IconSize)
            )
        }
    }
}

@Composable
private fun RssCopyMenuButton(
    rssUrl: String,
    showAutoDownload: Boolean,
    autoDownloadEnabled: Boolean,
    notificationsEnabled: Boolean,
    onToggleAutoDownload: () -> Unit,
    onToggleNotifications: () -> Unit
) {
    val colors = PodaraTheme.colors
    val surfaces = PodaraTheme.surfaces
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background = if (isHovered) surfaces.iconButtonFillHover else surfaces.iconButtonFill
    var showPopup by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DesignTokens.Button.Radius)
    PodaraTooltip(text = Strings["discover_more"], modifier = Modifier) {
    Box(
        modifier = Modifier
            .height(DesignTokens.Button.Height)
            .clip(shape)
            .border(DesignTokens.Border.Width, surfaces.pillBorder, shape)
            .background(background)
            .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { showPopup = true }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.MoreHoriz, contentDescription = Strings["discover_more"], tint = colors.textSecondary, modifier = Modifier.size(DesignTokens.Button.IconSize))

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
                    add(
                        PodaraDropdownMenuItem(
                            label = Strings["podcast_notifications"],
                            isSelected = notificationsEnabled,
                            onClick = {
                                showPopup = false
                                onToggleNotifications()
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
}