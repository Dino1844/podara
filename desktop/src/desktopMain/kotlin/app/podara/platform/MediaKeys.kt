package app.podara.platform

import app.podara.util.Logger
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import kotlin.concurrent.thread

private const val TAG = "GlobalMediaKeys"

// Multimedia virtual-key codes (winuser.h). JNA 5.6.0's WinUser does not define
// VK_MEDIA_* constants, so they are declared here.
internal const val VK_MEDIA_PLAY_PAUSE = 0xB3
internal const val VK_MEDIA_STOP = 0xB2
internal const val VK_MEDIA_PREVTRACK = 0xB1
internal const val VK_MEDIA_NEXTTRACK = 0xB0

// PeekMessage "remove from queue" flag; JNA 5.6.0 does not define PM_REMOVE.
internal const val PM_REMOVE = 0x0001

internal val MEDIA_VK_CODES = intArrayOf(
    VK_MEDIA_PLAY_PAUSE,
    VK_MEDIA_STOP,
    VK_MEDIA_PREVTRACK,
    VK_MEDIA_NEXTTRACK
)

// Park interval between PeekMessage sweeps; bounds the worst-case key-to-callback
// latency at a negligible CPU cost.
private const val PUMP_POLL_MS = 50L

// Upper bound for stop() joining the pump thread; the loop normally exits
// within one poll interval.
private const val PUMP_JOIN_TIMEOUT_MS = 1000L

/**
 * Pure-data action delivered when a global media key is pressed.
 */
enum class MediaKeyAction { PLAY_PAUSE, STOP, PREVIOUS, NEXT }

/**
 * Handler that receives media key actions.
 *
 * Invoked on the media-keys pump thread, never on the UI thread — consumers
 * must hop to a UI dispatcher (e.g. SwingUtilities.invokeLater) before
 * touching Compose state.
 */
fun interface MediaKeyActions {
    fun onAction(action: MediaKeyAction)
}

/**
 * Maps a Win32 virtual-key code to its media action, or null for keys this
 * class does not own. Pure so the mapping is unit-testable without loading
 * the JNA User32 bindings.
 */
internal fun mediaKeyActionFor(virtualKey: Int): MediaKeyAction? = when (virtualKey) {
    VK_MEDIA_PLAY_PAUSE -> MediaKeyAction.PLAY_PAUSE
    VK_MEDIA_STOP -> MediaKeyAction.STOP
    VK_MEDIA_PREVTRACK -> MediaKeyAction.PREVIOUS
    VK_MEDIA_NEXTTRACK -> MediaKeyAction.NEXT
    else -> null
}

/**
 * Platform guard, pure so tests can exercise it for arbitrary os.name values.
 */
internal fun isWindowsOsName(osName: String): Boolean = osName.lowercase().startsWith("windows")

/**
 * Registers the four multimedia keys (play/pause, stop, previous, next) as
 * system-wide Windows hotkeys and pumps their messages on a dedicated daemon
 * thread.
 *
 * Lifecycle is forgiving by design: start() is a no-op on non-Windows platforms
 * (decided by the injectable [isWindows] predicate) and while already running;
 * stop() is a no-op when never started or already stopped; handler exceptions
 * are logged and swallowed. A hotkey already claimed by another application is
 * logged and skipped while the remaining keys still register — start() never
 * throws and never crashes the pump.
 *
 * If stop() is forgotten, nothing leaks system-wide: hotkeys registered with a
 * NULL hWnd belong to the registering thread and are released when that thread
 * exits, and Windows unregisters every hotkey of a process when the process
 * terminates. The pump thread is a daemon, so it can never block JVM shutdown.
 *
 * Windows-only details (JNA 5.6.0 User32): RegisterHotKey(NULL hWnd, id, fsModifiers, vk)
 * binds the hotkey to the CALLING thread and posts WM_HOTKEY to that thread's
 * message queue, so registration must happen on the pump thread itself. The
 * virtual-key code doubles as the hotkey id (all four fit the 0x0001-0xBFFF
 * id range), letting the pump feed msg.wParam straight into [mediaKeyActionFor].
 *
 * Manual verification (the pump needs a live User32 message queue and cannot
 * run headlessly): run the app on Windows, put another window in the
 * foreground, then press the keyboard's media keys — playback should
 * toggle/stop/skip exactly like the in-app player controls.
 */
