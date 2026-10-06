package app.podara.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import app.podara.util.clickableWithoutIndicationOrFocusRing
import coil3.compose.AsyncImage
import java.awt.Cursor

/**
 * "Continue listening" card for the top of the Home screen.
 *
 * Pure presentation: the caller resolves the persisted player session
 * ([app.podara.data.PlayerSession]) plus the episode and podcast it points at,
 * and passes plain values — the component has no database or global
 * dependencies.
 *
 * Contract:
 * - [durationMs] <= 0 or [positionMs] <= 0 hides the progress bar. A position
 *   without a duration still shows the "left off at" label — the resume point
 *   is known even when the total is not.
 * - [positionMs] >= [durationMs] means the episode is finished. The component
 *   still renders in that case (full bar, "0 min left"); whether to show the
 *   card at all is the caller's decision, since only it knows the session
 *   context.
 *
 * Visual language follows the Favorites/episode cards: the same fill, border
 * and cover tokens from [DesignTokens.FavoriteEpisodeList], the same hover and
 * press timing, and the player's slider colours for the progress bar.
 */
@Composable
fun ContinueListeningCard(
    episodeTitle: String,
    podcastTitle: String,
    positionMs: Long,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null
) {
    val colors = PodaraTheme.colors
    val surfaces = PodaraTheme.surfaces
    val list = DesignTokens.FavoriteEpisodeList
    val button = DesignTokens.Button
    val shape = RoundedCornerShape(list.CardRadius)

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> list.PressedBackgroundColor
            isHovered -> list.HoverBackgroundColor
            else -> list.BackgroundColor
        },
        animationSpec = tween(durationMillis = DesignTokens.Animation.HoverMs)
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isHovered -> list.HoverBorderColor
            else -> list.BorderColor
        },
        animationSpec = tween(durationMillis = DesignTokens.Animation.HoverMs)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .border(list.BorderWidth, borderColor, shape)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            // clickable merges descendants, so the whole card reads as one
            // entry: badge, titles, times and the resume action.
            .clickableWithoutIndicationOrFocusRing(
                interactionSource = interactionSource,
                onClick = onClick
            )
            .padding(horizontal = list.CardPaddingHorizontal, vertical = list.CardPaddingVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(list.CoverSize)
                .shadow(
                    elevation = list.CoverShadowElevation,
                    shape = RoundedCornerShape(list.CoverRadius),
                    ambientColor = list.CoverShadowColor,
                    spotColor = list.CoverShadowColor
                )
                .clip(RoundedCornerShape(list.CoverRadius))
        ) {
            val resolvedImageUrl = imageUrl?.takeIf { it.isNotBlank() }
            if (resolvedImageUrl != null) {
                AsyncImage(
                    model = resolvedImageUrl,
                    contentDescription = episodeTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Podcasts,
                    contentDescription = null,
                    tint = list.MetadataColor,
                    modifier = Modifier.size(list.CoverSize * 0.45f).align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.width(list.CoverContentGap))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = Strings["home_continue_badge"],
                color = colors.accent,
                fontSize = DesignTokens.Badge.TextSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(DesignTokens.EpisodeRow.LineGap))
            Text(
                text = episodeTitle,
                color = list.TitleColor,
                fontSize = list.TitleSize,
                lineHeight = list.TitleLineHeight,
                fontWeight = list.TitleWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(list.PodcastNameMarginTop))
            Text(
                text = podcastTitle,
                color = list.PodcastNameColor,
                fontSize = list.PodcastNameSize,
                lineHeight = list.PodcastNameLineHeight,
                fontWeight = list.PodcastNameWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (positionMs > 0 || durationMs > 0) {
                Spacer(modifier = Modifier.height(DesignTokens.Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (positionMs > 0) {
                        Text(
                            text = Strings.get("home_continue_last_listened", formatEpisodeClock(positionMs)),
                            color = list.MetadataColor,
                            fontSize = list.MetadataSize,
                            maxLines = 1
                        )
                        if (durationMs > 0) Spacer(modifier = Modifier.width(DesignTokens.Spacing.sm))
                    }
                    if (durationMs > 0) {
                        val fraction = progressFraction(positionMs, durationMs)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(ContinueListeningBarHeight)
                                .clip(RoundedCornerShape(ContinueListeningBarHeight / 2))
                                .background(surfaces.sliderTrackInactive)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction)
                                    .clip(RoundedCornerShape(ContinueListeningBarHeight / 2))
                                    .background(colors.accent)
                            )
                        }
                        Spacer(modifier = Modifier.width(DesignTokens.Spacing.sm))
                        Text(
                            text = remainingLabel(durationMs, positionMs),
                            color = list.MetadataColor,
                            fontSize = list.MetadataSize,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(list.ContentActionsGap))

        // Static affordance; the whole card carries the click handler.
        Box(
            modifier = Modifier
                .size(DesignTokens.IconButton.Size)
                .shadow(
                    elevation = button.ShadowElevation,
                    shape = CircleShape,
                    ambientColor = button.ShadowColor,
                    spotColor = button.ShadowColor
                )
                .clip(CircleShape)
                .background(button.Gradient),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = Modifier.matchParentSize().background(button.InnerHighlight))
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = Strings["home_continue_play"],
                tint = button.IconColor,
                modifier = Modifier.size(button.IconSize)
            )
        }
    }
}

/** Thickness of the card's static progress bar. */
private val ContinueListeningBarHeight = 4.dp

/**
 * Clock text matching the player's time labels (`PlayerUI.formatTime`):
 * `h:mm:ss` when hours are present, `m:ss` otherwise. Kept local because the
 * player's helper is private and this module's files are single-owner.
 */
internal fun formatEpisodeClock(millis: Long): String {
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

/**
 * Played share of the episode, clamped to 0..1. Zero whenever either side is
 * unknown (<= 0), which is also the condition that hides the bar.
 */
internal fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0 || positionMs <= 0) return 0f
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Human-readable time still ahead, rounded up to whole minutes so a 46:59
 * remainder does not read as "0 min left". Empty when the duration is unknown.
 */
internal fun remainingLabel(durationMs: Long, positionMs: Long): String {
    if (durationMs <= 0) return ""
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0)
    val totalMinutes = (remainingMs + 59_999) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> Strings.get("home_continue_time_left_hours", hours, minutes)
        hours > 0 -> Strings.get("home_continue_time_left_hours_exact", hours)
        else -> Strings.get("home_continue_time_left_minutes", totalMinutes)
    }
}
