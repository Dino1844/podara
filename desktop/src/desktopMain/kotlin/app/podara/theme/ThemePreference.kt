package app.podara.theme

/**
 * How the app chooses between the light and dark palettes.
 *
 * Persisted via [app.podara.util.Settings] so the choice survives restarts, and
 * read at the root of the composition in [PodaraTheme].
 */
enum class ThemePreference(val settingValue: String) {
    /** Follow the OS desktop setting. */
    System("system"),

    /** Always the Apple Podcasts web palette. */
    Light("light"),

    /** Always the original dark glass palette. */
    Dark("dark");

    /**
     * Whether the dark scheme should be used.
     *
     * @param isSystemDark whether the OS reports a dark desktop. Only consulted
     *   for [System].
     */
    fun resolvesToDark(isSystemDark: Boolean): Boolean = when (this) {
        System -> isSystemDark
        Light -> false
        Dark -> true
    }

    companion object {
        /** Maps a persisted value, falling back to [System] for anything unknown. */
        fun fromSetting(value: String): ThemePreference =
            entries.firstOrNull { it.settingValue == value } ?: System
    }
}
