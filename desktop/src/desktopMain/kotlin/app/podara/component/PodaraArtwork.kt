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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
    showPlaceholder: Boolean = true
) {
    val colors = PodaraTheme.colors
    val artworkUrl = url?.takeIf { it.isNotBlank() }

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .then(
                if (showPlaceholder) Modifier.background(colors.elevated) else Modifier
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
                animationSpec = tween(DesignTokens.Animation.ArtworkFadeMs),
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
