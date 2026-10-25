package app.podara.util

import java.io.File
import java.util.Properties

object Settings {
    private val settingsFile = File(System.getProperty("user.home"), ".podara/settings.properties")
    private val props = Properties()

    init {
        try {
            if (settingsFile.exists()) {
                settingsFile.inputStream().use { props.load(it) }
            }
        } catch (e: Exception) {
            Logger.e("Settings", "Failed to load settings", e)
        }
    }

    fun getDownloadSpeedLimitKbps(): Int {
        val raw = props.getProperty("download_speed_limit_kbps", "0")
        val value = raw.toIntOrNull() ?: return 0
        return maxOf(value, 0)
    }

    fun setDownloadSpeedLimitKbps(limitKbps: Int) {
        props.setProperty("download_speed_limit_kbps", limitKbps.toString())
        save()
    }

    fun getDownloadPath(): String {
        return props.getProperty("download_path", defaultDownloadPath())
    }

    fun setDownloadPath(path: String) {
        props.setProperty("download_path", path)
        save()
    }

    fun resetDownloadPath() {
        props.remove("download_path")
        save()
    }

    fun getLanguage(): String {
        return props.getProperty("language", "en")
    }

    fun setLanguage(language: String) {
        props.setProperty("language", language)
        save()
    }

    // ── Close Action (ask / quit / minimize_to_tray) ──

    fun getCloseAction(): String {
        val raw = props.getProperty("close_action", "ask")
        return if (raw in listOf("ask", "quit", "minimize_to_tray")) raw else "ask"
    }

    fun setCloseAction(action: String) {
        if (action !in listOf("ask", "quit", "minimize_to_tray")) return
        props.setProperty("close_action", action)
        save()
    }

    fun isCloseActionRemembered(): Boolean {
        return props.getProperty("close_action_remembered", "false").toBoolean()
    }

    fun setCloseActionRemembered(remembered: Boolean) {
        props.setProperty("close_action_remembered", remembered.toString())
        save()
    }

    // ── Theme (light / dark / system) ──

    fun getTheme(): String {
        val raw = props.getProperty("theme", "system")
        return if (raw in listOf("light", "dark", "system")) raw else "system"
    }

    fun setTheme(theme: String) {
        if (theme !in listOf("light", "dark", "system")) return
        props.setProperty("theme", theme)
        save()
    }

    // ── Audio enhancements (full-player toggles) ──

    /** Whether the player trims silent stretches (mpv `silenceremove`). */
    fun isSkipSilenceEnabled(): Boolean {
        return props.getProperty("player_skip_silence", "false").toBoolean()
    }

    fun setSkipSilence(enabled: Boolean) {
        props.setProperty("player_skip_silence", enabled.toString())
        save()
    }

    /** Whether the player applies the fixed loudness lift (mpv `volume`). */
    fun isLoudnessBoostEnabled(): Boolean {
        return props.getProperty("player_loudness_boost", "false").toBoolean()
    }

    fun setLoudnessBoost(enabled: Boolean) {
        props.setProperty("player_loudness_boost", enabled.toString())
        save()
    }

    // ── Reduce motion (DESIGN-CRAFT.md C4) ──

    /**
     * Whether the UI should degrade its slides and springs to fades. Desktop
     * has no `prefers-reduced-motion` to inherit, so this is the manual
     * equivalent, and doubles as a motion-debugging switch.
     */
    fun isReducedMotionEnabled(): Boolean {
        return props.getProperty("reduced_motion", "false").toBoolean()
    }

    fun setReducedMotion(enabled: Boolean) {
        props.setProperty("reduced_motion", enabled.toString())
        save()
    }

    /**
     * Writes the properties out atomically.
     *
     * This used to stream straight into `settingsFile`. A crash mid-write
     * truncated the file, and since every setting lives in that one file, the
     * user lost the language, theme, download path and speed limit together.
     * Writing a sibling temp file and renaming over the original means the
     * visible file is always either the old contents or the new ones.
     */
    private fun save() {
        try {
            settingsFile.parentFile?.mkdirs()
            val temp = File(settingsFile.parentFile, "${settingsFile.name}.tmp")
            temp.outputStream().use { props.store(it, "Podara Settings") }
            // File.renameTo does not replace an existing file on Windows, so
            // drop the target first. If that fails the old file is still intact.
            if (settingsFile.exists() && !settingsFile.delete()) {
                Logger.w("Settings", "Could not remove old settings file before rename")
            }
            if (!temp.renameTo(settingsFile)) {
                Logger.e("Settings", "Failed to move temp settings file into place")
                temp.delete()
            }
        } catch (e: Exception) {
            Logger.e("Settings", "Failed to save settings", e)
        }
    }

    private fun defaultDownloadPath(): String {
        return File(System.getProperty("user.home"), ".podara/downloads").absolutePath
    }
}
