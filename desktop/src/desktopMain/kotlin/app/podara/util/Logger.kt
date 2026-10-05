package app.podara.util

import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    private val logDir = File(System.getProperty("user.home"), ".podara")
    private val logFile = File(logDir, "debug.log")
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
        if (!logFile.exists()) return
        if (logFile.length() < MAX_LOG_SIZE) return

        for (i in MAX_BACKUPS downTo 1) {
            val older = File(logDir, "debug.log.${i}")
            val newer = if (i == 1) logFile else File(logDir, "debug.log.${i - 1}")
            if (newer.exists()) {
                // Windows requires the destination to be gone first.
                older.delete()
                if (!newer.renameTo(older)) {
                    w("Logger", "Failed to rotate ${newer.name} -> ${older.name}")
                }
            }
        }
        File(logDir, "debug.log.1").delete()
        if (!logFile.renameTo(File(logDir, "debug.log.1"))) {
            // Rotation failed; truncate rather than leave the file unbounded.
            logFile.writeText("")
        }
    }

    fun getLogContent(): String {
        return try {
            if (logFile.exists()) logFile.readText() else ""
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
