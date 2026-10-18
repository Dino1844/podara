package app.podara.desktop

import app.podara.util.Logger
import java.io.File

/**
 * Keeps log output out of the developer's real profile.
 *
 * Every other test writes its database under `java.io.tmpdir`, but [Logger] used
 * to append to `~/.podara/debug.log` no matter what — one suite run left ~10k
 * lines of test stack traces and fake download paths in the real log. The fix is
 * [Logger.logDirectoryOverride]; this is the one-liner that sets it.
 *
 * Usage, always paired so the process-global override cannot outlive the test:
 *
 * ```
 * private lateinit var logs: LogIsolation
 *
 * @BeforeTest fun setup() { logs = isolateLogsForTest() }
 * @AfterTest fun teardown() { logs.close() }
 * ```
 *
 * The override is process-wide and not per-thread, so isolation is only correct
 * while tests run sequentially — which they do: the test task pins
 * `maxParallelForks = 1` and a class's methods run one at a time. A test that
 * opts in still receives logs from unrelated background threads (the mpv poll
 * loop, say); that is harmless here and preferable to letting them reach the
 * real directory.
 */
internal class LogIsolation(val directory: File) : AutoCloseable {

    /** `~/.podara/debug.log` at the time the isolation started, for assertions. */
    val realLogFile: File = File(System.getProperty("user.home"), ".podara/debug.log")

    override fun close() {
        Logger.logDirectoryOverride = null
        directory.deleteRecursively()
    }
}

/**
 * Points [Logger] at a fresh directory under `java.io.tmpdir` until [LogIsolation.close].
 *
 * Only tests that need log isolation should call this. Making every existing test
 * do so would mean editing forty files that do not otherwise care about logging.
 */
internal fun isolateLogsForTest(): LogIsolation {
    val directory = File(
        System.getProperty("java.io.tmpdir"),
        "podara_log_test_${System.nanoTime()}"
    )
    directory.mkdirs()
    Logger.logDirectoryOverride = directory
    return LogIsolation(directory)
}
