package app.podara.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    /**
     * Redirects the log directory for the current process.
     *
     * Tests are the reason this exists: everything else in the test suite already
     * writes to `java.io.tmpdir`, but the log directory was hard-coded to the real
     * user profile, so running the suite appended thousands of lines of test
     * failures and fake download paths to the developer's own `~/.podara/debug.log`.
     * Overriding `user.home` instead would have redirected settings, the single
     * instance lock and the real database along with it, so the redirect is kept
     * to the one component that needs it.
     *
     * Null means "the real location"; set it back to null when the test ends.
     */
    @Volatile
    internal var logDirectoryOverride: File? = null

    /**
     * Redirects the log directory for a whole JVM, without any test-side code.
     *
     * A per-test override only helps the tests that remember to set it, and
     * editing forty existing test classes to add one line each was not worth it.
     * One entry in the test task's `systemProperties` covers every test that
     * exists or is ever added:
     *
     * ```
     * tasks.withType<Test>().configureEach {
     *     systemProperty("podara.log.dir", layout.buildDirectory.dir("test-logs").get().asFile.absolutePath)
     * }
     * ```
     */
    private const val LOG_DIR_PROPERTY = "podara.log.dir"

    /**
     * Resolved per use, never cached: [logDirectoryOverride] must take effect for
     * an already-initialised object, and every path below has to agree with it.
     */
    private val logDir: File
        get() = logDirectoryOverride
            ?: System.getProperty(LOG_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".podara")

    private val logFile: File
        get() = File(logDir, "debug.log")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private const val MAX_LOG_SIZE = 1L * 1024 * 1024
    private const val MAX_BACKUPS = 3

    @Synchronized
    fun d(tag: String, message: String) {
        writeLog("D", tag, message)
    }

    @Synchronized
    fun i(tag: String, message: String) {
        writeLog("I", tag, message)
    }

    @Synchronized
    fun w(tag: String, message: String) {
        writeLog("W", tag, message)
    }

    @Synchronized
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeLog("E", tag, fullMessage)
    }

    private fun writeLog(level: String, tag: String, message: String) {
        try {
            logDir.mkdirs()
            rotateIfNeeded()
            val timestamp = dateFormat.format(Date())
            val entry = "[$timestamp] [$level/$tag] $message\n"
            logFile.appendText(entry)
        } catch (_: Exception) {
        }
    }

    /**
     * Shifts debug.log.N down by one and starts a fresh debug.log.
     *
     * `File.renameTo` fails on Windows when the destination already exists,
     * unlike on POSIX. The previous version renamed without deleting the target
     * first and discarded the boolean result, so once debug.log.1 existed every
     * rotation silently failed and the log grew past [MAX_LOG_SIZE] forever.
     */
    private fun rotateIfNeeded() {
        val dir = logDir
        val file = logFile
        if (!file.exists()) return
        if (file.length() < MAX_LOG_SIZE) return

        for (i in MAX_BACKUPS downTo 1) {
            val older = File(dir, "debug.log.${i}")
            val newer = if (i == 1) file else File(dir, "debug.log.${i - 1}")
            if (newer.exists()) {
                // Windows requires the destination to be gone first.
                older.delete()
                if (!newer.renameTo(older)) {
                    w("Logger", "Failed to rotate ${newer.name} -> ${older.name}")
                }
            }
        }
        File(dir, "debug.log.1").delete()
        if (!file.renameTo(File(dir, "debug.log.1"))) {
            // Rotation failed; truncate rather than leave the file unbounded.
            file.writeText("")
        }
    }

    fun getLogContent(): String {
        return try {
            val file = logFile
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun clearLog() {
        try {
            logFile.writeText("")
        } catch (_: Exception) {
        }
    }

    fun getLogFilePath(): String = logFile.absolutePath
}
