package app.podara.desktop

import app.podara.player.MPV_ERROR_GENERIC
import app.podara.player.MPV_ERROR_LOADING_FAILED
import app.podara.player.MPV_ERROR_NOTHING_TO_PLAY
import app.podara.player.MPV_ERROR_UNKNOWN_FORMAT
import app.podara.player.MpvAudioPlayerEngine
import app.podara.player.PlaybackErrorCategory
import app.podara.player.PlaybackState
import app.podara.player.playbackErrorCategory
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class MpvAudioPlayerEngineTest {

    @Test
    fun testErrorCategoryMapping() {
        assertEquals(
            PlaybackErrorCategory.UNSUPPORTED,
            playbackErrorCategory(MPV_ERROR_UNKNOWN_FORMAT, "https://example.com/a.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.UNSUPPORTED,
            playbackErrorCategory(MPV_ERROR_NOTHING_TO_PLAY, "episode.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.NETWORK,
            playbackErrorCategory(MPV_ERROR_LOADING_FAILED, "https://example.com/a.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.NETWORK,
            playbackErrorCategory(MPV_ERROR_LOADING_FAILED, "http://example.com/a.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.FILE,
            playbackErrorCategory(MPV_ERROR_LOADING_FAILED, "C:/Users/me/Music/episode.mp3")
        )
        assertEquals(
            PlaybackErrorCategory.GENERIC,
            playbackErrorCategory(MPV_ERROR_GENERIC, "https://example.com/a.mp3")
        )
    }

    @Test
    fun testMissingFileReportsErrorCategoryAndState() {
        // The regression this guards: mpv load failures used to be silent —
        // onError was declared but never invoked, so a missing file or a dead
        // stream looked like "tapped play, nothing happened".
        val engine = MpvAudioPlayerEngine()
        try {
            val error = AtomicReference<String?>(null)
            engine.onError = { error.set(it) }

            val missing = File(System.getProperty("java.io.tmpdir"), "podara-missing-${System.nanoTime()}.mp3")
                .absolutePath
            engine.play(missing)

            // The poll thread drains mpv events every 250 ms; a missing local
            // file fails the load attempt well within that order of magnitude.
            val deadline = System.currentTimeMillis() + 10_000
            while (error.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }

            assertEquals(PlaybackErrorCategory.FILE, error.get())
            assertFalse(engine.isPlaying)
            assertEquals(PlaybackState.ERROR, engine.playbackState)
        } finally {
            engine.release()
        }
    }

    @Test
    fun testInitialState() {
        val engine = MpvAudioPlayerEngine()
        assertFalse(engine.isPlaying)
        assertEquals(0L, engine.currentPosition)
        assertEquals(0L, engine.duration)
        assertEquals(PlaybackState.IDLE, engine.playbackState)
        assertNull(engine.metadata)
        engine.release()
    }

    @Test
    fun testPauseWhenNotPlaying() {
        val engine = MpvAudioPlayerEngine()
        engine.pause()
        assertFalse(engine.isPlaying)
        assertEquals(PlaybackState.PAUSED, engine.playbackState)
        engine.release()
    }

    @Test
    fun testStopResetsState() {
        val engine = MpvAudioPlayerEngine()
        engine.stop()
        assertFalse(engine.isPlaying)
        assertEquals(PlaybackState.STOPPED, engine.playbackState)
        engine.release()
    }

    @Test
    fun testSetSpeedClamps() {
        val engine = MpvAudioPlayerEngine()
        engine.setSpeed(0.01f)
        engine.setSpeed(100f)
        engine.release()
    }

    @Test
    fun testSetVolumeClamps() {
        val engine = MpvAudioPlayerEngine()
        engine.setVolume(-10)
        engine.setVolume(200)
        engine.release()
    }

    @Test
    fun testCallbackNullSafety() {
        val engine = MpvAudioPlayerEngine()
        engine.onPlayStateChanged = null
        engine.onPositionChanged = null
        engine.onError = null
        engine.stop()
        engine.release()
    }

    @Test
    fun testReleaseCleanup() {
        val engine = MpvAudioPlayerEngine()
        engine.release()
        assertEquals(PlaybackState.IDLE, engine.playbackState)
        assertNull(engine.metadata)
    }

    @Test
    fun testPlaySetsMetadata() {
        val engine = MpvAudioPlayerEngine()
        engine.play("https://example.com/test.mp3", durationMs = 60000L)
        assertNotNull(engine.metadata)
        assertEquals("https://example.com/test.mp3", engine.metadata!!.url)
        assertEquals(60000L, engine.metadata!!.durationMs)
        assertTrue(engine.playbackState == PlaybackState.LOADING || engine.playbackState == PlaybackState.PLAYING)
        engine.release()
    }
}
