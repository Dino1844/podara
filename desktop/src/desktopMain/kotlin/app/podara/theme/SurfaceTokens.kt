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
    // ── Ambient shell ──
    /**
     * The window background everything else floats on.
     *
     * A vertical wash, tinted only at the top: `#EDF0E1` under the title bar,
     * gone by the middle of the window. The earlier attempt at this was a
     * uniform mid-grey-green panel, and it failed twice over — the tint read as
     * dirt rather than light once it covered a whole surface, and the white
     * sheet on top of it separated by luminance instead of by an edge, which is
     * a much louder depth cue than a 1dp hairline.
     *
     * So the shell stays within about four percent of the sheet's own lightness
     * and the border does the work.
     */
    val shellGradient: Brush,
    /** The floating content sheet. Opaque: screens paint over it. */
    val sheetFill: Color,
    val sheetBorder: Color,
    val sheetShadow: Color,
    /**
     * Sidebar backdrop. Transparent, because the sidebar is no longer a panel —
     * it is the part of the shell that has no sheet over it. It used to be a
     * filled gray column, which put a second surface in the same visual layer
     * as the content and made the window read as two panes of glass.
     */
    val sidebarFill: Color,
    /**
     * Hover wash on a sidebar row.
     *
     * Separate from [pillFillSelected], which this used: that is an accent
     * tint, and hovering a nav item is not a selection. On the shell a hover
     * has to lighten toward white rather than darken toward grey.
     */
    val navHoverFill: Color,

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
    /**
     * Fill of the primary action button.
     *
     * A flat [Color], not a [Brush]. This was a four-stop vertical gradient
     * (`#E8BE8D → #C89363 → #AF7951 → #96623F`) with a separate white "sheen"
     * brush laid over the top half, a 1dp white border and an elevation
     * shadow — a literal metal sphere, and the same recipe was reused for the
     * 56dp play button, the seek-bar thumb and the download button. On a
     * near-black page a lit dome of copper reads as a plastic token from a
     * different decade; it also made the play button heavier than the play
     * *icon* inside it, which is backwards.
     *
     * One flat fill lets the icon carry the emphasis, which is what a control
     * should do.
     */
    val buttonFill: Color,
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
    /**
     * Hairline around the active nav row.
     *
     * A brush rather than a colour only because the call site already draws it
     * with `Modifier.border(brush)`. It used to be a three-stop gradient plus
     * two radial glows stacked on top of each other — a white light from the
     * top-left, a gold light from the bottom-left, and a gradient border along
     * the right. Three light sources on one 48dp row read as a smear, not as a
     * selection, and the row was the only item in the sidebar that had any of
     * it. Selection is now one flat tint plus one hairline.
     */
    val navActiveBorder: Brush,
    /**
     * Shadow for the active nav item.
     *
     * Separate from [shadow] on purpose. The shared token is full black in the
     * light scheme so that `Modifier.shadow` keeps its elevation-derived alpha
     * curve, which is right for cards, dialogs and menus that should read as
     * genuinely elevated. It is wrong for a 5 dp nav row: on a white page that
     * renders as a hard black halo around the active item.
     */
    val navActiveShadow: Color,

    // ── Queue panel ──
    val queueActionFill: Color,
    val queueActionFillHover: Color,
    val queueCloseFillHover: Color,

    // ── Hero / featured ──
    /**
     * Wash over the featured card's own fill.
     *
     * Two colours, not three: the card used to composite a gold radial glow
     * from the left, a second gold radial glow in the corner, *and* a decorative
     * waveform drawn from a hardcoded 61-sample array — none of which meant
     * anything. The waveform did not move with playback and did not represent
     * volume; it was there to look busy. The card now carries one soft wash and
     * lets the artwork be the loudest thing in it.
     */
    val heroWash: List<Color>,

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
    /**
     * Ring around the seek thumb.
     *
     * The full player drew its thumb as a 15dp disc with a 2dp accent border
     * and a 2dp white inset — a donut. At track height it read as a shrunken
     * copy of the play button. The thumb is now one solid dot; this is the
     * hairline that keeps it visible where it sits on the filled part of the
     * track in either scheme.
     */
    val sliderThumbRing: Color,

    // ── Shared ──
    val shadow: Color,
    val scrim: Color
) {
    companion object {

        /** Dark scheme — the existing glass and gold treatment, unchanged. */
        val Dark = SurfaceTokens(
            // Dark keeps the shell barely there: a few points of lift at the top
            // and the page colour by the middle. A tinted shell like the light
            // scheme's would invert the depth cue — a lighter backdrop makes the
            // darker sheet read as a hole rather than as a surface.
            shellGradient = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF0D1015),
                    0.45f to Color(0xFF0A0C10),
                    1f to PodiumBackground
                )
            ),
            // Equal to the page colour the screens paint themselves, so the sheet
            // is one value and not two that happen to look alike.
            sheetFill = PodiumBackground,
            sheetBorder = Color(0x14FFFFFF),
            sheetShadow = Color(0x59000000),
            sidebarFill = Color.Transparent,
            navHoverFill = Color(0x0FFFFFFF),

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
            // Same reasoning as the light scheme: a full-strength accent frame
            // around a whole row competes with the play button.
            cardSelectedBorder = Color(0x59E0B183),
            cardGradient = Brush.linearGradient(
                colors = listOf(Color(0xFF1C1C1E), Color(0xFF15171B)),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            ),

            iconButtonFill = Color(0x14FFFFFF),
            iconButtonFillHover = Color(0x24FFFFFF),
            iconButtonBorder = Color.Transparent,

            // Flat, not a dome. See the note on the field: the four-stop
            // vertical gradient plus a white sheen was the "metal sphere" that
            // appeared on the play button, the thumb and every primary button.
            // #C89B6A is PodiumAccentPressed — one step under the accent, so
            // the button is recognisably the accent without being lit from above.
            buttonFill = Color(0xFFC89B6A),
            buttonBorder = Color.Transparent,
            buttonText = Color(0xFF1A1208),
            buttonIcon = Color(0xFF1A1208),
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

            // One flat tint and one hairline. The three stacked glows this
            // replaces are explained on the `navActiveBorder` field.
            navActiveFill = Color(0x14E0B183),
            navActiveBorder = Brush.linearGradient(
                colors = listOf(Color(0x33E0B183), Color(0x33E0B183)),
                start = Offset(0f, 0f),
                end = Offset(1f, 0f)
            ),
            // On a dark page a soft black wash reads as depth.
            navActiveShadow = Color(0x24000000),

            queueActionFill = Color(0x0FFFFFFF),
            queueActionFillHover = Color(0x1AFFFFFF),
            queueCloseFillHover = Color(0x14FFFFFF),

            // A single warm wash, low enough to sit behind the artwork rather
            // than compete with it.
            heroWash = listOf(Color(0x24C88A35), Color(0x08C88A35), Color.Transparent),

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
            sliderThumbRing = Color(0xFF0F1116),

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
            // The tint is a warm green-grey at the very top and nothing below
            // the middle of the window. Everything past 0.45 is within four
            // percent of the sheet's own lightness, so the sheet's edge — not
            // its brightness — is what separates it from the shell.
            shellGradient = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(0xFFEDF0E1),
                    0.45f to Color(0xFFF3F4F0),
                    1f to Color(0xFFF6F6F5)
                )
            ),
            sheetFill = AppleLightPalette.Background,
            sheetBorder = Color(0x12000000),
            sheetShadow = Color(0x14000000),
            sidebarFill = Color.Transparent,
            // White, not the black wash every other light hover uses: on the
            // shell there is nothing to darken.
            navHoverFill = Color(0x8CFFFFFF),

            menuFill = Color(0xFFFFFFFF),
            menuFillHover = AppleLightPalette.HoverOverlay,
            menuBorder = AppleLightPalette.Border,
            menuShadow = Color(0x1F000000),
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
            // One step under pillText, which is the icon rule stated where it
            // belongs. The dark scheme already did this (pillIcon is a step below
            // pillText there); the light scheme had them on the same value, so
            // every default sort/filter pill drew its glyph as heavy as its label.
            pillIcon = AppleLightPalette.TextMuted,
            pillIconHover = AppleLightPalette.TextPrimary,
            pillShadow = Color(0x14000000),

            cardFill = AppleLightPalette.Surface,
            cardFillHover = Color(0xFFEFEFF2),
            cardFillPressed = Color(0xFFE8E8EC),
            cardBorder = AppleLightPalette.Border,
            cardBorderHover = Color(0x1F000000),
            cardSelectedFill = AppleLightPalette.AccentSubtle,
            // Was the full accent at 1dp. A red rectangle around a whole list
            // row reads as a validation error, not as "this is playing"; the
            // accent belongs on the small indicators, not on a 112dp frame.
            cardSelectedBorder = Color(0x4DFA2D48),
            cardGradient = Brush.linearGradient(
                colors = listOf(AppleLightPalette.Surface, AppleLightPalette.Surface),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            ),

            iconButtonFill = Color(0x0F000000),
            iconButtonFillHover = Color(0x1A000000),
            iconButtonBorder = Color(0x0F000000),

            // Ink, not the accent. The accent-filled button made every primary
            // action the reddest thing on the page, which is what drained the
            // red of meaning: play, follow, confirm and delete all wore it.
            // Ink keeps the button primary by weight rather than by colour, and
            // leaves the accent for the two things that still need to say
            // *this is playing* and *you saved this*.
            buttonFill = AppleLightPalette.TextPrimary,
            buttonBorder = Color.Transparent,
            buttonText = Color(0xFFFFFFFF),
            buttonIcon = Color(0xFFFFFFFF),
            buttonShadow = Color(0x1F000000),

            dialogFill = Color(0xFFFFFFFF),
            dialogBorder = AppleLightPalette.Border,
            dialogShadow = Color(0x33000000),
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
            // Not the accent. The podcast name under a row title used to be
            // drawn in #FA2D48 — the same red as the play button, the seek
            // thumb, the selected nav item and the delete button. Four
            // different meanings on one colour is how a scheme stops saying
            // anything; the row's second line is metadata, so it is grey.
            rowPodcastName = AppleLightPalette.TextSecondary,
            rowAuthor = AppleLightPalette.TextSecondary,
            rowMetadata = AppleLightPalette.TextMuted,
            // Colour.Black so Compose derives its alpha from the elevation
            // passed to Modifier.shadow; see the note on `shadow` below.
            rowCoverShadow = Color(0x1F000000),
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

            // A white pill lifted off the shell, not a tint. The accent wash
            // this replaces put the same red behind the selected nav item that
            // the play button wore, so the sidebar's selection was one of four
            // things claiming to be the app's one meaning.
            navActiveFill = Color(0xFFFFFFFF),
            navActiveBorder = Brush.linearGradient(
                colors = listOf(Color(0x0E000000), Color(0x0E000000)),
                start = Offset(0f, 0f),
                end = Offset(1f, 0f)
            ),
            // Needed now precisely because the fill stopped being a colour: on a
            // near-white shell a white pill with only a hairline reads as flat.
            // At 5dp and 8% this is a lift, not the dark ring the old comment
            // here was guarding against — that one was full-elevation black.
            navActiveShadow = Color(0x14000000),

            queueActionFill = Color(0x0F000000),
            queueActionFillHover = Color(0x1A000000),
            queueCloseFillHover = Color(0x0F000000),

            // The light scheme had the wash at zero alpha, which was honest —
            // there is nothing to tint on white. Kept explicit rather than
            // deleted so the two schemes stay structurally comparable.
            heroWash = listOf(Color(0x00FA2D48), Color.Transparent),

            dangerFill = AppleLightPalette.Danger,
            dangerFillHover = Color(0xFFB4000F),
            dangerFillPressed = Color(0xFF8E000C),
            dangerText = Color(0xFFFFFFFF),
            dangerShadow = Color(0x1FD70015),
            dangerIcon = AppleLightPalette.Danger,
            dangerIconHover = Color(0xFF9A000C),
            dangerBorderHover = Color(0x33D70015),

            sliderTrackInactive = Color(0xFFD8D8DE),
            // White with a ring, not a solid accent disc: at 12dp the accent
            // fill disappeared against the filled part of the track, so the
            // thumb only showed where it crossed the unfilled half.
            sliderThumb = Color(0xFFFFFFFF),
            // Neutral, not accent. An accent ring around a white core is a
            // donut at 14dp, which is exactly the shape this token replaced.
            sliderThumbRing = Color(0x33000000),

            // Explicit low-alpha black rather than Color.Black.
            //
            // This reverses an earlier change. Compose does substitute the
            // colour's own alpha for the elevation-derived one whenever the
            // shadow colour is not exactly Color.Black, so passing Color.Black
            // gives a *heavier* shadow — which is right for a dialog and wrong
            // for anything small. At 5-20 dp against a white page a
            // full-elevation black renders as a hard dark ring, which is what
            // appeared around the active sidebar item.
            //
            // Taking the alpha directly is the more predictable option for a
            // design system: every shadow is exactly the value written here.
            // Hairline borders carry the depth cues that elevation would
            // otherwise provide, which is also how podcasts.apple.com does it.
            shadow = AppleLightPalette.Shadow,
            scrim = AppleLightPalette.Scrim
        )
    }
}
