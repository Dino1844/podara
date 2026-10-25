package app.podara.data

import app.podara.data.model.*
import app.podara.util.Logger
import app.podara.util.stripHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors

/**
 * The one thread every DAO runs its JDBC work on.
 *
 * `internal` rather than file-private so [SearchDao] can share it: a second
 * executor would let search queries and writes contend for the same SQLite
 * connection from two threads, which is exactly what serialising was for.
 */
internal val DatabaseDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "podara-db").apply { isDaemon = true }
}.asCoroutineDispatcher()

/**
 * How many bound parameters to put in one statement.
 *
 * SQLite's default SQLITE_MAX_VARIABLE_NUMBER is 999 on older builds and
 * 32766 on newer ones; chunking well under the lower bound keeps the aggregate
 * queries working everywhere.
 */
private const val SQL_VARIABLE_CHUNK = 400

private inline fun <T : AutoCloseable, R> T.useResource(block: T.() -> R): R = use { resource ->
    resource.block()
}

/**
 * How deep the *current thread* already is inside a transaction.
 *
 * Nesting used to be detected with `if (!autoCommit)`, which reads a
 * connection-wide JDBC flag as if it were call-scoped. That conflated two
 * different situations — "this call is nested inside a transaction I already
 * own" and "somebody turned autocommit off for another reason" — and it made
 * the nesting decision depend on which thread happened to observe the flag. A
 * nested frame that decided it was the owner would commit or roll back the
 * outer transaction's work, and restoring `autoCommit = true` at the wrong
 * depth commits implicitly.
 *
 * The depth is per-thread because that is what actually defines nesting here:
 * the database work is serialised onto [DatabaseDispatcher]'s one thread, so a
 * call is nested exactly when the same thread is already inside a transaction.
 */
private val transactionDepth = ThreadLocal.withInitial { 0 }

private suspend fun <T> Connection.inTransaction(block: suspend () -> T): T {
    // Nested calls participate in the outer transaction; only its owner commits or rolls back.
    val depth = transactionDepth.get()
    if (depth > 0) {
        transactionDepth.set(depth + 1)
        try {
            return block()
        } finally {
            transactionDepth.set(depth)
        }
    }

    autoCommit = false
    transactionDepth.set(1)
    var failure: Throwable? = null
    try {
        val result = block()
        commit()
        return result
    } catch (throwable: Throwable) {
        failure = throwable
        try {
            rollback()
        } catch (rollbackFailure: Throwable) {
            throwable.addSuppressed(rollbackFailure)
        }
        throw throwable
    } finally {
        transactionDepth.set(0)
        try {
            autoCommit = true
        } catch (restoreFailure: Throwable) {
            failure?.addSuppressed(restoreFailure) ?: throw restoreFailure
        }
    }
}

/**
 * Blocking twin of [inTransaction], for schema migration.
 *
 * Migration runs during [AppDatabase.build] before any dispatcher exists, so
 * there is nothing to suspend on — and a half-applied migration must roll back
 * just as reliably as a half-applied write.
 */
private inline fun <T> Connection.inTransactionSync(block: () -> T): T {
    val depth = transactionDepth.get()
    if (depth > 0) {
        transactionDepth.set(depth + 1)
        try {
            return block()
        } finally {
            transactionDepth.set(depth)
        }
    }

    autoCommit = false
    transactionDepth.set(1)
    var failure: Throwable? = null
    try {
        val result = block()
        commit()
        return result
    } catch (throwable: Throwable) {
        failure = throwable
        try {
            rollback()
        } catch (rollbackFailure: Throwable) {
            throwable.addSuppressed(rollbackFailure)
        }
        throw throwable
    } finally {
        transactionDepth.set(0)
        try {
            autoCommit = true
        } catch (restoreFailure: Throwable) {
            failure?.addSuppressed(restoreFailure) ?: throw restoreFailure
        }
    }
}

/**
 * Creates the full-text tables and the triggers that keep them current.
 *
 * Shared by [AppDatabase.createTables] (new installs) and the v3 migration
 * (upgrades) so the two can never drift apart — a trigger definition that only
 * existed on the migration path would leave fresh databases silently
 * unindexed.
 *
 * ## Why external content
 *
 * Both FTS tables are declared `content='<table>'`, so they store **only** the
 * inverted index and read the column values back from the base table by
 * `rowid`. Without that, every episode's title and description would exist
 * twice — and descriptions are routinely several KB of HTML, so a large library
 * would roughly double its on-disk text. `podcastEpisode.id` is a TEXT primary
 * key, but `rowid` still exists and is stable (nothing here uses
 * `WITHOUT ROWID` or `VACUUM`, and VACUUM preserves rowids anyway), so
 * `content_rowid='rowid'` is safe.
 *
 * The trade-off of external content is that the FTS table cannot be written
 * implicitly: `INSERT INTO podcastEpisode` does **not** update the index on its
 * own, which is why every write is funnelled through the triggers below.
 *
 * ## Why `descriptionText` is a real column
 *
 * FTS5 has no HTML awareness. Indexing `podcastEpisode.description` directly
 * would put `div`, `href`, `https`, `img` and friends in the index alongside
 * the real words, and — worse — a search for a common tag word would match
 * almost every episode. SQLite cannot strip HTML on its own, and a trigger
 * cannot call Kotlin. So the plain text lives in a real column, written by
 * [EpisodeDao] at insert time, and the index reads *that*.
 *
 * The consequence is an invariant worth stating plainly: any writer that
 * inserts an episode must populate `descriptionText`, or that episode's body
 * will be searchable only by title. [EpisodeDao.insert] is the only such
 * writer and satisfies it; the migration's backfill covers pre-existing rows.
 *
 * ## Tokenizer
 *
 * `unicode61 remove_diacritics 2` with `prefix='1 2'`.
 *
 * `unicode61` folds case and Latin diacritics (`café` matches `cafe`), which is
 * what a mostly-English-plus-accents podcast library needs. Its documented
 * limitation is that it knows nothing about word segmentation in scripts that
 * are not space-delimited: a run of Han characters is one single token. So
 * `中文播客第一期` is a single term, and searching `播客` will **not** find
 * `中文播客`. Searching `中文` works only because `prefix='1 2'` lets a prefix
 * match, and that is a leading prefix of the token, not an infix — `播客`
 * still misses `中文播客`.
 *
 * This is a real limitation, not a bug to be papered over: fixing it properly
 * needs an actual Chinese segmenter (ICU or a `simple`-style bigram tokenizer
 * implemented as a custom extension), and `trigram` would make every CJK query
 * work but triples index size and breaks one- and two-character Latin queries.
 * Episodes additionally index their `podcastTitle`, so a search that matches a
 * show's name still surfaces its episodes.
 */
/**
 * Removes the search tables and their triggers, if present.
 *
 * Exists so the v3 migration can be re-run from *any* starting state. Without
 * it, a database that already holds search objects (an upgrade interrupted
 * between writing `user_version` and rebuilding, or a downgrade-and-upgrade
 * cycle) would fire the episode UPDATE trigger while `descriptionText` is being
 * backfilled — indexing into a virtual table whose shadow rows are stale — and
 * SQLite answers with `SQLITE_CORRUPT_VTAB`.
 *
 * Triggers are dropped first: dropping a table that a trigger references is
 * allowed, but leaving a trigger behind that writes to a missing table turns
 * every later insert into an error.
 */
internal fun dropSearchSchema(connection: Connection) {
    connection.createStatement().use { stmt ->
        for (trigger in SEARCH_TRIGGERS) {
            stmt.executeUpdate("DROP TRIGGER IF EXISTS $trigger")
        }
        // Dropping the virtual table also drops its shadow tables (`_data`,
        // `_idx`, `_content`, `_docsize`), so there is nothing else to clean.
        stmt.executeUpdate("DROP TABLE IF EXISTS podcastEpisodeFts")
        stmt.executeUpdate("DROP TABLE IF EXISTS podcastFts")
    }
}

/** Every trigger [createSearchSchema] installs; kept in sync by hand. */
internal val SEARCH_TRIGGERS = listOf(
    "podcastEpisodeFtsInsert",
    "podcastEpisodeFtsDelete",
    "podcastEpisodeFtsUpdate",
    "podcastFtsInsert",
    "podcastFtsDelete",
    "podcastFtsUpdate"
)

