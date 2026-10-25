package app.podara.util

/**
 * Feed-URL detection for Discover's search box.
 *
 * Discover's only entry used to be the iTunes catalogue search, which cannot
 * see podcasts outside Apple's directory — a large share of Chinese
 * independent shows and self-hosted feeds among them. When what the user
 * typed is already a feed URL, subscribing to it directly is both faster and
 * the only thing that can work.
 *
 * [searchInputAsFeedUrl] is a pure function: it returns the URL to subscribe
 * when the input names a feed, or null when the input should go to the
 * keyword search exactly as before.
 */
fun searchInputAsFeedUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    // A pasted URL never carries whitespace; a phrase of keywords does. One
    // check keeps multi-word searches ("planet money", "u.s. news") out of
    // the feed path before anything else runs.
    if (trimmed.any { it.isWhitespace() }) return null

    val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    return when {
        hasScheme -> if (hasHost(trimmed)) trimmed else null
        // Bare-host tolerance: the same feed pasted without its scheme, the
        // way feed addresses are most often shared as plain text. Completed
        // with https:// rather than tried scheme-less — feeds are served over
        // TLS in practice, and the BARE_HOST pattern keeps plain keywords
        // ("npr", "中文播客") and dotted ones ("3.5") out of the fetch path.
        BARE_HOST.matches(trimmed) -> "https://$trimmed"
        else -> null
    }
}

/** True when [url] carries a non-empty host after its `scheme://` prefix. */
private fun hasHost(url: String): Boolean {
    val rest = url.substringAfter("://", missingDelimiterValue = "")
    if (rest.isEmpty()) return false
    val authorityEnd = rest.indexOfAny(HOST_TERMINATORS)
    val authority = if (authorityEnd >= 0) rest.take(authorityEnd) else rest
    return authority.substringBefore(':').isNotEmpty()
}

private val HOST_TERMINATORS = charArrayOf('/', '?', '#')

/**
 * `example.com`, `example.com/feed.xml`, `example.com/feed.xml?id=1` — a
 * dotted host with an alphabetic TLD, then an optional path, query or
 * fragment. The alphabetic-TLD rule is what keeps dotted keywords ("3.5")
 * out; the no-whitespace rule is enforced by [searchInputAsFeedUrl].
 */
private val BARE_HOST = Regex(
    """[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}([/?#]\S*)?"""
)
