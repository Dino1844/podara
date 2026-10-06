package app.podara.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podara.component.PodaraDropdownMenu
import app.podara.component.PodaraDropdownMenuItem
import app.podara.component.PodaraDialog
import app.podara.component.PodaraDialogActionButton
import app.podara.component.PodaraDialogActionStyle
import app.podara.component.PodaraDialogBody
import app.podara.component.PodaraDialogSize
import app.podara.component.PodaraDialogTitle
import app.podara.component.ToolbarPillButton
import app.podara.data.AppDatabase
import app.podara.manager.ExportManager
import app.podara.manager.ImportManager
import app.podara.manager.ImportResult
import app.podara.theme.DesignTokens
import app.podara.theme.PodaraTheme
import app.podara.theme.PodaraColors
import app.podara.theme.ThemePreference
import app.podara.util.Logger
import app.podara.util.animateHoverBackgroundColor
import app.podara.util.Settings
import app.podara.util.Strings
import app.podara.util.clickableWithoutIndication
import app.podara.util.clickableWithoutIndicationOrFocusRing
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    database: AppDatabase,
    onBack: () -> Unit,
    onDownloadPathChanged: ((String) -> Unit)? = null,
    themePreference: ThemePreference = ThemePreference.System,
    onThemeChanged: ((ThemePreference) -> Unit)? = null,
    downloadSpeedLimitKbps: Int = 0,
    onDownloadSpeedLimitChanged: ((Int) -> Unit)? = null
) {
    val colors = PodaraTheme.colors
    val exportManager = remember { ExportManager(database) }
    val importManager = remember { ImportManager(database) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    var showExportDialog by remember { mutableStateOf(false) }
    var exportedOpml by remember { mutableStateOf<String?>(null) }
    var showCopiedSnackbar by remember { mutableStateOf(false) }
    var showImportResult by remember { mutableStateOf<ImportResult?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var downloadPath by remember { mutableStateOf(Settings.getDownloadPath()) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var localSpeedLimitInput by remember { mutableStateOf("") }
    var showSpeedLimitDialog by remember { mutableStateOf(false) }
    var speedLimitError by remember { mutableStateOf<String?>(null) }
    // ── Close behavior dialog state ──
    var showCloseBehaviorDialog by remember { mutableStateOf(false) }
    // Shared by the speed-limit row click and its Change button: both open the
    // same editor, and both start from the current value.
    val openSpeedLimitDialog = {
        localSpeedLimitInput = downloadSpeedLimitKbps.toString()
        speedLimitError = null
        showSpeedLimitDialog = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(top = 28.dp, start = 32.dp, end = 32.dp, bottom = 8.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // ── Page header ──
        Text(
            text = Strings["settings_title"],
            fontSize = DesignTokens.PageHeader.TitleSize,
            fontWeight = FontWeight.Bold,
            fontFamily = DesignTokens.TypeFamily.PageTitle,
            color = colors.textPrimary
        )

        Spacer(Modifier.height(24.dp))

        // ── Scrollable settings content ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ── Language section ──
            SettingsSection(title = Strings["settings_language"], colors = colors) {
                SettingsRow(
                    icon = Icons.Default.Language,
                    title = Strings["settings_language"],
                    // Read from Strings.currentLanguage rather than a local
                    // copy, so the label follows the shared snapshot state and
                    // every other screen updates with it.
                    subtitle = if (Strings.currentLanguage == "zh") "简体中文" else "English",
                    action = {
                        LanguageSelector(
                            selectedLanguage = Strings.currentLanguage,
                            expanded = showLanguageMenu,
                            onExpandedChange = { showLanguageMenu = it },
                            onLanguageSelected = { selected -> Strings.updateLanguage(selected) },
                            onChange = { Settings.setLanguage(Strings.currentLanguage) }
                        )
                    },
                    colors = colors
                )
            }

            Spacer(Modifier.height(DesignTokens.Spacing.sm))

            // ── Data section ──
            SettingsSection(title = Strings["settings_data"], colors = colors) {
                SettingsRow(
                    icon = Icons.Default.FileDownload,
                    title = Strings["settings_export_opml"],
                    subtitle = Strings["settings_export_opml_desc"],
                    action = {
                        SettingsActionButton(
                            text = Strings["settings_export"],
                            color = colors.accent,
                            onClick = {
                                scope.launch {
                                    exportedOpml = exportManager.exportOpml()
                                    showExportDialog = true
                                }
                            }
                        )
                    },
                    colors = colors
                )

                SettingsRow(
                    icon = Icons.Default.FileUpload,
                    title = Strings["settings_import_opml"],
                    subtitle = Strings["settings_import_opml_desc"],
                    action = {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.accent
                            )
                        } else {
                            SettingsActionButton(
                                text = Strings["settings_import"],
                                color = colors.accent,
                                onClick = {
                                    scope.launch {
                                        isImporting = true
                                        try {
                                            val file = openFilePicker("Select OPML File", "opml", "xml")
                                            if (file != null) {
                                                val content = file.readText()
                                                val result = importManager.importOpml(content)
                                                showImportResult = result
                                            }
                                        } finally {
                                            isImporting = false
                                        }
                                    }
                                }
                            )
                        }
                    },
                    colors = colors
                )
            }

            Spacer(Modifier.height(DesignTokens.Spacing.sm))

            // ── Downloads section ──
            SettingsSection(title = Strings["settings_downloads"], colors = colors) {
                SettingsRow(
                    icon = Icons.Default.Folder,
                    title = Strings["settings_download_location"],
                    subtitle = downloadPath,
                    action = {
                        SettingsActionButton(
                            text = Strings["settings_change"],
                            color = colors.accent,
                            onClick = {
                                val dir = openDirectoryPicker("Select Download Folder")
                                if (dir != null) {
                                    Settings.setDownloadPath(dir)
                                    downloadPath = dir
                                    onDownloadPathChanged?.invoke(dir)
                                    Logger.i("Settings", "Download path changed to: $dir")
                                }
                            }
                        )
                    },
                    colors = colors
                )

                if (downloadPath != java.io.File(System.getProperty("user.home"), ".podara/downloads").absolutePath) {
                    SettingsActionText(
                        text = Strings["settings_reset_default"],
                        color = colors.textSecondary,
                        onClick = {
                            Settings.resetDownloadPath()
                            downloadPath = Settings.getDownloadPath()
                            onDownloadPathChanged?.invoke(downloadPath)
                            Logger.i("Settings", "Download path reset to default")
                        },
                        modifier = Modifier.padding(start = 64.dp)
                    )
                }

                Spacer(Modifier.height(DesignTokens.Spacing.xs))

                SettingsRow(
                    icon = Icons.Default.Star,
                    title = Strings["settings_download_speed_limit"],
                    subtitle = if (downloadSpeedLimitKbps <= 0) {
                        Strings["settings_download_speed_unlimited"]
                    } else {
                        "$downloadSpeedLimitKbps ${Strings["settings_download_speed_unit"]}"
                    },
                    action = {
                        SettingsActionButton(
                            text = Strings["settings_change"],
                            color = colors.accent,
                            onClick = openSpeedLimitDialog
                        )
                    },
                    colors = colors,
                    // The row only exists to open the editor, so the row itself
                    // is clickable too — the standard settings-row pattern.
                    onClick = openSpeedLimitDialog
                )
            }

            Spacer(Modifier.height(DesignTokens.Spacing.sm))

            // ── Appearance section ──
            SettingsSection(title = Strings["settings_appearance"], colors = colors) {
                SettingsRow(
                    icon = Icons.Default.DarkMode,
                    title = Strings["settings_theme"],
                    subtitle = when (themePreference) {
                        ThemePreference.System -> Strings["settings_theme_system"]
                        ThemePreference.Light -> Strings["settings_theme_light"]
                        ThemePreference.Dark -> Strings["settings_theme_dark"]
                    },
                    action = {
                        ThemeSelector(
                            selected = themePreference,
                            onSelected = { preference -> onThemeChanged?.invoke(preference) }
                        )
                    },
                    colors = colors
                )
            }

            Spacer(Modifier.height(DesignTokens.Spacing.sm))

            // ── Close Behavior section ──
            SettingsSection(title = Strings["settings_close_behavior"], colors = colors) {
                SettingsRow(
                    icon = Icons.Default.ExitToApp,
                    title = Strings["settings_close_behavior"],
                    subtitle = when (Settings.getCloseAction()) {
                        "quit" -> Strings["settings_close_quit"]
                        "minimize_to_tray" -> Strings["settings_close_minimize_tray"]
                        else -> Strings["settings_close_ask"]
                    },
                    action = {
                        SettingsActionButton(
                            text = Strings["settings_change"],
                            color = colors.accent,
                            onClick = { showCloseBehaviorDialog = true }
                        )
                    },
                    colors = colors,
                    // Same behavior as the Change button: clicking the row
                    // opens the dialog.
                    onClick = { showCloseBehaviorDialog = true }
                )
            }

            Spacer(Modifier.height(DesignTokens.Spacing.sm))

            // ── About section ──
            SettingsSection(title = Strings["settings_about"], colors = colors) {
                Text(
                    text = Strings["settings_about_desc"],
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = Strings.get("settings_version", "0.1.0"),
                    fontSize = 12.sp,
                    color = colors.textMuted
                )
            }
        }
    }

    // ── Download speed limit dialog ──
    if (showSpeedLimitDialog) {
        PodaraDialog(
            onDismissRequest = { showSpeedLimitDialog = false; speedLimitError = null },
            title = { PodaraDialogTitle(Strings["settings_download_speed_limit"], textAlign = androidx.compose.ui.text.style.TextAlign.Start) },
            content = {
                Column {
                    PodaraDialogBody(Strings["settings_download_speed_hint"])
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = localSpeedLimitInput,
                        onValueChange = { localSpeedLimitInput = it; speedLimitError = null },
                        label = { Text(Strings["settings_download_speed_unit"]) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = speedLimitError != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor = colors.accent,
                            focusedLabelColor = colors.accent,
                            unfocusedLabelColor = colors.textSecondary,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            errorTextColor = colors.danger,
                            errorBorderColor = colors.danger,
                            errorLabelColor = colors.danger
                        )
                    )
                    speedLimitError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(text = it, color = colors.danger, fontSize = 12.sp)
                    }
                }
            },
            actions = {
                PodaraDialogActionButton(
                    label = Strings["dialog_cancel"],
                    onClick = { showSpeedLimitDialog = false; speedLimitError = null },
                    style = PodaraDialogActionStyle.Secondary
                )
                PodaraDialogActionButton(
                    label = Strings["dialog_ok"],
                    onClick = {
                        val trimmed = localSpeedLimitInput.trim()
                        val parsed = trimmed.toIntOrNull()
                        if (parsed == null || parsed < 0 || trimmed != parsed.toString()) {
                            speedLimitError = Strings["settings_download_speed_error"]
                        } else {
                            Settings.setDownloadSpeedLimitKbps(parsed)
                            onDownloadSpeedLimitChanged?.invoke(parsed)
                            showSpeedLimitDialog = false
                            speedLimitError = null
                        }
                    },
                    style = PodaraDialogActionStyle.Primary
                )
            }
        )
    }

    // ── Close behavior dialog ──
    if (showCloseBehaviorDialog) {
        var selectedAction by remember { mutableStateOf(Settings.getCloseAction()) }
        PodaraDialog(
            onDismissRequest = { showCloseBehaviorDialog = false },
            title = { PodaraDialogTitle(Strings["settings_close_behavior"], textAlign = androidx.compose.ui.text.style.TextAlign.Start) },
            content = {
                Column {
                    PodaraDialogBody(Strings["settings_close_behavior_desc"])
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "ask" to Strings["settings_close_ask"],
                        "quit" to Strings["settings_close_quit"],
                        "minimize_to_tray" to Strings["settings_close_minimize_tray"]
                    ).forEach { (action, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(44.dp).clickableWithoutIndication { selectedAction = action },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedAction == action,
                                onClick = { selectedAction = action },
                                colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.textSecondary)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp, color = colors.textPrimary)
                        }
                    }
                }
            },
            actions = {
                PodaraDialogActionButton(Strings["dialog_cancel"], { showCloseBehaviorDialog = false }, PodaraDialogActionStyle.Secondary)
                PodaraDialogActionButton(Strings["dialog_ok"], {
                    Settings.setCloseAction(selectedAction)
                    showCloseBehaviorDialog = false
                }, PodaraDialogActionStyle.Primary)
            }
        )
    }

    // ── Export dialog ──
    if (showExportDialog && exportedOpml != null) {
        PodaraDialog(
            onDismissRequest = { showExportDialog = false },
            size = PodaraDialogSize.Wide,
            title = { PodaraDialogTitle(Strings["opml_export_title"], textAlign = androidx.compose.ui.text.style.TextAlign.Start) },
            content = {
                Column {
                    PodaraDialogBody(Strings["opml_export_copy_hint"])
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportedOpml!!,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = DesignTokens.Dialog.Container.ScrollableContentMaxHeight),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.border,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                }
            },
            actions = {
                PodaraDialogActionButton(Strings["dialog_close"], { showExportDialog = false }, PodaraDialogActionStyle.Secondary)
                PodaraDialogActionButton(Strings["dialog_copy_to_clipboard"], {
                    clipboardManager.setText(AnnotatedString(exportedOpml!!))
                    showExportDialog = false
                    showCopiedSnackbar = true
                }, PodaraDialogActionStyle.Primary)
            }
        )
    }

    if (showCopiedSnackbar) {
        Snackbar(
            modifier = Modifier.padding(16.dp),
            containerColor = colors.surface,
            contentColor = colors.textPrimary,
            action = {
                TextButton(onClick = { showCopiedSnackbar = false }) {
                    Text(Strings["dialog_ok"], color = colors.accent)
                }
            }
        ) {
            Text(Strings["opml_export_copied"])
        }
    }

    // ── Import result dialog ──
    showImportResult?.let { result ->
        when (result) {
            is ImportResult.Success -> {
                PodaraDialog(
                    onDismissRequest = { showImportResult = null },
                    size = PodaraDialogSize.Wide,
                    title = { PodaraDialogTitle(Strings["opml_import_title"], textAlign = androidx.compose.ui.text.style.TextAlign.Start) },
                    content = {
                        Column(
                            modifier = Modifier.heightIn(max = DesignTokens.Dialog.Container.ScrollableContentMaxHeight).verticalScroll(rememberScrollState())
                        ) {
                            PodaraDialogBody(Strings.get("opml_import_added", result.added))
                            PodaraDialogBody(Strings.get("opml_import_skipped", result.skipped))
                            if (result.failed > 0) {
                                PodaraDialogBody(
                                    Strings.get("opml_import_failed", result.failed),
                                    color = colors.danger
                                )
                                result.errors.forEach { error ->
                                    Text(text = "  - $error", color = colors.danger, fontSize = 12.sp)
                                }
                            }
                        }
                    },
                    actions = {
                        PodaraDialogActionButton(Strings["dialog_ok"], { showImportResult = null }, PodaraDialogActionStyle.Primary)
                    }
                )
            }
            is ImportResult.Error -> {
                PodaraDialog(
                    onDismissRequest = { showImportResult = null },
                    title = { PodaraDialogTitle(Strings["opml_import_error"], textAlign = androidx.compose.ui.text.style.TextAlign.Start) },
                    content = { PodaraDialogBody(result.message) },
                    actions = {
                        PodaraDialogActionButton(Strings["dialog_ok"], { showImportResult = null }, PodaraDialogActionStyle.Primary)
                    }
                )
            }
        }
    }
}

