package app.podara.platform

import androidx.compose.ui.input.key.Key

/**
 * A player action a keyboard shortcut can trigger.
 *
 * Kept separate from Compose's `KeyEvent` so the mapping is a pure function and
 * can be tested without a window.
 */
enum class ShortcutAction {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    SEEK_FORWARD,
    SEEK_BACK,
    VOLUME_UP,
    VOLUME_DOWN,
    TOGGLE_MUTE,
    TOGGLE_FULL_PLAYER,
    TOGGLE_QUEUE,
    SPEED_UP,
    SPEED_DOWN,
    CLOSE_OVERLAY
}

/**
 * Maps a key to a player action.
 *
 * This app had no keyboard control at all: 51 call sites opted out of the focus
 * ring and the only `onKeyEvent` in the codebase was Discover's search box. For
 * a Windows podcast client that is a real gap — it is the kind of app you leave
 * running in the background, and reaching for the mouse to pause is friction
 * that a global media key does not cover (volume, seeking, speed).
 *
 * Deliberately ignores modifier state: these are bare-key shortcuts, and the
 * text fields in the app consume their own keystrokes before this sees them.
 *
 * @return the action, or null when [key] is not a shortcut.
 */
fun shortcutFor(key: Key): ShortcutAction? = when (key) {
    Key.Spacebar -> ShortcutAction.PLAY_PAUSE
    Key.DirectionRight -> ShortcutAction.SEEK_FORWARD
    Key.DirectionLeft -> ShortcutAction.SEEK_BACK
    Key.DirectionUp -> ShortcutAction.VOLUME_UP
    Key.DirectionDown -> ShortcutAction.VOLUME_DOWN
    Key.MediaNext, Key.PageDown -> ShortcutAction.NEXT
    Key.MediaPrevious, Key.PageUp -> ShortcutAction.PREVIOUS
    // Compose 1.9.0 has no `Key.Mute`; the volume-mute key is this one.
    Key.VolumeMute -> ShortcutAction.TOGGLE_MUTE
    Key.F -> ShortcutAction.TOGGLE_FULL_PLAYER
    Key.Q -> ShortcutAction.TOGGLE_QUEUE
    Key.Minus -> ShortcutAction.SPEED_DOWN
    Key.Equals, Key.Plus -> ShortcutAction.SPEED_UP
    Key.Escape -> ShortcutAction.CLOSE_OVERLAY
    else -> null
}
