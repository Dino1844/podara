package app.podara.desktop

import app.podara.util.Settings
import app.podara.util.Strings
import java.io.File
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Language persistence.
 *
 * The language row used to change only Strings' snapshot state and left the
 * write to a separate Change button, so picking a language and navigating away
 * kept the session translated while the next launch started in the old one.
 * These tests pin down the storage contract the row now relies on.
 *
 * Settings is a process-wide singleton over `~/.podara/settings.properties`,
 * so every test captures the value it found and restores it; leaving "zh"
 * behind would break the English-label assertions elsewhere (SettingsScreenTest
 * looks up Strings[...], and the tray menu keys resolve per language).
 */
class LanguagePreferenceTest {

    private lateinit var originalLanguage: String

    @BeforeTest
    fun rememberCurrentLanguage() {
        originalLanguage = Settings.getLanguage()
        Strings.updateLanguage(originalLanguage)
    }

    @AfterTest
    fun restoreCurrentLanguage() {
        Settings.setLanguage(originalLanguage)
        Strings.updateLanguage(originalLanguage)
    }

    @Test
    fun testLanguageRoundTrips() {
        Settings.setLanguage("zh")
        assertEquals("zh", Settings.getLanguage())

        Settings.setLanguage("en")
        assertEquals("en", Settings.getLanguage())
    }

    @Test
    fun testSelectionReachesTheSettingsFile() {
        Settings.setLanguage("zh")

        // The in-memory property could be right while nothing was written; the
        // bug this guards against was exactly a missing write, so read back the
        // file the next launch will load.
        val file = settingsFile()
        assertTrue(file.exists(), "settings.properties should exist after setLanguage")

        val reloaded = Properties()
        file.inputStream().use { reloaded.load(it) }
        assertEquals("zh", reloaded.getProperty("language"))
    }

    @Test
    fun testStringsFollowsTheSelectedLanguage() {
        // The call site updates both; assert the pair stays in step and that the
        // label actually changes, not just the stored code.
        Strings.updateLanguage("en")
        assertEquals("Play", Strings["tray_play"])

        Strings.updateLanguage("zh")
        Settings.setLanguage("zh")

        assertEquals("zh", Settings.getLanguage())
        assertEquals("播放", Strings["tray_play"])
        assertEquals("暂停", Strings["tray_pause"])
    }

    /** Mirrors the path Settings reads at startup. */
    private fun settingsFile(): File =
        File(System.getProperty("user.home"), ".podara/settings.properties")
}