// ── Reusable card section ──
@Composable
private fun SettingsSection(
    title: String,
    colors: PodaraColors,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val surfaces = PodaraTheme.surfaces

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        // Apple groups settings in bordered sections on a flat page rather than
        // relying on elevation alone.
        border = BorderStroke(DesignTokens.Border.Width, surfaces.cardBorder)
    ) {
        Column(modifier = Modifier.padding(horizontal = DesignTokens.Spacing.lg, vertical = 18.dp)) {
            SettingsSectionHeader(title)
            Spacer(Modifier.height(DesignTokens.Spacing.sm))
            content()
        }
    }
}

// ── Reusable section header ──
@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = DesignTokens.SectionHeader.TitleSize,
        fontWeight = FontWeight.SemiBold,
        fontFamily = DesignTokens.TypeFamily.PageTitle,
        color = PodaraTheme.colors.textPrimary
    )
}

// ── Language selection controls ──
@Composable
private fun LanguageSelector(
    selectedLanguage: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onChange: () -> Unit
) {
    val colors = PodaraTheme.colors
    val toolbarButton = DesignTokens.ToolbarButton
    val selectedLabel = if (selectedLanguage == "zh") "简体中文" else "English"

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            val interactionSource = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(toolbarButton.PillRadius)
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .widthIn(min = 108.dp)
                    .clip(shape)
                    .background(if (expanded) toolbarButton.PillSelectedBackgroundColor else toolbarButton.PillSortBackgroundColor)
                    .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)))
                    .clickableWithoutIndicationOrFocusRing(interactionSource = interactionSource) { onExpandedChange(true) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(toolbarButton.PillIconTextGap)
                ) {
                    Text(
                        text = selectedLabel,
                        color = if (expanded) toolbarButton.PillSelectedTextColor else colors.textPrimary,
                        fontSize = toolbarButton.PillTextSize,
                        lineHeight = toolbarButton.PillLineHeight,
                        fontWeight = if (expanded) toolbarButton.PillActiveTextWeight else toolbarButton.PillTextWeight
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = Strings["settings_language"],
                        tint = if (expanded) toolbarButton.PillSelectedIconColor else toolbarButton.PillIconColor,
                        modifier = Modifier.size(toolbarButton.PillTrailingIconSize)
                    )
                }
            }

            PodaraDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                items = listOf(
                    PodaraDropdownMenuItem(
                        label = "English",
                        isSelected = selectedLanguage == "en",
                        onClick = { onLanguageSelected("en"); onExpandedChange(false) }
                    ),
                    PodaraDropdownMenuItem(
                        label = "简体中文",
                        isSelected = selectedLanguage == "zh",
                        onClick = { onLanguageSelected("zh"); onExpandedChange(false) }
                    )
                )
            )
        }
        SettingsActionButton(
            text = Strings["settings_change"],
            color = colors.accent,
            onClick = onChange
        )
    }
}

