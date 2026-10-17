package app.podara.platform

import app.podara.util.Logger
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * A process-wide single-instance guard backed by an OS file lock.
 *
 * Without one, a second copy of the app starts, opens the same SQLite file and
 * runs the same schema statements. SQLite serialises that with a write lock, so
 * the loser failed with `database is locked` during table creation and crashed
 * on startup — double-clicking the icon twice was enough to reproduce.
 *
 * The lock is advisory but OS-enforced: Windows releases it when the process
 * dies, so a crash does not leave a stale lock that blocks every future launch
 * (which a plain marker file would).
 */
class SingleInstanceLock private constructor(
    private val lockFile: File,
    private val raf: RandomAccessFile,
    private val lock: FileLock
) {

    companion object {
        private const val TAG = "SingleInstanceLock"

        /**
         * Tries to become the only running instance.
         *
         * @return the acquired lock, or null when another instance holds it.
         *   Any I/O failure is treated as "assume we are alone" — refusing to
         *   start because a lock file could not be created would be worse than
         *   the duplicate-instance problem it is guarding against.
         */
        fun acquire(lockDir: File = File(System.getProperty("user.home"), ".podara")): SingleInstanceLock? {
            return try {
                lockDir.mkdirs()
                val file = File(lockDir, "podara.lock")
                val raf = RandomAccessFile(file, "rw")
                val channel = raf.channel
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    raf.close()
                    Logger.w(TAG, "Another instance already holds ${file.absolutePath}")
                    null
                } else {
                    Logger.i(TAG, "Acquired single-instance lock at ${file.absolutePath}")
                    SingleInstanceLock(file, raf, lock)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Could not create the single-instance lock; continuing without it", e)
                null
            }
        }
    }

    fun release() {
        try {
            lock.release()
            raf.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to release single-instance lock: ${e.message}")
        }
    }
}
