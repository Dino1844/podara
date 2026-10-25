package app.podara.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.podara.component.ToolbarPillButton
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import app.podara.util.TimeFormat
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.clickableWithoutIndication
import app.podara.util.clickableWithoutIndicationOrFocusRing

/**
 * The chapter list drawer of the full-screen player.
 *
 * Structurally the queue drawer's sibling: a scrimmed right-side panel with a
 * header and a LazyColumn, where one row is the *current* one instead of the
 * *playing* one. Rows show a chapter title and its start time; a click seeks,
 * because that is all a chapter is — a named position in the same file.
 *
 * The current row borrows the sidebar's active language (the `navActive` glass
 * fill) rather than the accent: the accent is reserved for playing/favourite
 * state elsewhere, and "where am I" is a selection, not a highlight.
 */
@Composable
internal fun ChaptersPanel(
    chapters: List<MpvChapter>,
    currentIndex: Int,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = PodaraTheme.colors
    val queuePanel = DesignTokens.QueuePanel
    val listState = rememberLazyListState()

    // Follow the playhead: opening the panel (or the chapter changing while it
    // is open) scrolls the current chapter into view.
    LaunchedEffect(currentIndex) {
        if (currentIndex in chapters.indices) {
            listState.scrollToItem(currentIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Same scrim arrangement as the queue drawer's host in App.kt: instant
        // fill, click-through to dismiss.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PodaraTheme.surfaces.scrim)
                .clickableWithoutIndication { onDismiss() }
        )
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(queuePanel.Width)
                .align(Alignment.CenterEnd)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            color = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = queuePanel.PaddingTop)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(queuePanel.HeaderHeight)
                        .padding(horizontal = queuePanel.PaddingHorizontal),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = Strings["player_chapters"],
                        color = colors.textPrimary,
                        fontSize = queuePanel.HeaderTitleSize,
                        lineHeight = queuePanel.HeaderTitleLineHeight,
                        fontWeight = FontWeight.SemiBold
                    )
                    ToolbarPillButton(
                        icon = Icons.Outlined.Close,
                        label = "",
                        contentDescription = Strings["player_close"],
                        onClick = onDismiss,
                        iconColor = colors.textPrimary,
                        hoverIconColor = PodaraTheme.surfaces.pillIconHover,
                        defaultBackgroundColor = PodaraTheme.surfaces.iconButtonFill,
                        defaultBorderColor = PodaraTheme.surfaces.iconButtonBorder,
                        hoverBackgroundColor = PodaraTheme.surfaces.iconButtonFillHover,
                        hoverBorderColor = PodaraTheme.surfaces.pillBorderHover
                    )
                }

                Spacer(modifier = Modifier.height(queuePanel.Spacing))

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = queuePanel.PaddingHorizontal),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.xs)
                ) {
                    itemsIndexed(chapters) { index, chapter ->
                        ChapterRow(
                            chapter = chapter,
                            position = index,
                            isCurrent = index == currentIndex,
                            onClick = { onSeek(chapter.startMs) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: MpvChapter,
    position: Int,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val colors = PodaraTheme.colors
    val activeGlass = DesignTokens.Navigation.ActiveGlass
    val rowShape = RoundedCornerShape(activeGlass.Radius)
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverBackground by animateHoverBackgroundColor(isHovered, PodaraTheme.surfaces.cardFillHover)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DesignTokens.Sidebar.NavItemHeight)
            .clip(rowShape)
            .then(
                if (isCurrent) {
                    // The sidebar's active nav pill: flat tint plus a hairline.
                    // Shadow and full border stack stay with the sidebar, whose
                    // rows float on the shell; inside this panel the fill is
                    // the whole message.
                    Modifier
                        .background(activeGlass.BaseColor)
                        .border(activeGlass.BorderWidth, activeGlass.Border, rowShape)
                } else {
                    Modifier.background(hoverBackground)
                }
            )
            .clickableWithoutIndicationOrFocusRing(
                interactionSource = interactionSource,
                onClick = onClick
            )
            .padding(horizontal = activeGlass.InnerPaddingHorizontal)
            .semantics { selected = isCurrent },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.Spacing.sm)
    ) {
        Text(
            text = chapter.title ?: Strings.get("player_chapter_fallback", position + 1),
            color = if (isCurrent) colors.textPrimary else colors.textSecondary,
            fontSize = DesignTokens.TypeScale.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = TimeFormat.clock(chapter.startMs),
            color = colors.textMuted,
            fontSize = DesignTokens.TypeScale.Caption,
            style = TextStyle(fontFeatureSettings = DesignTokens.TypeFamily.TabularFigures)
        )
    }
}
