package app.podara.util

/**
 * Tag matcher, entity matcher and whitespace collapser for [stripHtml].
 *
 * Hoisted to file scope on purpose. These were `Regex(...)` calls inside the
 * function body, which recompiled the patterns on **every** invocation — and the
 * call sites are composition hot paths (`EpisodeListItem`'s tertiary line, the
 * podcast blurb on Home and in the detail header). A row-scroll over a few
 * hundred episodes compiled a few hundred throwaway patterns.
 */
private val TAG_REGEX = Regex("<[^>]*>")
private val WHITESPACE_REGEX = Regex("\\s+")
private val ENTITY_REGEX = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,8});")

/**
 * The entities that actually turn up in podcast descriptions.
 *
 * Not the full HTML table: the rest is rare in feeds, and an unknown entity is
 * better left as written than mapped to something wrong. `nbsp` becomes a space
 * because the output here is a single collapsed line, where a hard no-break
 * space only defeats the collapse.
 */
private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "ndash" to "\u2013",
    "mdash" to "\u2014",
    "hellip" to "\u2026",
    "lsquo" to "\u2018",
    "rsquo" to "\u2019",
    "ldquo" to "\u201c",
    "rdquo" to "\u201d",
    "laquo" to "\u00ab",
    "raquo" to "\u00bb",
    "copy" to "\u00a9",
    "reg" to "\u00ae",
    "trade" to "\u2122"
)

/**
 * Reduces an HTML snippet to a single line of plain text.
 *
 * Entities are decoded *before* tags are stripped, not after. Done the other way
 * round, `&lt;p&gt;` survives the strip as the literal text `<p>` — it holds no
 * angle brackets yet — and the visible angle brackets come back afterwards. A
 * feed that escaped its whole body then shows raw markup in the list, which is
 * what this is here to prevent. Decoding first also turns prose escapes like
 * "x &lt; 5" into the character the author wrote.
 *
 * Still deliberately dumb: anything that needs structure (the full show notes in
 * the player) goes through a real parser instead.
 */
internal fun stripHtml(html: String): String {
    return decodeEntities(html)
        .replace(TAG_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
}

/**
 * Replaces known named entities and any numeric ones.
 *
 * Unknown entities are left exactly as written: inventing a replacement would
 * corrupt a title, and dropping the text would lose it.
 */
private fun decodeEntities(text: String): String {
    if (!text.contains('&')) return text
    return ENTITY_REGEX.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value

            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value

            else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
        }
    }
}

/** A numeric entity that is not a valid code point keeps its source text. */
private fun codePointToString(codePoint: Int): String? =
    if (codePoint > 0 && Character.isValidCodePoint(codePoint)) {
        String(Character.toChars(codePoint))
    } else {
        null
    }
