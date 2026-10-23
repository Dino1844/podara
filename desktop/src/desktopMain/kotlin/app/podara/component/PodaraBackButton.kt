package app.podara.component

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import app.podara.theme.PodaraTheme
import app.podara.util.Strings
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.clickableWithoutIndicationOrFocusRing
import app.podara.util.pressScale
import java.awt.Cursor

/**
 * The circular back affordance shared by every top-level screen.
 *
 * History, Favorites, Downloads and Settings each declared an `onBack`
 * parameter and none of them used it: the argument was passed in from App.kt
 * and dropped on the floor. Those four screens are reachable from the sidebar
 * but had no way back, which made them dead ends compared to the podcast detail
 * page. This is the same control the detail top bar draws.
 */
@Composable
fun PodaraBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PodaraTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val background by animateHoverBackgroundColor(isHovered, colors.elevated)

    Box(
        modifier = modifier
            .size(36.dp)
            .pressScale(interactionSource)
            .clip(CircleShape)
            .background(background)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = Strings["nav_back"],
            tint = if (isHovered) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}