internal fun createSearchSchema(connection: Connection) {
    connection.createStatement().use { stmt ->
        // `body` column for episodes is the pre-stripped text; see above.
        stmt.executeUpdate(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS podcastEpisodeFts USING fts5(
                title,
                podcastTitle,
                descriptionText,
                content='podcastEpisode',
                content_rowid='rowid',
                tokenize='unicode61 remove_diacritics 2',
                prefix='1 2'
            )
            """.trimIndent()
        )
        stmt.executeUpdate(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS podcastFts USING fts5(
                title,
                author,
                overrideTitle,
                content='podcast',
                content_rowid='rowid',
                tokenize='unicode61 remove_diacritics 2',
                prefix='1 2'
            )
            """.trimIndent()
        )

        // Internal-content rows are updated by hand for each event. The
        // 'delete' pseudo-row must name every indexed column with the OLD
        // values, or the index keeps the term frequencies of the previous text
        // and bm25 ranking drifts.
        stmt.executeUpdate(
            """
            CREATE TRIGGER IF NOT EXISTS podcastEpisodeFtsInsert AFTER INSERT ON podcastEpisode BEGIN
                INSERT INTO podcastEpisodeFts(rowid, title, podcastTitle, descriptionText)
                VALUES (new.rowid, new.title, new.podcastTitle, new.descriptionText);
            END
            """.trimIndent()
        )
        stmt.executeUpdate(
            """
            CREATE TRIGGER IF NOT EXISTS podcastEpisodeFtsDelete AFTER DELETE ON podcastEpisode BEGIN
                INSERT INTO podcastEpisodeFts(podcastEpisodeFts, rowid, title, podcastTitle, descriptionText)
                VALUES ('delete', old.rowid, old.title, old.podcastTitle, old.descriptionText);
            END
            """.trimIndent()
        )
        // `AFTER UPDATE OF` rather than plain `AFTER UPDATE`.
        //
        // EpisodeDao writes with `ON CONFLICT(id) DO UPDATE`, and the app also
        // touches `new`, `duration` and progress columns. Reindexing on every
        // such write would delete and re-insert the row's whole document —
        // including a multi-KB description — for a flag flip. The column list
        // keeps the trigger to writes that can actually change the index.
        stmt.executeUpdate(
            """
            CREATE TRIGGER IF NOT EXISTS podcastEpisodeFtsUpdate
            AFTER UPDATE OF title, podcastTitle, descriptionText ON podcastEpisode BEGIN
                INSERT INTO podcastEpisodeFts(podcastEpisodeFts, rowid, title, podcastTitle, descriptionText)
                VALUES ('delete', old.rowid, old.title, old.podcastTitle, old.descriptionText);
                INSERT INTO podcastEpisodeFts(rowid, title, podcastTitle, descriptionText)
                VALUES (new.rowid, new.title, new.podcastTitle, new.descriptionText);
            END
            """.trimIndent()
        )

        stmt.executeUpdate(
            """
            CREATE TRIGGER IF NOT EXISTS podcastFtsInsert AFTER INSERT ON podcast BEGIN
                INSERT INTO podcastFts(rowid, title, author, overrideTitle)
                VALUES (new.rowid, new.title, new.author, new.overrideTitle);
            END
            """.trimIndent()
        )
        stmt.executeUpdate(
            """
            CREATE TRIGGER IF NOT EXISTS podcastFtsDelete AFTER DELETE ON podcast BEGIN
                INSERT INTO podcastFts(podcastFts, rowid, title, author, overrideTitle)
                VALUES ('delete', old.rowid, old.title, old.author, old.overrideTitle);
            END
            """.trimIndent()
        )
        stmt.executeUpdate(
            """
            CREATE TRIGGER IF NOT EXISTS podcastFtsUpdate
            AFTER UPDATE OF title, author, overrideTitle ON podcast BEGIN
                INSERT INTO podcastFts(podcastFts, rowid, title, author, overrideTitle)
                VALUES ('delete', old.rowid, old.title, old.author, old.overrideTitle);
                INSERT INTO podcastFts(rowid, title, author, overrideTitle)
                VALUES (new.rowid, new.title, new.author, new.overrideTitle);
            END
            """.trimIndent()
        )
    }
}

/**
 * Adds [column] to [table] unless it is already there.
 *
 * Uses `PRAGMA table_info` instead of the `try { ALTER } catch (_: Exception) {}`
 * idiom used elsewhere in this file: inside a migration that catch would also
 * swallow genuine failures (a locked database, a corrupt schema) and then hand
 * the next statement a column that does not exist.
 */
