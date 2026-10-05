package app.podara.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Scheme-dependent surface tokens.
 *
 * [DesignTokens] is a plain `object` of `val`s, so its colour entries are
 * resolved once at class-init and cannot read the active theme. That works
 * while the whole app is dark, where "subtle fill" means
 * `Color.White.copy(alpha = 0.08f)`. It breaks the moment a light scheme is
 * used: a white wash on a white page is invisible, and a black shadow at dark
 * alphas reads as dirt rather than depth.
 *
 * These tokens are provided through a CompositionLocal so the same call sites
 * resolve correctly under either scheme. Anything here must be treated as
 * scheme-relative; a caller that needs an absolute colour belongs in
 * [PodaraColors] instead.
 */
@Immutable
data class SurfaceTokens(
    /** Subtle raised fill: menu, dropdown, popover. */
    val menuFill: Color,
    /** Fill for a control sitting on top of [menuFill]. */
    val menuFillHover: Color,
    val menuBorder: Color,
    val menuShadow: Color,
    val menuText: Color,
    val menuTextHover: Color,
    val menuIcon: Color,
    val menuSelectedFill: Color,
    val menuDivider: Color,

    /** Rounded pill button: rest / hover / pressed / selected fills. */
    val pillFill: Color,
    val pillFillHover: Color,
    val pillFillPressed: Color,
    val pillFillSelected: Color,
    val pillBorder: Color,
    val pillBorderHover: Color,
    val pillBorderSelected: Color,
    val pillText: Color,
    val pillTextHover: Color,
    val pillTextSelected: Color,
    val pillIcon: Color,
    val pillIconHover: Color,

    /** Generic card fill and its hover wash. */
    val cardFill: Color,
    val cardFillHover: Color,
    val cardBorder: Color,
    val cardSelectedFill: Color,
    val cardSelectedBorder: Color,

    /** Neutral icon button rest / hover. */
    val iconButtonFill: Color,
    val iconButtonFillHover: Color,
    val iconButtonBorder: Color,

    /** Top sheen laid over the primary button gradient. */
    val buttonSheen: Color,
    val buttonBorder: Color,

    /** Shared drop shadow. */
    val shadow: Color,
    /** Modal / full-player backdrop. */
    val scrim: Color
) {
    companion object {
        /** Dark scheme: translucent white fills, black shadows. */
        val Dark = SurfaceTokens(
            menuFill = Color(0xFF1B1D22),
            menuFillHover = Color(0x14FFFFFF),
            menuBorder = Color(0x14FFFFFF),
            menuShadow = Color(0x73000000),
            menuText = Color(0xFFB8BBC4),
            menuTextHover = Color(0xFFFFFFFF),
            menuIcon = Color(0xFF8B8E97),
            menuSelectedFill = Color(0x2EE0B183),
            menuDivider = Color(0x0FFFFFFF),

            pillFill = Color.Transparent,
            pillFillHover = Color(0x0FFFFFFF),
            pillFillPressed = Color(0x1AFFFFFF),
            pillFillSelected = Color(0x1FE0B183),
            pillBorder = Color(0x14FFFFFF),
            pillBorderHover = Color(0x24FFFFFF),
            pillBorderSelected = Color(0x73E0B183),
            pillText = Color(0xFFC4C6CD),
            pillTextHover = Color(0xFFFFFFFF),
            pillTextSelected = Color(0xFFE0B183),
            pillIcon = Color(0xFF9A9DA6),
            pillIconHover = Color(0xFFFFFFFF),

            cardFill = Color(0x0FFFFFFF),
            cardFillHover = Color(0x0AFFFFFF),
            cardBorder = Color(0x1AFFFFFF),
            cardSelectedFill = Color(0x26E0B183),
            cardSelectedBorder = Color(0x52E0B183),

            iconButtonFill = Color(0x14FFFFFF),
            iconButtonFillHover = Color(0x24FFFFFF),
            iconButtonBorder = Color.Transparent,

            buttonSheen = Color(0x1FFFFFFF),
            buttonBorder = Color(0x2EFFFFFF),

            shadow = Color(0x4D000000),
            scrim = Color(0x66000000)
        )

        /** Light scheme: solid grays and hairline borders, per Apple Podcasts web. */
        val Light = SurfaceTokens(
            menuFill = Color(0xFFFFFFFF),
            menuFillHover = AppleLightPalette.HoverOverlay,
            menuBorder = AppleLightPalette.Border,
            menuShadow = AppleLightPalette.Shadow,
            menuText = AppleLightPalette.TextPrimary,
            menuTextHover = AppleLightPalette.TextPrimary,
            menuIcon = AppleLightPalette.TextSecondary,
            menuSelectedFill = AppleLightPalette.AccentSubtle,
            menuDivider = AppleLightPalette.Divider,

            pillFill = Color(0xFFF2F2F4),
            pillFillHover = Color(0xFFE8E8EC),
            pillFillPressed = Color(0xFFDDDDD2),
            pillFillSelected = AppleLightPalette.AccentSubtle,
            pillBorder = Color(0x0F000000),
            pillBorderHover = Color(0x1A000000),
            pillBorderSelected = AppleLightPalette.Accent,
            pillText = AppleLightPalette.TextSecondary,
            pillTextHover = AppleLightPalette.TextPrimary,
            pillTextSelected = AppleLightPalette.Accent,
            pillIcon = AppleLightPalette.TextSecondary,
            pillIconHover = AppleLightPalette.TextPrimary,

            cardFill = AppleLightPalette.Surface,
            cardFillHover = Color(0xFFEFEFF2),
            cardBorder = AppleLightPalette.Border,
            cardSelectedFill = AppleLightPalette.AccentSubtle,
            cardSelectedBorder = AppleLightPalette.Accent,

            iconButtonFill = Color(0x0F000000),
            iconButtonFillHover = Color(0x1A000000),
            iconButtonBorder = Color(0x0F000000),

            buttonSheen = Color(0x1FFFFFFF),
            buttonBorder = Color(0x0F000000),

            shadow = AppleLightPalette.Shadow,
            scrim = AppleLightPalette.Scrim
        )
    }
}
