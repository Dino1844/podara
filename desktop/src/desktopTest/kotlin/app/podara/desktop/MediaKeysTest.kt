package app.podara.desktop

import app.podara.platform.GlobalMediaKeys
import app.podara.platform.MediaKeyAction
import app.podara.platform.VK_MEDIA_NEXTTRACK
import app.podara.platform.VK_MEDIA_PLAY_PAUSE
import app.podara.platform.VK_MEDIA_PREVTRACK
import app.podara.platform.VK_MEDIA_STOP
import app.podara.platform.isWindowsOsName
import app.podara.platform.mediaKeyActionFor
import kotlin.test.*

/**
 * Unit tests for the pure parts of the global media keys support: the
 * virtual-key -> action mapping, the platform guard, the lifecycle guards and
 * the dispatch path.
 *
 * The Windows message pump itself needs a live User32 message queue and
 * therefore cannot run headlessly; it is intentionally not covered here.
 *
 * Manual verification path: run the app on Windows, put another window in the
 * foreground, then press the keyboard's media keys — play/pause, stop,
 * previous and next should control podcast playback exactly like the in-app
 * player controls.
 */
class MediaKeysTest {

    @Test
    fun testVirtualKeyToActionMapping() {
        assertEquals(MediaKeyAction.PLAY_PAUSE, mediaKeyActionFor(VK_MEDIA_PLAY_PAUSE))
        assertEquals(MediaKeyAction.STOP, mediaKeyActionFor(VK_MEDIA_STOP))
        assertEquals(MediaKeyAction.PREVIOUS, mediaKeyActionFor(VK_MEDIA_PREVTRACK))
        assertEquals(MediaKeyAction.NEXT, mediaKeyActionFor(VK_MEDIA_NEXTTRACK))
    }

    @Test
    fun testUnknownVirtualKeysMapToNull() {
        assertNull(mediaKeyActionFor(0x41)) // 'A' — ordinary key, not owned by us
        assertNull(mediaKeyActionFor(0x00))
        assertNull(mediaKeyActionFor(-1))
        assertNull(mediaKeyActionFor(0x100)) // outside the virtual-key range
    }

    @Test
    fun testWindowsOsNameDetection() {
        assertTrue(isWindowsOsName("Windows 10"))
        assertTrue(isWindowsOsName("windows 11"))
        assertTrue(isWindowsOsName("Windows Server 2019"))
        assertFalse(isWindowsOsName("Linux"))
        assertFalse(isWindowsOsName("Mac OS X"))
        assertFalse(isWindowsOsName("Darwin"))
        assertFalse(isWindowsOsName(""))
    }

    @Test
    fun testStartIsNoOpOnNonWindows() {
        val keys = GlobalMediaKeys(isWindows = { false })
        val received = mutableListOf<MediaKeyAction>()
        keys.start { received.add(it) }
        keys.stop()
        assertFalse(keys.isRunning)
        assertTrue(received.isEmpty())
    }

    @Test
    fun testRepeatedStartAndStopDoNotThrow() {
        val keys = GlobalMediaKeys(isWindows = { false })
        keys.start { }
        keys.start { } // already running — ignored
        keys.stop()
        keys.stop() // already stopped — ignored
        assertFalse(keys.isRunning)
    }

    @Test
    fun testStopWithoutStartDoesNotThrow() {
        val keys = GlobalMediaKeys(isWindows = { false })
        keys.stop()
        assertFalse(keys.isRunning)
    }

    @Test
    fun testRestartAfterStop() {
        val keys = GlobalMediaKeys(isWindows = { false })
        keys.start { }
        keys.stop()
        keys.start { }
        keys.stop()
        assertFalse(keys.isRunning)
    }

    @Test
    fun testHandleHotKeyDispatchesMappedActions() {
        val keys = GlobalMediaKeys(isWindows = { false })
        val received = mutableListOf<MediaKeyAction>()
        keys.start { received.add(it) }
        // The pump never runs on the injected non-Windows platform, so dispatch
        // is driven directly through the same entry point the message loop uses.
        keys.handleHotKey(VK_MEDIA_PLAY_PAUSE)
        keys.handleHotKey(VK_MEDIA_STOP)
        keys.handleHotKey(VK_MEDIA_PREVTRACK)
        keys.handleHotKey(VK_MEDIA_NEXTTRACK)
        keys.handleHotKey(0x41) // unmapped — dropped
        assertEquals(
            listOf(
                MediaKeyAction.PLAY_PAUSE,
                MediaKeyAction.STOP,
                MediaKeyAction.PREVIOUS,
                MediaKeyAction.NEXT
            ),
            received
        )
        keys.stop()
    }

    @Test
    fun testHandlerExceptionIsSwallowed() {
        val keys = GlobalMediaKeys(isWindows = { false })
        var delivered = false
        keys.start { action ->
            if (action == MediaKeyAction.PLAY_PAUSE) throw RuntimeException("boom")
            delivered = true
        }
        keys.handleHotKey(VK_MEDIA_PLAY_PAUSE) // must not propagate
        keys.handleHotKey(VK_MEDIA_NEXTTRACK)
        assertTrue(delivered) // dispatch continues after the failure
        keys.stop()
    }

    @Test
    fun testHandleHotKeyAfterStopIsIgnored() {
        val keys = GlobalMediaKeys(isWindows = { false })
        var received = 0
        keys.start { received++ }
        keys.stop()
        keys.handleHotKey(VK_MEDIA_PLAY_PAUSE)
        assertEquals(0, received)
    }
}
