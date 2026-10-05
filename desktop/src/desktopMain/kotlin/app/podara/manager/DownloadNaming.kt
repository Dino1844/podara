package app.podara.manager

import java.io.File
import java.net.URL

/**
 * Pure download-target rules: where a file goes, what it is called, and how the
 * name is made safe for Windows.
 *
 * This was inlined in [DownloadManager] alongside the HTTP transfer loop, which
 * put policy and infrastructure in the same class. These functions touch no
 * network and hold no state, so they are testable without a server or a
 * temporary directory — [DownloadManager] keeps only the IO and delegates here.
 */
object DownloadNaming {

    /** Longest human-readable prefix kept in a path segment before the hash suffix. */
    const val MAX_READABLE_PREFIX_LENGTH = 24

    /** Extensions accepted from an audio URL; anything else falls back to mp3. */
    val ALLOWED_AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac")

    const val FALLBACK_AUDIO_EXTENSION = "mp3"

    private const val ILLEGAL_SUFFIX = "_"
    private const val FALLBACK_PODCAST_PREFIX = "podcast"
    private const val FALLBACK_EPISODE_PREFIX = "episode"

    private val WINDOWS_ILLEGAL_FILE_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private val WINDOWS_RESERVED_FILE_NAMES = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (index in 1..9) {
            add("COM$index")
            add("LPT$index")
        }
    }

    /**
     * Make [name] safe to use as a single path segment on Windows: strip control
     * characters and the reserved separators, trim trailing dots and spaces,
     * and reject reserved device names and the `.` / `..` directory aliases.
     */
    fun sanitizeFileName(name: String): String {
        val result = buildString(name.length) {
            for (c in name) {
                append(if (c.code < 32 || c in WINDOWS_ILLEGAL_FILE_CHARS) '_' else c)
            }
        }.trim().trimEnd('.', ' ')

        if (result.isEmpty() || result == "." || result == "..") return ILLEGAL_SUFFIX
        val baseName = result.substringBefore('.').uppercase()
        return if (baseName in WINDOWS_RESERVED_FILE_NAMES) ILLEGAL_SUFFIX else result
    }

    /** The extension to use for [audioUrl], falling back to mp3 for unknown or unsafe values. */
    fun audioFileExtension(audioUrl: String): String {
        val path = try {
            URL(audioUrl).path
        } catch (_: Exception) {
            audioUrl.substringBefore('?')
        }
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in ALLOWED_AUDIO_EXTENSIONS } ?: FALLBACK_AUDIO_EXTENSION
    }

    /**
     * The path an episode downloads to: `<downloadsDir>/<podcast>-<originHash>/<episode>-<idHash>.<ext>`.
     *
     * A readable title prefix is kept for humans, but the hash suffix is what
     * actually guarantees uniqueness and stability — titles change and collide,
     * IDs do not. Throws if the result would escape [downloadsDir].
     */
    fun buildDownloadFile(
        downloadsDir: File,
        origin: String,
        episodeId: String,
        audioUrl: String,
        episodeTitle: String,
        podcastTitle: String
    ): File {
        val podcastName = readablePathPrefix(podcastTitle, FALLBACK_PODCAST_PREFIX)
        val episodeName = readablePathPrefix(episodeTitle, FALLBACK_EPISODE_PREFIX)
        val podcastDir = File(downloadsDir, "$podcastName-${origin.sha256()}")
        val file = File(podcastDir, "$episodeName-${episodeId.sha256()}.${audioFileExtension(audioUrl)}")
        check(isInsideDownloadsDir(downloadsDir, file)) {
            "Download path escapes the configured download directory"
        }
        return file
    }

    /**
     * Whether [file] resolves to something strictly inside [downloadsDir].
     * Uses canonical paths so `..` segments and symlinks cannot escape.
     */
    fun isInsideDownloadsDir(downloadsDir: File, file: File): Boolean {
        val root = downloadsDir.canonicalFile.toPath()
        val resolved = file.canonicalFile.toPath()
        return resolved.startsWith(root) && resolved != root
    }

    /** A sanitized, length-capped title prefix, or [fallback] when nothing readable survives. */
    fun readablePathPrefix(value: String, fallback: String): String =
        sanitizeFileName(value).takeIf { it != ILLEGAL_SUFFIX }?.take(MAX_READABLE_PREFIX_LENGTH) ?: fallback
}

/** Lowercase hex SHA-256 of this string, used as the stable uniqueness suffix in download paths. */
fun String.sha256(): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(this.toByteArray())
        .joinToString("") { "%02x".format(it) }