package app.podara.player

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.podara.theme.PodaraTheme
import app.podara.util.Logger
import java.awt.Desktop
import java.net.URI

private const val TAG = "EpisodeNotes"

/**
 * Renders episode show notes with working hyperlinks and chapter timestamps.
 *
 * The previous implementation emitted a plain `Text`, which meant the
 * annotations produced by [parseSimpleHtml] were dead weight: links were styled
 * as if clickable but nothing handled a click, and timestamps were not
 * recognised at all.
 */
@Composable
internal fun EpisodeNotesText(
    html: String,
    onSeek: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    fontSize: TextUnit = 13.sp
) {
    val colors = PodaraTheme.colors
    val annotated = parseSimpleHtml(
        html = html,
        linkColor = colors.info,
        timestampColor = colors.accent
    )

    ClickableText(
        text = annotated,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize,
            lineHeight = 18.sp,
            color = colors.textSecondary
        ),
        onClick = { offset ->
            annotated.getStringAnnotations(URL_ANNOTATION, offset, offset).firstOrNull()
                ?.let { onOpenLink(it.item) }
                ?: annotated.getStringAnnotations(TIMESTAMP_ANNOTATION, offset, offset)
                    .firstOrNull()
                    ?.item
                    ?.toLongOrNull()
                    ?.let(onSeek)
        }
    )
}

/**
 * Opens [url] in the user's default browser.
 *
 * Only http/https are accepted: show notes come from arbitrary podcast hosts and
 * a `file:` or custom-scheme link should not be launched by the app.
 */
internal fun openExternalLink(url: String) {
    val normalized = url.trim()
    val allowed = normalized.startsWith("http://", ignoreCase = true) ||
        normalized.startsWith("https://", ignoreCase = true)

    if (!allowed) {
        Logger.w(TAG, "Refusing to open non-http link: $normalized")
        return
    }

    try {
        if (!Desktop.isDesktopSupported()) {
            Logger.w(TAG, "Desktop.browse unsupported")
            return
        }
        Desktop.getDesktop().browse(URI(normalized))
    } catch (e: Exception) {
        Logger.e(TAG, "Failed to open link: $normalized", e)
    }
}
