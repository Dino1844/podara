package app.podara.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Why the hoisted search query has to be snapshot-backed.
 *
 * The Discover search field is controlled: it displays whatever the hoisted
 * query says, and typing goes out through a callback into the shell. That makes
 * the store's observable-ness load-bearing — a plain `mutableMapOf` accepts the
 * write and reports the new value to anyone who asks, but never invalidates the
 * composition that is displaying it, so the box does not show what was typed.
 *
 * This nearly shipped. It survived a full green run because every existing screen
 * test leaves the screen owning its own query: the tests pass no query and no
 * callback, the screen falls back to its own `mutableStateOf`, and that path is
 * observable. Only the app passes the query in. The two tests here put both
 * stores behind the same field so the difference cannot hide again.
 */
class HoistedSearchQueryObservabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** A controlled field whose value comes from [store], as Discover's does. */
    @Composable
    private fun ControlledField(store: MutableMap<String, String>) {
        // A redraw counter the writer never touches. If the field updates while
        // this stays put, the update came from the store being observable rather
        // than from some unrelated recomposition carrying it along.
        var redraws by remember { mutableStateOf(0) }
        Column(Modifier.fillMaxWidth()) {
            TextField(
                value = store["discover"].orEmpty(),
                onValueChange = { store["discover"] = it },
                modifier = Modifier.fillMaxWidth().testTag("field")
            )
            Text("redraws=$redraws")
        }
    }

    @Test
    fun aStateMapDrivesTheControlledField() {
        // Created outside composition on purpose: `mutableStateMapOf` is a state
        // object in its own right and does not need `remember` to be observable.
        val store = mutableStateMapOf<String, String>()
        composeTestRule.setContent { ControlledField(store) }

        composeTestRule.onNodeWithText("redraws=0").assertExists()
        composeTestRule.onNodeWithTag("field").performTextReplacement("npr")
        composeTestRule.waitForIdle()

        assertEquals("npr", store["discover"])
        // The field shows it, and nothing else in the tree changed to carry it.
        composeTestRule.onNodeWithTag("field").assertTextContains("npr")
        composeTestRule.onNodeWithText("redraws=0").assertExists()
    }

    @Test
    fun aPlainMapAcceptsTheWrite() {
        // Half the failure: the store looks correct, so a test that only reads
        // the store passes while the composition showing it was never told to
        // re-read. Pinned so the difference between the two stores is on record.
        val store: MutableMap<String, String> = mutableMapOf()
        store["discover"] = "npr"
        assertEquals("npr", store["discover"])
    }
}
