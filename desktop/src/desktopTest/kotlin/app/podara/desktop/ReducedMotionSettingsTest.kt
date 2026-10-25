package app.podara.desktop

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.podara.data.AppDatabase
import app.podara.screen.SettingsScreen
import app.podara.theme.PodaraTheme
import app.podara.util.Settings
import app.podara.util.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The 减少动效 switch (DESIGN-CRAFT.md C4): that the preference persists, and
 * that the Settings row behaves like the other inline-control rows.
 *
 * Persistence is exercised through the real [Settings] singleton — the same
 * trade AudioEnhancementsTest makes: the write lands in the user's settings
 * file, so the original value is captured before and restored after. What this
 * proves is the props round-trip and the disk write path; it cannot prove what
 * a fresh process would read, because the object loads its file once per JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReducedMotionSettingsTest {

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File
    private val testDispatcher = StandardTestDispatcher()
    private val originalReducedMotion = Settings.isReducedMotionEnabled()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podara_reduced_motion_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
        Settings.setReducedMotion(false)
    }

    @After
    fun teardown() {
        Settings.setReducedMotion(originalReducedMotion)
        database.close()
        testDbFile.delete()
        Dispatchers.resetMain()
    }

    @Test
    fun reducedMotionRoundTripsThroughSettings() {
        Settings.setReducedMotion(true)
        assertTrue(
            "the switch must read back what was written",
            Settings.isReducedMotionEnabled()
        )
        Settings.setReducedMotion(false)
        assertFalse(
            "clearing the flag must read back as off",
            Settings.isReducedMotionEnabled()
        )
    }

    @Test
    fun reducedMotionRowIsPresentAndStaysAnInlineControl() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        // The Appearance section sits below the fold in the test scene, so the
        // row has to be scrolled into view before anything is asserted on it.
        composeTestRule.onNodeWithText(Strings["settings_reduced_motion"]).performScrollTo()
        composeTestRule.onNodeWithText(Strings["settings_reduced_motion"]).assertIsDisplayed()
        // The edit affordance is the switch, so per the SettingsRow contract
        // the row itself must not expose a click action — the same regression
        // guard SettingsScreenTest runs for the other inline rows.
        val row = composeTestRule.onNodeWithText(Strings["settings_reduced_motion"]).fetchSemanticsNode()
        assertFalse(
            "the reduce-motion row must not expose a row-level click action",
            hasClickAction().matches(row)
        )
    }

    @Test
    fun reducedMotionSwitchReflectsTheStateTheShellHandsIt() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {}, reducedMotionEnabled = true)
            }
        }
        composeTestRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun reducedMotionSwitchReportsTogglesToTheShell() {
        var reported: Boolean? = null
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(
                    database = database,
                    onBack = {},
                    reducedMotionEnabled = false,
                    onReducedMotionChanged = { reported = it }
                )
            }
        }
        // The switch is the screen's only toggleable node, and it sits below
        // the fold — scroll to it before the gesture. The toggleable modifier
        // exposes the change through its click action, so performClick is the
        // toggle on this Compose version.
        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()
        assertTrue(
            "toggling the switch must hand the new value back to the shell",
            reported == true
        )
    }
}
