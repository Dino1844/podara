package app.podara.desktop
import app.podara.APP_VERSION
import app.podara.screen.SettingsScreen

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.podara.data.AppDatabase
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsScreenTest {

    private lateinit var database: AppDatabase
    private lateinit var testDbFile: File
    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testDbFile = File(System.getProperty("java.io.tmpdir"), "podium_settings_test_${System.currentTimeMillis()}.db")
        testDbFile.deleteOnExit()
        database = AppDatabase.build(testDbFile)
    }

    @After
    fun teardown() {
        database.close()
        testDbFile.delete()
        Dispatchers.resetMain()
    }

    @Test
    fun testSettingsTitle() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["settings_title"]).assertIsDisplayed()
    }

    @Test
    fun testSettingsVersion() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(Strings.get("settings_version", APP_VERSION)).assertExists()
    }

    @Test
    fun testSettingsAppName() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["settings_about_desc"]).assertExists()
    }

    @Test
    fun testSettingsExportButton() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["settings_export_opml"]).assertIsDisplayed()
        composeTestRule.onNodeWithText(Strings["settings_export"]).assertIsDisplayed()
    }

    @Test
    fun testSettingsImportButton() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["settings_import_opml"]).assertIsDisplayed()
        composeTestRule.onNodeWithText(Strings["settings_import"]).assertIsDisplayed()
    }

    @Test
    fun testSettingsDataSection() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["settings_data"]).assertIsDisplayed()
    }

    @Test
    fun testSettingsAboutSection() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["settings_about"]).assertExists()
    }

    @Test
    fun testSettingsExportDescription() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["settings_export_opml_desc"]).assertIsDisplayed()
    }

    @Test
    fun testSettingsImportDescription() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        composeTestRule.onNodeWithText(Strings["settings_import_opml_desc"]).assertIsDisplayed()
    }

    @Test
    fun testSettingsStaticRowsAreNotClickable() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        // Regression guard: the shared row composable used to give every row an
        // empty click handler with a hand cursor, so clicks did nothing. Rows
        // whose edit affordance is an inline control or a trailing button must
        // not expose a row-level click action.
        assertRowNotClickable(Strings["settings_export_opml"])
        assertRowNotClickable(Strings["settings_import_opml"])
        assertRowNotClickable(Strings["settings_download_location"])
        assertRowNotClickable(Strings["settings_theme"])
    }

    private fun assertRowNotClickable(text: String) {
        val node = composeTestRule.onNodeWithText(text).fetchSemanticsNode()
        org.junit.Assert.assertFalse(
            "$text must not expose a row-level click action",
            hasClickAction().matches(node)
        )
    }

    @Test
    fun testSettingsDialogRowsAreClickable() {
        composeTestRule.setContent {
            PodaraTheme {
                SettingsScreen(database = database, onBack = {})
            }
        }
        // Rows that open an editor dialog keep a row-level click action (the
        // standard settings-row pattern, same as their Change button).
        composeTestRule.onNodeWithText(Strings["settings_download_speed_limit"]).assert(hasClickAction())
    }
}
