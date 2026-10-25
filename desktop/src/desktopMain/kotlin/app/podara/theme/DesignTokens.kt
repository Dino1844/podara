package app.podara.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Layout, sizing, and motion tokens, plus every colour entry.
 *
 * Colour entries are `@Composable` getters that delegate to [SurfaceTokens].
 * That keeps the call sites reading `DesignTokens.Group.SomeColor` while
 * resolving correctly for the active theme — which a plain `val` could not do,
 * since object initializers run before any CompositionLocal is available.
 */
object DesignTokens {

    private val surfaces: SurfaceTokens
        @Composable get() = PodaraTheme.surfaces

    private val ink: PodaraColors
        @Composable get() = PodaraTheme.colors

    /**
     * The type scale.
     *
     * Every font size in the app resolves to one of these six steps. Before this
     * existed there were seventeen distinct `.sp` values in use — 11, 12, 13, 14,
     * 15, 16, 17, 18, 19, 20, 21, 22, 24, 26, 30, 32 — with the bulk of them
     * between 11 and 14. That is the visual equivalent of a ruler with no
     * markings: everything sat in one narrow middle band, so nothing looked more
     * important than anything else and the eye had no entry point.
     *
     * The steps are roughly a 1.25x progression from [Body] up, with a wider jump
     * at the top so a page title reads as a different order of thing rather than
     * just a bigger row. [Caption] is the one compression: metadata below body
     * size has to stay legible at 11sp, and a true 1.25 step down from 13 would
     * land at 10.4.
     */
    object TypeScale {
        /** Disabled hints, keyboard shortcuts, badge counters. */
        val Micro = 11.sp

        /** Metadata, timestamps, author lines, secondary labels. */
        val Caption = 12.sp

        /** Body copy: descriptions, list secondary lines, control labels. */
        val Body = 13.sp

        /** Emphasised body: row titles, nav labels, section subtitles. */
        val BodyStrong = 15.sp

        /** Card titles, podcast names, panel headers. */
        val Heading = 20.sp

        /** Page titles. The only size allowed to dominate a screen. */
        val Display = 32.sp

        /**
         * Line heights, separate from the size steps.
         *
         * These did not exist: `lineHeight` was being fed *size* tokens, so the
         * show-notes body ran 13sp text on a 20sp leading (1.54x, which crowds
         * CJK glyphs that already fill their em box) while the 32sp page title
         * got 32sp of leading (1.0x, which clips descenders and makes a two-line
         * Chinese title collide with itself).
         *
         * CJK needs more leading than Latin at the same size because the glyphs
         * occupy the full em box, so [BodyText] sits at 1.75x rather than the
         * 1.5x a Latin-only design would use.
         */
        object LineHeight {
            /** Multi-line prose: show notes, descriptions. 13sp -> 22sp. */
            val BodyText = 22.sp
            /** Single-line body rows: keeps row heights stable. 13sp -> 18sp. */
            val BodyRow = 18.sp
            /** Row titles. 15sp -> 21sp. */
            val Title = 21.sp
            /** Section and card headings. 20sp -> 27sp. */
            val Heading = 27.sp
            /** Page titles. 32sp -> 40sp. */
            val Display = 40.sp
        }
    }

