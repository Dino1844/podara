package app.podara.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme

/** Rows a skeleton list draws while loading — enough to fill a window. */
internal const val SkeletonRowCount = 6

/**
 * D1 (DESIGN-CRAFT.md §5): loading placeholders shaped like the rows that are
 * coming. A spinner only says "waiting"; a skeleton says what is coming, and
 * because it takes the real row's height, data arriving swaps content in
 * place instead of jumping the layout.
 *
 * Two shapes only — the cover square and two grey bands. The grey is
 * `cardFillHover`, the card family's own low-emphasis step one above
 * `cardFill` (the same grey the scrollbar thumb rests in): an existing surface
 * token with a named role, not a new grey. Every radius is the real row's own
 * radius or a step on [DesignTokens.Radius]. Deliberately static — no shimmer,
 * no pulse, and therefore no new animation either.
 */

/**
 * The inside of a skeleton row: a [coverSize] square on the leading edge, then
 * a title-width and a subtitle-width band. Band heights are the line-height
 * tokens of the text they stand in for (sp, converted with the local density),
 * so the placeholder occupies exactly the space the text will.
 *
 * The call site supplies its own row chrome — background, border, height and
 * padding — from the same tokens the real row reads, which is what keeps the
 * skeleton and the loaded row the same shape. [EpisodeCardSkeletonRow] is that
 * wrapping for the `FavoriteEpisodeList` card language.
 */
@Composable
fun PodaraSkeletonRow(
    coverSize: Dp,
    coverRadius: Dp,
    coverContentGap: Dp,
    bandRadius: Dp,
    titleBandLineHeight: TextUnit,
    titleBandWidthFraction: Float,
    subtitleBandLineHeight: TextUnit,
    subtitleBandWidthFraction: Float,
    bandGap: Dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val skeletonFill = PodaraTheme.surfaces.cardFillHover
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(coverSize)
                .clip(RoundedCornerShape(coverRadius))
                .background(skeletonFill)
        )
        Spacer(modifier = Modifier.width(coverContentGap))
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth(titleBandWidthFraction)
                    .height(with(density) { titleBandLineHeight.toDp() })
                    .clip(RoundedCornerShape(bandRadius))
                    .background(skeletonFill)
            )
            Spacer(modifier = Modifier.height(bandGap))
            Box(
                modifier = Modifier
                    .fillMaxWidth(subtitleBandWidthFraction)
                    .height(with(density) { subtitleBandLineHeight.toDp() })
                    .clip(RoundedCornerShape(bandRadius))
                    .background(skeletonFill)
            )
        }
    }
}

/**
 * A skeleton row in the `FavoriteEpisodeList` card language — the row shape
 * the subscription list and every episode list row uses. Metrics and chrome
 * come from [DesignTokens.FavoriteEpisodeList] itself, so this cannot drift
 * from the real row's height.
 */
@Composable
fun EpisodeCardSkeletonRow(modifier: Modifier = Modifier) {
    val card = DesignTokens.FavoriteEpisodeList
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(card.CardHeight)
            .clip(RoundedCornerShape(card.CardRadius))
            .background(card.BackgroundColor)
            .border(card.BorderWidth, card.BorderColor, RoundedCornerShape(card.CardRadius))
            .padding(horizontal = card.CardPaddingHorizontal, vertical = card.CardPaddingVertical),
        contentAlignment = Alignment.CenterStart
    ) {
        PodaraSkeletonRow(
            coverSize = card.CoverSize,
            coverRadius = card.CoverRadius,
            coverContentGap = card.CoverContentGap,
            bandRadius = DesignTokens.Radius.Small,
            titleBandLineHeight = card.TitleLineHeight,
            titleBandWidthFraction = 0.5f,
            subtitleBandLineHeight = card.PodcastNameLineHeight,
            subtitleBandWidthFraction = 0.3f,
            bandGap = card.PodcastNameMarginTop,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
