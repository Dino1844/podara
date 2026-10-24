package app.podara.desktop

import app.podara.manager.DownloadNaming
import app.podara.manager.sha256
import app.podara.player.PlaybackRules
import app.podara.player.PreviousAction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The playback and download-naming rules, tested without Compose state, an audio
 * engine, a database, or a network. These cases previously required constructing
 * MediaPlayerState to reach.
 *
 * The old `decideStopTransition` cases are gone: that function was deleted along
 * with the wall-clock heuristic it encoded. End-of-track is now an explicit
 * engine event carrying a session id — see `QueueDeduplicationTest` and
 * `MediaPlayerStateTest` for the behaviour that replaced it.
 */
class PlaybackRulesTest {

    // ── nextIndexToPlay ──

    @Test
    fun nextIndexAdvancesWithinQueue() {
        assertEquals(1, PlaybackRules.nextIndexToPlay(currentIndex = 0, queueSize = 3))
        assertEquals(2, PlaybackRules.nextIndexToPlay(currentIndex = 1, queueSize = 3))
    }

    @Test
    fun nextIndexIsNullAtEndOrOnEmptyQueue() {
        assertNull(PlaybackRules.nextIndexToPlay(currentIndex = 2, queueSize = 3))
        assertNull(PlaybackRules.nextIndexToPlay(currentIndex = -1, queueSize = 0))
    }

    // ── decidePrevious ──

    @Test
    fun previousRestartsWhenPastRestartWindow() {
        assertEquals(
            PreviousAction.RestartCurrent,
            PlaybackRules.decidePrevious(currentPositionMs = 3001, currentIndex = 2)
        )
    }

    @Test
    fun previousGoesBackWhenNearStart() {
        assertEquals(
            PreviousAction.PlayIndex(1),
            PlaybackRules.decidePrevious(currentPositionMs = 0, currentIndex = 2)
        )
        assertEquals(
            PreviousAction.PlayIndex(0),
            PlaybackRules.decidePrevious(currentPositionMs = 3000, currentIndex = 1)
        )
    }

    @Test
    fun previousAtStartOfQueueDoesNothing() {
        assertEquals(
            PreviousAction.AtBeginning,
            PlaybackRules.decidePrevious(currentPositionMs = 0, currentIndex = 0)
        )
        assertEquals(
            PreviousAction.AtBeginning,
            PlaybackRules.decidePrevious(currentPositionMs = 0, currentIndex = -1)
        )
    }

    // ── indexAfterRemoval ──

    @Test
    fun removingPlayingItemHandsOffToSamePosition() {
        val outcome = PlaybackRules.indexAfterRemoval(
            queueSizeAfterRemoval = 2, removedIndex = 1, currentIndex = 1
        )
        assertEquals(1, outcome.nextIndex)
        assertTrue(outcome.playbackWasRemoved)
        assertFalse(outcome.shouldStopPlayback)
    }

    @Test
    fun removingLastPlayingItemClampsToNewLast() {
        val outcome = PlaybackRules.indexAfterRemoval(
            queueSizeAfterRemoval = 2, removedIndex = 2, currentIndex = 2
        )
        assertEquals(1, outcome.nextIndex)
        assertTrue(outcome.playbackWasRemoved)
        assertFalse(outcome.shouldStopPlayback)
    }

    @Test
    fun removingOnlyPlayingItemStopsPlayback() {
        val outcome = PlaybackRules.indexAfterRemoval(
            queueSizeAfterRemoval = 0, removedIndex = 0, currentIndex = 0
        )
        assertEquals(-1, outcome.nextIndex)
        assertTrue(outcome.playbackWasRemoved)
        assertTrue(outcome.shouldStopPlayback)
    }

    @Test
    fun removingItemAheadOfPlayingShiftsIndexBack() {
        val outcome = PlaybackRules.indexAfterRemoval(
            queueSizeAfterRemoval = 2, removedIndex = 0, currentIndex = 2
        )
        assertEquals(1, outcome.nextIndex)
        assertFalse(outcome.playbackWasRemoved)
        assertFalse(outcome.shouldStopPlayback)
    }

    @Test
    fun removingItemBeforePlayingShiftsIndexBack() {
        // Queue was [A, B, C, D] with B playing; removing A moves B to index 0.
        val outcome = PlaybackRules.indexAfterRemoval(
            queueSizeAfterRemoval = 3, removedIndex = 0, currentIndex = 1
        )
        assertEquals(0, outcome.nextIndex)
        assertFalse(outcome.playbackWasRemoved)
    }

