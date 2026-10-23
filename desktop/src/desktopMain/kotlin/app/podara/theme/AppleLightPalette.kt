package app.podara.theme

import androidx.compose.ui.graphics.Color

/**
 * Apple Podcasts web palette.
 *
 * Sourced from podcasts.apple.com: a near-white page, Apple's neutral gray
 * ramp, hairline borders, and the red-pink accent used for follow buttons and
 * active state. Values are deliberately plain and opaque — unlike the dark
 * scheme's alpha-based glass, these sit on a light page and must read as solid
 * fills.
 *
 * Kept separate from [PodiumBackground] and friends so the existing dark scheme
 * stays untouched and both palettes remain comparable.
 */
object AppleLightPalette {

    // ── Surfaces ──
    /** Page background — Apple uses a very slightly warm off-white. */
    val Background = Color(0xFFFFFFFF)
    /** Card and grouped-list fill. Very light gray, distinguishable from the page. */
    val Surface = Color(0xFFF5F5F7)
    /** Raised fill for controls sitting on a card. */
    val Elevated = Color(0xFFFFFFFF)
    /** Sidebar / chrome fill. */
    val Chrome = Color(0xFFFBFBFD)

    // ── Lines ──
    /** Hairline border. Apple's dividers are a low-alpha black, not gray. */
    val Border = Color(0x1A000000)
    val Divider = Color(0x0F000000)

    // ── Text ──
    //
    // A five-step grayscale ladder rather than the four values this started with.
    // The ladder carries hierarchy once decoration is subtracted: on a near-white
    // page with no shadows, "how dark is this text" is the only signal separating
    // a title from its metadata.
    //
    // The earlier attempt to separate muted from secondary went the wrong way —
    // it lightened muted until it failed contrast, then collapsed it back onto
    // secondary, leaving two tokens with one visual value. The fix is to move
    // *secondary* darker instead: it was sitting at 5.07:1, darker than it needed
    // to be while muted had nowhere to go.
    //
    // Ratios below were measured against #FFFFFF and the #F5F5F7 surface, and
    // they are now asserted rather than commented: see ContrastFloorTest, which
    // recomputes them instead of trusting this text. Note that the shell gradient
    // adds a *darker* background under the sidebar, where [TextMuted] lands at
    // 4.38:1 — under the text floor, and legal only because the sidebar uses that
    // step for icon ink and never for a label.

    /** Titles and body. 16.8:1 / 15.5:1. */
    val TextPrimary = Color(0xFF1D1D1F)

    /**
     * Panel and card headings that sit under a page title. 13.5:1 / 12.4:1.
     *
     * Exists because Primary and Secondary were two stops apart with a wide
     * gap between them: a section heading drawn in Primary competed with the
     * page title, and one drawn in Secondary read as body text. This is the step
     * that says "heading, but not the page's heading".
     */
    val TextStrong = Color(0xFF2E2E31)

    /** Descriptions and secondary lines. 7.07:1 / 6.50:1. */
    val TextSecondary = Color(0xFF58585D)

    /**
     * Metadata, timestamps, tertiary hints. 5.07:1 / 4.66:1.
     *
     * The last step that still clears AA on the gray surface, which is why the
     * ladder is anchored here rather than at a rounder-looking gray.
     */
    val TextMuted = Color(0xFF6E6E73)

    /**
     * Counters, separators, the dot between two metadata items. 3.43:1 — below
     * AA by design, so it is only used for glyphs, never for a sentence.
     */
    val TextFaint = Color(0xFF8A8A90)

    /** Disabled copy that is present but not actionable. 2.21:1. */
    val TextDisabled = Color(0xFFAEAEB2)

    /** Text on top of an accent-filled control. */
    val TextOnAccent = Color(0xFFFFFFFF)

    // ── Accent ──
    /** Apple Podcasts follow/play accent. */
    val Accent = Color(0xFFFA2D48)
    val AccentHover = Color(0xFFE01F3D)
    val AccentPressed = Color(0xFFC21532)
    /** Low-alpha accent for selected fills (chips, active rows). */
    val AccentSubtle = Color(0x14FA2D48)

    // ── Status ──
    val Success = Color(0xFF248A3D)
    val Warning = Color(0xFFB25000)
    val Danger = Color(0xFFD70015)
    val Info = Color(0xFF0071E3)

    // ── Interaction overlays ──
    /** Hover wash for rows and cards on a light page. */
    val HoverOverlay = Color(0x0A000000)
    val PressedOverlay = Color(0x14000000)
    /** Drop shadows on light are lower-alpha than on dark. */
    val Shadow = Color(0x14000000)
    /** Backdrop behind modals and the full player. */
    val Scrim = Color(0x40000000)
}
