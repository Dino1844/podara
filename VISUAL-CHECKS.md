# Visual checks

This file is for the things **no automated test in this repo can tell you**.

It is deliberately short. Anything that can be asserted headlessly, is asserted
headlessly — see [What the tests already cover](#what-the-tests-already-cover).
What is left is timing, feel and legibility, and those need a person watching
the real window.

## How to run

```
./gradlew :desktop:run
```

Windows: `gradlew.bat :desktop:run`. The app needs libmpv on the machine to
play audio; the layout and animation checks below still work without it, but
anything that depends on playback (the progress bar) does not.

Suggested pass: `./gradlew :desktop:clean :desktop:run` so you are not looking
at a stale frame, and run it on each theme (Settings → Appearance → Light /
Dark / System). Every item below should be checked in **both** themes unless it
says otherwise.

---

## 1. Full-screen player: open, close, and what happens behind

**Where:** Discover or Home → **scroll the list down a little first** → tap the
mini player (or its expand button) to open the full player; press the close
button (top-left) to dismiss it. Repeat a few times.

**Look at:** how the player arrives and leaves, what the screen behind is doing
during those ~0.4 s, and — after closing — whether the screen is where you left
it.

| | Expected | Known current behaviour |
|---|---|---|
| Enter | Player rises from the bottom over ~400 ms, **without fading** | There is deliberately no fade on the way in: `FullPlayer`'s root is already opaque, so sliding it up means every pixel is either the screen behind or the player, never a blend. A fade left the player translucent while it travelled, which is the screen-behind-showing-through half of the white flash. |
| Exit | Player slides down and fades out over ~300 ms, revealing the screen behind | The fade on exit is fine — it reveals the screen behind rather than washing it out. |
| During the transition | **The screen behind must stay visible until the panel has physically covered it.** No blank field of the page background at any point | An opaque backing fill also "covers" the area, but it covers it *instantly*, which under the light theme is a hard cut to a blank white page. Three earlier attempts at this bug each reintroduced it this way. If the player blinks into existence, the transition is not running — check `FullPlayerOverlay.kt`. |
| **After closing** | **The screen behind is exactly as you left it — same scroll position, same loaded rows, no refetch, no reset to the top** | This was a separate bug from the flash and the more obvious one. The screen used to sit inside `if (!showFullPlayer)`, so every open *destroyed* it and every close rebuilt it: scroll position reset and every loading effect re-ran (Home re-read the database, Discover re-fetched the top charts over the network). The fix is that the content is never unmounted — it stays composed and is simply painted over. **Check this every time.** It is the one that looks like "the app refreshed". |

**Why a human:** frame pacing and whether the motion feels right are properties
of the running app. `OverlayPixelTest` now animates the real `FullPlayerOverlay`
composable with a frozen clock and asserts the frame series, and
`FullPlayerOverlayTest` pins the content staying composed — but neither can tell
you the movement is smooth on your machine, nor that 400 ms is the right number.

---

## 2. Queue drawer: slide distance and scrim

**Where:** Mini player → queue button. Dismiss with the scrim click or `Esc`.

**Look at:** the panel's entry, the scrim's opacity, and whether the panel
travels the full window width or stops short.

| | Expected | Known current behaviour |
|---|---|---|
| Panel entry | Slides in from the right edge, flush with the edge, no gap | Confirm it starts exactly at the right edge. A few px of inset reads as a bug. |
| Panel width | Leaves the underlying screen partly visible on the left | Confirm the panel does not cover the full window. |
| Scrim | Appears *instantly* and stays fully opaque for the whole time the panel is open | The scrim and the panel are separate (`App.kt`, "Scrim — appears instantly, separate from panel animation"). Confirm the scrim does not fade in late — if it does, the panel slides over an undimmed screen and the layering looks wrong. |
| Dismiss | Scrim click closes it; `Esc` closes it; no residual dimming | Confirm nothing is left behind after dismissal. |

**Why a human:** the exact travel distance and the dim level are design values,
and "does this feel like it belongs" is not an assertion.

---

## 3. Hover feedback on settings rows and the featured card

**Where:** Settings → any tappable row (Export OPML, Import OPML, Appearance,
About), and Discover → the featured card at the top. Hover with the mouse
without clicking, and sweep the pointer in and out *slowly*.

**Look at:** whether the row changes at all on hover, how fast, and **what colour
it passes through on the way**.

| | Expected | Known current behaviour |
|---|---|---|
| Row highlight | Each tappable row tints on hover, using the scheme's surface-hover token | Confirm every tappable row reacts. A row that highlights on click but not on hover is the common miss. |
| Duration | Snappy — `DesignTokens.Animation.HoverMs` is 150 ms; the featured card sweep is 300 ms | Confirm it is not instant and not sluggish. |
| Transition path | The tint fades smoothly toward the hover colour, monotonically | **New regression class, reported on the Discover featured card.** `animateColorAsState(if (hovered) X else Color.Transparent)` passes through dark grey on the way in *and* out, because `Color.Transparent` is black at zero alpha and the animation interpolates the colour channels. Fixed in 14 places via `animateHoverBackgroundColor`, which fades only the alpha. Sweep slowly and watch the middle of the transition: it must never be darker than either endpoint. `HoverBackgroundAnimationTest` pins this with a frozen clock. |
| Dark halo | No dark ring or hard shadow appears on hover | **This is a known regression class in this repo.** Two commits (`6354967`, `f62a917`) removed a "stray press indication that drew a dark ring on click" and replaced a shadow-used-as-focus-ring. Check for any dark outline, especially in the dark theme. |
| Release | Highlight disappears when the pointer leaves the row | Confirm no stuck highlight. |

**Why a human:** hover is not a semantic state and Compose Desktop's ui-test has
no hover assertion for it. A pixel test can prove a colour was painted, not
that the right colour was painted on the right row.

---

## 4. Progress bar smoothness during playback

**Where:** Play anything, then look at the scrubber on the mini player and on
the full player.

**Look at:** whether the fill moves in one continuous motion or in visible
steps, and whether it tracks the audio.

| | Expected | Known current behaviour |
|---|---|---|
| Fill motion | Continuous, no visible stepping or stutter | Confirm over at least a minute of playback. Stepping here usually means the position is polled too coarsely rather than pushed. |
| Tracking | The fill stays in step with the audio; drift under ~1 s over a few minutes | Confirm by ear against the fill. |
| Drag | Dragging the scrubber seeks and the fill follows the pointer immediately | Confirm the fill does not lag or jump back on release. |
| On completion | The bar ends full, does not overshoot, and does not sit at 99% | Confirm with a short episode. |

**Why a human:** this is entirely about time and smoothness in a real window.
A headless frame capture at a fixed clock step cannot see stutter, and no
assertion in this repo touches the progress value during playback.

---

## 5. Cross-screen transition

**Where:** Sidebar → Discover, Home, Favorites, History, Settings, Downloads.

**Look at:** whether the content area swaps cleanly or shows a leftover frame of
the screen you came from.

| | Expected | Known current behaviour |
|---|---|---|
| Swap | Old screen fully replaced by the new one | Confirm no sliver of the previous screen survives. The sidebar is a sibling of the content Box, so it is the one thing that has already been broken by a bad overlay fill — check it stays correct. |
| Layout jump | Content area keeps the same width; no reflow flash when the new screen's scroll position differs | Confirm. |
| Back navigation | Returning to a previous screen restores its scroll position, not the top | Confirm on History and Downloads, which have lists. |
| No flash | No white or theme-background frame between screens | This is the same class of bug as the full-player flash. Confirm on a cold start too, not just in-app navigation. |

**Why a human:** a one-frame leftover is invisible to semantics assertions and
easy to miss even in a pixel test unless you know to sample the exact frame.

---

## 6. Focus-ring visibility

**Where:** `Tab` / `Shift+Tab` through the sidebar, mini player controls, and
settings rows. Then click a control with the mouse and `Tab` away.

**Look at:** whether keyboard focus is always visible, and whether mouse clicks
leave a ring behind.

| | Expected | Known current behaviour |
|---|---|---|
| Keyboard focus | A clearly visible focus ring on whatever has focus | Confirm in both themes. The dark theme has less contrast to work with. |
| Mouse click | No focus ring left behind after a click | **Known regression class.** Commit `810e97f` ("suppress the focus ring that appeared after clicking") — confirm it has not regressed for any control, including the ones added since. |
| No dark halo | The ring is a ring, not a filled dark disc | The previous implementation drew the ring as a shadow, which read as a dark halo. Confirm the current ring does not do that. |
| Contrast | The ring is distinguishable from both the resting surface and the hover state | Confirm in Dark specifically. |

**Why a human:** whether an indicator is *legible* against a given background is
a contrast judgement, and no assertion here measures perceived contrast.

---

## What the tests already cover

Do not re-check these by hand as a substitute for fixing a failing test — they
are automated, and they are the reason the list above is short.

- **Structure**: node present in the semantics tree, composition counts,
  enabled/disabled state, text and content descriptions — `FullPlayerOverlayTest`,
  `AppGUITest`, `DiscoverScreenTest`, `DownloadsScreenTest`, `SettingsScreenTest`.
- **Occlusion and paint order**: whether content is actually *visible* or is
  covered by an opaque container — `OverlayPixelTest`. This reads the rendered
  pixels; the notes claiming Compose Desktop's ui-test has no `captureToImage`
  are wrong for Compose Multiplatform 1.9.0.
- **Animation frames**: whether the full-player enter transition starts on the
  screen behind and covers the area progressively instead of cutting to a blank
  page, and whether the panel stays opaque while it travels —
  `OverlayPixelTest`. Frozen clock, 16 ms steps. This animates the real
  `FullPlayerOverlay` composable rather than a copy of its shape, so it cannot
  pass while the app's own transition is broken; each of the three defects that
  have shipped has been verified to fail it.
- **Geometry**: that the overlay's bounds equal the area it is meant to cover —
  `OverlayPixelTest`. Explicitly weaker than pixels: bounds say nothing about
  what was painted on top.
- **Palette values**: opacity and scheme constants — `ThemePreferenceTest`.

## Known gap: goldens exist but are not wired up

Compose Multiplatform 1.9.0 also ships `DesktopScreenshotTestRule`, which
diffs rendered frames against checked-in PNGs (SSIM, default threshold 0.96;
missing goldens write an `_actual.png` next to them and fail the test). It is
verified working in this repo but **no golden tests are committed**, because
every pixel captured here would need a reviewed reference image — and a
reference image nobody looked at is worse than no test at all. If the team
wants them, the right first step is to agree on which screens are worth locking
down and to have a human approve the first set of goldens.