// ── Theme picker ──

/**
 * Segmented control for the light/dark/system choice.
 *
 * Applies immediately rather than behind a separate confirm button: switching
 * palette is cheap, reversible, and seeing the result is the fastest way to
 * choose.
 */
@Composable
private fun ThemeSelector(
    selected: ThemePreference,
    onSelected: (ThemePreference) -> Unit
) {
    val colors = PodaraTheme.colors
    val toolbarButton = DesignTokens.ToolbarButton
    val shape = RoundedCornerShape(toolbarButton.PillRadius)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ThemePreference.entries.forEach { preference ->
            val interactionSource = remember { MutableInteractionSource() }
            val isSelected = preference == selected
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(shape)
                    .background(
                        if (isSelected) colors.accent else Color.Transparent
                    )
                    .border(
                        if (isSelected) 0.dp else DesignTokens.Border.Width,
                        if (isSelected) Color.Transparent else toolbarButton.PillDefaultBorderColor,
                        shape
                    )
                    .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)))
                    .clickableWithoutIndication(interactionSource) {
                        onSelected(preference)
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (preference) {
                        ThemePreference.System -> Strings["settings_theme_system"]
                        ThemePreference.Light -> Strings["settings_theme_light"]
                        ThemePreference.Dark -> Strings["settings_theme_dark"]
                    },
                    color = if (isSelected) colors.background else toolbarButton.PillTextColor,
                    fontSize = toolbarButton.PillTextSize,
                    lineHeight = toolbarButton.PillLineHeight,
                    fontWeight = if (isSelected) {
                        toolbarButton.PillActiveTextWeight
                    } else {
                        toolbarButton.PillTextWeight
                    }
                )
            }
        }
    }
}

