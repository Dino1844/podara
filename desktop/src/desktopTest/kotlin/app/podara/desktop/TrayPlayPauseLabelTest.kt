package app.podara.desktop

import app.podara.util.Strings
import app.podara.util.playPauseStringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The tray menu's play/pause item.
 *
 * The label must describe what the click does, and the click toggles playback.
 * The item used to be built from a hard-coded "Play" and patched afterwards by
 * updatePlayPauseLabel, which wrote to the previous menu's already-disposed
 * JMenuItem — so a playing episode showed "Play" and the click paused it.
 *
 * SystemTrayManager itself needs a live AWT tray, which no headless runner
 * provides, so the state-to-key decision lives in an internal pure function and
 * is covered here.
 */
class TrayPlayPauseLabelTest {

    @Test
    fun testPlayingOffersPause() {
        assertEquals("tray_pause", playPauseStringKey(isPlaying = true))
    }

    @Test
    fun testNotPlayingOffersPlay() {
        assertEquals("tray_play", playPauseStringKey(isPlaying = false))
    }

    @Test
    fun testKeysResolveToRealLabelsInEveryLanguage() {
        // playPauseStringKey only produces a key. Strings[key] falls back to the
        // key itself when a map lacks it, which would render "tray_play" in the
        // menu — so a resolved label must not equal the key and the two states
        // must stay distinguishable.
        val original = Strings.currentLanguage
        try {
            for (language in listOf("en", "zh")) {
                Strings.updateLanguage(language)
                val play = Strings[playPauseStringKey(isPlaying = false)]
                val pause = Strings[playPauseStringKey(isPlaying = true)]
                assertNotEquals("tray_play", play, "$language is missing tray_play")
                assertNotEquals("tray_pause", pause, "$language is missing tray_pause")
                assertNotEquals(play, pause, "$language needs distinct play/pause labels")
            }
        } finally {
            Strings.updateLanguage(original)
        }
    }
}
