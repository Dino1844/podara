package app.podara.desktop

import app.podara.player.MediaPlayerState
import app.podara.util.Settings
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two audio enhancement toggles at the state layer.
 *
 * [MediaPlayerState] owns the pair: a user flip mirrors into Compose state,
 * persists through [Settings] (the same properties file language and theme
 * use), and reaches the engine as one (skipSilence, loudnessBoost) pair — the
 * engine overwrites the whole `af` chain from it, so repeated switching cannot
 * stack filters. A newly constructed state must re-apply the persisted pair
 * before any load, which is what makes the toggles survive a restart.
 *
 * Settings is a process-wide singleton over `~/.podara/settings.properties`,
 * so every test captures what it found and restores it; leaving a toggle on
 * behind would leak into other tests' constructor pushes.
 */
class AudioEnhancementsTest {

    private var originalSkipSilence = false
    private var originalLoudnessBoost = false

    @BeforeTest
    fun rememberPersistedToggles() {
        originalSkipSilence = Settings.isSkipSilenceEnabled()
        originalLoudnessBoost = Settings.isLoudnessBoostEnabled()
        // Deterministic starting point for the constructor-push assertions.
        Settings.setSkipSilence(false)
        Settings.setLoudnessBoost(false)
    }

    @AfterTest
    fun restorePersistedToggles() {
        Settings.setSkipSilence(originalSkipSilence)
        Settings.setLoudnessBoost(originalLoudnessBoost)
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }

    @Test
    fun aFreshStatePushesThePersistedPairBeforeAnyLoad() {
        Settings.setSkipSilence(true)
        Settings.setLoudnessBoost(true)
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            awaitUntil { engine.receivedAudioFilters.isNotEmpty() }
            assertEquals(
                listOf(true to true),
                engine.receivedAudioFilters.toList(),
                "the persisted toggles must reach the engine at construction, before any open"
            )
            assertTrue(state.skipSilence && state.loudnessBoost)
            assertEquals(
                app.podara.player.PlaybackState.IDLE,
                engine.snapshot.state,
                "no load may be involved in the push"
            )
        } finally {
            state.release()
        }
    }

    @Test
    fun changingSkipSilenceReachesEngineAndPersistence() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            awaitUntil { engine.receivedAudioFilters.isNotEmpty() }

            state.changeSkipSilence(true)

            assertEquals(
                listOf(false to false, true to false),
                engine.receivedAudioFilters.toList(),
                "the pair is always sent whole: the engine overwrites the chain from it"
            )
            assertTrue(state.skipSilence)
            assertEquals(true, Settings.isSkipSilenceEnabled(), "the choice must survive the state object")
        } finally {
            state.release()
        }
    }

    @Test
    fun changingLoudnessBoostReachesEngineAndPersistence() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            awaitUntil { engine.receivedAudioFilters.isNotEmpty() }

            state.changeLoudnessBoost(true)
            state.changeSkipSilence(true)

            assertEquals(
                listOf(false to false, false to true, true to true),
                engine.receivedAudioFilters.toList(),
                "either toggle's flip must retarget the combined chain"
            )
            assertEquals(true, Settings.isLoudnessBoostEnabled())
            assertEquals(true, Settings.isSkipSilenceEnabled())
        } finally {
            state.release()
        }
    }

    @Test
    fun settingTheSameValueAgainIsANoOp() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            awaitUntil { engine.receivedAudioFilters.isNotEmpty() }

            state.changeSkipSilence(false)
            state.changeLoudnessBoost(false)

            assertEquals(
                1,
                engine.receivedAudioFilters.size,
                "a same-value flip queues no engine command and no persistence write"
            )
        } finally {
            state.release()
        }
    }

    @Test
    fun aRestartedStateResumesWithThePersistedToggles() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.changeSkipSilence(true)
        } finally {
            state.release()
        }

        // The "restart": a brand-new state reads what the last one wrote.
        val restarted = MediaPlayerState(FakeAudioEngine())
        try {
            awaitUntil { restarted.skipSilence }
            assertTrue(restarted.skipSilence, "the flip must outlive the state object")
            assertTrue(!restarted.loudnessBoost, "the untouched toggle stays off")
        } finally {
            restarted.release()
        }
    }

    @Test
    fun theTogglesReachTheSettingsFileTheNextLaunchWillLoad() {
        // The in-memory property could be right while nothing was written; the
        // failure mode this guards is a toggle that works for the session and
        // is gone after a restart.
        Settings.setSkipSilence(true)
        Settings.setLoudnessBoost(false)

        val reloaded = Properties()
        settingsFile().inputStream().use { reloaded.load(it) }
        assertEquals("true", reloaded.getProperty("player_skip_silence"))
        assertEquals("false", reloaded.getProperty("player_loudness_boost"))
    }

    /** Mirrors the path Settings reads at startup. */
    private fun settingsFile(): java.io.File =
        java.io.File(System.getProperty("user.home"), ".podara/settings.properties")
}
