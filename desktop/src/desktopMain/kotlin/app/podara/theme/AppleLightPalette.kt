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
    val TextPrimary = Color(0xFF1D1D1F)
    val TextSecondary = Color(0xFF6E6E73)
    val TextMuted = Color(0xFF86868B)
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
