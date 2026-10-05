package app.podara.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

    // ── Spacing ──
    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 16.dp
        val lg = 24.dp
        val xl = 32.dp
    }

    // ── Common Border ──
    object Border {
        val Width = 1.dp
        val SecondaryColor: Color @Composable get() = surfaces.menuBorder
    }

    // ── Glass surfaces ──
    // Colour entries delegate to SurfaceTokens so they resolve per scheme.
    // Geometry stays here as plain vals.
    object Glass {
        val CompactRadius = 12.dp
        val CompactBorderWidth = 0.6.dp
        val CompactShadowElevation = 8.dp

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
        val Radius = 8.dp
        val IconSize = 16.dp
        val TextSize = 13.sp
        val StrongTextSize = 15.sp
        val Gap = 6.dp
        val BorderWidth = 0.6.dp
        val BackgroundColor = Color.Transparent
        val PillHeight = 37.dp
        val PillRadius = 11.dp
        val PillPaddingHorizontal = 13.dp
        val PillIconTextGap = 7.dp
        val PillIconSize = 17.dp
        val PillTrailingIconSize = 15.dp
        val PillTextSize = 13.sp
        val PillLineHeight = 19.sp
        val PillTextWeight = FontWeight(450)
        val PillActiveTextWeight = FontWeight.Medium
        val ManageMinWidth = 78.dp
        val SortMinWidth = 139.dp
        val PillSelectedIconColor: Color @Composable get() = surfaces.pillTextSelected
        val PillHoverShadowElevation = 4.dp

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
        val Radius = 14.dp
        val Padding = 7.dp
        val ItemHeight = 38.dp
        val ItemRadius = 9.dp
        val ItemPaddingHorizontal = 10.dp
        val ItemIconTextGap = 10.dp
        val ItemIconSize = 16.dp
        val OffsetY = 5.dp
        val ShadowElevation = 10.dp
        val LabelSize = ToolbarButton.PillTextSize
        val LabelLineHeight = 18.sp
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
            val Radius = (28f * Scale).dp
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
            val Radius = (14f * Scale).dp
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
        val Radius = 9.dp
        val PaddingHorizontal = 6.dp
        val PaddingVertical = 2.dp
        val TextSize = 11.sp
        val AccentBackgroundColor: Color @Composable get() = surfaces.badgeFill
        val AccentBorderColor: Color @Composable get() = surfaces.badgeBorder
        val AccentTextColor: Color @Composable get() = surfaces.badgeText
    }

    // ── Empty states ──
    object EmptyState {
        val PanelWidth = 400.dp
        val PanelRadius = 18.dp
        val PanelPadding = 24.dp
        val IconSize = 56.dp
        val TitleSize = 20.sp
        val SubtitleSize = 14.sp
        val Gap = 12.dp
    }

    // ── Navigation active glass ──
    object Navigation {
        object ActiveGlass {
            val Radius = 13.dp
            val BorderWidth = 0.6.dp
            val ShadowElevation = 5.dp
            val InnerPaddingHorizontal = 14.dp

            val BaseColor: Color @Composable get() = surfaces.navActiveFill
            val LeftGlow: Brush @Composable get() = surfaces.navActiveAccentGlow
            val TopGlow: Brush @Composable get() = surfaces.navActiveTopGlow
            val RightGlow: Brush @Composable get() = surfaces.navActiveAccentGlow
            val Border: Brush @Composable get() = surfaces.navActiveBorder
            val ShadowColor: Color @Composable get() = surfaces.shadow
        }
    }

    // ── Hero surfaces ──
    object Hero {
        val LeftAmbientCenterFactor = Offset(-0.28f, 2.92f)
        val LeftAmbientRadiusFactor = 0.92f
        val CornerGlowCenterFactor = Offset(1.06f, 1.12f)
        val CornerGlowRadiusFactor = 0.18f

        val WaveformGold: Color @Composable get() = surfaces.heroWaveform
        val LeftAmbientGlowColors: List<Color> @Composable get() = surfaces.heroAmbientGlow
        val CornerGlowColors: List<Color> @Composable get() = surfaces.heroCornerGlow
    }

    // ── Button: primary ──
    object Button {
        val Height = 40.dp
        val Radius = 10.dp
        val IconSize = 20.dp
        val TextSize = 14.sp
        val PaddingHorizontal = 16.dp
        val ShadowElevation = 10.dp

        val Gradient: Brush @Composable get() = surfaces.buttonGradient
        val InnerHighlight: Brush @Composable get() = surfaces.buttonSheen
        val SpecularSheen: Brush @Composable get() = surfaces.buttonSheen
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
        val Radius = 18.dp
        val IconSize = 20.dp
        val FlyawayDurationMs = 600
        val FlyawayOffsetX = 25.dp
        val FlyawayOffsetY = 60.dp
    }

    // ── MiniPlayer ──
    object MiniPlayer {
        val Height = 88.dp
        val PaddingHorizontal = 20.dp
        object Speed { val Size = 36.dp; val TextSize = 14.sp }
        object RewindForward { val Size = 40.dp; val IconSize = 22.dp }
        object PlayPause { val Size = 56.dp; val IconSize = 26.dp }
        object Volume { val IconSize = 22.dp }
        object QueueFullscreen { val Size = 32.dp; val IconSize = 20.dp }
        object Time { val TextSize = 12.sp }
        object Slider { val Height = 20.dp }
    }

    // ── Sidebar ──
    object Sidebar {
        val Width = 240.dp
        val PaddingVertical = 20.dp
        val PaddingHorizontal = 20.dp
        val NavItemHeight = 48.dp
        val NavItemPadding = 12.dp
        val NavIconSize = 20.dp
        val NavTextSize = 14.sp
        val NavSpacing = 10.dp
        val LogoSize = 40.dp
        val LogoRadius = 10.dp
        val LogoIconSize = 18.dp
        val LogoTextSize = 20.sp
        val DividerPadding = 20.dp
    }

    // ── Search Bar ──
    object SearchBar {
        val Width = 320.dp
        val Height = 40.dp
        val Radius = 10.dp
        val PaddingHorizontal = 12.dp
        val IconSize = 16.dp
        val TextSize = 13.sp
        val Gap = 8.dp
        val ShortcutRadius = 5.dp
        val ShortcutTextSize = 11.sp
        val ShortcutPaddingH = 6.dp
        val ShortcutPaddingV = 2.dp
        val ClearIconSize = 14.dp
    }

    // ── Featured Card ──
    object FeaturedCard {
        val Height = 250.dp
        val Radius = 18.dp
        val Padding = 20.dp
        val CoverRadius = 12.dp
        val ContentGap = 24.dp
        val TextGap = 10.dp
        val ButtonGap = 10.dp
        val NavButtonSize = 40.dp
        val NavButtonGap = 6.dp
        val NavIconSize = 16.dp
        val NavPadding = 12.dp
        val ShadowElevation = 8.dp
        val ShadowColor: Color @Composable get() = surfaces.shadow
    }

    // ── Podcast Card ──
    object PodcastCard {
        val Width = 150.dp
        val ImageSize = 150.dp
        val ImageRadius = 14.dp
        val Spacing = 12.dp
        val Gap = 10.dp
        val TitleSize = 13.sp
        val AuthorSize = 11.sp
    }

    // ── Episode Row ──
    object EpisodeRow {
        val Height = 88.dp
        val PaddingHorizontal = 14.dp
        val PaddingVertical = 8.dp
        val LineGap = 2.dp
        val CoverSize = 64.dp
        val CoverRadius = 10.dp
        val Spacing = 14.dp
        val TitleSize = 14.sp
        val AuthorSize = 12.sp
        val DescSize = 11.sp
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
        val CardRadius = (16f * Scale).dp
        val CardPaddingHorizontal = (18f * Scale).dp
        val CardPaddingVertical = (16f * Scale).dp
        val CoverContentGap = (20f * Scale).dp
        val ContentActionsGap = (24f * Scale).dp

        val BorderWidth = (1f * Scale).dp

        val CoverSize = (72f * Scale).dp
        val CoverRadius = (14f * Scale).dp
        val CoverShadowElevation = (12f * Scale).dp

        val DurationInset = (6f * Scale).dp
        val DurationHeight = (20f * Scale).dp
        val DurationPaddingHorizontal = (6f * Scale).dp
        val DurationRadius = (6f * Scale).dp
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
        val MetadataSize = (13f * Scale).sp

        val ActionsGap = (24f * Scale).dp
        val ActionButtonSize = (36f * Scale).dp
        val ActionButtonRadius = (10f * Scale).dp
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
        val CoverRadius = 12.dp
        val Spacing = 14.dp
        val MetaGap = 6.dp
        val MetaEndPadding = 4.dp
        val TitleSize = 14.sp
        val AuthorSize = 12.sp
        val DescSize = 11.sp
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
        val HeaderTitleSize = 21.sp
        val HeaderTitleLineHeight = 26.sp
        val HeaderTitleBottomOffset = 3.dp
        val ClearTextSize = 13.sp
        val DragHandleSize = 16.dp
        val ActiveCoverBadge = 20.dp
        val HeaderActionMinWidth = 54.dp
        val HeaderActionHeight = 28.dp
        val HeaderActionRadius = 8.dp
        val HeaderActionBorderWidth = 0.1.dp
        val HeaderActionPaddingHorizontal = 9.dp
        val HeaderActionIconSize = 15.dp
        val HeaderActionIconTextGap = 5.dp
        val HeaderActionTextSize = 12.sp
        val HeaderActionLineHeight = 16.sp
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
        val CoverRadius = 10.dp
        val CoverContentGap = 12.dp
        val TitleSize = 13.sp
        val TitleLineHeight = 17.sp
        val TitleWeight = FontWeight.Normal
        val SubtitleMarginTop = 2.dp
        val SubtitleSize = 12.sp
        val SubtitleLineHeight = 16.sp
        val PlayingIndicatorWidth = 2.dp
        val PlayingIndicatorInset = 14.dp
        val RemoveButtonInset = 4.dp
        val DragHandleVerticalOffset = 0.dp
    }

    // ── Page Header ──
    object PageHeader {
        val TitleSize = 32.sp
        val SubtitleSize = 14.sp
        val PaddingHorizontal = 32.dp
        val PaddingTop = 28.dp
        val Gap = 4.dp
    }

    // ── Section Header ──
    object SectionHeader {
        val TitleSize = 20.sp
        val LinkSize = 13.sp
        val PaddingHorizontal = 32.dp
    }

    /**
     * Page titles use the platform sans-serif.
     *
     * These were `FontFamily.Serif` throughout the app, which reads as an
     * editorial treatment. Apple Podcasts web sets its titles in the system
     * face at a heavy weight, so the family is centralised here rather than
     * repeated at twelve call sites.
     */
    object TypeFamily {
        val PageTitle = FontFamily.SansSerif
    }

    // ── Card Background ──
    object Card {
        val Gradient: Brush @Composable get() = surfaces.cardGradient
    }

    // ── Animation durations ──
    object Animation {
        val HoverMs = 150
        val NormalMs = 300
        val SlowMs = 500
    }
}