    @Test
    fun removingItemAfterPlayingLeavesIndexAlone() {
        // Queue was [A, B, C] with B playing; removing C leaves B at index 1.
        val outcome = PlaybackRules.indexAfterRemoval(
            queueSizeAfterRemoval = 2, removedIndex = 2, currentIndex = 1
        )
        assertEquals(1, outcome.nextIndex)
        assertFalse(outcome.playbackWasRemoved)
    }

    // ── indexAfterBatchRemoval ──

    @Test
    fun batchRemovalContainingPlayingItemMovesPlayback() {
        val outcome = PlaybackRules.indexAfterBatchRemoval(
            queueSizeAfterRemoval = 2, removedIndices = setOf(0, 2), currentIndex = 2
        )
        assertEquals(1, outcome.nextIndex)
        assertTrue(outcome.playbackWasRemoved)
        assertFalse(outcome.shouldStopPlayback)
    }

    @Test
    fun batchRemovalNotContainingPlayingItemShiftsIndexForEarlierRemovals() {
        // Queue [A, B, C, D] with B playing (index 1). Deleting A and C leaves
        // [B, D], so B is now index 0. The old code only clamped 1 into range
        // and kept pointing at index 1 — a different episode.
        val outcome = PlaybackRules.indexAfterBatchRemoval(
            queueSizeAfterRemoval = 2, removedIndices = setOf(0, 2), currentIndex = 1
        )
        assertEquals(0, outcome.nextIndex)
        assertFalse(outcome.playbackWasRemoved)
        assertFalse(outcome.shouldStopPlayback)
    }

    @Test
    fun batchRemovalShiftsPlayingIndexByEveryEarlierRemoval() {
        // Six items with the fourth playing (index 3). Deleting the first two
        // leaves four items where that same episode sits at index 1. Clamping
        // alone kept 3, which is the sixth episode.
        val outcome = PlaybackRules.indexAfterBatchRemoval(
            queueSizeAfterRemoval = 4, removedIndices = setOf(0, 1), currentIndex = 3
        )
        assertEquals(1, outcome.nextIndex)
        assertFalse(outcome.playbackWasRemoved)
        assertFalse(outcome.shouldStopPlayback)
    }

    @Test
    fun batchRemovalIgnoresRemovalsBehindThePlayhead() {
        // [A, B, C, D, E] playing C (2); deleting D and E leaves [A, B, C], so
        // C is still index 2. Only removals *before* the playhead shift it.
        val outcome = PlaybackRules.indexAfterBatchRemoval(
            queueSizeAfterRemoval = 3, removedIndices = setOf(3, 4), currentIndex = 2
        )
        assertEquals(2, outcome.nextIndex)
        assertFalse(outcome.playbackWasRemoved)
    }

    @Test
    fun batchRemovalLeavingQueueEmptyStopsOnlyIfPlayingRemoved() {
        val playingRemoved = PlaybackRules.indexAfterBatchRemoval(
            queueSizeAfterRemoval = 0, removedIndices = setOf(0), currentIndex = 0
        )
        assertEquals(-1, playingRemoved.nextIndex)
        assertTrue(playingRemoved.shouldStopPlayback)

        val playingKept = PlaybackRules.indexAfterBatchRemoval(
            queueSizeAfterRemoval = 0, removedIndices = setOf(0), currentIndex = -1
        )
        assertEquals(-1, playingKept.nextIndex)
        assertFalse(playingKept.shouldStopPlayback)
    }

    @Test
    fun batchRemovalClampsAnOutOfRangeIndexDefensively() {
        // The shift is currentIndex minus the removals before it, so a real
        // queue can never produce an out-of-range result. This pins the clamp
        // anyway: (3 - one earlier removal) is 2, but only two items are left.
        val outcome = PlaybackRules.indexAfterBatchRemoval(
            queueSizeAfterRemoval = 2, removedIndices = setOf(0), currentIndex = 3
        )
        assertEquals(1, outcome.nextIndex)
    }