internal fun addColumnIfMissing(
    connection: Connection,
    table: String,
    column: String,
    definition: String
) {
    val alreadyPresent = connection.createStatement().use { stmt ->
        stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
            var found = false
            while (rs.next()) {
                if (rs.getString("name").equals(column, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }
    }
    if (alreadyPresent) return
    connection.createStatement().use { stmt ->
        stmt.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
    }
}

/**
 * Fills `podcastEpisode.descriptionText` for rows written before v3.
 *
 * Done in Kotlin, not SQL: stripping markup needs the same [stripHtml] the UI
 * uses, and re-implementing it as nested `replace()` calls in SQLite would
 * handle only the tag shapes someone thought of. Only rows whose text is still
 * empty are touched, so running this twice is harmless.
 *
 * Batched around [SQL_VARIABLE_CHUNK] ids to stay under SQLite's bound-variable
 * limit on large libraries.
 */
internal fun backfillDescriptionText(connection: Connection) {
    val pending = connection.createStatement().use { stmt ->
        stmt.executeQuery(
            "SELECT rowid, description FROM podcastEpisode WHERE descriptionText = '' AND description != ''"
        ).use { rs ->
            val rows = mutableListOf<Pair<Long, String>>()
            while (rs.next()) rows.add(rs.getLong(1) to rs.getString(2))
            rows
        }
    }
    if (pending.isEmpty()) return

    connection.prepareStatement("UPDATE podcastEpisode SET descriptionText = ? WHERE rowid = ?").use { ps ->
        pending.forEach { (rowId, description) ->
            ps.setString(1, stripHtml(description))
            ps.setLong(2, rowId)
            ps.addBatch()
        }
        ps.executeBatch()
    }
}

class AppDatabase private constructor(private val connection: Connection) {

    companion object {
        /**
         * Current schema version, tracked in SQLite's `PRAGMA user_version`.
         *
         * Bump this and add a step to [MIGRATIONS] whenever a table or index
         * changes. Without a version the only migration this project had was a
         * bare `ALTER TABLE` wrapped in `catch (_: Exception) {}`, which cannot
         * tell "column already exists" apart from "the migration actually
         * failed" — so a genuinely broken upgrade looked exactly like a no-op.
         */
        const val SCHEMA_VERSION = 7

        fun build(databaseFile: File): AppDatabase {
            databaseFile.parentFile?.mkdirs()
            val connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")
            configureConnection(connection)
            return initialise(connection)
        }

        /**
         * Runs the schema setup and migrations, closing the connection again if
         * either fails.
         *
         * A refused migration (an unknown version, a newer database) is meant to
         * abort startup; leaking the JDBC connection on that path would keep a
         * lock on the file for the rest of the process.
         */
        private fun initialise(connection: Connection): AppDatabase {
            try {
                val db = AppDatabase(connection)
                db.createTables()
                db.migrate()
                return db
            } catch (failure: Throwable) {
                try {
                    connection.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        /**
         * A throwaway database that exists only in this process's memory.
         *
         * Same schema and same migrations as [build], but no file is created, no
         * WAL sidecars are written, and nothing is ever fsynced. Tests that
         * build a database just to have tables to read and write use this,
         * which is most of them: creating and migrating a file database costs a
         * file create, two WAL sidecars, and three DDL migrations, and paying
         * that once per test class showed up in test runs as hundreds of
         * repeated "Migrated database to schema version 1/2/3" log lines.
         *
         * Tests that verify persistence across a reopen must still use [build]
         * with a real file, because an in-memory database cannot be reopened.
         */
        fun buildInMemory(): AppDatabase {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            configureConnection(connection)
            return initialise(connection)
        }

        /**
         * Pragmas that must be set before any statement runs.
         *
         * - `busy_timeout`: without it SQLite gives up on a locked database
         *   instantly, so a second instance crashed during table creation
         *   instead of waiting for the first to finish.
         * - `journal_mode=WAL`: readers no longer block the writer, which
         *   matters because the download manager writes progress while the UI
         *   reads lists.
         * - `foreign_keys=ON`: SQLite defaults this OFF, so declared foreign
         *   keys would be decorative.
         * - `synchronous=NORMAL`: safe under WAL and much cheaper than FULL.
         */
        private fun configureConnection(connection: Connection) {
            connection.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA busy_timeout=5000")
                stmt.execute("PRAGMA foreign_keys=ON")
                stmt.execute("PRAGMA synchronous=NORMAL")
            }
        }
    }

    /**
     * Ordered migration steps, indexed by the version they upgrade *to*.
     *
     * Each entry runs exactly once, inside a transaction, and only when the
     * database's `user_version` is lower than its target.
     */
    private fun migrate() {
        val current = connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        if (current > SCHEMA_VERSION) {
            // A newer build wrote this file. Running the old DDL against it would
            // silently drop the columns/indexes that build added, so refuse
            // instead of opening it half-understood.
            error(
                "Database schema version $current is newer than this build supports " +
                    "($SCHEMA_VERSION); refusing to open it"
            )
        }
        if (current == SCHEMA_VERSION) return

        for (version in (current + 1)..SCHEMA_VERSION) {
            connection.inTransactionSync {
                when (version) {
                    // v1 is the base schema, created by `createTables` for both
                    // new and existing files; there is nothing to upgrade.
                    1 -> Unit

                    // v1 → v2: the query indexes. Everything before this ran on
                    // primary-key autoindexes alone, so listing a podcast's
                    // episodes was a full table scan plus a temp B-tree sort.
                    2 -> connection.createStatement().use { stmt ->
                        stmt.executeUpdate(
                            "CREATE INDEX IF NOT EXISTS idx_episode_origin_pubDate " +
                                "ON podcastEpisode(origin, pubDate DESC)"
                        )
                        stmt.executeUpdate(
                            "CREATE INDEX IF NOT EXISTS idx_history_origin_ts " +
                                "ON podcastHistory(origin, timestamp DESC)"
                        )
                        stmt.executeUpdate(
                            "CREATE INDEX IF NOT EXISTS idx_history_episode " +
                                "ON podcastHistory(episodeId)"
                        )
                        stmt.executeUpdate(
                            "CREATE INDEX IF NOT EXISTS idx_download_origin " +
                                "ON podcastDownload(origin)"
                        )
                        stmt.executeUpdate(
                            "CREATE INDEX IF NOT EXISTS idx_episode_progress_lastPlayed " +
                                "ON podcastEpisodeProgress(lastPlayedAt DESC)"
                        )
                    }

                    // v2 → v3: full-text search over episodes and podcasts.
                    //
                    // Order matters in a way that is easy to get wrong: the FTS
                    // *search* tables and their triggers must be gone before
                    // `descriptionText` is written, because the episode UPDATE
                    // trigger would otherwise fire while indexing a table whose
                    // shadow rows do not exist yet, and SQLite raises
                    // SQLITE_CORRUPT_VTAB. Dropping the triggers and virtual
                    // tables first makes the whole step idempotent from any
                    // starting state — including a database that already has
                    // search objects because a previous upgrade was interrupted
                    // between `user_version` and the rebuild.
                    //
                    // 1. drop stale search objects (safe when absent)
                    // 2. add `descriptionText` and backfill it with stripped text,
                    //    so the index never contains raw markup (`<p>`, `href`)
                    // 3. create the tables and triggers
                    // 4. `rebuild` so pre-existing rows are searchable at once
                    3 -> {
                        dropSearchSchema(connection)
                        addColumnIfMissing(connection, "podcastEpisode", "descriptionText", "TEXT NOT NULL DEFAULT ''")
                        backfillDescriptionText(connection)
                        createSearchSchema(connection)
                        connection.createStatement().use { stmt ->
                            stmt.executeUpdate(
                                "INSERT INTO podcastEpisodeFts(podcastEpisodeFts) VALUES('rebuild')"
                            )
                            stmt.executeUpdate("INSERT INTO podcastFts(podcastFts) VALUES('rebuild')")
                        }
                    }

                    // v3 → v4: the ORDER BY clauses finally get indexes that
                    // cover their tiebreak column.
                    //
                    // Both listing queries sort by `timestamp DESC, id DESC` /
                    // `pubDate DESC, id DESC`, but v2 built the indexes without
                    // the trailing `id`; SQLite cannot satisfy the second sort
                    // key from an index that stops one column short, so it
                    // scanned and sorted into a temp B-tree on every history and
                    // episode listing.
                    //
                    // The episode index is dropped first because v2 created it
                    // under the same name with the shorter column list;
                    // `CREATE INDEX IF NOT EXISTS` alone would leave the
                    // two-column version in place forever.
                    4 -> connection.createStatement().use { stmt ->
                        stmt.executeUpdate(
                            "CREATE INDEX IF NOT EXISTS idx_history_timestamp_id " +
                                "ON podcastHistory(timestamp DESC, id DESC)"
                        )
                        stmt.executeUpdate("DROP INDEX IF EXISTS idx_episode_origin_pubDate")
                        stmt.executeUpdate(
                            "CREATE INDEX IF NOT EXISTS idx_episode_origin_pubDate " +
                                "ON podcastEpisode(origin, pubDate DESC, id DESC)"
                        )
                    }

                    // v4 → v5: remember the stream URL of a downloaded queue
                    // item, and drop the sync-action table.
                    //
                    // `QueueItem.fallbackUrl` is what makes "delete the download,
                    // keep playing" work: a queue row records the local file
                    // *and* the stream it came from, so a restored session whose
                    // download is gone can still play instead of failing with a
                    // FILE error. The old rows have no stream URL to recover —
                    // the column is simply null for them, which is the same
                    // state as an episode that was never downloaded.
                    //
                    // `syncAction` was written by nothing: `SyncManager` was
                    // never instantiated and no code enqueued an action, so the
                    // table and the DAO that read it were dead weight. Dropping
                    // it is safe because there is nothing to lose.
                    5 -> {
                        addColumnIfMissing(connection, "playerQueueItem", "fallbackUrl", "TEXT")
                        connection.createStatement().use { stmt ->
                            stmt.executeUpdate("DROP TABLE IF EXISTS syncAction")
                        }
                    }

                    // v5 → v6: per-podcast playback speed override.
                    //
                    // The player's speed was purely session-level: `playerSession`
                    // remembered the last speed used by *any* show and applied it to
                    // the next one. `playbackSpeedOverride` lets one show pin its
                    // own speed — written when the user changes the speed while one
                    // of its episodes plays, cleared from the show's menu. NULL (the
                    // state of every pre-existing row, and of a fresh subscription)
                    // keeps the old behaviour: follow the session speed.
                    6 -> addColumnIfMissing(connection, "podcastSubscription", "playbackSpeedOverride", "REAL")

                    // v6 → v7: per-podcast "keep latest N downloads" auto-cleanup.
                    //
                    // Downloads only ever grew: finished episodes stayed on disk
                    // until the user deleted them one by one. `keepDownloads` lets
                    // one show prune its own downloads to the most recent N,
                    // enforced after a feed refresh added episodes and whenever a
                    // download of that show completes. 0 (the state of every
                    // pre-existing row, and of a fresh subscription) keeps the old
                    // behaviour: nothing is cleaned up automatically.
                    7 -> addColumnIfMissing(connection, "podcastSubscription", "keepDownloads", "INTEGER NOT NULL DEFAULT 0")

                    // Reaching here means SCHEMA_VERSION was bumped without a
                    // step for it. Failing is the whole point: the previous code
                    // wrote `user_version` anyway, so the missing migration
                    // became permanent and silent.
                    else -> error("No migration defined for schema version $version")
                }
                connection.createStatement().use { stmt ->
                    stmt.executeUpdate("PRAGMA user_version=$version")
                }
            }
            Logger.i("AppDatabase", "Migrated database to schema version $version")
        }
    }

    private fun createTables() {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS podcast (
                    origin TEXT NOT NULL PRIMARY KEY,
                    link TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    author TEXT NOT NULL,
                    imageUrl TEXT NOT NULL,
                    imageSeedColor INTEGER NOT NULL DEFAULT 0,
                    languageCode TEXT NOT NULL,
                    fileSize INTEGER NOT NULL DEFAULT 0,
                    overrideTitle TEXT NOT NULL DEFAULT ''
                )
            """)
            // Old installs still carry skipBeginning/skipEnding columns on this table.
            // Nothing reads them and new databases stop creating them; the INSERT below
            // names its columns explicitly, so both shapes work.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS podcastEpisode (
                    id TEXT NOT NULL PRIMARY KEY,
                    guid TEXT NOT NULL,
                    origin TEXT NOT NULL,
                    link TEXT NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    imageUrl TEXT,
                    author TEXT NOT NULL,
                    pubDate INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    audioUrl TEXT NOT NULL,
                    podcastTitle TEXT NOT NULL,
                    imageSeedColor INTEGER NOT NULL DEFAULT 0,
                    new INTEGER NOT NULL DEFAULT 0,
                    descriptionText TEXT NOT NULL DEFAULT ''
                )
            """)
            // Fresh databases get the search tables here rather than through the
            // v3 migration, which only runs on upgrades. Both paths call the same
            // builder so a new install and an upgraded one end up identical.
            createSearchSchema(connection)
            // The play-state table was written by initState and read by nothing.
            // DROP (not just stop creating) so existing installs shed it too;
            // dropping a table is safe in SQLite, unlike dropping a column.
            stmt.executeUpdate("DROP TABLE IF EXISTS podcastEpisodePlayState")
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS podcastHistory (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    origin TEXT NOT NULL,
                    episodeId TEXT NOT NULL,
                    timestamp INTEGER NOT NULL
                )
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS podcastFavorite (
                    episodeId TEXT NOT NULL PRIMARY KEY,
                    origin TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    podcastTitle TEXT NOT NULL DEFAULT '',
                    imageUrl TEXT,
                    audioUrl TEXT NOT NULL DEFAULT '',
                    duration INTEGER NOT NULL DEFAULT 0,
                    pubDate INTEGER NOT NULL DEFAULT 0
                )
            """)
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_podcastFavorite_timestamp ON podcastFavorite(timestamp DESC)")
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS podcastSubscription (
                    origin TEXT NOT NULL PRIMARY KEY,
                    enableNotifications INTEGER NOT NULL DEFAULT 0,
                    enableAutoDownload INTEGER NOT NULL DEFAULT 0,
                    lastUpdate INTEGER NOT NULL DEFAULT 0,
                    newEpisodes INTEGER NOT NULL DEFAULT 0,
                    cacheETag TEXT NOT NULL DEFAULT '',
                    cacheLastModified TEXT NOT NULL DEFAULT '',
                    cacheContentLength TEXT NOT NULL DEFAULT '',
                    playbackSpeedOverride REAL,
                    keepDownloads INTEGER NOT NULL DEFAULT 0
                )
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS podcastDownload (
                    episodeId TEXT NOT NULL PRIMARY KEY,
                    origin TEXT NOT NULL,
                    filePath TEXT NOT NULL,
                    podcastTitle TEXT NOT NULL,
                    episodeTitle TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL
                )
            """)
            // Old installs predate `episodeTitle`. This has to run *after* the
            // CREATE above: as a bare `try { ALTER } catch (_: Exception) {}`
            // placed earlier in this function it threw "no such table" on every
            // fresh install and swallowed that, while also swallowing genuine
            // failures (locked database, I/O error, wrong column name) and
            // leaving the next reader to fail far from the cause. `addColumnIfMissing`
            // checks PRAGMA table_info instead of guessing from an exception.
            addColumnIfMissing(connection, "podcastDownload", "episodeTitle", "TEXT NOT NULL DEFAULT ''")
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS downloadTask (
                    episodeId TEXT NOT NULL PRIMARY KEY,
                    origin TEXT NOT NULL,
                    audioUrl TEXT NOT NULL,
                    podcastTitle TEXT NOT NULL,
                    episodeTitle TEXT NOT NULL,
                    targetFilePath TEXT NOT NULL DEFAULT '',
                    downloadedBytes INTEGER NOT NULL DEFAULT 0,
                    totalBytes INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL DEFAULT 'DOWNLOADING',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS podcastItunesLookup (
                    itunesUrl TEXT NOT NULL PRIMARY KEY,
                    rssUrl TEXT NOT NULL
                )
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS playerSession (
                    id INTEGER NOT NULL PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                    queueIndex INTEGER NOT NULL DEFAULT -1,
                    currentPositionMs INTEGER NOT NULL DEFAULT 0,
                    playbackSpeed REAL NOT NULL DEFAULT 1.0,
                    volume INTEGER NOT NULL DEFAULT 100,
                    currentEpisodeId TEXT,
                    lastUpdate INTEGER NOT NULL DEFAULT 0
                )
            """)
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS playerQueueItem (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    queueOrder INTEGER NOT NULL,
                    url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    subtitle TEXT,
                    artworkUrl TEXT,
                    podcastArtworkUrl TEXT,
                    episodeId TEXT,
                    isDownloaded INTEGER NOT NULL DEFAULT 0,
                    fallbackUrl TEXT
                )
            """)
            // Per-episode resume points.
            //
            // `playerSession` above is a single row describing *the one* episode
            // the player currently holds, so switching episodes discarded the
            // previous position. Every resume feature ("continue listening",
            // per-episode progress bars, playing the next unwatched episode)
            // needs one row per episode instead.
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS podcastEpisodeProgress (
                    episodeId TEXT NOT NULL PRIMARY KEY,
                    origin TEXT NOT NULL,
                    positionMs INTEGER NOT NULL DEFAULT 0,
                    durationMs INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL DEFAULT 0,
                    lastPlayedAt INTEGER NOT NULL DEFAULT 0
                )
            """)
        }
    }

    val podcasts = PodcastDao(connection)
    val episodes = EpisodeDao(connection)
    val history = HistoryDao(connection)
    val favorites = FavoriteDao(connection)
    val subscriptions = SubscriptionDao(connection)
    val downloads = DownloadDao(connection)
    val downloadTasks = DownloadTaskDao(connection)
    val itunesLookup = ItunesLookupDao(connection)
    val playerSession = PlayerSessionDao(connection)
    val playerQueue = PlayerQueueDao(connection)
    val progress = EpisodeProgressDao(connection)
    val search = SearchDao(connection)

    /** Runs database-only work atomically on the serialized database dispatcher. */
    internal suspend fun <T> transaction(block: suspend AppDatabase.() -> T): T = withContext(DatabaseDispatcher) {
        connection.inTransaction { block() }
    }

    fun close() { connection.close() }
}

class PodcastDao(private val conn: Connection) {
    suspend fun getAllSync(): List<Podcast> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<Podcast>()
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM podcast ORDER BY title ASC").use { rs ->
                while (rs.next()) {
                    list.add(readPodcastRow(rs))
                }
            }
        }
        list
    }

    suspend fun getAllOrigins(): Set<String> = withContext(DatabaseDispatcher) {
        val origins = mutableSetOf<String>()
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT origin FROM podcast").use { rs ->
                while (rs.next()) origins.add(rs.getString("origin"))
            }
        }
        origins
    }

    suspend fun getByOrigin(origin: String): Podcast? = withContext(DatabaseDispatcher) {
        conn.prepareStatement("SELECT * FROM podcast WHERE origin = ?").use { ps ->
            ps.setString(1, origin)
            ps.executeQuery().use { rs ->
                if (rs.next()) readPodcastRow(rs) else null
            }
        }
    }

    suspend fun insert(podcast: Podcast) = withContext(DatabaseDispatcher) {
        conn.prepareStatement(
            """
            INSERT INTO podcast (origin, link, title, description, author, imageUrl, imageSeedColor, languageCode, fileSize, overrideTitle)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(origin) DO UPDATE SET
                link = excluded.link,
                title = excluded.title,
                description = excluded.description,
                author = excluded.author,
                imageUrl = excluded.imageUrl,
                imageSeedColor = excluded.imageSeedColor,
                languageCode = excluded.languageCode,
                fileSize = excluded.fileSize
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, podcast.origin); ps.setString(2, podcast.link); ps.setString(3, podcast.title)
            ps.setString(4, podcast.description); ps.setString(5, podcast.author); ps.setString(6, podcast.imageUrl)
            ps.setInt(7, podcast.imageSeedColor); ps.setString(8, podcast.languageCode); ps.setLong(9, podcast.fileSize)
            ps.setString(10, podcast.overrideTitle)
            ps.executeUpdate()
        }
    }

    suspend fun delete(origin: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcast WHERE origin = ?").useResource {
            setString(1, origin); executeUpdate()
        }
    }
}

