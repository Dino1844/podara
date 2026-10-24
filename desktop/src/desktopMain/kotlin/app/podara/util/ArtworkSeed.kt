package app.podara.util

/**
 * A stable, non-zero colour seed for a piece of content.
 *
 * `imageSeedColor` exists so a podcast without cover art can still get a
 * recognisable placeholder colour, and every write path has carried the field
 * since the first schema — but nothing ever set it to anything but 0, so the
 * colour was one value for the whole library and the field was decoration.
 *
 * Two properties matter and both come from using the identity rather than a
 * random number:
 *
 * - it must be the same on every launch, or the user's library would reshuffle
 *   its colours on every start;
 * - it must be spread out, or "stable" would just mean "all the same again".
 *
 * `String.hashCode()` gives both, and 0 is remapped because 0 is the sentinel
 * the UI reads as "no seed recorded" — a podcast that happened to hash to 0
 * would silently be treated as one that predates the field.
 *
 * @param parts identity of the content, most specific first. Joined with a
 *   separator that cannot appear in a URL, so `["ab", "c"]` and `["a", "bc"]`
 *   do not collide.
 */
fun artworkSeedFor(vararg parts: String): Int {
    val key = parts.joinToString("\u0000")
    val hashed = key.hashCode()
    return if (hashed == 0) FALLBACK_SEED else hashed
}

/** Seed used when the identity hashes to the "no seed" sentinel. */
private const val FALLBACK_SEED = 0x5F3759DF.toInt()
