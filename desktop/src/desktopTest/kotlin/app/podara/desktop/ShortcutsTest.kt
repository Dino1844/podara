package app.podara.desktop

import androidx.compose.ui.input.key.Key
import app.podara.platform.ShortcutAction
import app.podara.platform.shortcutFor
import kotlin.test.*

/**
 * The key-to-action table, tested without a window.
 *
 * `shortcutFor` is a pure function on purpose: the wiring in `App.kt` needs a
 * composition and a focused window, so the mapping is the part worth pinning.
 * Every action the handler implements must be reachable from some key —
 * previously four of them were declared but no key produced them.
 */
class ShortcutsTest {

    @Test
    fun spaceTogglesPlayback() {
        assertEquals(ShortcutAction.PLAY_PAUSE, shortcutFor(Key.Spacebar))
    }

    @Test
    fun arrowsSeekAndChangeVolume() {
        assertEquals(ShortcutAction.SEEK_FORWARD, shortcutFor(Key.DirectionRight))
        assertEquals(ShortcutAction.SEEK_BACK, shortcutFor(Key.DirectionLeft))
        assertEquals(ShortcutAction.VOLUME_UP, shortcutFor(Key.DirectionUp))
        assertEquals(ShortcutAction.VOLUME_DOWN, shortcutFor(Key.DirectionDown))
    }

    @Test
    fun pageKeysAndMediaKeysMoveWithinTheQueue() {
        assertEquals(ShortcutAction.NEXT, shortcutFor(Key.MediaNext))
        assertEquals(ShortcutAction.NEXT, shortcutFor(Key.PageDown))
        assertEquals(ShortcutAction.PREVIOUS, shortcutFor(Key.MediaPrevious))
        assertEquals(ShortcutAction.PREVIOUS, shortcutFor(Key.PageUp))
    }

    @Test
    fun muteUsesTheVolumeMuteKey() {
        // Compose 1.9.0 has no `Key.Mute`; this used to be an unresolved
        // reference and the whole module failed to compile.
        assertEquals(ShortcutAction.TOGGLE_MUTE, shortcutFor(Key.VolumeMute))
    }

    @Test
    fun escapeCloses() {
        assertEquals(ShortcutAction.CLOSE_OVERLAY, shortcutFor(Key.Escape))
    }

    @Test
    fun fAndQOpenTheOverlays() {
        assertEquals(ShortcutAction.TOGGLE_FULL_PLAYER, shortcutFor(Key.F))
        assertEquals(ShortcutAction.TOGGLE_QUEUE, shortcutFor(Key.Q))
    }

    @Test
    fun bracketsAndEqualsChangeSpeed() {
        assertEquals(ShortcutAction.SPEED_DOWN, shortcutFor(Key.Minus))
        assertEquals(ShortcutAction.SPEED_UP, shortcutFor(Key.Equals))
        assertEquals(ShortcutAction.SPEED_UP, shortcutFor(Key.Plus))
    }

    @Test
    fun everyActionIsReachableFromSomeKey() {
        // Guards against adding a case to the enum and forgetting the mapping:
        // the handler would then carry a branch nothing can trigger.
        val reachable = listOf(
            Key.Spacebar, Key.DirectionRight, Key.DirectionLeft, Key.DirectionUp,
            Key.DirectionDown, Key.MediaNext, Key.PageDown, Key.MediaPrevious,
            Key.PageUp, Key.VolumeMute, Key.F, Key.Q, Key.Minus, Key.Equals,
            Key.Plus, Key.Escape
        ).mapNotNull { shortcutFor(it) }.toSet()

        assertEquals(
            ShortcutAction.entries.toSet(),
            reachable,
            "actions without a key: ${ShortcutAction.entries.toSet() - reachable}"
        )
    }

    @Test
    fun unmappedKeysReturnNull() {
        assertNull(shortcutFor(Key.A))
        assertNull(shortcutFor(Key.Enter))
    }
}
