package app.podara.component

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.util.LocalSetTextFieldFocus
import app.podara.util.Strings
import app.podara.util.clickableWithoutIndication

/**
 * The rounded search field used by Home, Discover, History and Favorites.
 *
 * This was four near-identical copies of the same ~45 lines (Surface + border +
 * BasicTextField + clear button). They had already drifted: only Discover's
 * field submitted on Enter, so pressing Enter in the other three did nothing.
 * Folding them together means a behaviour fix lands everywhere at once.
 *
 * @param onSearch invoked on Enter when [submitOnEnter] is set. Defaults to a
 *   no-op so a filter-as-you-type screen does not need to supply one.
 * @param onClear invoked after the value is cleared, for screens that also need
 *   to reset their result state.
 */
@Composable
fun PodaraSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    submitOnEnter: Boolean = false,
    onSearch: () -> Unit = {},
    onClear: () -> Unit = {}
) {
    val colors = PodaraTheme.colors
    val surfaces = PodaraTheme.surfaces
    val searchToken = DesignTokens.SearchBar
    val shape = RoundedCornerShape(searchToken.Radius)
    val interactionSource = MutableInteractionSource()
    var isFocused by remember { mutableStateOf(false) }

    // Announce focus upward so the window-level shortcut handler stops
    // intercepting Space and the arrow keys while the user is typing.
    val setTextFieldFocus = LocalSetTextFieldFocus.current
    DisposableEffect(Unit) {
        onDispose { setTextFieldFocus(false) }
    }

    // No resting border. The field's own fill is what separates it from the
    // sheet, and a hairline around it draws a second edge around the same
    // rectangle; the line appears only while focused, which is the one moment an
    // edge carries information rather than decoration.
    Surface(
        modifier = modifier
            .width(searchToken.Width)
            .height(searchToken.Height)
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                if (isFocused) Modifier.border(DesignTokens.Border.Width, surfaces.sheetBorder, shape)
                else Modifier
            ),
        shape = shape,
        color = colors.surface
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = searchToken.PaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(searchToken.IconSize)
            )
            Spacer(modifier = Modifier.width(searchToken.Gap))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = colors.textDisabled,
                        fontSize = searchToken.TextSize
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { setTextFieldFocus(it.isFocused) }
                        .then(
                            if (submitOnEnter) {
                                Modifier.onKeyEvent { event ->
                                    if (event.key == Key.Enter &&
                                        event.type == KeyEventType.KeyUp &&
                                        value.isNotBlank()
                                    ) {
                                        onSearch()
                                        true
                                    } else {
                                        false
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = searchToken.TextSize
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    interactionSource = interactionSource
                )
            }
            if (value.isNotEmpty()) {
                PodaraTooltip(text = Strings["discover_search_clear"]) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = Strings["discover_search_clear"],
                        tint = colors.textMuted,
                        modifier = Modifier
                            .size(searchToken.ClearIconSize)
                            .clickableWithoutIndication {
                                onValueChange("")
                                onClear()
                            }
                    )
                }
            }
        }
    }
}
