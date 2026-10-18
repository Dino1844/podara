package app.podara.desktop

import app.podara.util.Logger
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Log output must not land in the developer's real profile.
 *
 * Every other test already keeps its database under `java.io.tmpdir`, but the log
 * directory was hard-coded to `~/.podara`, so one suite run appended thousands of
 * lines of test stack traces and fake download paths to the real debug.log. These
 * tests pin the override that fixes it.
 */
class LoggerIsolationTest {

    private lateinit var logs: LogIsolation
    private val marker = "logger_isolation_marker_${System.nanoTime()}"

    @BeforeTest
    fun setup() {
        logs = isolateLogsForTest()
    }

    @AfterTest
    fun teardown() {
        logs.close()
        // A leaked override would silently redirect every later test's logging.
        assertNull(Logger.logDirectoryOverride, "the override must not outlive the test")
        assertFalse(logs.directory.exists(), "the temporary log directory should be cleaned up")
    }

    @Test
    fun testLogsLandInTheOverrideDirectory() {
        Logger.i("Test", marker)

        val logFile = File(logs.directory, "debug.log")
        assertTrue(logFile.exists(), "debug.log should be created inside the override directory")
        assertTrue(logFile.readText().contains(marker), "the entry should be appended to that file")
        assertEquals(logFile.absolutePath, Logger.getLogFilePath())
        assertTrue(Logger.getLogContent().contains(marker))
    }

    @Test
    fun testRealProfileIsNotWritten() {
        Logger.e("Test", marker, RuntimeException(marker))

        // Assert the marker specifically rather than the file length: unrelated
        // background threads may append to the real log while this runs.
        val realLog = logs.realLogFile
        val realContent = try {
            if (realLog.exists()) realLog.readText() else ""
        } catch (_: Exception) {
            ""
        }
        assertFalse(
            realContent.contains(marker),
            "test log entries must not reach ${realLog.absolutePath}"
        )
    }

    @Test
    fun testClearLogOnlyTouchesTheOverrideFile() {
        Logger.i("Test", marker)

        Logger.clearLog()

        assertTrue(Logger.getLogContent().isEmpty(), "clearing should empty the override log")
    }

    @Test
    fun testRotationUsesTheOverrideDirectory() {
        // An oversize log is cheaper to plant than to produce by logging. If
        // rotateIfNeeded still consulted a cached path, this file would never be
        // rotated and would grow past the cap.
        val logFile = File(logs.directory, "debug.log")
        logFile.writeText("x".repeat(1_100_000))

        Logger.i("Test", marker)

        assertTrue(logFile.length() < 1_100_000, "an oversize log should have been rotated")
        assertTrue(Logger.getLogContent().contains(marker), "the new entry should start a fresh log")
    }

    @Test
    fun testOverrideResetsToTheRealLocation() {
        logs.close()

        // Without -Dpodara.log.dir the default is the real profile; when a build
        // sets it, that directory is what the override falls back to instead.
        val propertyDir = System.getProperty("podara.log.dir")?.takeIf { it.isNotBlank() }
        val expected = propertyDir
            ?.let { File(it, "debug.log") }
            ?: File(System.getProperty("user.home"), ".podara/debug.log")
        assertEquals(expected.absolutePath, Logger.getLogFilePath())
        assertNull(Logger.logDirectoryOverride)
    }

    @Test
    fun testSystemPropertyRedirectsWithoutAnyTestCode() {
        // The property is how whole-suite isolation is meant to work: it needs no
        // change in any existing test class. Asserting on the path only — writing
        // would pollute the directory the property is really used for.
        val previous = System.getProperty("podara.log.dir")
        val redirected = File(System.getProperty("java.io.tmpdir"), "podara_prop_log_${System.nanoTime()}")
        try {
            logs.close()
            System.setProperty("podara.log.dir", redirected.absolutePath)

            assertEquals(
                File(redirected, "debug.log").absolutePath,
                Logger.getLogFilePath(),
                "the -Dpodara.log.dir property should select the log directory"
            )
        } finally {
            if (previous == null) System.clearProperty("podara.log.dir")
            else System.setProperty("podara.log.dir", previous)
            redirected.deleteRecursively()
        }
    }
}