class GlobalMediaKeys(
    // Injectable so tests can force the non-Windows no-op path on any host.
    private val isWindows: () -> Boolean = { isWindowsOsName(System.getProperty("os.name") ?: "") }
) {

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var actions: MediaKeyActions? = null

    private var pumpThread: Thread? = null

    /**
     * Starts listening for global media keys. Repeated calls while running are
     * ignored (logged); never throws.
     */
    fun start(actions: MediaKeyActions) {
        synchronized(this) {
            if (isRunning) {
                Logger.d(TAG, "start() ignored: already running")
                return
            }
            // Remember the handler even when the platform gate below bails out:
            // the pump never runs there, but tests drive dispatch via handleHotKey.
            this.actions = actions
            if (!isWindows()) {
                Logger.d(TAG, "Global media keys unavailable on \"${System.getProperty("os.name")}\"; disabled")
                return
            }
            isRunning = true
            pumpThread = thread(name = "podara-media-keys", isDaemon = true) {
                pumpLoop()
            }
        }
    }

    /**
     * Stops the pump and unregisters the hotkeys. Safe to call when never
     * started or already stopped. Joins the pump thread (bounded) so a
     * subsequent start() cannot race a dying thread for the same hotkeys.
     */
    fun stop() {
        val threadToJoin: Thread? = synchronized(this) {
            actions = null
            isRunning = false
            val t = pumpThread
            pumpThread = null
            t
        }
        if (threadToJoin != null) {
            threadToJoin.interrupt()
            try {
                threadToJoin.join(PUMP_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Maps a hotkey notification's virtual-key code to its action and hands it
     * to the handler, swallowing handler failures so a broken consumer can
     * never take down the pump. Pump-internal in production (the pump gates
     * dispatch, not this method); internal so tests can drive the dispatch
     * path without a live message queue.
     */
    internal fun handleHotKey(virtualKey: Int) {
        val action = mediaKeyActionFor(virtualKey) ?: return
        val handler = actions ?: return
        try {
            handler.onAction(action)
        } catch (e: Throwable) {
            Logger.e(TAG, "Media key handler failed for $action", e)
        }
    }

    private fun pumpLoop() {
        try {
            val user32 = User32.INSTANCE
            val msg = WinUser.MSG()
            // Hotkeys registered with a NULL hWnd belong to the calling thread
            // and their WM_HOTKEY messages are posted to that thread's queue,
            // so this registration must stay on the pump thread. The hotkey id
            // is the virtual-key code itself, so WM_HOTKEY's wParam maps
            // directly through mediaKeyActionFor.
            val registered = mutableListOf<Int>()
            for (vk in MEDIA_VK_CODES) {
                // MOD_NOREPEAT (no auto-repeat while held) requires Windows 7+;
                // retry without it on older hosts.
                val ok = user32.RegisterHotKey(null, vk, WinUser.MOD_NOREPEAT, vk) ||
                    user32.RegisterHotKey(null, vk, 0, vk)
                if (ok) {
                    registered.add(vk)
                } else {
                    Logger.w(
                        TAG,
                        "RegisterHotKey failed for vk=0x${vk.toString(16)} (may be held by another program); key disabled"
                    )
                }
            }
            if (registered.isEmpty()) {
                Logger.w(TAG, "No media hotkey registered; global media keys disabled")
                return
            }
            try {
                while (isRunning) {
                    while (user32.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                        if (msg.message == WinUser.WM_HOTKEY) {
                            handleHotKey(msg.wParam.toInt())
                        } else {
                            user32.TranslateMessage(msg)
                            user32.DispatchMessage(msg)
                        }
                    }
                    try {
                        Thread.sleep(PUMP_POLL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                for (vk in registered) {
                    if (!user32.UnregisterHotKey(null, vk)) {
                        Logger.w(TAG, "UnregisterHotKey failed for vk=0x${vk.toString(16)}")
                    }
                }
            }
        } catch (e: Throwable) {
            // Anything fatal here (e.g. User32 failing to load) must degrade to
            // "no media keys", never propagate out of the daemon thread.
            Logger.e(TAG, "Media key pump stopped unexpectedly", e)
        } finally {
            isRunning = false
        }
    }
}
