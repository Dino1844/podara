package app.podara.data

import app.podara.data.model.Podcast
import app.podara.data.model.PodcastEpisode
import kotlinx.coroutines.withContext
import java.sql.Connection

/**
 * How many terms one query may contain.
 *
 * The input is user-typed, and every whitespace-separated word becomes an
 * `AND`ed phrase. A pasted log line would otherwise build a MATCH expression
 * with thousands of terms — SQLite parses it fine but spends the time. Capping
 * keeps the worst case bounded and, since each extra `AND` only narrows the
 * result set, dropping the tail cannot hide an episode that mattered.
 */
private const val MAX_QUERY_TERMS = 64

/**
 * Full-text search over episodes and podcasts (FTS5).
 *
 * ## Why this exists
 *
 * The screens used to `SELECT *` every episode, pull the whole list into memory
 * and filter it with Kotlin `contains`. That reads every description — routinely
 * several KB of HTML — out of SQLite on the single-threaded database dispatcher
 * for every keystroke, and `contains` cannot rank or page.
 *
 * ## Ranking
 *
 * Results come back in `bm25()` order, which returns **smaller** (more negative)
 * numbers for better matches, so the sort is `ASC`. The weights passed to bm25
 * mirror how much a hit there means to the user: a word in an episode title is
 * a far stronger signal than the same word buried in a show-notes paragraph,
 * which is why the title column is weighted ~10x the description.
 *
 * `bm25()` must be given the *table name*, never an alias — SQLite resolves the
 * argument against real (virtual) tables, and `bm25(f, …)` fails with
 * "no such column: f".
 *
 * ## Ordering ties
 *
 * `bm25` ties constantly: episodes sharing a title word and nothing else all
 * score identically. The secondary `id ASC` makes the order total, so paging
 * with LIMIT/OFFSET cannot show the same row twice or skip one — the same
 * reasoning as the `id` tiebreak in [EpisodeDao.getAllByOrigin].
 */
class SearchDao(private val conn: Connection) {

    /**
     * Episodes matching [query], best match first.
     *
     * @param query raw user input; it is sanitised, never spliced into MATCH.
     * @param limit rows per page.
     * @param offset rows to skip; must be paired with a stable sort, see above.
     * @return matching episodes in relevance order, empty when [query] has no
     *   searchable terms (which also means no database round trip).
     */
    suspend fun searchEpisodes(query: String, limit: Int, offset: Int): List<PodcastEpisode> =
        withContext(DatabaseDispatcher) {
            val expression = toMatchExpression(query) ?: return@withContext emptyList()
            if (limit <= 0) return@withContext emptyList()

            val episodes = mutableListOf<PodcastEpisode>()
            conn.prepareStatement(
                """
                SELECT episode.* FROM podcastEpisodeFts
                JOIN podcastEpisode episode ON episode.rowid = podcastEpisodeFts.rowid
                WHERE podcastEpisodeFts MATCH ?
                ORDER BY bm25(podcastEpisodeFts, $EPISODE_WEIGHTS) ASC, episode.id ASC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, expression)
                ps.setInt(2, limit)
                ps.setInt(3, offset.coerceAtLeast(0))
                ps.executeQuery().use { rs ->
                    while (rs.next()) episodes.add(readEpisodeRow(rs))
                }
            }
            episodes
        }

    /** Number of episodes matching [query], for paging controls. */
    suspend fun searchEpisodesCount(query: String): Int = withContext(DatabaseDispatcher) {
        val expression = toMatchExpression(query) ?: return@withContext 0
        conn.prepareStatement(
            """
            SELECT COUNT(*) FROM podcastEpisodeFts
            JOIN podcastEpisode episode ON episode.rowid = podcastEpisodeFts.rowid
            WHERE podcastEpisodeFts MATCH ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, expression)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** Podcasts matching [query], best match first; same contract as [searchEpisodes]. */
    suspend fun searchPodcasts(query: String, limit: Int, offset: Int): List<Podcast> =
        withContext(DatabaseDispatcher) {
            val expression = toMatchExpression(query) ?: return@withContext emptyList()
            if (limit <= 0) return@withContext emptyList()

            val podcasts = mutableListOf<Podcast>()
            conn.prepareStatement(
                """
                SELECT podcast.* FROM podcastFts
                JOIN podcast ON podcast.rowid = podcastFts.rowid
                WHERE podcastFts MATCH ?
                ORDER BY bm25(podcastFts, $PODCAST_WEIGHTS) ASC, podcast.title ASC, podcast.origin ASC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, expression)
                ps.setInt(2, limit)
                ps.setInt(3, offset.coerceAtLeast(0))
                ps.executeQuery().use { rs ->
                    while (rs.next()) podcasts.add(readPodcastRow(rs))
                }
            }
            podcasts
        }

    /** Number of podcasts matching [query]. */
    suspend fun searchPodcastsCount(query: String): Int = withContext(DatabaseDispatcher) {
        val expression = toMatchExpression(query) ?: return@withContext 0
        conn.prepareStatement(
            """
            SELECT COUNT(*) FROM podcastFts
            JOIN podcast ON podcast.rowid = podcastFts.rowid
            WHERE podcastFts MATCH ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, expression)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private companion object {
        /**
         * bm25 weights, in the FTS table's column order.
         *
         * Episode columns: title, podcastTitle, descriptionText. Naming the show
         * counts for something — people search "radiolab" to find that show's
         * episodes — but a real title hit still wins.
         */
        const val EPISODE_WEIGHTS = "10.0, 4.0, 1.0"

        /** Podcast columns: title, author, overrideTitle. */
        const val PODCAST_WEIGHTS = "10.0, 4.0, 8.0"
    }
}

/**
 * Turns raw user input into a safe FTS5 MATCH expression, or null when there is
 * nothing to search for.
 *
 * This is the security boundary. A MATCH string is *SQL syntax*, not a value:
 * `"` opens a string, `*` is a prefix operator, `-` and `NOT` negate, `:`
 * introduces a column filter, `(` groups, and `AND`/`OR` are keywords. Feeding
 * raw input through made SQLite throw
 * (`no such column: foo` for `foo:bar`, `fts5: syntax error near "AND"`) — so
 * an apostrophe or a stray bracket in a search box could crash the screen.
 *
 * The fix is FTS5's own quoting rule: each whitespace-separated word becomes a
 * double-quoted phrase, and an embedded `"` is doubled. Inside quotes every
 * operator is literal, so the whole expression is inert. Terms are then joined
 * with `AND`, which is what a search box means by several words.
 *
 * A trailing `*` on each phrase makes it a prefix match. That is deliberate: it
 * makes "typ" find "typescript" while the user is still typing, and — because
 * `unicode61` emits a whole run of Han characters as one token — it is the only
 * reason a Chinese query like `中文` finds `中文播客`. See `createSearchSchema`
 * for the limitation that does *not* solve.
 *
 * @return a MATCH expression, or null for blank/no-word input so callers can
 *   skip the query entirely.
 */
internal fun toMatchExpression(query: String): String? {
    val terms = query.split(WHITESPACE_REGEX)
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(MAX_QUERY_TERMS)
        .map { term -> "\"" + term.replace("\"", "\"\"") + "\"*" }
        .toList()
    return if (terms.isEmpty()) null else terms.joinToString(" AND ")
}

private val WHITESPACE_REGEX = Regex("\\s+")
