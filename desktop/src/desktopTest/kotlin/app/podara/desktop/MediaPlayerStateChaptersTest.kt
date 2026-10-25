package app.podara.desktop

import app.podara.player.MpvChapter
import app.podara.player.MediaPlayerState
import app.podara.player.PlayerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chapter handling at the state layer, driven through [FakeAudioEngine.emit]
 * the way the real engine's event loop would deliver them.
 *
 * The contract being pinned: a [PlayerEvent.ChaptersLoaded] replaces the whole
 * list (a chapterless file ships an empty one, which is what clears the
 * previous file's entries) and resets the current index; a
 * [PlayerEvent.ChapterChanged] only moves the index. Both are dropped for
 * superseded sessions like every other event, and every load path clears the
 * list eagerly so an old episode's chapters never outlive their file.
 */
class MediaPlayerStateChaptersTest {

    private val chapters = listOf(
        MpvChapter("Intro", 0L),
        MpvChapter("The middle bit", 61_500L),
        MpvChapter("Outro", 1_800_000L)
    )

    @Test
    fun chaptersLoadedReplacesTheListAndResetsTheIndex() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            // The first claimed session is 1.
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")

            engine.emit(PlayerEvent.ChaptersLoaded(session = 1, chapters = chapters))
            assertEquals(chapters, state.chapters)
            assertEquals(-1, state.currentChapterIndex, "the index waits for the chapter property")

            engine.emit(PlayerEvent.ChapterChanged(session = 1, index = 2))
            assertEquals(2, state.currentChapterIndex)
        } finally {
            state.release()
        }
    }

    @Test
    fun chapterIndexBelowTheListIsKeptAsReported() {
        // mpv reports -1 whenever the playhead is outside any chapter; the
        // state layer is not in the business of second-guessing it.
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            engine.emit(PlayerEvent.ChaptersLoaded(session = 1, chapters = chapters))
            engine.emit(PlayerEvent.ChapterChanged(session = 1, index = -1))
            assertEquals(-1, state.currentChapterIndex)
        } finally {
            state.release()
        }
    }

    @Test
    fun aChapterlessFileClearsThePreviousOneSChapters() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            engine.emit(PlayerEvent.ChaptersLoaded(session = 1, chapters = chapters))
            assertEquals(chapters, state.chapters)

            // The second load claims session 2; its FILE_LOADED reports none.
            state.play(url = "https://example.com/2.mp3", title = "Episode 2")
            assertEquals(emptyList(), state.chapters, "the new load resets the list at once")
            assertEquals(-1, state.currentChapterIndex)

            engine.emit(PlayerEvent.ChaptersLoaded(session = 2, chapters = emptyList()))
            assertEquals(emptyList(), state.chapters, "the engine's confirmation changes nothing")
        } finally {
            state.release()
        }
    }

    @Test
    fun stopClearsChapters() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            engine.emit(PlayerEvent.ChaptersLoaded(session = 1, chapters = chapters))
            engine.emit(PlayerEvent.ChapterChanged(session = 1, index = 0))

            state.stop()
            assertTrue(state.chapters.isEmpty(), "a stopped transport has no chapters")
            assertEquals(-1, state.currentChapterIndex)
        } finally {
            state.release()
        }
    }

    @Test
    fun chapterEventsForASupersededSessionAreDropped() {
        val engine = FakeAudioEngine()
        val state = MediaPlayerState(engine)
        try {
            state.play(url = "https://example.com/1.mp3", title = "Episode 1")
            // Session 99 never existed; the stale-event guard must swallow it.
            engine.emit(PlayerEvent.ChaptersLoaded(session = 99, chapters = chapters))
            engine.emit(PlayerEvent.ChapterChanged(session = 99, index = 1))

            assertTrue(state.chapters.isEmpty())
            assertEquals(-1, state.currentChapterIndex)
        } finally {
            state.release()
        }
    }
}