/**
 * Maps one `podcast` row to its model; see [readEpisodeRow] for why it is not a
 * DAO member.
 */
internal fun readPodcastRow(rs: java.sql.ResultSet) = Podcast(
    origin = rs.getString("origin"), link = rs.getString("link"), title = rs.getString("title"),
    description = rs.getString("description"), author = rs.getString("author"),
    imageUrl = rs.getString("imageUrl"), imageSeedColor = rs.getInt("imageSeedColor"),
    languageCode = rs.getString("languageCode"), fileSize = rs.getLong("fileSize"),
    overrideTitle = rs.getString("overrideTitle")
)

class EpisodeDao(private val conn: Connection) {
    suspend fun getAllByOrigin(origin: String): List<PodcastEpisode> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<PodcastEpisode>()
        // `id` breaks pubDate ties, which are common: a feed that omits
        // per-episode dates yields 0 for all of them, and several episodes
        // published the same day parse to the same instant. Without a tiebreak
        // the list order could change between two identical reads.
        conn.prepareStatement(
            "SELECT * FROM podcastEpisode WHERE origin = ? ORDER BY pubDate DESC, id DESC"
        ).use { ps ->
            ps.setString(1, origin)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(readEpisode(rs))
            }
        }
        list
    }

    suspend fun getById(id: String): PodcastEpisode? = withContext(DatabaseDispatcher) {
        conn.prepareStatement("SELECT * FROM podcastEpisode WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) readEpisode(rs) else null
            }
        }
    }

    /**
     * Artwork URLs for [ids], keyed by episode id.
     *
     * The Downloads screen needs one image URL per downloaded episode and used to
     * call [getById] once per row to get it. That is a `SELECT *`, so every call
     * dragged the episode's description — routinely several KB of HTML — out of
     * SQLite on the single-threaded database dispatcher just to read one short
     * column. Selecting the two columns and batching the ids turns N round trips
     * into one per [SQL_VARIABLE_CHUNK] ids.
     *
     * @param ids episode ids; duplicates are harmless.
     * @return url by episode id. Ids with no episode row are absent, while ids
     *   whose row has no artwork are present with a null value — the key set is
     *   exactly "episodes we have a record of", which the caller cannot tell from
     *   a null value alone.
     */
    suspend fun getImageUrlsByIds(ids: List<String>): Map<String, String?> =
        withContext(DatabaseDispatcher) {
            val urls = mutableMapOf<String, String?>()
            if (ids.isEmpty()) return@withContext urls

            // Chunked to stay well under SQLite's variable limit for large libraries.
            ids.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                conn.prepareStatement(
                    "SELECT id, imageUrl FROM podcastEpisode WHERE id IN ($placeholders)"
                ).use { ps ->
                    chunk.forEachIndexed { index, id -> ps.setString(index + 1, id) }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            urls[rs.getString("id")] = rs.getString("imageUrl")
                        }
                    }
                }
            }
            urls
        }

    /**
     * Stream URL by episode id, for the same reason as [getImageUrlsByIds].
     *
     * The Downloads screen knows only download rows, which record the local file
     * path and no stream. A queue item built there therefore had no fallback to
     * play if the user deleted the download afterwards, and that is exactly the
     * case `QueueItem.fallbackUrl` exists for.
     *
     * @return url by episode id. Ids with no episode row are absent; an episode
     *   stored without an audio URL is present with a null value.
     */
    suspend fun getAudioUrlsByIds(ids: List<String>): Map<String, String?> =
        withContext(DatabaseDispatcher) {
            val urls = mutableMapOf<String, String?>()
            if (ids.isEmpty()) return@withContext urls

            ids.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                conn.prepareStatement(
                    "SELECT id, audioUrl FROM podcastEpisode WHERE id IN ($placeholders)"
                ).use { ps ->
                    chunk.forEachIndexed { index, id -> ps.setString(index + 1, id) }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            urls[rs.getString("id")] = rs.getString("audioUrl")
                        }
                    }
                }
            }
            urls
        }

    /**
     * Episode count and newest pubDate per origin, in one pass.     *
 * Exists so the Home screen can show "N episodes" and sort by "recent update"
 * without materialising every episode row. `getAllByOrigin` is a `SELECT *`,
 * so doing it per podcast pulls each episode's title, description and audio URL
 * — descriptions are often several KB of HTML — out of SQLite only to compute
 * two aggregates in Kotlin.
 *
 * @param origins origins to report; those with no episodes are omitted from the
 *   maps rather than reported as zero.
 * @return counts by origin and newest pubDate by origin.
 */
