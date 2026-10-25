package app.podara.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.podara.component.EpisodeActionIconButton
import app.podara.component.ToolbarPillButton
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.theme.ThemePreference
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A1 mockup generator: filled vs outlined icons, one above the other, for a
 * human to judge. This is NOT the migration and asserts no design truth —
 * DESIGN-CRAFT.md §8 puts A1 at "待方案（先看 mockup）", so this test exists to
 * produce that mockup and nothing more.
 *
 * What it renders, per theme (light → mockups/icons-light.png, dark →
 * mockups/icons-dark.png), every surface shown twice: a top row of
 * Icons.Default.* labelled 「现状 · 实心」 and a bottom row of Icons.Outlined.*
 * labelled 「提案 · 描线」:
 *
 *   - 侧栏导航行 ×6, one row in the selected state (white pill via
 *     DesignTokens.Navigation.ActiveGlass + IconInk.Active), the rest resting.
 *   - 工具条胶囊 ×3 — the real [ToolbarPillButton] with labels.
 *   - 单集行内操作（收藏 / 加入队列 / 下载）— the real [EpisodeActionIconButton].
 *   - 播放 / 暂停圆钮 — the recorded exception: both rows stay filled, with the
 *     side note 「例外：播放/暂停保留实心」 (DESIGN-CRAFT.md §2 A1 risk note).
 *
 * Icon greys follow the A2 rule and nothing is re-coloured here: the nav rows
 * use DesignTokens.IconInk.Resting / Active directly, and the two reused
 * components resolve their own scheme-dependent inks.
 *
 * Component reuse vs re-drawing, honestly stated:
 *
 *   - [ToolbarPillButton] and [EpisodeActionIconButton] take an ImageVector
 *     parameter, so the outlined rows pass Icons.Outlined.* into the real
 *     components unmodified.
 *   - The sidebar nav row is private inside App.kt's private Sidebar, so it is
 *     re-drawn here from the same tokens (Sidebar.NavItemHeight/NavIconSize/
 *     NavTextSize, Navigation.ActiveGlass.*, IconInk), same geometry (48dp row,
 *     20dp icon, 10dp gap) and same label greys (textPrimary when active,
 *     textSecondary otherwise). Laid out as a horizontal strip to fit the
 *     comparison canvas; the row itself is unchanged.
 *   - The 56dp play disc is inline and private in PlayerUI.kt, so it is
 *     re-drawn from the same recipe: DesignTokens.MiniPlayer.PlayPause.Size,
 *     surfaces.buttonFill disc, surfaces.buttonShadow elevation, glyph at
 *     PlayPause.IconSize with the play triangle's recorded 6% rightward nudge.
 *
 * Capturing follows the OverlayPixelTest pattern: setContent → waitForIdle →
 * onNodeWithTag(tag).captureToImage(). The desktop test scene is 1024×768 at
 * density 1 (read out of DesktopComposeUiTest's defaults), taller than the
 * whole comparison, so each section is captured on its own setContent and the
 * sections are stacked into one PNG with a Skia canvas afterwards.
 *
 * Assertions are plumbing, not aesthetics — a PNG that is written, large
 * enough, and not blank. Whether the outlined set actually reads better is
 * exactly the human judgement this file exists to enable (VISUAL-CHECKS.md).
 */
class IconsContrastMockupTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Canvas plan ──

    /** One mockup canvas is this wide; each section is captured at this width. */
    private val CanvasWidthDp = 640.dp

    /** Gap painted between sections when the PNG is assembled. */
    private val SectionGapDp = 16.dp

    private val SectionHorizontalPaddingDp = 20.dp

    private val MinPngBytes = 10_000L

    /** Sampling stride and per-channel tolerance for the non-blank ink check. */
    private val InkStride = 3
    private val InkTolerance = 6f / 255f

    private val TagHeader = "mock_header"
    private val TagNav = "mock_nav"
    private val TagPills = "mock_pills"
    private val TagActions = "mock_actions"
    private val TagPlay = "mock_play"

    /** Tags on the last nav item of each strip, so overflow can be asserted. */
    private val TagNavSolidLast = "mock_nav_solid_last"
    private val TagNavOutlineLast = "mock_nav_outline_last"

    // ── The two tests: one PNG per theme ──

    @Test
    fun lightMockupWritesIconsLightPng() {
        assertMockupRendered(ThemePreference.Light, "icons-light.png")
    }

    @Test
    fun darkMockupWritesIconsDarkPng() {
        assertMockupRendered(ThemePreference.Dark, "icons-dark.png")
    }

    // ── Capture, assemble, write, verify ──

    private fun assertMockupRendered(preference: ThemePreference, fileName: String) {
        // Header first: its captured pixel width fixes the px-per-dp scale the
        // rest of the plan is asserted against.
        val header = captureSection(preference, TagHeader)
        val scale = header.width.toFloat() / CanvasWidthDp.value
        assertTrue(
            "the captured canvas is ${header.width}px wide for ${CanvasWidthDp.value}dp; " +
                "the test scene is not rendering at a usable size",
            header.width >= 512
        )

        val nav = captureSection(preference, TagNav)
        assertNavStripsFitInsideTheCanvas(scale)

        val pills = captureSection(preference, TagPills)
        val actions = captureSection(preference, TagActions)
        val play = captureSection(preference, TagPlay)
        val sections = listOf(header, nav, pills, actions, play)

        // Non-blank, section by section. A capture that came back as bare
        // background (theme not applied, icons not drawn) fails here rather
        // than shipping a mockup of empty paper.
        sections.forEachIndexed { index, bitmap ->
            val ink = inkSamples(bitmap)
            assertTrue(
                "section #$index rendered only ${bitmap.width}x${bitmap.height} with $ink ink samples; " +
                    "a real section paints far more than that",
                bitmap.width > 0 && bitmap.height > 0 && ink >= 40
            )
        }

        val file = assembleAndSave(sections, scale, fileName)

        // Byte threshold: a blank canvas of this size compresses to a few KB;
        // text + glyphs keep it well above this floor.
        assertTrue(
            "mockups/$fileName is ${file.length()} bytes, below the non-blank floor of $MinPngBytes",
            file.length() > MinPngBytes
        )

        // Size assertions: decode the written file back and match it against
        // the assembled plan exactly.
        val decoded = Image.makeFromEncoded(file.readBytes())
        val expectedWidth = sections.first().width
        val expectedHeight = sections.sumOf { it.height } +
            (SectionGapDp.value * scale).roundToInt() * (sections.size - 1)
        assertEquals("mockups/$fileName PNG width", expectedWidth, decoded.width)
        assertEquals("mockups/$fileName PNG height", expectedHeight, decoded.height)
        assertTrue(
            "mockups/$fileName came out ${decoded.width}x${decoded.height}; " +
                "it should hold all five sections",
            decoded.width >= 512 && decoded.height >= 640
        )
    }

    /**
     * Renders one section alone and captures it. Sections are set one per
     * setContent so nothing can be clipped by the fixed 1024×768 test scene.
     */
    private fun captureSection(preference: ThemePreference, tag: String): ImageBitmap {
        composeTestRule.setContent {
            PodaraTheme(preference = preference) {
                Box(Modifier.width(CanvasWidthDp)) {
                    when (tag) {
                        TagHeader -> HeaderSection()
                        TagNav -> NavSection()
                        TagPills -> ToolbarPillsSection()
                        TagActions -> EpisodeActionsSection()
                        TagPlay -> PlayPauseSection()
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        return composeTestRule.onNodeWithTag(tag).captureToImage()
    }

    /** The nav strips are the widest content; overflow here would silently clip icons. */
    private fun assertNavStripsFitInsideTheCanvas(scale: Float) {
        val section = composeTestRule.onNodeWithTag(TagNav).fetchSemanticsNode().boundsInRoot
        val rightInsetPx = SectionHorizontalPaddingDp.value * scale
        listOf(TagNavSolidLast, TagNavOutlineLast).forEach { tag ->
            val last = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$tag ends at ${last.right} but the canvas content ends at ${section.right - rightInsetPx}; " +
                    "the nav strip overflows and its icons would be clipped from the mockup",
                last.right <= section.right - rightInsetPx + 0.5f
            )
        }
    }

    /** Counts sampled pixels that differ from the section's own background. */
    private fun inkSamples(bitmap: ImageBitmap): Int {
        val pixels = bitmap.toPixelMap()
        val background = pixels[2, 2]
        var count = 0
        for (y in 0 until pixels.height step InkStride) {
            for (x in 0 until pixels.width step InkStride) {
                val c = pixels[x, y]
                if (abs(c.red - background.red) > InkTolerance ||
                    abs(c.green - background.green) > InkTolerance ||
                    abs(c.blue - background.blue) > InkTolerance
                ) {
                    count++
                }
            }
        }
        return count
    }

    /** Stacks the section captures vertically into one PNG under mockups/. */
    private fun assembleAndSave(sections: List<ImageBitmap>, scale: Float, fileName: String): File {
        val widthPx = sections.first().width
        val heightPx = sections.sumOf { it.height } +
            (SectionGapDp.value * scale).roundToInt() * (sections.size - 1)

        val out = Bitmap()
        out.allocN32Pixels(widthPx, heightPx)
        val canvas = Canvas(out)

        // The paper colour comes from the header capture itself, so the gaps
        // match the theme whatever the tokens resolve to.
        val paper = sections.first().toPixelMap()[2, 2]
        canvas.clear(Color(paper.red, paper.green, paper.blue).toArgb())

        var y = 0f
        sections.forEach { bitmap ->
            canvas.drawImage(Image.makeFromBitmap(bitmap.asSkiaBitmap()), 0f, y)
            y += bitmap.height + (SectionGapDp.value * scale).roundToInt()
        }

        val encoded = Image.makeFromBitmap(out).encodeToData(EncodedImageFormat.PNG)
            ?: error("Skia returned no PNG data for $fileName")
        val file = File(repoRoot(), "mockups/$fileName")
        file.parentFile.mkdirs()
        file.writeBytes(encoded.bytes)
        return file
    }

    /**
     * Gradle's test working directory is the module dir; the mockups/ output
     * belongs to the repo root, so walk up to the build's settings file.
     */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir ?: File(System.getProperty("user.dir"))
    }

    // ── Sections ──

    @Composable
    private fun HeaderSection() {
        val colors = PodaraTheme.colors
        SectionColumn(TagHeader, colors.background, verticalPadding = 16.dp) {
            Text(
                text = "Podara 图标对比：现状实心 vs 提案描线",
                color = colors.textPrimary,
                fontSize = DesignTokens.TypeScale.Heading,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "A1 mockup · 仅供人眼拍板，不做迁移 · 图标灰度遵循 A2 IconInk（Resting / Active）",
                color = colors.textMuted,
                fontSize = DesignTokens.TypeScale.Caption
            )
            Text(
                text = "除播放 / 暂停外，每个表面上行＝现状 Icons.Default（实心），下行＝提案 Icons.Outlined（描线）",
                color = colors.textSecondary,
                fontSize = DesignTokens.TypeScale.Caption
            )
        }
    }

    @Composable
    private fun NavSection() {
        // The real sidebar is a transparent strip over the shell; its fill is
        // what the rows actually sit on, so the section uses that, not paper.
        MockSection(
            tag = TagNav,
            background = PodaraTheme.surfaces.sidebarFill,
            title = "侧栏导航行 ×6",
            note = "选中态：白药丸 + IconInk.Active，其余 IconInk.Resting"
        ) {
            VariantRow("现状 · 实心") {
                NavEntries.forEachIndexed { index, entry ->
                    NavRowItem(
                        icon = entry.filled,
                        label = entry.label,
                        active = index == ActiveNavIndex,
                        testTag = if (index == NavEntries.lastIndex) TagNavSolidLast else null
                    )
                }
            }
            VariantRow("提案 · 描线") {
                NavEntries.forEachIndexed { index, entry ->
                    NavRowItem(
                        icon = entry.outlined,
                        label = entry.label,
                        active = index == ActiveNavIndex,
                        testTag = if (index == NavEntries.lastIndex) TagNavOutlineLast else null
                    )
                }
            }
        }
    }

    @Composable
    private fun ToolbarPillsSection() {
        MockSection(TagPills, PodaraTheme.colors.background, "工具条胶囊 ×3", "ToolbarPillButton 带标签形态，真实组件") {
            VariantRow("现状 · 实心") {
                ToolbarPillButton(icon = Icons.Default.Add, label = "添加播客", contentDescription = "添加播客", onClick = {})
                ToolbarPillButton(icon = Icons.Default.Edit, label = "管理", contentDescription = "管理", onClick = {})
                ToolbarPillButton(icon = Icons.Default.SelectAll, label = "全选", contentDescription = "全选", onClick = {})
            }
            VariantRow("提案 · 描线") {
                ToolbarPillButton(icon = Icons.Outlined.Add, label = "添加播客", contentDescription = "添加播客", onClick = {})
                ToolbarPillButton(icon = Icons.Outlined.Edit, label = "管理", contentDescription = "管理", onClick = {})
                ToolbarPillButton(icon = Icons.Outlined.SelectAll, label = "全选", contentDescription = "全选", onClick = {})
            }
        }
    }

    @Composable
    private fun EpisodeActionsSection() {
        MockSection(TagActions, PodaraTheme.colors.background, "单集行内操作", "收藏 / 加入队列 / 下载，EpisodeActionIconButton 真实组件") {
            VariantRow("现状 · 实心") {
                EpisodeActionIconButton(icon = Icons.Default.Favorite, contentDescription = "收藏", onClick = {})
                EpisodeActionIconButton(icon = Icons.Default.PlaylistAdd, contentDescription = "加入队列", onClick = {})
                EpisodeActionIconButton(icon = Icons.Default.Download, contentDescription = "下载", onClick = {})
            }
            VariantRow("提案 · 描线") {
                EpisodeActionIconButton(icon = Icons.Outlined.Favorite, contentDescription = "收藏", onClick = {})
                EpisodeActionIconButton(icon = Icons.Outlined.PlaylistAdd, contentDescription = "加入队列", onClick = {})
                EpisodeActionIconButton(icon = Icons.Outlined.Download, contentDescription = "下载", onClick = {})
            }
        }
    }

    /**
     * The recorded exception (DESIGN-CRAFT.md §2 A1): PlayArrow / Pause drawn
     * as outlines would read smaller on the disc, so both rows stay filled and
     * the note says so.
     */
    @Composable
    private fun PlayPauseSection() {
        MockSection(TagPlay, PodaraTheme.colors.background, "播放 / 暂停圆钮", "例外：播放/暂停保留实心") {
            VariantRow("播放 · 实心") {
                PlayDisc(icon = Icons.Default.PlayArrow, nudgeRight = true)
            }
            VariantRow("暂停 · 实心") {
                PlayDisc(icon = Icons.Default.Pause, nudgeRight = false)
            }
        }
    }

    // ── Re-drawn pieces (private in desktopMain, same tokens) ──

    /** One sidebar nav row, re-drawn from App.kt's private Sidebar recipe. */
    @Composable
    private fun NavRowItem(icon: ImageVector, label: String, active: Boolean, testTag: String?) {
        val colors = PodaraTheme.colors
        val sidebar = DesignTokens.Sidebar
        val glass = DesignTokens.Navigation.ActiveGlass
        val shape = RoundedCornerShape(glass.Radius)
        val iconTint = if (active) DesignTokens.IconInk.Active else DesignTokens.IconInk.Resting

        Box(
            modifier = Modifier
                .height(sidebar.NavItemHeight)
                .let { mod ->
                    if (active) {
                        // The active pill, exactly as App.kt lays it: shadow,
                        // gradient hairline, clip, white base.
                        mod.shadow(glass.ShadowElevation, shape, ambientColor = glass.ShadowColor, spotColor = glass.ShadowColor)
                            .border(glass.BorderWidth, glass.Border, shape)
                            .clip(shape)
                            .background(glass.BaseColor)
                    } else {
                        mod
                    }
                }
                .padding(horizontal = glass.InnerPaddingHorizontal)
                .let { mod -> if (testTag != null) mod.testTag(testTag) else mod },
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(sidebar.NavIconSize))
                Text(
                    text = label,
                    color = if (active) colors.textPrimary else colors.textSecondary,
                    fontSize = sidebar.NavTextSize
                )
            }
        }
    }

    /** The 56dp play disc, re-drawn from PlayerUI.kt's inline full-player button. */
    @Composable
    private fun PlayDisc(icon: ImageVector, nudgeRight: Boolean) {
        val surfaces = PodaraTheme.surfaces
        val glyphSize = DesignTokens.MiniPlayer.PlayPause.IconSize
        Box(
            modifier = Modifier
                .size(DesignTokens.MiniPlayer.PlayPause.Size)
                .shadow(DesignTokens.Elevation.Raised, CircleShape, ambientColor = surfaces.buttonShadow, spotColor = surfaces.buttonShadow)
                .clip(CircleShape)
                .background(surfaces.buttonFill),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = surfaces.buttonIcon,
                modifier = Modifier
                    .size(glyphSize)
                    // The play triangle's recorded optical nudge; pause is
                    // symmetric and gets none (PlayerUI.kt PlayPauseGlyph).
                    .offset(x = if (nudgeRight) glyphSize * 0.06f else 0.dp)
            )
        }
    }

    // ── Layout helpers ──

    private val ActiveNavIndex = 1 // 订阅, mirroring a typical selected screen

    private data class NavEntry(val filled: ImageVector, val outlined: ImageVector, val label: String)

    /**
     * The six sidebar rows, mirroring App.kt's navItems (Explore / LibraryMusic /
     * Favorite / History / Folder) plus its settings row. History is already
     * the A3-corrected glyph; both families carry it.
     */
    private val NavEntries = listOf(
        NavEntry(Icons.Default.Explore, Icons.Outlined.Explore, "发现"),
        NavEntry(Icons.Default.LibraryMusic, Icons.Outlined.LibraryMusic, "订阅"),
        NavEntry(Icons.Default.Favorite, Icons.Outlined.Favorite, "收藏"),
        NavEntry(Icons.Default.History, Icons.Outlined.History, "历史"),
        NavEntry(Icons.Default.Folder, Icons.Outlined.Folder, "下载"),
        NavEntry(Icons.Default.Settings, Icons.Outlined.Settings, "设置")
    )

    @Composable
    private fun SectionColumn(
        tag: String,
        background: Color,
        verticalPadding: androidx.compose.ui.unit.Dp,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column(
            modifier = Modifier
                .width(CanvasWidthDp)
                .background(background)
                .padding(horizontal = SectionHorizontalPaddingDp, vertical = verticalPadding)
                .testTag(tag),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }

    @Composable
    private fun MockSection(
        tag: String,
        background: Color,
        title: String,
        note: String?,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val colors = PodaraTheme.colors
        SectionColumn(tag, background, verticalPadding = 14.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = DesignTokens.TypeScale.Body,
                    fontWeight = FontWeight.Medium
                )
                note?.let {
                    Text(text = it, color = colors.textMuted, fontSize = DesignTokens.TypeScale.Caption)
                }
            }
            content()
        }
    }

    /** 「现状 · 实心」 / 「提案 · 描线」 caption above one variant's row. */
    @Composable
    private fun VariantRow(caption: String, content: @Composable RowScope.() -> Unit) {
        val colors = PodaraTheme.colors
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = caption, color = colors.textMuted, fontSize = DesignTokens.TypeScale.Caption)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}
