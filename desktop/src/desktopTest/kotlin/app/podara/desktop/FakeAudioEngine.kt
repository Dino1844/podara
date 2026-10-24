package app.podara.desktop

import app.podara.player.AudioPlayerEngine
import app.podara.player.PlaybackState
import app.podara.player.PlayerEvent
import app.podara.player.PlayerSnapshot

/**
 * An [AudioPlayerEngine] that decodes nothing and touches no native library.
 *
 * UI tests that only assert what is on screen were each constructing a real
 * `MpvAudioPlayerEngine`, which loads a 99 MB `mpv-1.dll`, starts a worker
 * thread, and — because the tests pass real `https://` URLs — makes a genuine
 * network request that fails after a DNS lookup and a connect timeout. None of
 * that is what those tests are about.
 *
 * This engine answers `open` immediately with a PLAYING snapshot, so the state
 * layer behaves the way it does when playback starts, and never contacts
 * anything. Tests that exist to verify the real engine — `MpvStartupOptionsTest`,
 * `MpvAudioPlayerEngineTest`, `RealPlaybackIntegrationTest` — must keep using
 * the real one.
 *
 * Events are delivered synchronously on the calling thread rather than from a
 * worker: a test that wants to observe a transition can call `open` and read
 * `snapshot` straight away, with nothing to wait for.
 */
class FakeAudioEngine : AudioPlayerEngine {

    private var handler: ((PlayerEvent) -> Unit)? = null

    private var _snapshot = PlayerSnapshot()

    override val snapshot: PlayerSnapshot get() = _snapshot

    override fun setEventHandler(handler: (PlayerEvent) -> Unit) {
        this.handler = handler
    }

    override fun open(session: Long, url: String, speed: Float, startPositionMs: Long, durationMs: Long): Long {
        _snapshot = PlayerSnapshot(
            state = PlaybackState.PLAYING,
            positionMs = startPositionMs,
            durationMs = durationMs,
            session = session
        )
        handler?.invoke(PlayerEvent.StateChanged(session, PlaybackState.PLAYING))
        handler?.invoke(
            PlayerEvent.Progress(session, startPositionMs, durationMs)
        )
        return session
    }

    override fun setPaused(paused: Boolean) {
        val state = if (paused) PlaybackState.PAUSED else PlaybackState.PLAYING
        _snapshot = _snapshot.copy(state = state)
        handler?.invoke(PlayerEvent.StateChanged(_snapshot.session, state))
    }

    override fun stop() {
        _snapshot = PlayerSnapshot()
    }

    override fun seek(positionMs: Long) {
        _snapshot = _snapshot.copy(positionMs = positionMs)
        handler?.invoke(PlayerEvent.Progress(_snapshot.session, positionMs, _snapshot.durationMs))
    }

    override fun setSpeed(speed: Float) = Unit

    override fun setVolume(volume: Int) = Unit

    override fun release() {
        // Nothing to tear down: no thread, no handle, no native library.
    }
}
