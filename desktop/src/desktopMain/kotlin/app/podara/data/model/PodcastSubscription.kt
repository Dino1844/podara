package app.podara.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PodcastSubscription(
    val origin: String,
    val enableNotifications: Boolean = false,
    val enableAutoDownload: Boolean = false,
    val lastUpdate: Long = 0,
    val newEpisodes: Int = 0,
    val cacheETag: String = "",
    val cacheLastModified: String = "",
    val cacheContentLength: String = "",
    /**
     * The speed this show always plays at, or null to follow the session
     * speed. Written when the user changes the speed while one of the show's
     * episodes plays; cleared from the show's menu. See schema v6.
     */
    val playbackSpeedOverride: Float? = null,
    /**
     * How many of this show's most recently downloaded episodes to keep; the
     * rest are pruned when a refresh adds episodes or a download finishes.
     * 0 (every pre-existing row's value) means no automatic cleanup. See
     * schema v7.
     */
    val keepDownloads: Int = 0
)