suspend fun getCountsAndLatestByOrigin(origins: List<String>): Pair<Map<String, Int>, Map<String, Long>> =
    withContext(DatabaseDispatcher) {
        val counts = mutableMapOf<String, Int>()
        val latest = mutableMapOf<String, Long>()
        if (origins.isEmpty()) return@withContext counts to latest

        // Chunked to stay well under SQLite's variable limit for large libraries.
        origins.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            conn.prepareStatement(
                """
                SELECT origin, COUNT(*) AS episodeCount, MAX(pubDate) AS latestPubDate
                FROM podcastEpisode
                WHERE origin IN ($placeholders)
                GROUP BY origin
                """.trimIndent()
            ).use { ps ->
                chunk.forEachIndexed { index, origin -> ps.setString(index + 1, origin) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val origin = rs.getString("origin")
                        counts[origin] = rs.getInt("episodeCount")
                        latest[origin] = rs.getLong("latestPubDate")
                    }
                }
            }
        }
        counts to latest
    }

    suspend fun getEpisodeIds(origin: String): List<String> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<String>()
        conn.prepareStatement("SELECT id FROM podcastEpisode WHERE origin = ?").use { ps ->
            ps.setString(1, origin)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(rs.getString("id"))
            }
        }
        list
    }

    suspend fun deleteByOrigin(origin: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastEpisode WHERE origin = ?").useResource {
            setString(1, origin); executeUpdate()
        }
    }

    suspend fun insert(episode: PodcastEpisode) = withContext(DatabaseDispatcher) {
        conn.prepareStatement(
            """
            INSERT INTO podcastEpisode (id, guid, origin, link, title, description, imageUrl, author, pubDate, duration, audioUrl, podcastTitle, imageSeedColor, new, descriptionText)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                guid = excluded.guid,
                origin = excluded.origin,
                link = excluded.link,
                title = excluded.title,
                description = excluded.description,
                imageUrl = excluded.imageUrl,
                author = excluded.author,
                pubDate = excluded.pubDate,
                duration = excluded.duration,
                audioUrl = excluded.audioUrl,
                podcastTitle = excluded.podcastTitle,
                imageSeedColor = excluded.imageSeedColor,
                descriptionText = excluded.descriptionText
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, episode.id); ps.setString(2, episode.guid); ps.setString(3, episode.origin)
            ps.setString(4, episode.link); ps.setString(5, episode.title); ps.setString(6, episode.description)
            ps.setString(7, episode.imageUrl); ps.setString(8, episode.author); ps.setLong(9, episode.pubDate)
            ps.setInt(10, episode.duration); ps.setString(11, episode.audioUrl); ps.setString(12, episode.podcastTitle)
            ps.setInt(13, episode.imageSeedColor); ps.setInt(14, if (episode.isNew) 1 else 0)
            // The FTS index reads this column instead of `description`.
            // FTS5 cannot strip HTML, and the raw description is full of tags —
            // indexing it would make `div` and `href` searchable and bury the
            // real words. Stripping here, once per write, is the only place the
            // plain text can be produced: an SQL trigger cannot call Kotlin.
            // See `createSearchSchema` for what depends on this.
            ps.setString(15, stripHtml(episode.description))
            ps.executeUpdate()
        }
    }

    private fun readEpisode(rs: java.sql.ResultSet) = readEpisodeRow(rs)
}

/**
 * Maps one `podcastEpisode` row to its model.
 *
 * File-scope rather than a member of [EpisodeDao] so [SearchDao] can share it:
 * the search query selects `episode.*` through a join and needs exactly the
 * same mapping, and a second copy is a second place for the column list to rot.
 */
internal fun readEpisodeRow(rs: java.sql.ResultSet) = PodcastEpisode(
    id = rs.getString("id"), guid = rs.getString("guid"), origin = rs.getString("origin"),
    link = rs.getString("link"), title = rs.getString("title"), description = rs.getString("description"),
    imageUrl = rs.getString("imageUrl"), author = rs.getString("author"), pubDate = rs.getLong("pubDate"),
    duration = rs.getInt("duration"), audioUrl = rs.getString("audioUrl"),
    podcastTitle = rs.getString("podcastTitle"), imageSeedColor = rs.getInt("imageSeedColor"),
    isNew = rs.getInt("new") == 1
)

class HistoryDao(private val conn: Connection) {
    suspend fun getAllSync(): List<PodcastHistory> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<PodcastHistory>()
        conn.createStatement().use { statement ->
            // `id DESC` breaks timestamp ties. Two plays within the same
            // millisecond are entirely normal (a fast double-click, a queue
            // advancing), and `ORDER BY timestamp DESC` alone leaves their
            // relative order to whatever the plan happens to produce — which
            // changed once the covering index existed.
            statement.executeQuery(
                "SELECT * FROM podcastHistory ORDER BY timestamp DESC, id DESC"
            ).use { rs ->
                while (rs.next()) list.add(PodcastHistory(
                    id = rs.getInt("id"), origin = rs.getString("origin"),
                    episodeId = rs.getString("episodeId"), timestamp = rs.getLong("timestamp")
                ))
            }
        }
        list
    }

    /** Returns the latest listen timestamp per podcast origin. */
    suspend fun getLatestTimestampPerOrigin(): Map<String, Long> = withContext(DatabaseDispatcher) {
        val map = mutableMapOf<String, Long>()
        conn.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT origin, MAX(timestamp) AS maxTs FROM podcastHistory GROUP BY origin"
            ).use { rs ->
                while (rs.next()) map[rs.getString("origin")] = rs.getLong("maxTs")
            }
        }
        map
    }

    suspend fun getAllWithEpisode(): List<Pair<PodcastHistory, PodcastEpisode?>> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<Pair<PodcastHistory, PodcastEpisode?>>()
        conn.createStatement().use { statement ->
            statement.executeQuery(
                """SELECT h.id, h.origin, h.episodeId, h.timestamp,
               e.title as epTitle, e.audioUrl as epAudioUrl, e.imageUrl as epImageUrl,
               e.podcastTitle as epPodcastTitle, e.duration as epDuration,
               p.imageUrl as podcastImageUrl
               FROM podcastHistory h
               LEFT JOIN podcastEpisode e ON h.episodeId = e.id
               LEFT JOIN podcast p ON h.origin = p.origin
               ORDER BY h.timestamp DESC"""
            ).use { rs ->
                while (rs.next()) {
                    val history = PodcastHistory(
                        id = rs.getInt("id"), origin = rs.getString("origin"),
                        episodeId = rs.getString("episodeId"), timestamp = rs.getLong("timestamp")
                    )
                    val epTitle = rs.getString("epTitle")
                    val episode = if (epTitle != null) PodcastEpisode(
                        id = history.episodeId, guid = "", origin = history.origin,
                        link = "", title = epTitle, description = "",
                        imageUrl = rs.getString("epImageUrl") ?: rs.getString("podcastImageUrl"), author = "",
                        pubDate = 0, duration = rs.getInt("epDuration"),
                        audioUrl = rs.getString("epAudioUrl") ?: "",
                        podcastTitle = rs.getString("epPodcastTitle") ?: ""
                    ) else null
                    list.add(history to episode)
                }
            }
        }
        list
    }

    suspend fun insert(origin: String, episodeId: String) = withContext(DatabaseDispatcher) {
        val ts = System.currentTimeMillis()
        conn.prepareStatement("INSERT INTO podcastHistory (origin, episodeId, timestamp) VALUES (?, ?, ?)").useResource {
            setString(1, origin); setString(2, episodeId); setLong(3, ts); executeUpdate()
        }
    }

    /**
     * Deletes one history row by its own id.
     *
     * History is one row per listen, so the same episode can appear many times.
     * Deleting by episodeId removed *every* occurrence of that episode — the
     * ✕ on one row wiped the whole episode from history.
     */
    suspend fun deleteById(id: Int) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastHistory WHERE id = ?").useResource {
            setInt(1, id); executeUpdate()
        }
    }

    suspend fun deleteAll() = withContext(DatabaseDispatcher) {
        conn.createStatement().use { it.executeUpdate("DELETE FROM podcastHistory") }
    }
}

class FavoriteDao(private val conn: Connection) {
    suspend fun getAllSync(): List<PodcastFavorite> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<PodcastFavorite>()
        conn.createStatement().use { statement ->
            // `episodeId` is the primary key, so it makes the order total —
            // two favourites saved in the same millisecond would otherwise
            // swap places between reads.
            statement.executeQuery(
                "SELECT * FROM podcastFavorite ORDER BY timestamp DESC, episodeId ASC"
            ).use { rs ->
                while (rs.next()) list.add(readFavorite(rs))
            }
        }
        list
    }

    suspend fun getAllEpisodeIds(): Set<String> = withContext(DatabaseDispatcher) {
        val ids = mutableSetOf<String>()
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT episodeId FROM podcastFavorite").use { rs ->
                while (rs.next()) ids.add(rs.getString("episodeId"))
            }
        }
        ids
    }

    suspend fun isFavorite(episodeId: String): Boolean = withContext(DatabaseDispatcher) {
        conn.prepareStatement("SELECT 1 FROM podcastFavorite WHERE episodeId = ? LIMIT 1").use { ps ->
            ps.setString(1, episodeId)
            ps.executeQuery().use { it.next() }
        }
    }

    suspend fun getAllWithEpisode(): List<Pair<PodcastFavorite, PodcastEpisode?>> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<Pair<PodcastFavorite, PodcastEpisode?>>()
        conn.createStatement().use { statement ->
            statement.executeQuery(
                """SELECT f.episodeId, f.origin, f.timestamp,
               f.title as favTitle, f.podcastTitle as favPodcastTitle, f.imageUrl as favImageUrl,
               f.audioUrl as favAudioUrl, f.duration as favDuration, f.pubDate as favPubDate,
               e.guid as epGuid, e.link as epLink, e.title as epTitle, e.description as epDescription,
               e.imageUrl as epImageUrl, e.author as epAuthor, e.pubDate as epPubDate,
               e.duration as epDuration, e.audioUrl as epAudioUrl, e.podcastTitle as epPodcastTitle,
               e.imageSeedColor as epImageSeedColor, e.new as epNew,
               p.imageUrl as podcastImageUrl
               FROM podcastFavorite f
               LEFT JOIN podcastEpisode e ON f.episodeId = e.id
               LEFT JOIN podcast p ON f.origin = p.origin
               ORDER BY f.timestamp DESC"""
            ).use { rs ->
                while (rs.next()) {
                    val favorite = PodcastFavorite(
                        episodeId = rs.getString("episodeId"),
                        origin = rs.getString("origin"),
                        timestamp = rs.getLong("timestamp"),
                        title = rs.getString("favTitle") ?: "",
                        podcastTitle = rs.getString("favPodcastTitle") ?: "",
                        imageUrl = rs.getString("favImageUrl"),
                        audioUrl = rs.getString("favAudioUrl") ?: "",
                        duration = rs.getInt("favDuration"),
                        pubDate = rs.getLong("favPubDate")
                    )
                    val title = rs.getString("epTitle") ?: favorite.title
                    val episode = if (title.isNotBlank()) PodcastEpisode(
                        id = favorite.episodeId,
                        guid = rs.getString("epGuid") ?: "",
                        origin = favorite.origin,
                        link = rs.getString("epLink") ?: "",
                        title = title,
                        description = rs.getString("epDescription") ?: "",
                        imageUrl = rs.getString("epImageUrl") ?: favorite.imageUrl ?: rs.getString("podcastImageUrl"),
                        author = rs.getString("epAuthor") ?: "",
                        pubDate = rs.getLong("epPubDate").takeIf { rs.getString("epTitle") != null } ?: favorite.pubDate,
                        duration = rs.getInt("epDuration").takeIf { rs.getString("epTitle") != null } ?: favorite.duration,
                        audioUrl = rs.getString("epAudioUrl") ?: favorite.audioUrl,
                        podcastTitle = rs.getString("epPodcastTitle") ?: favorite.podcastTitle,
                        imageSeedColor = rs.getInt("epImageSeedColor"),
                        isNew = rs.getInt("epNew") == 1
                    ) else null
                    list.add(favorite to episode)
                }
            }
        }
        list
    }

    suspend fun insert(episode: PodcastEpisode) = insert(
        PodcastFavorite(
            episodeId = episode.id,
            origin = episode.origin,
            timestamp = System.currentTimeMillis(),
            title = episode.title,
            podcastTitle = episode.podcastTitle,
            imageUrl = episode.imageUrl,
            audioUrl = episode.audioUrl,
            duration = episode.duration,
            pubDate = episode.pubDate
        )
    )

    suspend fun insert(favorite: PodcastFavorite) = withContext(DatabaseDispatcher) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO podcastFavorite (episodeId, origin, timestamp, title, podcastTitle, imageUrl, audioUrl, duration, pubDate) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).useResource {
            setString(1, favorite.episodeId); setString(2, favorite.origin); setLong(3, favorite.timestamp)
            setString(4, favorite.title); setString(5, favorite.podcastTitle); setString(6, favorite.imageUrl)
            setString(7, favorite.audioUrl); setInt(8, favorite.duration); setLong(9, favorite.pubDate)
            executeUpdate()
        }
    }

    /**
     * Adds [episode] to the favourites, or removes it, and reports the new state.
     *
     * The read and the write are one transaction. They used to be two separate
     * autocommitted statements, so two toggles racing on the same episode — a
     * double-clicked heart, or the row activated twice while the first write
     * was still queued — both read the same starting state and both did the
     * same thing. Two clicks then behaved as one, and a toggle-off interleaved
     * with a toggle-on could leave the opposite of what the user last asked
     * for.
     */
    suspend fun toggle(episode: PodcastEpisode): Boolean = withContext(DatabaseDispatcher) {
        conn.inTransaction {
            if (isFavorite(episode.id)) {
                delete(episode.id)
                false
            } else {
                insert(episode)
                true
            }
        }
    }

    suspend fun delete(episodeId: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastFavorite WHERE episodeId = ?").useResource {
            setString(1, episodeId); executeUpdate()
        }
    }

    suspend fun deleteAll() = withContext(DatabaseDispatcher) {
        conn.createStatement().use { it.executeUpdate("DELETE FROM podcastFavorite") }
    }

    private fun readFavorite(rs: java.sql.ResultSet) = PodcastFavorite(
        episodeId = rs.getString("episodeId"),
        origin = rs.getString("origin"),
        timestamp = rs.getLong("timestamp"),
        title = rs.getString("title") ?: "",
        podcastTitle = rs.getString("podcastTitle") ?: "",
        imageUrl = rs.getString("imageUrl"),
        audioUrl = rs.getString("audioUrl") ?: "",
        duration = rs.getInt("duration"),
        pubDate = rs.getLong("pubDate")
    )
}

class SubscriptionDao(private val conn: Connection) {
    suspend fun getAllSync(): List<PodcastSubscription> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<PodcastSubscription>()
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM podcastSubscription ORDER BY origin ASC").use { rs ->
                while (rs.next()) list.add(PodcastSubscription(
                    origin = rs.getString("origin"), enableNotifications = rs.getInt("enableNotifications") == 1,
                    enableAutoDownload = rs.getInt("enableAutoDownload") == 1, lastUpdate = rs.getLong("lastUpdate"),
                    newEpisodes = rs.getInt("newEpisodes"), cacheETag = rs.getString("cacheETag"),
                    cacheLastModified = rs.getString("cacheLastModified"), cacheContentLength = rs.getString("cacheContentLength"),
                    playbackSpeedOverride = rs.getFloat("playbackSpeedOverride").takeUnless { rs.wasNull() },
                    keepDownloads = rs.getInt("keepDownloads")
                ))
            }
        }
        list
    }

    suspend fun getByOriginSync(origin: String): PodcastSubscription? = withContext(DatabaseDispatcher) {
        conn.prepareStatement("SELECT * FROM podcastSubscription WHERE origin = ?").use { ps ->
            ps.setString(1, origin)
            ps.executeQuery().use { rs ->
                if (rs.next()) PodcastSubscription(
                    origin = rs.getString("origin"), enableNotifications = rs.getInt("enableNotifications") == 1,
                    enableAutoDownload = rs.getInt("enableAutoDownload") == 1, lastUpdate = rs.getLong("lastUpdate"),
                    newEpisodes = rs.getInt("newEpisodes"), cacheETag = rs.getString("cacheETag"),
                    cacheLastModified = rs.getString("cacheLastModified"), cacheContentLength = rs.getString("cacheContentLength"),
                    playbackSpeedOverride = rs.getFloat("playbackSpeedOverride").takeUnless { rs.wasNull() },
                    keepDownloads = rs.getInt("keepDownloads")
                ) else null
            }
        }
    }

    suspend fun insert(origin: String, enableNotifications: Boolean, enableAutoDownload: Boolean) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("INSERT OR IGNORE INTO podcastSubscription (origin, enableNotifications, enableAutoDownload, lastUpdate, newEpisodes, cacheETag, cacheLastModified, cacheContentLength) VALUES (?, ?, ?, 0, 0, '', '', '')").use { ps ->
            ps.setString(1, origin); ps.setInt(2, if (enableNotifications) 1 else 0); ps.setInt(3, if (enableAutoDownload) 1 else 0)
            ps.executeUpdate()
        }
    }

    suspend fun updateCache(origin: String, eTag: String, lastModified: String, contentLength: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("UPDATE podcastSubscription SET cacheETag = ?, cacheLastModified = ?, cacheContentLength = ? WHERE origin = ?").use { ps ->
            ps.setString(1, eTag); ps.setString(2, lastModified); ps.setString(3, contentLength); ps.setString(4, origin)
            ps.executeUpdate()
        }
    }

    suspend fun updateLastUpdate(origin: String, lastUpdate: Long) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("UPDATE podcastSubscription SET lastUpdate = ? WHERE origin = ?").use { ps ->
            ps.setLong(1, lastUpdate); ps.setString(2, origin); ps.executeUpdate()
        }
    }

    /**
     * Stores how many episodes the last refresh added, for the "new" badge on
     * the subscription card.
     *
     * `newEpisodes` has been a column since the first schema and `App.kt` has
     * always read it to draw the badge, but nothing ever wrote it — the INSERT
     * hardcoded 0 and no UPDATE existed, so the badge could never appear.
     */
    suspend fun setNewEpisodeCount(origin: String, count: Int) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("UPDATE podcastSubscription SET newEpisodes = ? WHERE origin = ?").use { ps ->
            ps.setInt(1, count.coerceAtLeast(0)); ps.setString(2, origin); ps.executeUpdate()
        }
    }

    /**
     * Clears the "new" badge for one subscription.
     *
     * Called when the user opens the podcast: the badge means "episodes arrived
     * since you last looked", so looking is what clears it. Without this the
     * count would only ever grow.
     */
    suspend fun clearNewEpisodeCount(origin: String) = setNewEpisodeCount(origin, 0)

    suspend fun setAutoDownload(origin: String, enabled: Boolean) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("UPDATE podcastSubscription SET enableAutoDownload = ? WHERE origin = ?").use { ps ->
            ps.setInt(1, if (enabled) 1 else 0); ps.setString(2, origin); ps.executeUpdate()
        }
    }

    /**
     * Toggles desktop notifications for one subscription.
     *
     * `enableNotifications` has been a column since the first schema but had no
     * writer and no reader, so the field never did anything.
     */
    suspend fun setNotificationsEnabled(origin: String, enabled: Boolean) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("UPDATE podcastSubscription SET enableNotifications = ? WHERE origin = ?").useResource {
            setInt(1, if (enabled) 1 else 0); setString(2, origin); executeUpdate()
        }
    }

    /**
     * Pins one subscription's playback speed ("this show always plays at this
     * speed"). Written when the user changes the speed while one of the show's
     * episodes plays; cleared from the show's menu.
     */
    suspend fun setSpeedOverride(origin: String, speed: Float) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("UPDATE podcastSubscription SET playbackSpeedOverride = ? WHERE origin = ?").useResource {
            setFloat(1, speed); setString(2, origin); executeUpdate()
        }
    }

    /** Removes the speed pin, so the show follows the session speed again. */
    suspend fun clearSpeedOverride(origin: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("UPDATE podcastSubscription SET playbackSpeedOverride = NULL WHERE origin = ?").useResource {
            setString(1, origin); executeUpdate()
        }
    }

    /**
     * Sets how many recently downloaded episodes this show keeps before the
     * rest are pruned automatically. 0 disables the cleanup, which is the
     * default for every subscription.
     */
    suspend fun setKeepDownloads(origin: String, keep: Int) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("UPDATE podcastSubscription SET keepDownloads = ? WHERE origin = ?").useResource {
            setInt(1, keep.coerceAtLeast(0)); setString(2, origin); executeUpdate()
        }
    }

    suspend fun delete(origin: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastSubscription WHERE origin = ?").useResource {
            setString(1, origin); executeUpdate()
        }
    }
}

data class PodcastDownload(
    val episodeId: String,
    val origin: String,
    val filePath: String,
    val podcastTitle: String,
    val episodeTitle: String = "",
    val timestamp: Long
)

data class DownloadTask(
    val episodeId: String,
    val origin: String,
    val audioUrl: String,
    val podcastTitle: String,
    val episodeTitle: String,
    val targetFilePath: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val state: String = "DOWNLOADING",
    val createdAt: Long,
    val updatedAt: Long
)

class DownloadDao(private val conn: Connection) {
    suspend fun insert(episodeId: String, origin: String, filePath: String, podcastTitle: String, episodeTitle: String = "") = withContext(DatabaseDispatcher) {
        val ts = System.currentTimeMillis()
        conn.prepareStatement("INSERT OR REPLACE INTO podcastDownload (episodeId, origin, filePath, podcastTitle, episodeTitle, timestamp) VALUES (?, ?, ?, ?, ?, ?)").useResource {
            setString(1, episodeId); setString(2, origin); setString(3, filePath)
            setString(4, podcastTitle); setString(5, episodeTitle); setLong(6, ts); executeUpdate()
        }
    }

    suspend fun getByEpisodeId(episodeId: String): PodcastDownload? = withContext(DatabaseDispatcher) {
        conn.prepareStatement("SELECT * FROM podcastDownload WHERE episodeId = ?").use { ps ->
            ps.setString(1, episodeId)
            ps.executeQuery().use { rs ->
                if (rs.next()) PodcastDownload(
                    episodeId = rs.getString("episodeId"),
                    origin = rs.getString("origin"),
                    filePath = rs.getString("filePath"),
                    podcastTitle = rs.getString("podcastTitle"),
                    episodeTitle = rs.getString("episodeTitle") ?: "",
                    timestamp = rs.getLong("timestamp")
                ) else null
            }
        }
    }

    suspend fun getAllDownloadedIds(): Set<String> = withContext(DatabaseDispatcher) {
        val ids = mutableSetOf<String>()
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT episodeId FROM podcastDownload").use { rs ->
                while (rs.next()) ids.add(rs.getString("episodeId"))
            }
        }
        ids
    }

    /**
     * Every download row, paired with its file's size, or null when the file is
     * gone.
     *
     * `File.exists()` and `File.length()` are filesystem calls, and they ran on
     * the single database thread: opening the Downloads screen stat'ed every
     * downloaded file from inside the dispatcher, so the UI waited on the disk
     * and every unrelated query queued behind it. The rows are read on the
     * dispatcher, the stat happens on [Dispatchers.IO], and only the resulting
     * deletes go back to the dispatcher — batched, instead of one round trip
     * per missing file.
     */
    private suspend fun downloadsWithFileSizes(): List<Pair<PodcastDownload, Long?>> {
        val rows = withContext(DatabaseDispatcher) {
            val all = mutableListOf<PodcastDownload>()
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM podcastDownload").use { rs ->
                    while (rs.next()) all.add(PodcastDownload(
                        episodeId = rs.getString("episodeId"), origin = rs.getString("origin"),
                        filePath = rs.getString("filePath"), podcastTitle = rs.getString("podcastTitle"),
                        episodeTitle = rs.getString("episodeTitle") ?: "",
                        timestamp = rs.getLong("timestamp")
                    ))
                }
            }
            all
        }
        if (rows.isEmpty()) return emptyList()

        val checked = withContext(Dispatchers.IO) {
            rows.map { row ->
                val file = File(row.filePath)
                row to if (file.exists()) file.length() else null
            }
        }
        val live = mutableListOf<Pair<PodcastDownload, Long?>>()
        val missing = mutableListOf<PodcastDownload>()
        for (entry in checked) {
            if (entry.second == null) missing.add(entry.first) else live.add(entry)
        }
        if (missing.isNotEmpty()) {
            Logger.w("AppDatabase", "Dropping ${missing.size} download record(s) whose file is gone")
            withContext(DatabaseDispatcher) { deleteDownloadsById(missing.map { it.episodeId }) }
        }
        return live
    }

    /** Deletes several download rows in one statement per parameter chunk. */
    private suspend fun deleteDownloadsById(episodeIds: List<String>) = withContext(DatabaseDispatcher) {
        episodeIds.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            conn.prepareStatement(
                "DELETE FROM podcastDownload WHERE episodeId IN ($placeholders)"
            ).useResource {
                chunk.forEachIndexed { index, id -> setString(index + 1, id) }
                executeUpdate()
            }
        }
    }

    suspend fun getAllValidDownloadedIds(): Set<String> =
        downloadsWithFileSizes().mapTo(mutableSetOf()) { it.first.episodeId }

    /** Return all valid download records (cleans up stale records where file is gone). */
    suspend fun getAllValid(): List<PodcastDownload> =
        downloadsWithFileSizes().map { it.first }

    /** Get total bytes of all valid downloaded files. */
    suspend fun getTotalDownloadedBytes(): Long =
        downloadsWithFileSizes().sumOf { it.second ?: 0L }

    /** Get all downloads for a specific podcast origin. */
    suspend fun getAllByOrigin(origin: String): List<PodcastDownload> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<PodcastDownload>()
        conn.prepareStatement("SELECT * FROM podcastDownload WHERE origin = ?").use { ps ->
            ps.setString(1, origin)
            ps.executeQuery().use { rs ->
                while (rs.next()) list.add(PodcastDownload(
                    episodeId = rs.getString("episodeId"), origin = rs.getString("origin"),
                    filePath = rs.getString("filePath"), podcastTitle = rs.getString("podcastTitle"),
                    episodeTitle = rs.getString("episodeTitle") ?: "",
                    timestamp = rs.getLong("timestamp")
                ))
            }
        }
        list
    }

    /** Delete download record (does NOT delete file — use DownloadManager for full cleanup). */
    suspend fun delete(episodeId: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastDownload WHERE episodeId = ?").useResource {
            setString(1, episodeId); executeUpdate()
        }
    }

    /** Delete all download records for a podcast origin (does NOT delete files). */
    suspend fun deleteByOrigin(origin: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastDownload WHERE origin = ?").useResource {
            setString(1, origin); executeUpdate()
        }
    }

    /** Clear all download records. */
    suspend fun deleteAll() = withContext(DatabaseDispatcher) {
        conn.createStatement().use { it.executeUpdate("DELETE FROM podcastDownload") }
    }
}

class DownloadTaskDao(private val conn: Connection) {
    suspend fun insert(task: DownloadTask) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("INSERT OR REPLACE INTO downloadTask (episodeId, origin, audioUrl, podcastTitle, episodeTitle, targetFilePath, downloadedBytes, totalBytes, state, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").useResource {
            setString(1, task.episodeId); setString(2, task.origin); setString(3, task.audioUrl)
            setString(4, task.podcastTitle); setString(5, task.episodeTitle); setString(6, task.targetFilePath)
            setLong(7, task.downloadedBytes); setLong(8, task.totalBytes); setString(9, task.state)
            setLong(10, task.createdAt); setLong(11, task.updatedAt); executeUpdate()
        }
    }

    suspend fun getByEpisodeId(episodeId: String): DownloadTask? = withContext(DatabaseDispatcher) {
        conn.prepareStatement("SELECT * FROM downloadTask WHERE episodeId = ?").use { ps ->
            ps.setString(1, episodeId)
            ps.executeQuery().use { rs ->
                if (rs.next()) DownloadTask(
                    episodeId = rs.getString("episodeId"), origin = rs.getString("origin"),
                    audioUrl = rs.getString("audioUrl"), podcastTitle = rs.getString("podcastTitle"),
                    episodeTitle = rs.getString("episodeTitle"), targetFilePath = rs.getString("targetFilePath") ?: "",
                    downloadedBytes = rs.getLong("downloadedBytes"), totalBytes = rs.getLong("totalBytes"),
                    state = rs.getString("state") ?: "DOWNLOADING",
                    createdAt = rs.getLong("createdAt"), updatedAt = rs.getLong("updatedAt")
                ) else null
            }
        }
    }

    /** Get all tasks that are not completed (DOWNLOADING / PAUSED / FAILED). */
    suspend fun getAllActive(): List<DownloadTask> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<DownloadTask>()
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM downloadTask WHERE state != 'COMPLETED' ORDER BY createdAt DESC, episodeId ASC").use { rs ->
                while (rs.next()) list.add(DownloadTask(
                    episodeId = rs.getString("episodeId"), origin = rs.getString("origin"),
                    audioUrl = rs.getString("audioUrl"), podcastTitle = rs.getString("podcastTitle"),
                    episodeTitle = rs.getString("episodeTitle"), targetFilePath = rs.getString("targetFilePath") ?: "",
                    downloadedBytes = rs.getLong("downloadedBytes"), totalBytes = rs.getLong("totalBytes"),
                    state = rs.getString("state") ?: "DOWNLOADING",
                    createdAt = rs.getLong("createdAt"), updatedAt = rs.getLong("updatedAt")
                ))
            }
        }
        list
    }

    suspend fun updateProgress(episodeId: String, downloadedBytes: Long, totalBytes: Long) = withContext(DatabaseDispatcher) {
        val ts = System.currentTimeMillis()
        conn.prepareStatement("UPDATE downloadTask SET downloadedBytes = ?, totalBytes = ?, updatedAt = ? WHERE episodeId = ?").useResource {
            setLong(1, downloadedBytes); setLong(2, totalBytes); setLong(3, ts); setString(4, episodeId); executeUpdate()
        }
    }

    suspend fun updateState(episodeId: String, state: String, targetFilePath: String = "") = withContext(DatabaseDispatcher) {
        val ts = System.currentTimeMillis()
        if (targetFilePath.isNotEmpty()) {
            conn.prepareStatement("UPDATE downloadTask SET state = ?, targetFilePath = ?, updatedAt = ? WHERE episodeId = ?").useResource {
                setString(1, state); setString(2, targetFilePath); setLong(3, ts); setString(4, episodeId); executeUpdate()
            }
        } else {
            conn.prepareStatement("UPDATE downloadTask SET state = ?, updatedAt = ? WHERE episodeId = ?").useResource {
                setString(1, state); setLong(2, ts); setString(3, episodeId); executeUpdate()
            }
        }
    }

    suspend fun delete(episodeId: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM downloadTask WHERE episodeId = ?").useResource {
            setString(1, episodeId); executeUpdate()
        }
    }

    /** Clear all download tasks. */
    suspend fun deleteAll() = withContext(DatabaseDispatcher) {
        conn.createStatement().use { it.executeUpdate("DELETE FROM downloadTask") }
    }
}

class ItunesLookupDao(private val conn: Connection) {
    suspend fun getAll(): Map<String, String> = withContext(DatabaseDispatcher) {
        val map = mutableMapOf<String, String>()
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT itunesUrl, rssUrl FROM podcastItunesLookup").use { rs ->
                while (rs.next()) map[rs.getString("itunesUrl")] = rs.getString("rssUrl")
            }
        }
        map
    }

    suspend fun insert(itunesUrl: String, rssUrl: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("INSERT OR REPLACE INTO podcastItunesLookup (itunesUrl, rssUrl) VALUES (?, ?)").useResource {
            setString(1, itunesUrl); setString(2, rssUrl); executeUpdate()
        }
    }

    suspend fun delete(itunesUrl: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastItunesLookup WHERE itunesUrl = ?").useResource {
            setString(1, itunesUrl); executeUpdate()
        }
    }

    suspend fun deleteByRssUrl(rssUrl: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastItunesLookup WHERE rssUrl = ?").useResource {
            setString(1, rssUrl); executeUpdate()
        }
    }
}

data class PlayerSession(
    val id: Int = 1,
    val queueIndex: Int = -1,
    val currentPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val volume: Int = 100,
    val currentEpisodeId: String? = null,
    val lastUpdate: Long = 0L
)

data class PlayerQueueRow(
    val id: Int = 0,
    val queueOrder: Int,
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val podcastArtworkUrl: String? = null,
    val episodeId: String? = null,
    val isDownloaded: Boolean = false,
    /**
     * The remote stream for an item whose [url] is a local download path.
     *
     * Null for rows written before schema v5, and for episodes that were never
     * downloaded. See `QueueItem.fallbackUrl` for why it is worth a column.
     */
    val fallbackUrl: String? = null
)

class PlayerSessionDao(private val conn: Connection) {
    suspend fun saveSession(queueIndex: Int, currentPositionMs: Long, playbackSpeed: Float, volume: Int, currentEpisodeId: String?) = withContext(DatabaseDispatcher) {
        val ts = System.currentTimeMillis()
        conn.prepareStatement(
            "INSERT OR REPLACE INTO playerSession (id, queueIndex, currentPositionMs, playbackSpeed, volume, currentEpisodeId, lastUpdate) VALUES (1, ?, ?, ?, ?, ?, ?)"
        ).useResource {
            setInt(1, queueIndex); setLong(2, currentPositionMs); setFloat(3, playbackSpeed)
            setInt(4, volume); setString(5, currentEpisodeId); setLong(6, ts); executeUpdate()
        }
    }

    suspend fun loadSession(): PlayerSession? = withContext(DatabaseDispatcher) {
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM playerSession WHERE id = 1").use { rs ->
                if (rs.next()) PlayerSession(
                    id = rs.getInt("id"), queueIndex = rs.getInt("queueIndex"),
                    currentPositionMs = rs.getLong("currentPositionMs"),
                    playbackSpeed = rs.getFloat("playbackSpeed"), volume = rs.getInt("volume"),
                    currentEpisodeId = rs.getString("currentEpisodeId"), lastUpdate = rs.getLong("lastUpdate")
                ) else null
            }
        }
    }

    /**
     * Saves the heartbeat: position *and* which queue row it belongs to.
     *
     * Writing only the position left the two halves of the session row able to
     * disagree after a crash — the queue index and episode id still described
     * the track from the last clean exit, while the position came from the one
     * playing when the process died. Restoring that combination resumed the
     * wrong episode part-way through.
     */
    suspend fun updatePosition(currentPositionMs: Long, queueIndex: Int, currentEpisodeId: String?) = withContext(DatabaseDispatcher) {
        val ts = System.currentTimeMillis()
        conn.prepareStatement(
            "UPDATE playerSession SET currentPositionMs = ?, queueIndex = ?, currentEpisodeId = ?, lastUpdate = ? WHERE id = 1"
        ).useResource {
            setLong(1, currentPositionMs); setInt(2, queueIndex)
            setString(3, currentEpisodeId); setLong(4, ts); executeUpdate()
        }
    }

    suspend fun deleteSession() = withContext(DatabaseDispatcher) {
        conn.createStatement().use { it.executeUpdate("DELETE FROM playerSession WHERE id = 1") }
    }
}

class PlayerQueueDao(private val conn: Connection) {
    suspend fun saveQueue(items: List<PlayerQueueRow>) = withContext(DatabaseDispatcher) {
        conn.inTransaction {
            conn.createStatement().use { it.executeUpdate("DELETE FROM playerQueueItem") }
            conn.prepareStatement(
                "INSERT INTO playerQueueItem (queueOrder, url, title, subtitle, artworkUrl, podcastArtworkUrl, episodeId, isDownloaded, fallbackUrl) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                items.forEach { item ->
                    ps.setInt(1, item.queueOrder); ps.setString(2, item.url); ps.setString(3, item.title)
                    ps.setString(4, item.subtitle); ps.setString(5, item.artworkUrl)
                    ps.setString(6, item.podcastArtworkUrl); ps.setString(7, item.episodeId)
                    ps.setInt(8, if (item.isDownloaded) 1 else 0)
                    ps.setString(9, item.fallbackUrl)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    suspend fun loadQueue(): List<PlayerQueueRow> = withContext(DatabaseDispatcher) {
        val list = mutableListOf<PlayerQueueRow>()
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM playerQueueItem ORDER BY queueOrder ASC").use { rs ->
                while (rs.next()) list.add(PlayerQueueRow(
                    id = rs.getInt("id"), queueOrder = rs.getInt("queueOrder"), url = rs.getString("url"),
                    title = rs.getString("title"), subtitle = rs.getString("subtitle"),
                    artworkUrl = rs.getString("artworkUrl"), podcastArtworkUrl = rs.getString("podcastArtworkUrl"),
                    episodeId = rs.getString("episodeId"), isDownloaded = rs.getInt("isDownloaded") == 1,
                    fallbackUrl = rs.getString("fallbackUrl")
                ))
            }
        }
        list
    }

    suspend fun clearQueue() = withContext(DatabaseDispatcher) {
        conn.createStatement().use { it.executeUpdate("DELETE FROM playerQueueItem") }
    }
}

/**
 * Per-episode resume points (see [app.podara.data.model.EpisodeProgress]).
 *
 * Every write goes through [record], which owns the "does this count as
 * finished" rule so no caller has to re-derive it.
 */
class EpisodeProgressDao(private val conn: Connection) {

    /**
     * Records where playback currently is for [episodeId].
     *
     * A position at or past [EpisodeProgress.COMPLETION_THRESHOLD] marks the
     * episode finished and resets its stored position to zero, so a finished
     * episode does not offer to resume near its own end. A later seek backwards
     * on an already-finished episode does not un-finish it.
     *
     * The read of the existing row and the upsert are one transaction: the rule
     * "once finished, always finished" is derived from the row that is about to
     * be overwritten, so a heartbeat and a completion arriving back to back
     * used to be able to read the same pre-completion row and then write the
     * completed flag back to 0.
     */
    suspend fun record(
        episodeId: String,
        origin: String,
        positionMs: Long,
        durationMs: Long,
        nowMillis: Long = System.currentTimeMillis()
    ) = withContext(DatabaseDispatcher) {
        conn.inTransaction {
            val existing = getById(episodeId)
            val alreadyCompleted = existing?.completed == true
            val reachedEnd = durationMs > 0L &&
                positionMs >= (durationMs * app.podara.data.model.EpisodeProgress.COMPLETION_THRESHOLD).toLong()

            val completed = alreadyCompleted || reachedEnd
            val storedPosition = if (completed) 0L else positionMs.coerceAtLeast(0L)
            val storedDuration = if (durationMs > 0L) durationMs else (existing?.durationMs ?: 0L)

            conn.prepareStatement(
                """
                INSERT INTO podcastEpisodeProgress
                    (episodeId, origin, positionMs, durationMs, completed, lastPlayedAt)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(episodeId) DO UPDATE SET
                    origin = excluded.origin,
                    positionMs = excluded.positionMs,
                    durationMs = excluded.durationMs,
                    completed = excluded.completed,
                    lastPlayedAt = excluded.lastPlayedAt
                """.trimIndent()
            ).useResource {
                setString(1, episodeId); setString(2, origin)
                setLong(3, storedPosition); setLong(4, storedDuration)
                setInt(5, if (completed) 1 else 0); setLong(6, nowMillis)
                executeUpdate()
            }
        }
    }

    suspend fun getById(episodeId: String): app.podara.data.model.EpisodeProgress? =
        withContext(DatabaseDispatcher) {
            conn.prepareStatement("SELECT * FROM podcastEpisodeProgress WHERE episodeId = ?").use { ps ->
                ps.setString(1, episodeId)
                ps.executeQuery().use { rs -> if (rs.next()) readProgress(rs) else null }
            }
        }

    /** Progress rows for [episodeIds], keyed by episode id. Missing ids are simply absent. */
    suspend fun getByIds(episodeIds: List<String>): Map<String, app.podara.data.model.EpisodeProgress> =
        withContext(DatabaseDispatcher) {
            if (episodeIds.isEmpty()) return@withContext emptyMap()
            val map = mutableMapOf<String, app.podara.data.model.EpisodeProgress>()
            episodeIds.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                conn.prepareStatement(
                    "SELECT * FROM podcastEpisodeProgress WHERE episodeId IN ($placeholders)"
                ).use { ps ->
                    chunk.forEachIndexed { index, id -> ps.setString(index + 1, id) }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val row = readProgress(rs)
                            map[row.episodeId] = row
                        }
                    }
                }
            }
            map
        }

    /**
     * The episode to offer under "continue listening": the most recently played
     * row that is neither finished nor still at position zero.
     */
    suspend fun getMostRecentResumable(): app.podara.data.model.EpisodeProgress? =
        withContext(DatabaseDispatcher) {
            conn.prepareStatement(
                """
                SELECT * FROM podcastEpisodeProgress
                WHERE completed = 0 AND positionMs > 0
                ORDER BY lastPlayedAt DESC
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) readProgress(rs) else null }
            }
        }

    /** Progress rows for every episode of [origin], keyed by episode id. */
    suspend fun getAllByOrigin(origin: String): Map<String, app.podara.data.model.EpisodeProgress> =
        withContext(DatabaseDispatcher) {
            val map = mutableMapOf<String, app.podara.data.model.EpisodeProgress>()
            conn.prepareStatement("SELECT * FROM podcastEpisodeProgress WHERE origin = ?").use { ps ->
                ps.setString(1, origin)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val row = readProgress(rs)
                        map[row.episodeId] = row
                    }
                }
            }
            map
        }

    suspend fun delete(episodeId: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastEpisodeProgress WHERE episodeId = ?").useResource {
            setString(1, episodeId); executeUpdate()
        }
    }

    suspend fun deleteByOrigin(origin: String) = withContext(DatabaseDispatcher) {
        conn.prepareStatement("DELETE FROM podcastEpisodeProgress WHERE origin = ?").useResource {
            setString(1, origin); executeUpdate()
        }
    }

    private fun readProgress(rs: java.sql.ResultSet) = app.podara.data.model.EpisodeProgress(
        episodeId = rs.getString("episodeId"),
        origin = rs.getString("origin"),
        positionMs = rs.getLong("positionMs"),
        durationMs = rs.getLong("durationMs"),
        completed = rs.getInt("completed") == 1,
        lastPlayedAt = rs.getLong("lastPlayedAt")
    )
}