    @Test
    fun removalKeepsTheIdleSentinelWhenNothingWasPlaying() {
        // queueIndex == -1 is not an index, it means "queue loaded, nothing ever
        // played". Coercing it into 0..n-1 would point the transport at the
        // first episode and lose the idle state.
        val single = PlaybackRules.indexAfterRemoval(
            queueSizeAfterRemoval = 2, removedIndex = 0, currentIndex = -1
        )
        assertEquals(-1, single.nextIndex)
        assertFalse(single.playbackWasRemoved)
        assertFalse(single.shouldStopPlayback)

        val batch = PlaybackRules.indexAfterBatchRemoval(
            queueSizeAfterRemoval = 2, removedIndices = setOf(0), currentIndex = -1
        )
        assertEquals(-1, batch.nextIndex)
        assertFalse(batch.playbackWasRemoved)
        assertFalse(batch.shouldStopPlayback)
    }

    // ── indexAfterMove ──

    @Test
    fun draggingPlayingItemCarriesPlayhead() {
        assertEquals(2, PlaybackRules.indexAfterMove(currentIndex = 0, fromIndex = 0, toIndex = 2))
    }

    @Test
    fun draggingItemForwardAcrossPlayheadShiftsIndexBack() {
        assertEquals(1, PlaybackRules.indexAfterMove(currentIndex = 2, fromIndex = 0, toIndex = 3))
    }

    @Test
    fun draggingItemBackwardAcrossPlayheadShiftsIndexForward() {
        assertEquals(3, PlaybackRules.indexAfterMove(currentIndex = 2, fromIndex = 4, toIndex = 1))
    }

    @Test
    fun draggingItemWithinSameSideOfPlayheadLeavesIndexAlone() {
        assertEquals(2, PlaybackRules.indexAfterMove(currentIndex = 2, fromIndex = 0, toIndex = 1))
        assertEquals(2, PlaybackRules.indexAfterMove(currentIndex = 2, fromIndex = 3, toIndex = 4))
    }

    // ── clampRestoredIndex ──

    @Test
    fun restoredIndexIsClampedIntoQueue() {
        // Only *positive* indices are clamped into range. A negative stored index
        // is the idle sentinel (queue saved, nothing ever played) and must stay
        // -1; clamping it to 0 resumed an episode the user never started.
        assertEquals(-1, PlaybackRules.clampRestoredIndex(queueSize = 3, storedIndex = -5))
        assertEquals(-1, PlaybackRules.clampRestoredIndex(queueSize = 3, storedIndex = -1))
        assertEquals(2, PlaybackRules.clampRestoredIndex(queueSize = 3, storedIndex = 99))
        assertEquals(1, PlaybackRules.clampRestoredIndex(queueSize = 3, storedIndex = 1))
        assertEquals(-1, PlaybackRules.clampRestoredIndex(queueSize = 0, storedIndex = 2))
    }

    // ── sleepTimerDeadline ──

    @Test
    fun sleepTimerDeadlineIsNowPlusMinutes() {
        assertEquals(100_000L + 30 * 60_000L, PlaybackRules.sleepTimerDeadline(100_000L, 30))
    }

    @Test
    fun sleepTimerDeadlineIsNullWhenCleared() {
        assertNull(PlaybackRules.sleepTimerDeadline(100_000L, null))
        assertNull(PlaybackRules.sleepTimerDeadline(100_000L, 0))
        assertNull(PlaybackRules.sleepTimerDeadline(100_000L, -5))
    }

    // ── DownloadNaming.sanitizeFileName ──

    @Test
    fun sanitizeReplacesWindowsIllegalCharacters() {
        assertEquals("a_b_c", DownloadNaming.sanitizeFileName("a/b\\c"))
        assertEquals("a_b_c_d", DownloadNaming.sanitizeFileName("a:b*c?d"))
        assertEquals("a__b", DownloadNaming.sanitizeFileName("a<>b"))
    }

    @Test
    fun sanitizeRejectsDirectoryAliasesAndBlanks() {
        assertEquals("_", DownloadNaming.sanitizeFileName("."))
        assertEquals("_", DownloadNaming.sanitizeFileName(".."))
        assertEquals("_", DownloadNaming.sanitizeFileName("   "))
        assertEquals("_", DownloadNaming.sanitizeFileName(""))
    }

    @Test
    fun sanitizeRejectsWindowsReservedNamesCaseInsensitively() {
        assertEquals("_", DownloadNaming.sanitizeFileName("CON"))
        assertEquals("_", DownloadNaming.sanitizeFileName("lpt9.txt"))
        assertEquals("_", DownloadNaming.sanitizeFileName("nul"))
    }

