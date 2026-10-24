package app.podara.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter

/**
 * Cover art, with the three things a cover needs and `AsyncImage` does not give
 * you by itself: something to be while it loads, something to fade in *to*, and
 * something to fall back to when the feed has no artwork or the URL is dead.
 *
 * The callers used to be eleven separate `AsyncImage` calls. Three of them had
 * hand-written `Podcasts`-icon fallbacks, the other eight had nothing — so a
 * podcast with no artwork left a flat empty rectangle, and a slow network left
 * the same rectangle for as long as it took. The player overlaid its own spinner
 * on top of the same gap.
 *
 * The fade is done here rather than through Coil: `crossfade` in Coil 3 is an
 * `ImageLoader` plugin and this app builds no `ImageLoader` of its own, and the
 * per-request `crossfade(true)` builder option the docs mention does not exist
 * in the version on the classpath. Animating the image's own alpha against the
 * painter's state is four lines and has no dependency on Coil's internals.
 *
 * The placeholder glyph stays in place underneath the image rather than being
 * swapped for it, which is what makes the failure case quiet: an error leaves
 * the alpha at zero, and what you see is the glyph, not a hole.
 *
 * @param url blank and null both mean "no artwork"; the glyph renders instead of
 *   a request that could only fail.
 * @param loadingSpinnerSize non-null shows a spinner over the glyph while the
 *   image is in flight, at this diameter. Only the two player covers use it —
 *   a row in a list should not churn while the list scrolls.
 * @param busy keeps that spinner up for work the cover cannot see: the player
 *   opening its stream is the case, where the artwork may have loaded long
 *   before playback actually starts.
 * @param showPlaceholder set false for decorative copies of a cover that is
 *   already rendered elsewhere (the featured card's blurred echoes). Both the
 *   resting fill and the glyph are the placeholder: an echo that faded in from a
 *   grey square would read as a second panel, not as the same record.
 * @param seedColor stable colour seed for the placeholder, from
 *   [app.podara.util.artworkSeedFor]. A library of grey squares tells the user
 *   nothing about which show is which; a faint per-show hue is recognisable at a
 *   glance and, being derived from the feed's identity, does not move between
 *   launches. 0 means "no seed recorded" and keeps the plain surface colour.
 */
@Composable
fun PodaraArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(DesignTokens.Radius.Small),
    loadingSpinnerSize: Dp? = null,
    loadingStrokeWidth: Dp = DesignTokens.Artwork.SpinnerStroke,
    busy: Boolean = false,
    showPlaceholder: Boolean = true,
    seedColor: Int = 0
) {
    val colors = PodaraTheme.colors
    val artworkUrl = url?.takeIf { it.isNotBlank() }

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .then(
                if (showPlaceholder) Modifier.background(placeholderSurface(colors.elevated, seedColor)) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // The glyph is the backing for both cases: no artwork at all, and an
        // artwork request that came back dead. It is drawn under the image
        // rather than in place of it, and the image's own alpha is what decides
        // which of the two is visible.
        if (showPlaceholder) {
            Icon(
                imageVector = Icons.Default.Podcasts,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(maxWidth * DesignTokens.Artwork.GlyphScale)
            )
        }

        // Only ask Coil when there is something to ask for: the painter's
        // request runs on Dispatchers.Main, and a cover with no artwork used to
        // reach it anyway — which is what the headless tests trip over.
        var isFetchingArtwork = false
        if (artworkUrl != null) {
            val painter = rememberAsyncImagePainter(model = artworkUrl)
            // Collected rather than read: the painter keeps its state in a plain
            // `StateFlow`, not snapshot state, so `state.value` would answer
            // correctly once and then never change again — the image would stay
            // at alpha zero forever. Collecting is what makes the fade fire.
            val painterState by painter.state.collectAsState()
            val imageAlpha by animateFloatAsState(
                targetValue = if (painterState is AsyncImagePainter.State.Success) 1f else 0f,
                animationSpec = tween(
                    DesignTokens.Animation.ArtworkFadeMs,
                    easing = DesignTokens.Animation.StandardEasing
                ),
                label = "artworkFade"
            )
            isFetchingArtwork = painterState is AsyncImagePainter.State.Loading
            Image(
                painter = painter,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = imageAlpha }
            )
        }

        if ((isFetchingArtwork || busy) && loadingSpinnerSize != null) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = loadingStrokeWidth,
                modifier = Modifier.size(loadingSpinnerSize)
            )
        }
    }
}

/**
 * How much of the seed's hue to mix into the placeholder surface.
 *
 * Low on purpose: the placeholder is a background for a glyph and, in the
 * player, for a spinner, so it has to stay a surface rather than become a colour
 * block. Mixing with the theme's own `elevated` instead of picking an absolute
 * light or dark value is what keeps it correct in both themes without the
 * component having to know which one is active.
 */
private const val PLACEHOLDER_TINT_WEIGHT = 0.16f

/**
 * The placeholder surface for [seedColor], mixed over [base].
 *
 * The hue comes from the low 16 bits of the seed, which is plenty of spread for
 * a colour and immune to the sign of `hashCode()`. A seed of 0 means no seed was
 * recorded, so the base colour comes back untouched and the placeholder looks
 * exactly as it did before seeds existed.
 */
private fun placeholderSurface(base: Color, seedColor: Int): Color {
    if (seedColor == 0) return base
    val hue = (seedColor and 0xFFFF).toFloat() / 0xFFFF * 360f
    return lerp(base, Color.hsl(hue, 0.55f, 0.5f), PLACEHOLDER_TINT_WEIGHT)
}