    /**
     * The spacing scale.
     *
     * Kept to five steps so gaps that are meant to look the same actually are.
     * The scale was already correct; the problem was adoption — 34 places still
     * passed raw `.dp` values, which is how 65 distinct dimensions accumulated.
     */
    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 32.dp
    }

    /**
     * The corner radius scale.
     *
     * Three steps instead of the nine that were in use (5, 6, 8, 9, 10, 11, 12,
     * 13, 14, 18). Mismatched radii are the quietest signal of an unsystematic
     * UI: the eye cannot name what is wrong, it just reads as "not quite right".
     *
     * [Small] for chips and inline controls, [Medium] for cards and rows,
     * [Large] for panels and dialogs. Anything visibly rounder than that is a
     * deliberate circle and should use `CircleShape`.
     */
    object Radius {
        val Small = 6.dp
        val Medium = 10.dp
        val Large = 16.dp
    }

    /**
     * The elevation scale.
     *
     * Three heights, the same discipline [Radius] applies. Elevation is the
     * strongest depth cue on a dark surface, and the values in use were spread
     * across 4, 5, 8, 10, 12, 18 and 20dp with no rule tying a height to what it
     * meant — so a hover pill and a modal panel could sit at the same depth, and
     * the eye reads that as sloppiness without being able to name it.
     *
     * [Resting] for cards and rows on the page, [Raised] for hover and floating
     * controls, [Overlay] for panels and dialogs.
     *
     * This is a *scale*, not a decoration budget: a container may legitimately
     * pair a border with a shadow to read as glass. What it may not do is pick a
     * height that means nothing.
     */
    object Elevation {
        val Resting = 5.dp
        val Raised = 10.dp
        val Overlay = 20.dp
    }

    // ── Common Border ──
    object Border {
        val Width = 1.dp
        val SecondaryColor: Color @Composable get() = surfaces.menuBorder
    }

    /**
     * Keyboard focus ring.
     *
     * Drawn by `Modifier.clickableWithFocusIndicator` rather than by Compose's
     * default indicator, because these controls pass `indication = null` to
     * suppress the material ripple — which also suppressed every focus cue.
     *
     * 2.dp rather than the 1.dp used for resting borders: the ring has to be
     * distinguishable from the border the control already draws for itself, and
     * at 1.dp the two read as the same line.
     */
    object FocusIndicator {
        val Width = 2.dp
        val Radius = DesignTokens.Radius.Small
    }

    // ── Glass surfaces ──
    // Colour entries delegate to SurfaceTokens so they resolve per scheme.
    // Geometry stays here as plain vals.
    object Glass {
        val CompactRadius = DesignTokens.Radius.Medium
        val CompactBorderWidth = 0.6.dp
        val CompactShadowElevation = DesignTokens.Elevation.Raised

        /**
         * Flattened to [SurfaceTokens.cardGradient]. Was a fixed
         * `Color.White.copy(alpha = 0.085f)` wash, which composites to exactly
         * the page colour under the light scheme and left the panel with no
         * fill at all — only its border and shadow.
         */
        val CompactGradient: Brush @Composable get() = surfaces.cardGradient

        val CompactBorderColor: Color @Composable get() = surfaces.cardBorder
        val CompactShadowColor: Color @Composable get() = surfaces.shadow
        val HoverOverlayColor: Color @Composable get() = surfaces.cardFillHover
        val SelectedOverlayColor: Color @Composable get() = surfaces.cardSelectedFill
        val SelectedBorderColor: Color @Composable get() = surfaces.cardSelectedBorder
    }

    // ── Toolbar icon buttons ──
    object ToolbarButton {
        val Size = 32.dp
        val Radius = DesignTokens.Radius.Small
        val IconSize = 16.dp
        val TextSize = DesignTokens.TypeScale.Body
        val StrongTextSize = DesignTokens.TypeScale.BodyStrong
        val Gap = 6.dp
        val BorderWidth = 0.6.dp
        val BackgroundColor = Color.Transparent
        val PillHeight = 37.dp
        /**
         * The player toolbar's compact pills — the speed readout, the audio
         * enhancement toggles, the retry action. One named height for the
         * family, so a fourth 30dp pill cannot reappear as an inline value.
         */
        val CompactPillHeight = 30.dp
        val PillRadius = DesignTokens.Radius.Medium
        val PillPaddingHorizontal = 13.dp
        val PillIconTextGap = 7.dp
        val PillIconSize = 17.dp
        val PillTrailingIconSize = 15.dp
        val PillTextSize = DesignTokens.TypeScale.Body
        val PillLineHeight = DesignTokens.TypeScale.Heading
        val PillTextWeight = FontWeight(450)
        val PillActiveTextWeight = FontWeight.Medium
        val ManageMinWidth = 78.dp
        val SortMinWidth = 139.dp
        val PillSelectedIconColor: Color @Composable get() = surfaces.pillTextSelected
        val PillHoverShadowElevation = DesignTokens.Elevation.Resting

        val BorderColor: Color @Composable get() = surfaces.pillBorder
        val HoverBackgroundColor: Color @Composable get() = surfaces.pillFillHover
        val DangerHoverBackgroundColor: Color @Composable get() = surfaces.dangerIconHover.copy(alpha = 0.15f)
        val PillDefaultBackgroundColor: Color @Composable get() = surfaces.pillFill
        val PillSortBackgroundColor: Color @Composable get() = surfaces.pillFill
        val PillHoverBackgroundColor: Color @Composable get() = surfaces.pillFillHover
        val PillPressedBackgroundColor: Color @Composable get() = surfaces.pillFillPressed
        val PillSelectedBackgroundColor: Color @Composable get() = surfaces.pillFillSelected
        val PillDefaultBorderColor: Color @Composable get() = surfaces.pillBorder
        val PillSortBorderColor: Color @Composable get() = surfaces.pillBorder
        val PillHoverBorderColor: Color @Composable get() = surfaces.pillBorderHover
        val PillSelectedBorderColor: Color @Composable get() = surfaces.pillBorderSelected
        val PillTextColor: Color @Composable get() = surfaces.pillText
        val PillHoverTextColor: Color @Composable get() = surfaces.pillTextHover
        val PillSelectedTextColor: Color @Composable get() = surfaces.pillTextSelected
        val PillIconColor: Color @Composable get() = surfaces.pillIcon
        val PillHoverIconColor: Color @Composable get() = surfaces.pillIconHover
        val PillHoverShadowColor: Color @Composable get() = surfaces.pillShadow
    }

    // ── Subscription selection toolbar ──
    object SubscriptionSelectionToolbar {
        val DeleteButtonHoverBackgroundColor: Color @Composable get() = surfaces.dangerIcon.copy(alpha = 0.10f)
        val DeleteButtonPressedBackgroundColor: Color @Composable get() = surfaces.dangerIcon.copy(alpha = 0.18f)
        val DeleteButtonHoverBorderColor: Color @Composable get() = surfaces.dangerBorderHover
        val DeleteIconColor: Color @Composable get() = surfaces.dangerIcon
        val DeleteIconHoverColor: Color @Composable get() = surfaces.dangerIconHover
    }

    // ── Dropdown menus ──
    object DropdownMenu {
        val Width = 192.dp
        val Radius = DesignTokens.Radius.Large
        val Padding = 7.dp
        val ItemHeight = 38.dp
        val ItemRadius = DesignTokens.Radius.Medium
        val ItemPaddingHorizontal = 10.dp
        val ItemIconTextGap = 10.dp
        val ItemIconSize = 16.dp
        val OffsetY = 5.dp
        val ShadowElevation = DesignTokens.Elevation.Raised
        val LabelSize = ToolbarButton.PillTextSize
        val LabelLineHeight = DesignTokens.TypeScale.BodyStrong
        val LabelWeight = FontWeight(450)
        val SelectedLabelWeight = FontWeight.Medium
        val DividerHeight = 1.dp
        val DividerMarginVertical = 8.dp
        val EnterMs = 150

        val ShadowColor: Color @Composable get() = surfaces.menuShadow
        val BackgroundColor: Color @Composable get() = surfaces.menuFill
        val BorderColor: Color @Composable get() = surfaces.menuBorder
        val HoverBackgroundColor: Color @Composable get() = surfaces.menuFillHover
        val SelectedBackgroundColor: Color @Composable get() = surfaces.menuSelectedFill
        val TextColor: Color @Composable get() = surfaces.menuText
        val HoverTextColor: Color @Composable get() = surfaces.menuTextHover
        val SelectedTextColor: Color @Composable get() = surfaces.pillTextSelected
        val IconColor: Color @Composable get() = surfaces.menuIcon
        val DividerColor: Color @Composable get() = surfaces.menuDivider
    }

    // ── Dialogs ──
    object Dialog {
        // The existing Material TextButton label is 14sp; supplied dialog tokens use 17px.
        private const val SourceActionLabelSize = 17f
        private val Scale = Button.TextSize.value / SourceActionLabelSize

        object Container {
            val CompactWidth = (320f * Scale).dp
            val StandardWidth = (400f * Scale).dp
            val WideWidth = (560f * Scale).dp
            val StandardMinHeight = (360f * Scale).dp
            // Was `(28f * Scale).dp` = 21.4dp, a corner that exists on no scale
            // and sat 5dp rounder than the largest step. A modal is the largest
            // surface in the app, so it takes the largest step.
            val Radius = DesignTokens.Radius.Large
            val BorderWidth = (1f * Scale).dp
            val ShadowElevation = (24f * Scale).dp
            val PaddingTop = (32f * Scale).dp
            val Background: Color @Composable get() = surfaces.dialogFill
            val BorderColor: Color @Composable get() = surfaces.dialogBorder
            val ShadowColor: Color @Composable get() = surfaces.dialogShadow
            val PaddingHorizontal = (36f * Scale).dp
            val PaddingBottom = (32f * Scale).dp
            val ContentGap = (24f * Scale).dp
            val ScrollableContentMaxHeight = 320.dp
        }

        object Typography {
            val TitleSize = (24f * Scale).sp
            val TitleLineHeight = (32f * Scale).sp
            val TitleWeight = FontWeight.Bold
            val BodySize = (17f * Scale).sp
            val BodyLineHeight = (28f * Scale).sp
            val EmphasisWeight = FontWeight.SemiBold
            val TitleColor: Color @Composable get() = surfaces.dialogTitle
            val BodyColor: Color @Composable get() = surfaces.dialogBody
            val EmphasisColor: Color @Composable get() = surfaces.dialogEmphasis
        }

        object Icon {
            val ContainerSize = (64f * Scale).dp
            /** A circle: the icon container is square, so half its width is the
             *  only radius that keeps it round. */
            val ContainerRadius = (32f * Scale).dp
            val ContainerBorderWidth = (1f * Scale).dp
            val Size = (30f * Scale).dp
            val ContainerBackground: Color @Composable get() = surfaces.dialogIconFill
            val ContainerBorderColor: Color @Composable get() = surfaces.dialogIconBorder
            val Color: Color @Composable get() = surfaces.dialogIconTint
        }

        object Action {
            val Gap = (14f * Scale).dp
            val MarginTop = (8f * Scale).dp
            val Width = (150f * Scale).dp
            val Height = (48f * Scale).dp
            // 10.7dp against a 16dp container: an action button is a control, so
            // it sits on the middle step, not on one of its own.
            val Radius = DesignTokens.Radius.Medium
            val LabelSize = (17f * Scale).sp
            val LabelWeight = FontWeight.Normal
            const val DisabledContentAlpha = 0.45f
            const val DisabledBackgroundAlpha = 0.40f

            object Primary {
                val HoverOverlay: Color @Composable get() = surfaces.dialogPrimaryHoverOverlay
                val PressedOverlay: Color @Composable get() = surfaces.dialogPrimaryPressedOverlay
            }

            object Secondary {
                val Background: Color @Composable get() = surfaces.dialogSecondaryFill
                val HoverBackground: Color @Composable get() = surfaces.dialogSecondaryFillHover
                val PressedBackground: Color @Composable get() = surfaces.dialogSecondaryFillPressed
                val TextColor: Color @Composable get() = surfaces.dialogSecondaryText
            }

            object Destructive {
                val Background: Color @Composable get() = surfaces.dangerFill
                val HoverBackground: Color @Composable get() = surfaces.dangerFillHover
                val PressedBackground: Color @Composable get() = surfaces.dangerFillPressed
                val TextColor: Color @Composable get() = surfaces.dangerText
                val ShadowColor: Color @Composable get() = surfaces.dangerShadow
            }

            object Text {
                val TextColor: Color @Composable get() = surfaces.dialogTextActionText
                val HoverBackground: Color @Composable get() = surfaces.dialogTextActionFillHover
                val PressedBackground: Color @Composable get() = surfaces.dialogTextActionFillPressed
            }
        }

        object Motion {
            val EnterDurationMs = 180
            val EnterStartScale = 0.96f
            val EnterStartTranslationY = (10f * Scale).dp
        }
    }

    // ── Status badges ──
    object Badge {
        val Radius = DesignTokens.Radius.Medium
        val PaddingHorizontal = 6.dp
        val PaddingVertical = 2.dp
        val TextSize = DesignTokens.TypeScale.Micro
        val AccentBackgroundColor: Color @Composable get() = surfaces.badgeFill
        val AccentBorderColor: Color @Composable get() = surfaces.badgeBorder
        val AccentTextColor: Color @Composable get() = surfaces.badgeText
    }

    // ── Empty states ──
    object EmptyState {
        val PanelWidth = 400.dp
        val PanelRadius = DesignTokens.Radius.Large
        val PanelPadding = 24.dp
        val IconSize = 56.dp
        val TitleSize = DesignTokens.TypeScale.Heading
        val SubtitleSize = DesignTokens.TypeScale.Body
        val Gap = 12.dp
    }

    // ── Navigation active glass ──
    //
    // "Glass" is now a misnomer kept for the call sites' sake: the active row is
    // a flat tint plus a hairline. It used to stack a white radial glow, a gold
    // radial glow and a gradient border, from three different origins, on one
    // 48dp row — see `navActiveBorder` in SurfaceTokens.
    object Navigation {
        object ActiveGlass {
            val Radius = DesignTokens.Radius.Medium
            val BorderWidth = 0.6.dp
            val ShadowElevation = DesignTokens.Elevation.Resting
            val InnerPaddingHorizontal = 14.dp

            val BaseColor: Color @Composable get() = surfaces.navActiveFill
            val Border: Brush @Composable get() = surfaces.navActiveBorder
            val ShadowColor: Color @Composable get() = surfaces.navActiveShadow
        }
    }

    // ── Hero surfaces ──
    object Hero {
        val WashCenterFactor = Offset(0f, 0.5f)
        val WashRadiusFactor = 1.15f

        val WashColors: List<Color> @Composable get() = surfaces.heroWash
    }

    // ── Button: primary ──
    object Button {
        val Height = 40.dp
        val Radius = DesignTokens.Radius.Medium
        /**
         * 18dp, down from 20dp.
         *
         * At 20dp the play triangle was the same optical weight as the 13sp
         * label beside it, so the two read as one blob. 18dp is clearly an icon
         * and leaves the label as the thing you read.
         */
        val IconSize = 18.dp
        val TextSize = DesignTokens.TypeScale.Body
        val PaddingHorizontal = 16.dp
        val ShadowElevation = DesignTokens.Elevation.Raised

        val Fill: Color @Composable get() = surfaces.buttonFill
        val BorderColor: Color @Composable get() = surfaces.buttonBorder
        val TextColor: Color @Composable get() = surfaces.buttonText
        val IconColor: Color @Composable get() = surfaces.buttonIcon
        val ShadowColor: Color @Composable get() = surfaces.buttonShadow
    }

    // ── Button: icon (circular secondary) ──
    object IconButton {
        val Size = 40.dp
        val IconSize = 20.dp
    }

    object EpisodeActionButton {
        val Size = 36.dp
        val Radius = DesignTokens.Radius.Large
        val IconSize = 20.dp
        val FlyawayDurationMs = 600
        val FlyawayOffsetX = 25.dp
        val FlyawayOffsetY = 60.dp
    }

    // ── MiniPlayer ──
    object MiniPlayer {
        val Height = 88.dp
        val PaddingHorizontal = 20.dp
        object Speed { val Size = 36.dp; val TextSize = DesignTokens.TypeScale.Body }
        object RewindForward { val Size = 40.dp; val IconSize = 22.dp }
        object PlayPause {
            val Size = 56.dp
            /**
             * 26dp inside the 56dp disc — 46%, matching the seek icons either
             * side of it. Was 40dp (71%), which made the main control the
             * densest object in a row of otherwise light controls, and put the
             * triangle's optical centre visibly off the disc's.
             */
            val IconSize = 26.dp
        }
        object Volume { val IconSize = 22.dp }
        object QueueFullscreen { val Size = 32.dp; val IconSize = 20.dp }
        object Time { val TextSize = DesignTokens.TypeScale.Caption }
        object Slider { val Height = 20.dp }
    }

    // ── Ambient shell ──
    /**
     * The gutter between the window edge and the content sheet.
     *
     * Deliberately smaller than [Spacing.sm]: this is the gap that lets light
     * through, not a spacing between two things the user reads. At 16dp it stops
     * being a shell and starts being a frame around a picture.
     */
    object Shell {
        val Gutter = 10.dp
        val Radius = DesignTokens.Radius.Large
        val BorderWidth = 1.dp
        val ShadowElevation = DesignTokens.Elevation.Resting

        /**
         * Where the reading column stops growing.
         *
         * A 1600dp window does not make a subscription list easier to scan; it
         * puts the cover and its play button a metre apart. Past this the sheet
         * keeps filling and the content centres inside it.
         */
        val ContentMaxWidth = 860.dp

        /**
         * Where prose stops growing (B4).
         *
         * [ContentMaxWidth] is a *scanning* width: rows of covers stay
         * legible at 860dp because the eye jumps between anchors. Running
         * prose has no anchors — a show-notes line stretched across 860dp
         * ran 60+ CJK glyphs per line, and Chinese is comfortable at 35–45.
         * Roughly 40em at Body size.
         */
        val NotesMaxWidth = 560.dp
    }

    // ── Sidebar ──
    object Sidebar {
        val Width = 240.dp
        val PaddingVertical = 20.dp
        val PaddingHorizontal = 20.dp
        val NavItemHeight = 48.dp
        val NavItemPadding = 12.dp
        val NavIconSize = 20.dp
        val NavTextSize = DesignTokens.TypeScale.Body
        val NavSpacing = 10.dp
        val LogoSize = 40.dp
        val LogoRadius = DesignTokens.Radius.Medium
        val LogoIconSize = 18.dp
        val LogoTextSize = DesignTokens.TypeScale.Heading
        val DividerPadding = 20.dp

        /**
         * Section labels above the nav groups (E2). Micro, because a group
         * label is the quietest text on the sidebar — but rendered in
         * `textSecondary`, not `textFaint`: ContrastFloorTest pins that
         * nothing below Secondary may carry text on the shell the sidebar
         * sits on.
         */
        val GroupLabelSize = DesignTokens.TypeScale.Micro
        val GroupLabelTracking = 1.2.sp
    }

    // ── Search Bar ──
    object SearchBar {
        val Width = 320.dp
        val Height = 40.dp
        val Radius = DesignTokens.Radius.Medium
        val PaddingHorizontal = 12.dp
        val IconSize = 16.dp
        val TextSize = DesignTokens.TypeScale.Body
        val Gap = 8.dp
        val ShortcutRadius = DesignTokens.Radius.Small
        val ShortcutTextSize = DesignTokens.TypeScale.Micro
        val ShortcutPaddingH = 6.dp
        val ShortcutPaddingV = 2.dp
        val ClearIconSize = 14.dp
    }

    // ── Featured Card ──
    object FeaturedCard {
        val Height = 250.dp
        val Radius = DesignTokens.Radius.Large
        val Padding = 20.dp
        val CoverRadius = DesignTokens.Radius.Medium
        val ContentGap = 24.dp
        val TextGap = 10.dp
        val ButtonGap = 10.dp
        val NavButtonSize = 40.dp
        val NavButtonGap = 6.dp
        val NavIconSize = 16.dp
        val NavPadding = 12.dp
        val ShadowElevation = DesignTokens.Elevation.Raised
        val ShadowColor: Color @Composable get() = surfaces.shadow
    }

    // ── Podcast Card ──
    object PodcastCard {
        val Width = 150.dp
        val ImageSize = 150.dp
        val ImageRadius = DesignTokens.Radius.Large
        val Spacing = 12.dp
        val Gap = 10.dp
        val TitleSize = DesignTokens.TypeScale.Body
        val AuthorSize = DesignTokens.TypeScale.Micro
    }

    // ── Episode Row ──
    object EpisodeRow {
        val Height = 88.dp
        val PaddingHorizontal = 14.dp
        val PaddingVertical = 8.dp
        val LineGap = 2.dp
        val CoverSize = 64.dp
        val CoverRadius = DesignTokens.Radius.Medium
        val Spacing = 14.dp
        val TitleSize = DesignTokens.TypeScale.Body
        val AuthorSize = DesignTokens.TypeScale.Caption
        val DescSize = DesignTokens.TypeScale.Micro
        val IconSize = 20.dp
    }

    // ── Favorite episode list ──
    object FavoriteEpisodeList {
        private const val SourceTitleSize = 16f
        val Scale = EpisodeRow.TitleSize.value / SourceTitleSize

        val CardGap = (12f * Scale).dp
        val ListPaddingTop = CardGap
        val ListPaddingBottom = (120f * Scale).dp

        val CardHeight = (112f * Scale).dp
        // These four radii were all scaled copies of a source mock
        // (`16f * Scale`, `14f * Scale`, `6f * Scale`, `10f * Scale`), which
        // resolved to 13 / 11.4 / 4.9 / 8.1dp — four unrelated corner languages
        // inside a single 112dp row, and none of them a step on [Radius]. They
        // now take the scale directly, which also makes the nesting readable:
        // card 10dp, artwork inside it 6dp, chips and icon buttons 6dp.
        val CardRadius = DesignTokens.Radius.Medium
        val CardPaddingHorizontal = (18f * Scale).dp
        val CardPaddingVertical = (16f * Scale).dp
        val CoverContentGap = (20f * Scale).dp
        val ContentActionsGap = (24f * Scale).dp

        val BorderWidth = (1f * Scale).dp

        val CoverSize = (72f * Scale).dp
        val CoverRadius = DesignTokens.Radius.Small
        val CoverShadowElevation = (12f * Scale).dp

        val DurationInset = (6f * Scale).dp
        val DurationHeight = (20f * Scale).dp
        val DurationPaddingHorizontal = (6f * Scale).dp
        val DurationRadius = DesignTokens.Radius.Small
        val DurationTextSize = (12f * Scale).sp
        val DurationTextWeight = FontWeight.Medium

        val TitleSize = EpisodeRow.TitleSize
        val TitleLineHeight = (22f * Scale).sp
        val TitleWeight = FontWeight.SemiBold
        val PodcastNameMarginTop = (6f * Scale).dp
        val PodcastNameSize = (14f * Scale).sp
        val PodcastNameLineHeight = (20f * Scale).sp
        val PodcastNameWeight = FontWeight.Medium
        val AuthorMarginTop = (2f * Scale).dp
        val AuthorSize = (13f * Scale).sp
        val AuthorLineHeight = (18f * Scale).sp
        val MetadataMarginTop = (6f * Scale).dp
        /**
         * Date, time, author — the row's third line.
         *
         * Was 13sp, the same size as the podcast name above it and only two
         * steps from the 16sp title. Three lines at 16/14/13 read as one
         * paragraph with arbitrary bolding, so the eye had no entry point. The
         * third line drops to the caption step and gains a bullet separator
         * (see `formatEpisodeMetadata`), which is what makes it read as
         * metadata rather than more sentence.
         */
        val MetadataSize = DesignTokens.TypeScale.Caption

        val ActionsGap = (24f * Scale).dp
        val ActionButtonSize = (36f * Scale).dp
        val ActionButtonRadius = DesignTokens.Radius.Small
        val ActionIconSize = (22f * Scale).dp
        val QueueIconSize = (26f * Scale).dp
        val FavoriteIconSize = (24f * Scale).dp

        val BackgroundColor: Color @Composable get() = surfaces.cardFill
        val HoverBackgroundColor: Color @Composable get() = surfaces.cardFillHover
        val PressedBackgroundColor: Color @Composable get() = surfaces.cardFillPressed
        val PlayingBackgroundColor: Color @Composable get() = surfaces.cardSelectedFill
        val BorderColor: Color @Composable get() = surfaces.cardBorder
        val HoverBorderColor: Color @Composable get() = surfaces.cardBorderHover
        val PlayingBorderColor: Color @Composable get() = surfaces.cardSelectedBorder
        val CoverShadowColor: Color @Composable get() = surfaces.rowCoverShadow
        val DurationBackgroundColor: Color @Composable get() = surfaces.rowDurationFill
        val DurationTextColor: Color @Composable get() = surfaces.rowDurationText
        val TitleColor: Color @Composable get() = surfaces.rowTitle
        val PodcastNameColor: Color @Composable get() = surfaces.rowPodcastName
        val AuthorColor: Color @Composable get() = surfaces.rowAuthor
        val MetadataColor: Color @Composable get() = surfaces.rowMetadata
        val ActionButtonHoverBackgroundColor: Color @Composable get() = surfaces.cardFillHover
        val ActionIconColor: Color @Composable get() = surfaces.rowActionIcon
        val ActionIconHoverColor: Color @Composable get() = surfaces.rowActionIconHover
        val QueueIconColor: Color @Composable get() = surfaces.rowQueueIcon
        val QueueIconHoverColor: Color @Composable get() = surfaces.rowQueueIconHover
        val FavoriteActiveColor: Color @Composable get() = surfaces.rowFavoriteActive
        val FavoriteInactiveColor: Color @Composable get() = surfaces.rowFavoriteInactive
    }

    // ── Subscription rows ──
    object SubscriptionRow {
        val Height = 96.dp
        val PaddingHorizontal = 14.dp
        val PaddingVertical = 12.dp
        val CoverSize = 64.dp
        val CoverRadius = DesignTokens.Radius.Medium
        val Spacing = 14.dp
        val MetaGap = 6.dp
        val MetaEndPadding = 4.dp
        val TitleSize = DesignTokens.TypeScale.Body
        val AuthorSize = DesignTokens.TypeScale.Caption
        val DescSize = DesignTokens.TypeScale.Micro
        val DescriptionMaxWidth = (480f * FavoriteEpisodeList.Scale).dp
        val LineGap = 2.dp
        val CheckboxSize = 24.dp
        val ActionButtonSize = 32.dp
        val ActionIconSize = 16.dp
    }

    // ── Queue Panel ──
    object QueuePanel {
        val Width = 420.dp
        val PaddingTop = 20.dp
        val PaddingHorizontal = 24.dp
        val RowHeight = 80.dp
        val Spacing = 12.dp
        val HeaderHeight = 56.dp
        val HeaderContentTopOffset = 14.dp
        val HeaderTitleSize = DesignTokens.TypeScale.Heading
        val HeaderTitleLineHeight = DesignTokens.TypeScale.Heading
        val HeaderTitleBottomOffset = 3.dp
        val ClearTextSize = DesignTokens.TypeScale.Body
        val DragHandleSize = 16.dp
        val ActiveCoverBadge = 20.dp
        val HeaderActionMinWidth = 54.dp
        val HeaderActionHeight = 28.dp
        val HeaderActionRadius = DesignTokens.Radius.Small
        val HeaderActionBorderWidth = 0.1.dp
        val HeaderActionPaddingHorizontal = 9.dp
        val HeaderActionIconSize = 15.dp
        val HeaderActionIconTextGap = 5.dp
        val HeaderActionTextSize = DesignTokens.TypeScale.Caption
        val HeaderActionLineHeight = DesignTokens.TypeScale.BodyStrong
        val HeaderActionVerticalOffset = 4.dp
        val HeaderCloseButtonSize = 28.dp
        val HeaderCloseButtonMargin = 6.dp
        val HeaderCloseIconSize = 16.dp
        val HeaderActionGlassBackgroundColor: Color @Composable get() = surfaces.queueActionFill
        val HeaderActionGlassHoverBackgroundColor: Color @Composable get() = surfaces.queueActionFillHover
        val HeaderCloseHoverBackgroundColor: Color @Composable get() = surfaces.queueCloseFillHover

        // Queue rows use the Favorites card language with a denser two-line layout.
        val CardHeight = 80.dp
        val CardGap = DesignTokens.FavoriteEpisodeList.CardGap
        val CardPaddingHorizontal = 14.dp
        val CardPaddingVertical = 8.dp
        val CoverSize = 64.dp
        val CoverRadius = DesignTokens.Radius.Medium
        val CoverContentGap = 12.dp
        val TitleSize = DesignTokens.TypeScale.Body
        val TitleLineHeight = DesignTokens.TypeScale.BodyStrong
        val TitleWeight = FontWeight.Normal
        val SubtitleMarginTop = 2.dp
        val SubtitleSize = DesignTokens.TypeScale.Caption
        val SubtitleLineHeight = DesignTokens.TypeScale.BodyStrong
        val PlayingIndicatorWidth = 2.dp
        val PlayingIndicatorInset = 14.dp
        val RemoveButtonInset = 4.dp
        val DragHandleVerticalOffset = 0.dp
    }

    // ── Page Header ──
    object PageHeader {
        val TitleSize = DesignTokens.TypeScale.Display
        val SubtitleSize = DesignTokens.TypeScale.Body
        val PaddingHorizontal = 32.dp
        val PaddingTop = 28.dp
        val Gap = 4.dp
    }

    // ── Section Header ──
    object SectionHeader {
        val TitleSize = DesignTokens.TypeScale.Heading
        val LinkSize = DesignTokens.TypeScale.Body
        val PaddingHorizontal = 32.dp
    }

    /**
     * Page titles ride the bundled Noto Sans SC family ([BundledFont]).
     *
     * These were `FontFamily.Serif` throughout the app, which reads as an
     * editorial treatment. Apple Podcasts web sets its titles in the system
     * face at a heavy weight, so the family is centralised here rather than
     * repeated at twelve call sites.
     *
     * A getter rather than a `val`, so the font bytes load on first
     * composition instead of whenever `DesignTokens` initializes — and so a
     * load failure degrades to the system sans-serif inside [BundledFont]
     * instead of throwing from object initialization.
     */
    object TypeFamily {
        val PageTitle: FontFamily
            get() = BundledFont.family

        /**
         * Medium, not Bold.
         *
         * The title already has 32sp, which is the largest thing on any screen
         * by a factor of 1.6 — it does not also need the heaviest weight in the
         * family. Bold at that size on a near-white page reads as shouting, and
         * it made the row titles below it look like a different app's text.
         */
        val PageTitleWeight = FontWeight.Medium

        /**
         * Pass to `fontFeatureSettings` on any Text that shows a number a user
         * watches change, or that must line up with a sibling number.
         *
         * Proportional figures are the default, and they are wrong for a time
         * readout: "1:09" and "1:11" are different widths, so the row around the
         * player's clock shifts every 250ms while the position advances. Same for
         * counts and durations sitting in a column.
         */
        const val TabularFigures = "tnum"
    }

    /**
     * The grayscale an icon is drawn in, as a function of the text beside it.
     *
     * The rule is one step down, always. An icon is a label for a control, and a
     * 20dp grid carries far more ink per unit of area than 13sp of prose does;
     * drawing both at `textSecondary` makes the icon read as the heavier, more
     * important of the two, which is the reverse of what it is.
     */
    object IconInk {
        /** Paired text is at [PodaraColors.textSecondary] or below. */
        val Resting: Color @Composable get() = ink.textMuted

        /** Paired text is at [PodaraColors.textPrimary], or the pointer is on it. */
        val Hover: Color @Composable get() = ink.textSecondary

        /** Selected, active, now-playing. Matches the text it labels. */
        val Active: Color @Composable get() = ink.textPrimary
    }

    /**
     * Optical alignment for icons (DESIGN-CRAFT F4).
     *
     * A 20dp grid does not centre every glyph: circles fill their box, the
     * play triangle's visual mass sits left of its bounding box, arrows press
     * against an edge. The compensation is a small nudge, as a fraction of the
     * render size.
     *
     * The rule is exclusivity. A nudge is allowed **only** for a glyph listed
     * here; the moment offsets appear at call sites for unlisted glyphs this
     * stops being optical correction and becomes freehand — the thing the
     * token exists to prevent.
     */
    object IconBox {
        /** The play triangle sits left of centre in its box; pause's bars are symmetric and need none. */
        const val PlayArrowShift = 0.06f
    }

    // ── Card Background ──
    object Card {
        val Gradient: Brush @Composable get() = surfaces.cardGradient
    }

    // ── Cover art ──
    object Artwork {
        /** The glyph's share of the cover box, when there is no cover to show. */
        val GlyphScale = 0.45f
        val SpinnerStroke = 2.dp
    }

    // ── Scroll indicator ──
    object Scrollbar {
        /**
         * 6dp rather than the ~17dp a native Windows scrollbar is: this sits
         * inside the list, over its content, and a wider thumb would start
         * covering the row text it is meant to accompany.
         */
        val Thickness = 6.dp
        val MinimalHeight = 16.dp

        /** Keeps the thumb off the panel edge it docks against. */
        val Inset = 6.dp
    }

    // ── Hover tooltip ──
    object Tooltip {
        /**
         * How long the pointer must rest on a control before its tooltip fires.
         *
         * The desktop convention, and the reason it is not zero: a tooltip that
         * appears instantly is painted on the way past the control. Sweeping the
         * cursor across a toolbar would otherwise strobe every glyph on the path.
         */
        const val DelayMs = 400

        /**
         * A tooltip is a label, not a paragraph. Past this it stops being
         * something readable in the corner of the eye and becomes a small block
         * of text the user has to stop and parse.
         */
        val MaxWidth = 240.dp

        /**
         * The gap between the control and the bubble. A tooltip fused to its
         * anchor reads as part of the button; a small air gap says it is a note
         * about the button.
         */
        val AnchorGap = 8.dp
    }

    // ── Animation durations ──
    object Animation {
        val HoverMs = 150
        val NormalMs = 300
        val SlowMs = 500

        /**
         * The curve everything that moves should use.
         *
         * `tween` without an easing argument gets the platform default, a
         * symmetric general-purpose curve. On an element entering the screen it
         * opens too fast and then coasts, which is the single most common reason
         * motion reads as cheap. This is the asymmetric version: it leaves
         * promptly and settles slowly.
         *
         * It existed once, hardcoded in the confirm dialog, and was available to
         * exactly one of the app's 49 animations.
         */
        val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /**
         * [StandardEasing] with a harder arrival, for things the user just
         * asked for and that should feel like they answered immediately.
         *
         * Only for short travel or plain appearance. A curve this front-loaded
         * covers a third of a full-height slide in a single frame, which is the
         * cut that `OverlayPixelTest` exists to reject: when the distance is the
         * message, the motion has to stay legible, and that is [TravelEasing].
         */
        val EmphasisEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** For things leaving: slow to let go, then gone. */
        val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        /**
         * The symmetric general-purpose curve — the thing `tween` gets if you
         * name no easing. Pinned as a token for the one case where it is the
         * right answer: **long travel**.
         *
         * Both curves above front-load their motion, which is what makes an
         * element feel responsive. It is also wrong when the distance is the
         * message. The full player rises the entire height of the window
         * precisely so the eye can follow it, and a front-loaded curve spends a
         * third of that height inside the first frames: measured, [StandardEasing]
         * moves the panel 15.4% of the area in one frame against the 15% the
         * pixel test allows, and [EmphasisEasing] moves 38%. Either of those is a
         * cut with extra steps, which is the defect this overlay has been
         * rewritten around three times.
         *
         * So the rule is: name a curve everywhere, and name *this* one when the
         * element has to be seen travelling.
         */
        val TravelEasing = FastOutSlowInEasing

        // Full-player overlay. The slide-in is the slowest transition in the app
        // because the panel travels the full height of the window; the fade-out
        // is quicker so the screen underneath is revealed promptly once the user
        // has asked to leave. There is deliberately no fade-in counterpart — see
        // the overlay comment in App.kt.
        val FullPlayerSlideInMs = 400
        val FullPlayerFadeOutMs = 200

        /**
         * Peer screen switch.
         *
         * Shorter than [NormalMs] on purpose: this plays on every sidebar click,
         * so anything over ~250ms starts to feel like the app is thinking. The
         * offset is a rise, not a slide — peers sit side by side in the sidebar,
         * so horizontal travel would tell the user something untrue about them.
         * Podcast detail has its own push, below.
         */
        val ScreenEnterMs = 220
        val ScreenExitMs = 120
        val ScreenEnterRise = 12.dp

        /**
         * Going into, and back out of, a podcast.
         *
         * A sidebar switch is a lateral move between peers, so it rises. Opening
         * a podcast is going somewhere *deeper*, and depth reads as horizontal
         * travel: the arriving page slides in from the right while the page being
         * left gives way by a shorter distance to the left. Back reverses the two
         * so the user can tell which way they travelled.
         *
         * Both sides share [ScreenPushMs]: a push where one surface moves faster
         * than the other looks like the pages are on separate tracks.
         *
         * Fixed offsets, not the window width — a full-width push would drag the
         * neighbour across the whole screen and compete with the full player,
         * which owns the vertical axis.
         */
        val ScreenPushMs = 240
        val ScreenPushFar = 40.dp
        val ScreenPushNear = 20.dp

        /** Queue drawer exit. The spring owns the entry; the exit stays a tween. */
        val DrawerExitMs = 200

        /**
         * How long cover art takes to fade in once it has loaded.
         *
         * Short: this plays for every visible row on every screen, and a cover
         * that arrives late is already the user's second problem. Long enough
         * that a hard swap never happens, short enough that it never looks like
         * the image is being introduced.
         */
        val ArtworkFadeMs = 180

        /**
         * Press feedback.
         *
         * A press is a physical thing: it should answer immediately and settle
         * slowly. A tween gives both halves the same slope, which is why the
         * buttons here read as *switching* rather than *giving way*. The
         * sub-critical damping ratio is the slow part — one small overshoot on
         * release, not a wobble.
         */
        val PressSpring: FiniteAnimationSpec<Float> = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow
        )
        val PressedScale = 0.96f

        /** The drawer's panel: heavy enough that a bounce would look loose. */
        val DrawerSpring: FiniteAnimationSpec<IntOffset> = spring(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessMediumLow
        )
    }
}

/**
 * Whether the user asked the app to keep still — the "减少动效" switch in
 * Settings (DESIGN-CRAFT.md C4).
 *
 * Desktop has no `prefers-reduced-motion` to inherit, so the preference is a
 * persisted [app.podara.util.Settings] flag, read once at the App root and
 * provided here. Every motion entry that slides or springs — the screen push,
 * the full player's rise, the queue drawer, the dialog pop — reads this local
 * and degrades to a fade; the pure-logic transition specs take the flag as a
 * parameter so the degraded shapes stay unit-testable.
 *
 * `staticCompositionLocalOf` on purpose: the flag changes at most when someone
 * flips the switch, and invalidating the whole tree on that one write is the
 * correct trade for making every read a plain field access.
 *
 * This is also a debugging tool, as DESIGN-CRAFT puts it: with the switch on,
 * every gated entrance becomes a plain crossfade, which is the fastest way to
 * see what a transition is actually hiding.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }