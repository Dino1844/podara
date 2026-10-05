package app.podara.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Scheme-dependent surface tokens.
 *
 * [DesignTokens] is a plain `object` of `val`s, so its colour entries resolve
 * once at class-init and cannot read the active theme. That works while the
 * whole app is dark, where a "subtle fill" means
 * `Color.White.copy(alpha = 0.08f)` over a dark card. It breaks the moment a
 * light scheme is used: a white wash on a white page is invisible, and a
 * black shadow at dark-theme alphas reads as smudges rather than depth.
 *
 * These tokens are provided through a CompositionLocal. Every colour entry in
 * [DesignTokens] delegates here, so call sites keep reading
 * `DesignTokens.SomeGroup.SomeColor` while resolving correctly under either
 * scheme.
 *
 * Gradients: the dark scheme keeps its glass and gold treatments. The light
 * scheme drops them — Apple Podcasts web uses flat fills with hairline borders,
 * and a gold sheen on white reads as a printing misregistration.
 */
@Immutable
data class SurfaceTokens(
    // ── Menu / dropdown ──
    val menuFill: Color,
    val menuFillHover: Color,
    val menuBorder: Color,
    val menuShadow: Color,
    val menuText: Color,
    val menuTextHover: Color,
    val menuIcon: Color,
    val menuSelectedFill: Color,
    val menuDivider: Color,

    // ── Pill button ──
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
    val pillShadow: Color,

    // ── Card / row surfaces ──
    val cardFill: Color,
    val cardFillHover: Color,
    val cardFillPressed: Color,
    val cardBorder: Color,
    val cardBorderHover: Color,
    val cardSelectedFill: Color,
    val cardSelectedBorder: Color,
    /** Flat card background; replaces the dark scheme's gradient. */
    val cardGradient: Brush,

    // ── Generic icon button ──
    val iconButtonFill: Color,
    val iconButtonFillHover: Color,
    val iconButtonBorder: Color,

    // ── Primary button ──
    val buttonGradient: Brush,
    val buttonSheen: Brush,
    val buttonBorder: Color,
    val buttonText: Color,
    val buttonIcon: Color,
    val buttonShadow: Color,

    // ── Dialog ──
    val dialogFill: Color,
    val dialogBorder: Color,
    val dialogShadow: Color,
    val dialogTitle: Color,
    val dialogBody: Color,
    val dialogEmphasis: Color,
    val dialogIconTint: Color,
    val dialogIconFill: Color,
    val dialogIconBorder: Color,
    val dialogSecondaryFill: Color,
    val dialogSecondaryFillHover: Color,
    val dialogSecondaryFillPressed: Color,
    val dialogSecondaryText: Color,
    val dialogTextActionText: Color,
    val dialogTextActionFillHover: Color,
    val dialogTextActionFillPressed: Color,
    val dialogPrimaryHoverOverlay: Color,
    val dialogPrimaryPressedOverlay: Color,

    // ── Episode / favourite row ──
    val rowTitle: Color,
    val rowPodcastName: Color,
    val rowAuthor: Color,
    val rowMetadata: Color,
    val rowCoverShadow: Color,
    val rowDurationFill: Color,
    val rowDurationText: Color,
    val rowActionIcon: Color,
    val rowActionIconHover: Color,
    val rowQueueIcon: Color,
    val rowQueueIconHover: Color,
    val rowFavoriteActive: Color,
    val rowFavoriteInactive: Color,

    // ── Badge ──
    val badgeFill: Color,
    val badgeBorder: Color,
    val badgeText: Color,

    // ── Navigation active item ──
    val navActiveFill: Color,
    val navActiveBorder: Brush,
    val navActiveTopGlow: Brush,
    val navActiveAccentGlow: Brush,

    // ── Queue panel ──
    val queueActionFill: Color,
    val queueActionFillHover: Color,
    val queueCloseFillHover: Color,

    // ── Hero / featured ──
    val heroWaveform: Color,
    val heroAmbientGlow: List<Color>,
    val heroCornerGlow: List<Color>,

    // ── Destructive ──
    val dangerFill: Color,
    val dangerFillHover: Color,
    val dangerFillPressed: Color,
    val dangerText: Color,
    val dangerShadow: Color,
    val dangerIcon: Color,
    val dangerIconHover: Color,
    val dangerBorderHover: Color,

    // ── Slider ──
    /** Unfilled portion of a seek bar. Apple's unselected grey, not white. */
    val sliderTrackInactive: Color,
    /** Thumb on an unfilled track. */
    val sliderThumb: Color,

    // ── Shared ──
    val shadow: Color,
    val scrim: Color
) {
    companion object {

        /** Dark scheme — the existing glass and gold treatment, unchanged. */
        val Dark = SurfaceTokens(
            menuFill = Color(0xFF1B1D22),
            menuFillHover = Color(0x0FFFFFFF),
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
            pillShadow = Color(0x40000000),

            cardFill = Color(0xFF15181D),
            cardFillHover = Color(0xFF1B1F25),
            cardFillPressed = Color(0xFF22262D),
            cardBorder = Color(0x0FFFFFFF),
            cardBorderHover = Color(0x1FFFFFFF),
            cardSelectedFill = Color(0x1FE0B183),
            cardSelectedBorder = Color(0x73E0B183),
            cardGradient = Brush.linearGradient(
                colors = listOf(Color(0xFF1C1C1E), Color(0xFF15171B)),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            ),

            iconButtonFill = Color(0x14FFFFFF),
            iconButtonFillHover = Color(0x24FFFFFF),
            iconButtonBorder = Color.Transparent,

            buttonGradient = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFFE8BE8D),
                    0.32f to Color(0xFFC89363),
                    0.62f to Color(0xFFAF7951),
                    1.00f to Color(0xFF96623F)
                ),
                startY = 0f,
                endY = 48f
            ),
            buttonSheen = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.03f), Color.Transparent),
                startY = 0f,
                endY = 24f
            ),
            buttonBorder = Color.White.copy(alpha = 0.18f),
            buttonText = Color(0xFFFFFBF5),
            buttonIcon = Color.White,
            buttonShadow = Color(0x3D000000),

            dialogFill = Color(0xFF1B1D22),
            dialogBorder = Color(0x0FFFFFFF),
            dialogShadow = Color(0x8C000000),
            dialogTitle = Color(0xFFF5F6F7),
            dialogBody = Color(0xFFC6C8CE),
            dialogEmphasis = Color(0xFFFFFFFF),
            dialogIconTint = Color(0xFFE0B183),
            dialogIconFill = Color(0x14E0B183),
            dialogIconBorder = Color(0x2EE0B183),
            dialogSecondaryFill = Color(0xFF252932),
            dialogSecondaryFillHover = Color(0xFF2D313B),
            dialogSecondaryFillPressed = Color(0xFF22262E),
            dialogSecondaryText = Color(0xFFECEDEF),
            dialogTextActionText = Color(0xFFE0B183),
            dialogTextActionFillHover = Color(0x0FFFFFFF),
            dialogTextActionFillPressed = Color(0x1AFFFFFF),
            dialogPrimaryHoverOverlay = Color(0x14FFFFFF),
            dialogPrimaryPressedOverlay = Color(0x1F000000),

            rowTitle = Color(0xFFF5F5F7),
            rowPodcastName = Color(0xFFE0B183),
            rowAuthor = Color(0xFF858892),
            rowMetadata = Color(0xFF858892),
            rowCoverShadow = Color(0x59000000),
            rowDurationFill = Color(0xA6000000),
            rowDurationText = Color(0xFFFFFFFF),
            rowActionIcon = Color(0xFFA5A8B0),
            rowActionIconHover = Color(0xFFFFFFFF),
            rowQueueIcon = Color(0xFFA5A8B0),
            rowQueueIconHover = Color(0xFFE0B183),
            rowFavoriteActive = Color(0xFFE0B183),
            rowFavoriteInactive = Color(0xFF858892),

            badgeFill = Color(0x26E0B183),
            badgeBorder = Color(0x40E0B183),
            badgeText = Color(0xFFE0B183),

            navActiveFill = Color(0xFF211F1E),
            navActiveBorder = Brush.linearGradient(
                colors = listOf(Color(0x60D3A05F), Color.White.copy(alpha = 0.06f), Color(0x1AD3A05F)),
                start = Offset(0f, 48f),
                end = Offset(200f, 0f)
            ),
            navActiveTopGlow = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(142f, 0f),
                radius = 90f
            ),
            navActiveAccentGlow = Brush.radialGradient(
                colors = listOf(Color(0x66C7924F), Color(0x22C7924F), Color.Transparent),
                center = Offset(8f, 42f),
                radius = 58f
            ),

            queueActionFill = Color(0x0FFFFFFF),
            queueActionFillHover = Color(0x1AFFFFFF),
            queueCloseFillHover = Color(0x14FFFFFF),

            heroWaveform = Color(0xFFC88A35),
            heroAmbientGlow = listOf(Color(0xE8F4DEAA), Color(0x8CDEB66F), Color.Transparent),
            heroCornerGlow = listOf(
                Color(0xB8F6DCA6), Color(0x8CDEAA62), Color(0x5CC9954C),
                Color(0x2EC9954C), Color(0x10C9954C), Color(0x04C9954C), Color.Transparent
            ),

            dangerFill = Color(0xFFB8454A),
            dangerFillHover = Color(0xFFD0575D),
            dangerFillPressed = Color(0xFF92353A),
            dangerText = Color(0xFFFFFFFF),
            dangerShadow = Color(0x40FF5A5F),
            dangerIcon = Color(0xFFFF5D73),
            dangerIconHover = Color(0xFFFF7E91),
            dangerBorderHover = Color(0x59FF5268),

            sliderTrackInactive = Color(0xFF2C313A),
            sliderThumb = Color(0xFFFFFFFF),

            // Kept as a tinted black rather than Color.Black: on a dark page a
            // subtle wash reads as depth, whereas Color.Black would make
            // Compose derive a much heavier elevation alpha.
            shadow = Color(0x4D000000),
            scrim = Color(0x66000000)
        )

        /**
         * Light scheme — podcasts.apple.com. Flat fills, hairline borders, and
         * the red-pink accent. Gradients are flattened to their dominant stop:
         * the gold sheen has no light-mode equivalent.
         */
        val Light = SurfaceTokens(
            menuFill = Color(0xFFFFFFFF),
            menuFillHover = AppleLightPalette.HoverOverlay,
            menuBorder = AppleLightPalette.Border,
            menuShadow = Color.Black,
            menuText = AppleLightPalette.TextPrimary,
            menuTextHover = AppleLightPalette.TextPrimary,
            menuIcon = AppleLightPalette.TextSecondary,
            menuSelectedFill = AppleLightPalette.AccentSubtle,
            menuDivider = AppleLightPalette.Divider,

            pillFill = Color(0xFFF2F2F4),
            pillFillHover = Color(0xFFE8E8EC),
            pillFillPressed = Color(0xFFDDDDE1),
            pillFillSelected = AppleLightPalette.AccentSubtle,
            pillBorder = Color(0x0F000000),
            pillBorderHover = Color(0x1A000000),
            pillBorderSelected = AppleLightPalette.Accent,
            pillText = AppleLightPalette.TextSecondary,
            pillTextHover = AppleLightPalette.TextPrimary,
            pillTextSelected = AppleLightPalette.Accent,
            pillIcon = AppleLightPalette.TextSecondary,
            pillIconHover = AppleLightPalette.TextPrimary,
            pillShadow = Color.Black,

            cardFill = AppleLightPalette.Surface,
            cardFillHover = Color(0xFFEFEFF2),
            cardFillPressed = Color(0xFFE8E8EC),
            cardBorder = AppleLightPalette.Border,
            cardBorderHover = Color(0x1F000000),
            cardSelectedFill = AppleLightPalette.AccentSubtle,
            cardSelectedBorder = AppleLightPalette.Accent,
            cardGradient = Brush.linearGradient(
                colors = listOf(AppleLightPalette.Surface, AppleLightPalette.Surface),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            ),

            iconButtonFill = Color(0x0F000000),
            iconButtonFillHover = Color(0x1A000000),
            iconButtonBorder = Color(0x0F000000),

            buttonGradient = Brush.verticalGradient(
                colors = listOf(AppleLightPalette.Accent, AppleLightPalette.Accent),
                startY = 0f,
                endY = 48f
            ),
            buttonSheen = Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.12f), Color.Transparent),
                startY = 0f,
                endY = 24f
            ),
            buttonBorder = Color(0x0F000000),
            buttonText = AppleLightPalette.TextOnAccent,
            buttonIcon = Color(0xFFFFFFFF),
            buttonShadow = Color.Black,

            dialogFill = Color(0xFFFFFFFF),
            dialogBorder = AppleLightPalette.Border,
            dialogShadow = Color.Black,
            dialogTitle = AppleLightPalette.TextPrimary,
            dialogBody = AppleLightPalette.TextSecondary,
            dialogEmphasis = AppleLightPalette.TextPrimary,
            dialogIconTint = AppleLightPalette.Accent,
            dialogIconFill = AppleLightPalette.AccentSubtle,
            dialogIconBorder = Color(0x33FA2D48),
            dialogSecondaryFill = Color(0xFFF2F2F4),
            dialogSecondaryFillHover = Color(0xFFE8E8EC),
            dialogSecondaryFillPressed = Color(0xFFDDDDE1),
            dialogSecondaryText = AppleLightPalette.TextPrimary,
            dialogTextActionText = AppleLightPalette.Accent,
            dialogTextActionFillHover = Color(0x0F000000),
            dialogTextActionFillPressed = Color(0x1A000000),
            dialogPrimaryHoverOverlay = Color(0x14FFFFFF),
            dialogPrimaryPressedOverlay = Color(0x1F000000),

            rowTitle = AppleLightPalette.TextPrimary,
            rowPodcastName = AppleLightPalette.Accent,
            rowAuthor = AppleLightPalette.TextSecondary,
            rowMetadata = AppleLightPalette.TextMuted,
            // Colour.Black so Compose derives its alpha from the elevation
            // passed to Modifier.shadow; see the note on `shadow` below.
            rowCoverShadow = Color.Black,
            rowDurationFill = Color(0xA6000000),
            rowDurationText = Color(0xFFFFFFFF),
            rowActionIcon = AppleLightPalette.TextSecondary,
            rowActionIconHover = AppleLightPalette.TextPrimary,
            rowQueueIcon = AppleLightPalette.TextSecondary,
            rowQueueIconHover = AppleLightPalette.Accent,
            rowFavoriteActive = AppleLightPalette.Accent,
            rowFavoriteInactive = AppleLightPalette.TextMuted,

            badgeFill = AppleLightPalette.AccentSubtle,
            badgeBorder = Color(0x33FA2D48),
            badgeText = AppleLightPalette.Accent,

            navActiveFill = AppleLightPalette.AccentSubtle,
            navActiveBorder = Brush.linearGradient(
                colors = listOf(AppleLightPalette.Accent, Color(0x00FA2D48)),
                start = Offset(0f, 0f),
                end = Offset(200f, 0f)
            ),
            navActiveTopGlow = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Transparent),
                center = Offset(142f, 0f),
                radius = 90f
            ),
            navActiveAccentGlow = Brush.radialGradient(
                colors = listOf(Color(0x1FFA2D48), Color.Transparent),
                center = Offset(8f, 42f),
                radius = 58f
            ),

            queueActionFill = Color(0x0F000000),
            queueActionFillHover = Color(0x1A000000),
            queueCloseFillHover = Color(0x0F000000),

            heroWaveform = AppleLightPalette.Accent,
            heroAmbientGlow = listOf(Color(0x00FA2D48), Color.Transparent),
            heroCornerGlow = listOf(Color(0x00FA2D48), Color.Transparent),

            dangerFill = AppleLightPalette.Danger,
            dangerFillHover = Color(0xFFB4000F),
            dangerFillPressed = Color(0xFF8E000C),
            dangerText = Color(0xFFFFFFFF),
            dangerShadow = Color(0x1FD70015),
            dangerIcon = AppleLightPalette.Danger,
            dangerIconHover = Color(0xFF9A000C),
            dangerBorderHover = Color(0x33D70015),

            sliderTrackInactive = Color(0x1F767680),
            sliderThumb = AppleLightPalette.Accent,

            // A tinted black at low alpha flattens every elevation into the same
            // uniform grey ring: Compose replaces the elevation-derived alpha
            // whenever the colour is not exactly Color.Black. These are
            // therefore full black so the alpha curve is preserved.
            shadow = Color.Black,
            scrim = AppleLightPalette.Scrim
        )
    }
}