// ── Settings action button ──
@Composable
private fun SettingsActionButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    val toolbarButton = DesignTokens.ToolbarButton

    ToolbarPillButton(
        label = text,
        contentDescription = text,
        onClick = onClick,
        height = 32.dp,
        radius = 8.dp,
        minWidth = 0.dp,
        horizontalPadding = 12.dp,
        textColor = color,
        hoverTextColor = color,
        defaultBackgroundColor = toolbarButton.PillSortBackgroundColor,
        hoverBackgroundColor = toolbarButton.PillSortBackgroundColor,
        pressedBackgroundColor = toolbarButton.PillSelectedBackgroundColor,
        defaultBorderColor = Color.Transparent,
        hoverBorderColor = Color.Transparent
    )
}

// ── Text button without MD hover background ──
@Composable
private fun SettingsActionText(
    text: String,
    color: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (enabled) color else color.copy(alpha = 0.38f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)))
            .clickableWithoutIndicationOrFocusRing(
                interactionSource = remember { MutableInteractionSource() },
                enabled = enabled,
                onClick = onClick
            )
    )
}

// ── Reusable settings row ──

/**
 * One settings row.
 *
 * [onClick] is opt-in. Rows whose edit affordance is an inline control
 * (language dropdown, theme segmented control) or a discrete action behind
 * its own button (export, import, folder picker) must not offer a row-level
 * click: an empty onClick with a hand cursor read as a broken control. Rows
 * that open a dialog pass [onClick] and keep the hand cursor and hover wash;
 * static rows lose both.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: @Composable () -> Unit,
    colors: PodaraColors,
    onClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    // colors.elevated is the page colour under the light scheme, so the hover
    // wash was invisible there. Use the row's own hover token instead. On
    // static rows the source never attaches, so the wash stays invisible —
    // hover feedback is only meaningful on rows that do something on click.
    val rowBg by animateHoverBackgroundColor(isHovered, PodaraTheme.surfaces.cardFillHover)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .then(
                if (onClick != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)))
                        .clickableWithoutIndicationOrFocusRing(
                            interactionSource = interactionSource,
                            onClick = onClick
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        action()
    }
}

// ── Platform helpers ──

/**
 * Picks a file constrained to [extensions].
 *
 * The AWT FileDialog this replaced silently dropped every extension but the
 * first — the call site passed ("opml", "xml") and `feeds.xml` was unselectable.
 * JFileChooser's FileNameExtensionFilter is what the directory picker below
 * already uses and takes all of them.
 */
private fun openFilePicker(title: String, vararg extensions: String): File? {
    val chooser = javax.swing.JFileChooser()
    chooser.dialogTitle = title
    chooser.isAcceptAllFileFilterUsed = false
    if (extensions.isNotEmpty()) {
        chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            extensions.joinToString("/") { it.uppercase() } + " Files",
            *extensions
        )
    }

    val result = chooser.showOpenDialog(null)
    return if (result == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun openDirectoryPicker(title: String): String? {
    val chooser = javax.swing.JFileChooser()
    chooser.dialogTitle = title
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    chooser.isAcceptAllFileFilterUsed = false

    val result = chooser.showOpenDialog(null)
    return if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}
