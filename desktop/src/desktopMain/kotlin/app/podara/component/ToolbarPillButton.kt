package app.podara.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import java.awt.Cursor

@Composable
fun ToolbarPillButton(
    icon: ImageVector? = null,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = DesignTokens.ToolbarButton.PillHeight,
    radius: androidx.compose.ui.unit.Dp = DesignTokens.ToolbarButton.PillRadius,
    borderWidth: androidx.compose.ui.unit.Dp = DesignTokens.ToolbarButton.BorderWidth,
    horizontalPadding: androidx.compose.ui.unit.Dp = if (label.isBlank()) 0.dp else DesignTokens.ToolbarButton.PillPaddingHorizontal,
    iconSize: androidx.compose.ui.unit.Dp = DesignTokens.ToolbarButton.PillIconSize,
    iconTextGap: androidx.compose.ui.unit.Dp = DesignTokens.ToolbarButton.PillIconTextGap,
    textSize: androidx.compose.ui.unit.TextUnit = DesignTokens.ToolbarButton.PillTextSize,
    lineHeight: androidx.compose.ui.unit.TextUnit = DesignTokens.ToolbarButton.PillLineHeight,
    minWidth: androidx.compose.ui.unit.Dp = if (label.isBlank()) DesignTokens.ToolbarButton.PillHeight else DesignTokens.ToolbarButton.ManageMinWidth,
    // Scheme-dependent fills resolve through SurfaceTokens rather than the
    // DesignTokens constants, which are frozen to dark-assumption values and
    // would render as white-on-white under the light scheme.
    iconColor: Color = PodaraTheme.surfaces.pillIcon,
    hoverIconColor: Color = PodaraTheme.surfaces.pillIconHover,
    textColor: Color = PodaraTheme.surfaces.pillText,
    hoverTextColor: Color = PodaraTheme.surfaces.pillTextHover,
    hoverBackgroundColor: Color = PodaraTheme.surfaces.pillFillHover,
    defaultBackgroundColor: Color = PodaraTheme.surfaces.pillFill,
    pressedBackgroundColor: Color = PodaraTheme.surfaces.pillFillPressed,
    defaultBorderColor: Color = PodaraTheme.surfaces.pillBorder,
    hoverBorderColor: Color = PodaraTheme.surfaces.pillBorderHover
) {
    val toolbarButton = DesignTokens.ToolbarButton
    val surfaces = PodaraTheme.surfaces
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(radius)
    val foregroundColor = if (isHovered || isPressed) hoverTextColor else textColor
    val iconTint = if (isHovered || isPressed) hoverIconColor else iconColor

    Box(
        modifier = modifier
            .height(height)
            .widthIn(min = minWidth)
            .shadow(
                if (isHovered) toolbarButton.PillHoverShadowElevation else androidx.compose.ui.unit.Dp.Hairline,
                shape,
                ambientColor = surfaces.shadow,
                spotColor = surfaces.shadow
            )
            .clip(shape)
            .background(
                when {
                    isPressed -> pressedBackgroundColor
                    isHovered -> hoverBackgroundColor
                    else -> defaultBackgroundColor
                }
            )
            .border(
                borderWidth,
                if (isHovered || isPressed) hoverBorderColor else defaultBorderColor,
                shape
            )
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(iconSize)
                )
            }
            if (label.isNotBlank()) {
                if (icon != null) Spacer(Modifier.width(iconTextGap))
                Text(
                    text = label,
                    color = foregroundColor,
                    fontSize = textSize,
                    lineHeight = lineHeight,
                    fontWeight = toolbarButton.PillTextWeight
                )
            }
        }
    }
}
