package app.podara.util

/**
 * Tag matcher and whitespace collapser for [stripHtml].
 *
 * Hoisted to file scope on purpose. These were `Regex(...)` calls inside the
 * function body, which recompiled both patterns on **every** invocation — and the
 * call sites are composition hot paths (`EpisodeListItem`'s tertiary line, the
 * podcast blurb on Home and in the detail header). A row-scroll over a few
 * hundred episodes compiled a few hundred throwaway patterns.
 */
private val TAG_REGEX = Regex("<[^>]*>")
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Reduces an HTML snippet to a single line of plain text.
 *
 * Deliberately dumb: feed descriptions are a mix of escaped HTML, raw tags, and
 * assorted entities, and this is only ever used for a one-or-two-line preview.
 * Anything that needs structure (the full show notes in the player) goes through
 * a real parser instead.
 */
internal fun stripHtml(html: String): String {
    return html.replace(TAG_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
}