    @Test
    fun sanitizeTrimsTrailingDotsAndSpaces() {
        assertEquals("name", DownloadNaming.sanitizeFileName("name..."))
        assertEquals("name", DownloadNaming.sanitizeFileName("name   "))
    }

    // ── DownloadNaming.audioFileExtension ──

    @Test
    fun extensionAcceptsKnownAudioTypes() {
        assertEquals("m4a", DownloadNaming.audioFileExtension("https://example.com/ep.m4a"))
        assertEquals("mp3", DownloadNaming.audioFileExtension("https://example.com/ep.MP3"))
    }

    @Test
    fun extensionIgnoresDomainDots() {
        assertEquals("mp3", DownloadNaming.audioFileExtension("https://example.com/ep"))
    }

    @Test
    fun extensionFallsBackForUnknownOrUnsafeValues() {
        assertEquals("mp3", DownloadNaming.audioFileExtension("https://example.com/ep.exe"))
        assertEquals("mp3", DownloadNaming.audioFileExtension("https://example.com/ep.php"))
    }

    // ── DownloadNaming.buildDownloadFile ──

    @Test
    fun downloadFileUsesReadablePrefixAndHashSuffix() {
        val dir = File("C:/tmp/podara-test")
        val origin = "https://example.com/feed.xml"
        val file = DownloadNaming.buildDownloadFile(
            downloadsDir = dir,
            origin = origin,
            episodeId = "ep-1",
            audioUrl = "https://example.com/audio.m4a",
            episodeTitle = "Test Episode",
            podcastTitle = "Test Podcast"
        )
        assertEquals("Test Podcast-${origin.sha256()}", file.parentFile.name)
        assertEquals("Test Episode-${"ep-1".sha256()}.m4a", file.name)
    }

    @Test
    fun downloadFileIsStableForIdenticalInputs() {
        val dir = File("C:/tmp/podara-test")
        val first = DownloadNaming.buildDownloadFile(dir, "feed", "ep-1", "https://e.com/a.mp3", "Episode", "Show")
        val second = DownloadNaming.buildDownloadFile(dir, "feed", "ep-1", "https://e.com/a.mp3", "Episode", "Show")
        assertEquals(first, second)
    }

    @Test
    fun downloadPathHashSuffixSurvivesTitleChanges() {
        // The readable prefix tracks the title, so it changes — but the hash
        // suffix is what guarantees a stable, collision-free identity, and it is
        // derived from the origin/episode id only.
        val dir = File("C:/tmp/podara-test")
        val first = DownloadNaming.buildDownloadFile(dir, "feed", "ep-1", "https://e.com/a.mp3", "Old Title", "Show")
        val second = DownloadNaming.buildDownloadFile(dir, "feed", "ep-1", "https://e.com/a.mp3", "New Title", "Show")

        assertTrue(first.name.endsWith("-${"ep-1".sha256()}.mp3"))
        assertTrue(second.name.endsWith("-${"ep-1".sha256()}.mp3"))
        assertEquals(first.parentFile.name, second.parentFile.name)
    }

    @Test
    fun downloadFileDistinguishesDifferentEpisodes() {
        val dir = File("C:/tmp/podara-test")
        val first = DownloadNaming.buildDownloadFile(dir, "feed", "ep-1", "https://e.com/a.mp3", "Same Title", "Show")
        val second = DownloadNaming.buildDownloadFile(dir, "feed", "ep-2", "https://e.com/a.mp3", "Same Title", "Show")
        assertTrue(first != second)
    }

    @Test
    fun downloadFileFallsBackToGenericPrefixWhenTitleUnusable() {
        val dir = File("C:/tmp/podara-test")
        val file = DownloadNaming.buildDownloadFile(dir, "feed", "ep-1", "https://e.com/a.mp3", "CON", "...")
        assertTrue(file.parentFile.name.startsWith("podcast-"))
        assertTrue(file.name.startsWith("episode-"))
    }

    // ── DownloadNaming.isInsideDownloadsDir ──

    @Test
    fun containmentAcceptsChildAndRejectsEscape() {
        val dir = File("C:/tmp/podara-test")
        assertTrue(DownloadNaming.isInsideDownloadsDir(dir, File(dir, "child/file.mp3")))
        assertFalse(DownloadNaming.isInsideDownloadsDir(dir, File(dir, "../../escaped.mp3")))
        assertFalse(DownloadNaming.isInsideDownloadsDir(dir, dir))
    }
}