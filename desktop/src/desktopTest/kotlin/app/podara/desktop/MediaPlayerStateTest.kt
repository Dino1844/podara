package app.podara.desktop

import app.podara.data.AppDatabase
import app.podara.data.model.PodcastEpisode
import app.podara.player.AudioPlayerEngine
import app.podara.player.MediaPlayerState
import app.podara.player.PlaybackErrorCategory
import app.podara.player.PlaybackState
import app.podara.player.PlayerEvent
import app.podara.player.PlayerSnapshot
import app.podara.player.QueueItem
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MediaPlayerStateTest {

    private lateinit var state: MediaPlayerState

    @BeforeTest
    fun setup() {
        state = MediaPlayerState()
    }

    @AfterTest
    fun teardown() {
        state.release()
    }

    @Test
    fun testInitialQueueIsEmpty() {
        assertEquals(0, state.queue.size)
        assertEquals(-1, state.queueIndex)
    }

    @Test
    fun testAddToQueue() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")

        assertEquals(2, state.queue.size)
        assertEquals("Episode 1", state.queue[0].title)
        assertEquals("Episode 2", state.queue[1].title)
        assertEquals("https://example.com/audio1.mp3", state.queue[0].url)
    }

    @Test
    fun testAddToQueueWithArtwork() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1", "https://example.com/art.jpg")

        assertEquals(1, state.queue.size)
        assertEquals("https://example.com/art.jpg", state.queue[0].artworkUrl)
    }

    @Test
    fun testRemoveFromQueue() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")
        state.addToQueue("https://example.com/audio3.mp3", "Episode 3")

        state.removeFromQueue(1)

        assertEquals(2, state.queue.size)
        assertEquals("Episode 1", state.queue[0].title)
        assertEquals("Episode 3", state.queue[1].title)
    }

    @Test
    fun testRemoveFromQueueOutOfBounds() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")

        state.removeFromQueue(-1)
        assertEquals(1, state.queue.size)

        state.removeFromQueue(5)
        assertEquals(1, state.queue.size)
    }

    @Test
    fun testPlayFromQueue() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")

        try {
            state.playFromQueue(1)
        } catch (e: Throwable) {
            // JavaFX native libraries not available in test environment
        }

        assertEquals(1, state.queueIndex)
        assertEquals("Episode 2", state.currentTitle)
    }

    @Test
    fun testPlayFromQueueOutOfBounds() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")

        state.playFromQueue(-1)
        assertEquals(-1, state.queueIndex)

        state.playFromQueue(5)
        assertEquals(-1, state.queueIndex)
    }

    @Test
    fun testSleepTimerCancel() {
        state.setSleepTimer(30)
        assertNotNull(state.sleepTimerMinutes)

        state.cancelSleepTimer()
        assertNull(state.sleepTimerMinutes)
        assertNull(state.sleepTimerTrigger)
    }

    @Test
    fun testSleepTimerSetNull() {
        state.setSleepTimer(30)
        state.setSleepTimer(null)
        assertNull(state.sleepTimerMinutes)
    }

    @Test
    fun testSleepTimerSetValues() {
        state.setSleepTimer(15)
        assertEquals(15, state.sleepTimerMinutes)
        assertNotNull(state.sleepTimerTrigger)
    }

    @Test
    fun testGetProgress() {
        assertEquals(0f, state.getProgress())
    }

    @Test
    fun testInitialVolume() {
        assertEquals(100, state.volume)
    }

    @Test
    fun testInitialSpeed() {
        assertEquals(1.0f, state.playbackSpeed)
    }

    @Test
    fun testInitialStates() {
        assertFalse(state.isPlaying)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertNull(state.currentUrl)
        assertNull(state.currentTitle)
        assertNull(state.currentArtworkUrl)
        assertNull(state.sleepTimerMinutes)
        assertNull(state.sleepTimerTrigger)
    }

    @Test
    fun testTogglePlayPauseWhenStopped() {
        state.togglePlayPause()
        // MpvAudioPlayerEngine sets isPlaying=true on resume (mpv doesn't need a URL)
    }

    @Test
    fun testQueueItemDataClass() {
        val item = QueueItem(
            url = "https://example.com/audio.mp3",
            title = "Test Episode",
            artworkUrl = "https://example.com/art.jpg"
        )
        assertEquals("https://example.com/audio.mp3", item.url)
        assertEquals("Test Episode", item.title)
        assertEquals("https://example.com/art.jpg", item.artworkUrl)
    }

    @Test
    fun testQueueItemDefaultArtwork() {
        val item = QueueItem(url = "https://example.com/audio.mp3", title = "Test")
        assertNull(item.artworkUrl)
    }

    @Test
    fun testQueueItemWithEpisodeId() {
        val item = QueueItem(
            url = "https://example.com/audio.mp3",
            title = "Test",
            episodeId = "ep-123",
            isDownloaded = true
        )
        assertEquals("ep-123", item.episodeId)
        assertTrue(item.isDownloaded)
    }

    @Test
    fun testPlayAutoAddsToQueue() {
        try {
            state.play("https://example.com/audio1.mp3", "Episode 1")
        } catch (e: Throwable) {
            // Audio player native libraries not available in test
        }

        assertEquals(1, state.queue.size)
        assertEquals("Episode 1", state.queue[0].title)
        assertEquals(0, state.queueIndex)
    }

    @Test
    fun testPlayDoesNotDuplicateInQueue() {
        try {
            state.play("https://example.com/audio1.mp3", "Episode 1")
            state.play("https://example.com/audio1.mp3", "Episode 1")
        } catch (e: Throwable) {}

        assertEquals(1, state.queue.size)
        assertEquals(0, state.queueIndex)
    }

    @Test
    fun testPlayUpdatesQueueIndexForExisting() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")

        try {
            state.play("https://example.com/audio2.mp3", "Episode 2")
        } catch (e: Throwable) {}

        assertEquals(1, state.queueIndex)
    }

    @Test
    fun testRemoveCurrentlyPlayingPlaysNext() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")
        state.addToQueue("https://example.com/audio3.mp3", "Episode 3")

        // Simulate playing index 1
        try {
            state.playFromQueue(1)
        } catch (e: Throwable) {}

        // Remove currently playing item
        state.removeFromQueue(1)

        assertEquals(2, state.queue.size)
        assertEquals("Episode 3", state.queue[state.queueIndex].title)
    }

    @Test
    fun testRemoveCurrentlyPlayingPreservesNextItemMetadata() {
        val testState = MediaPlayerState(FakeAudioPlayerEngine())
        try {
            testState.playWithContext(
                context = listOf(
                    QueueItem(url = "url-1", title = "Episode 1", subtitle = "Podcast 1", artworkUrl = "art-1", podcastArtworkUrl = "pod-art-1", episodeId = "ep-1"),
                    QueueItem(url = "url-2", title = "Episode 2", subtitle = "Podcast 2", artworkUrl = "art-2", podcastArtworkUrl = "pod-art-2", episodeId = "ep-2")
                ),
                targetUrl = "url-1",
                title = "Episode 1",
                subtitle = "Podcast 1",
                artworkUrl = "art-1",
                podcastArtworkUrl = "pod-art-1",
                episodeId = "ep-1"
            )

            testState.removeFromQueue(0)

            assertEquals("Episode 2", testState.currentTitle)
            assertEquals("Podcast 2", testState.currentSubtitle)
            assertEquals("art-2", testState.currentArtworkUrl)
            assertEquals("ep-2", testState.currentEpisodeId)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testRemoveSelectedCurrentlyPlayingPreservesNextItemMetadata() {
        val testState = MediaPlayerState(FakeAudioPlayerEngine())
        try {
            testState.playWithContext(
                context = listOf(
                    QueueItem(url = "url-1", title = "Episode 1", subtitle = "Podcast 1", artworkUrl = "art-1", podcastArtworkUrl = "pod-art-1", episodeId = "ep-1"),
                    QueueItem(url = "url-2", title = "Episode 2", subtitle = "Podcast 2", artworkUrl = "art-2", podcastArtworkUrl = "pod-art-2", episodeId = "ep-2"),
                    QueueItem(url = "url-3", title = "Episode 3", subtitle = "Podcast 3", artworkUrl = "art-3", podcastArtworkUrl = "pod-art-3", episodeId = "ep-3")
                ),
                targetUrl = "url-2",
                title = "Episode 2",
                subtitle = "Podcast 2",
                artworkUrl = "art-2",
                podcastArtworkUrl = "pod-art-2",
                episodeId = "ep-2"
            )

            testState.removeSelectedFromQueue(setOf(1))

            assertEquals("Episode 3", testState.currentTitle)
            assertEquals("Podcast 3", testState.currentSubtitle)
            assertEquals("art-3", testState.currentArtworkUrl)
            assertEquals("ep-3", testState.currentEpisodeId)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testPlayWithContextMatchesByEpisodeIdWhenUrlDiffers() {
        // The regression: playing a downloaded episode targets a local file path,
        // which no context item carries — URL matching found nothing and the
        // episode was appended a second time, so it appeared twice in the queue
        // and "next" replayed it from the network.
        val testState = MediaPlayerState(FakeAudioPlayerEngine())
        try {
            testState.playWithContext(
                context = listOf(
                    QueueItem(url = "https://stream/ep1.mp3", title = "Episode 1", episodeId = "ep-1"),
                    QueueItem(url = "https://stream/ep2.mp3", title = "Episode 2", episodeId = "ep-2")
                ),
                targetUrl = "/downloads/ep1.mp3",
                title = "Episode 1",
                episodeId = "ep-1"
            )

            assertEquals(2, testState.queue.size, "the episode must not be appended a second time")
            assertEquals(0, testState.queueIndex)
            assertEquals("ep-1", testState.queue[testState.queueIndex].episodeId)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testRemoveCurrentlyPlayingStopsWhenEmpty() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")

        try {
            state.playFromQueue(0)
        } catch (e: Throwable) {}

        state.removeFromQueue(0)

        assertEquals(0, state.queue.size)
        assertEquals(-1, state.queueIndex)
    }

    @Test
    fun testRemoveCurrentlyPlayingPlaysFirstWhenNoNext() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")

        try {
            state.playFromQueue(1)
        } catch (e: Throwable) {}

        // Remove the last item (index 1), should play from index 0
        state.removeFromQueue(1)

        assertEquals(1, state.queue.size)
        assertEquals(0, state.queueIndex)
        assertEquals("Episode 1", state.queue[0].title)
    }

    @Test
    fun testRemoveItemBeforePlayingAdjustsIndex() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")
        state.addToQueue("https://example.com/audio3.mp3", "Episode 3")

        try {
            state.playFromQueue(2)
        } catch (e: Throwable) {}

        state.removeFromQueue(0)

        assertEquals(2, state.queue.size)
        assertEquals(1, state.queueIndex)
    }

    @Test
    fun testClearQueue() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")

        try {
            state.playFromQueue(0)
        } catch (e: Throwable) {}

        state.clearQueue()

        assertEquals(0, state.queue.size)
        assertEquals(-1, state.queueIndex)
    }

    @Test
    fun testRemoveSelectedFromQueue() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")
        state.addToQueue("https://example.com/audio3.mp3", "Episode 3")
        state.addToQueue("https://example.com/audio4.mp3", "Episode 4")

        state.removeSelectedFromQueue(setOf(0, 2))

        assertEquals(2, state.queue.size)
        assertEquals("Episode 2", state.queue[0].title)
        assertEquals("Episode 4", state.queue[1].title)
    }

    @Test
    fun testRemoveSelectedAdjustsPlayingIndex() {
        state.addToQueue("https://example.com/audio1.mp3", "Episode 1")
        state.addToQueue("https://example.com/audio2.mp3", "Episode 2")
        state.addToQueue("https://example.com/audio3.mp3", "Episode 3")

        try {
            state.playFromQueue(2)
        } catch (e: Throwable) {}

        state.removeSelectedFromQueue(setOf(0))

        assertEquals(2, state.queue.size)
        assertEquals(1, state.queueIndex)
    }

    @Test
    fun testAddToQueueWithEpisodeIdAndDownloaded() {
        state.addToQueue(
            url = "https://example.com/audio1.mp3",
            title = "Episode 1",
            episodeId = "ep-123",
            isDownloaded = true
        )

        assertEquals(1, state.queue.size)
        assertEquals("ep-123", state.queue[0].episodeId)
        assertTrue(state.queue[0].isDownloaded)
    }

    // ── Playback failure feedback ──

    @Test
    fun testPlaybackFailureExposesErrorAndResetsPlaying() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1")
            assertTrue(testState.isPlaying)

            fake.failCurrentTrack(PlaybackErrorCategory.NETWORK)

            assertEquals(PlaybackErrorCategory.NETWORK, testState.error)
            assertFalse(testState.isPlaying)
            assertFalse(testState.isLoading)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testLoadingClearsWhenPositionIsReported() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1")
            // The engine reports playing optimistically before the file has
            // loaded, so loading stays on until real progress or an error.
            assertTrue(testState.isLoading)

            fake.seek(1000L)

            assertFalse(testState.isLoading)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testPlayClearsPreviousError() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1")
            fake.failCurrentTrack(PlaybackErrorCategory.FILE)
            assertEquals(PlaybackErrorCategory.FILE, testState.error)

            testState.play("url-2", "Episode 2")

            assertNull(testState.error)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testStopClearsError() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1")
            fake.failCurrentTrack(PlaybackErrorCategory.GENERIC)
            assertNotNull(testState.error)

            testState.stop()

            assertNull(testState.error)
            assertNull(testState.currentUrl)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testRetryReplaysFailedTrack() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1")
            fake.failCurrentTrack(PlaybackErrorCategory.NETWORK)

            testState.retry()

            assertEquals("url-1", testState.currentUrl)
            assertEquals("Episode 1", testState.currentTitle)
            assertNull(testState.error)
            assertTrue(testState.isLoading)
            assertEquals(listOf("url-1", "url-1"), fake.playedUrls)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testResumeAfterFailureRetriesInsteadOfResuming() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1")
            fake.failCurrentTrack(PlaybackErrorCategory.NETWORK)
            val playsBefore = fake.playedUrls.size

            // The play button (togglePlayPause) must still do something useful
            // after a failure: unpausing an idle engine would be a no-op.
            testState.togglePlayPause()

            assertEquals(playsBefore + 1, fake.playedUrls.size)
            assertNull(testState.error)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testRetryWithNoTrackIsNoOp() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.retry()
            assertTrue(fake.playedUrls.isEmpty())
            assertNull(testState.error)
        } finally {
            testState.release()
        }
    }

    // ── Switching episodes must not inherit timing (H3) ──

    @Test
    fun testSwitchingEpisodeResetsPositionAndDuration() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1", durationMs = 100_000L)
            fake.seek(95_000L)
            assertEquals(95_000L, testState.currentPosition)
            assertEquals(100_000L, testState.duration)

            testState.play("url-2", "Episode 2", durationMs = 60_000L)

            assertEquals(
                0L,
                testState.currentPosition,
                "the new episode starts at zero; keeping the old position let the 30s heartbeat write it under the new episode id"
            )
            assertEquals(60_000L, testState.duration, "the new episode must not inherit the old length")
        } finally {
            testState.release()
        }
    }

    @Test
    fun testPlayWithContextResetsPositionAndDuration() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1", durationMs = 100_000L)
            fake.seek(95_000L)

            testState.playWithContext(
                context = listOf(
                    QueueItem(url = "url-1", title = "Episode 1", episodeId = "ep-1"),
                    QueueItem(url = "url-2", title = "Episode 2", episodeId = "ep-2")
                ),
                targetUrl = "url-2",
                title = "Episode 2",
                durationMs = 60_000L,
                episodeId = "ep-2"
            )

            assertEquals(0L, testState.currentPosition)
            assertEquals(60_000L, testState.duration)
        } finally {
            testState.release()
        }
    }

    // ── Queue index arithmetic through the state holder (H1/H2) ──

    @Test
    fun testBatchRemovalKeepsPlayheadOnTheSameEpisode() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            for (i in 1..6) testState.addToQueue("https://example.com/$i.mp3", "Episode $i")
            testState.playFromQueue(3)

            testState.removeSelectedFromQueue(setOf(0, 1))

            assertEquals(4, testState.queue.size)
            assertEquals(
                1,
                testState.queueIndex,
                "after deleting the first two of six items, the fourth episode is index 1"
            )
            assertEquals("Episode 4", testState.queue[testState.queueIndex].title)
            assertEquals("Episode 4", testState.currentTitle, "the playhead must stay on the playing episode")
        } finally {
            testState.release()
        }
    }

    @Test
    fun testRemovingFromAnIdleQueueKeepsTheIdleSentinel() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.addToQueue("https://example.com/1.mp3", "Episode 1")
            testState.addToQueue("https://example.com/2.mp3", "Episode 2")
            assertEquals(-1, testState.queueIndex)

            testState.removeFromQueue(0)
            assertEquals(-1, testState.queueIndex, "removing an item must not start playback at index 0")

            testState.removeSelectedFromQueue(setOf(0))
            assertEquals(-1, testState.queueIndex)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testRestoreKeepsIdleQueueWithoutOpeningATrack() {
        val database = AppDatabase.buildInMemory()
        val savingState = MediaPlayerState(FakeAudioPlayerEngine())
        val restoredFake = FakeAudioPlayerEngine()
        val restoredState = MediaPlayerState(restoredFake)
        try {
            runBlocking {
                savingState.addToQueue("https://example.com/1.mp3", "Episode 1", episodeId = "ep-1")
                savingState.addToQueue("https://example.com/2.mp3", "Episode 2", episodeId = "ep-2")
                savingState.saveSession(database)

                restoredState.restoreSession(database)
            }

            assertEquals(2, restoredState.queue.size)
            assertEquals(-1, restoredState.queueIndex, "the saved idle sentinel must survive the restore")
            assertNull(restoredState.currentUrl, "an idle queue must not open its first item on restore")
            assertTrue(restoredFake.openedSessions.isEmpty(), "no track may be loaded for an idle queue")
        } finally {
            database.close()
            savingState.release()
            restoredState.release()
        }
    }

    // ── stop() invalidates the session (H5) ──

    @Test
    fun testStopDropsLateTrackCompletedInsteadOfAdvancingTheQueue() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.playWithContext(
                context = listOf(
                    QueueItem(url = "url-1", title = "Episode 1", episodeId = "ep-1"),
                    QueueItem(url = "url-2", title = "Episode 2", episodeId = "ep-2")
                ),
                targetUrl = "url-1",
                title = "Episode 1",
                episodeId = "ep-1"
            )
            val session = fake.openedSessions.last()
            testState.stop()

            fake.emitStaleCompletion(session)

            assertNull(testState.currentUrl, "a completion queued before stop() must not start the next episode")
            assertFalse(testState.isPlaying)
            assertEquals(listOf("url-1"), fake.playedUrls, "stop() must not be followed by another open()")
            assertEquals(0, testState.queueIndex)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testStopDropsLateTrackFailedInsteadOfRestoringTheError() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1")
            testState.stop()

            fake.failCurrentTrack(PlaybackErrorCategory.GENERIC)

            assertNull(testState.error, "a failure queued before stop() must not reappear")
            assertFalse(testState.isPlaying)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testStopDropsLateProgress() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1", durationMs = 100_000L)
            fake.seek(50_000L)
            assertEquals(50_000L, testState.currentPosition)

            testState.stop()
            fake.seek(70_000L)

            assertEquals(
                0L,
                testState.currentPosition,
                "a progress event in flight when stop() ran must not resurrect the position"
            )
        } finally {
            testState.release()
        }
    }

    // ── error lifecycle (M6) ──

    @Test
    fun testProgressFromTheFailedSessionClearsTheError() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1")
            fake.failCurrentTrack(PlaybackErrorCategory.NETWORK)
            assertEquals(PlaybackErrorCategory.NETWORK, testState.error)

            fake.seek(1_000L)

            assertNull(testState.error, "a live progress event proves the failed load recovered")
            assertEquals(1_000L, testState.currentPosition)
        } finally {
            testState.release()
        }
    }

    @Test
    fun testPlayingStateFromTheFailedSessionClearsTheError() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        try {
            testState.play("url-1", "Episode 1")
            fake.failCurrentTrack(PlaybackErrorCategory.FILE)
            assertEquals(PlaybackErrorCategory.FILE, testState.error)

            fake.setPaused(false)

            assertNull(testState.error, "a PLAYING event for the failed session means it is no longer failing")
        } finally {
            testState.release()
        }
    }

    // ── Cross-suspend progress write (M4) ──

    @Test
    fun testProgressSaveIsDiscardedWhenTheEpisodeChangesMidWrite() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        val database = AppDatabase.buildInMemory()
        try {
            runBlocking {
                database.episodes.insert(testEpisode("ep-1"))
                database.episodes.insert(testEpisode("ep-2"))

                testState.play("url-1", "Episode 1", durationMs = 100_000L, episodeId = "ep-1")
                fake.seek(95_000L)

                // Run until the first suspension (the episode lookup), which is
                // queued on the database thread. Its continuation is resumed on
                // this event loop, so it cannot run while this block keeps
                // going: the track switch below is guaranteed to land between
                // the two reads, which is exactly the heartbeat's interleaving.
                val save = launch(start = CoroutineStart.UNDISPATCHED) {
                    testState.saveEpisodeProgress(database)
                }
                testState.play("url-2", "Episode 2", durationMs = 200_000L, episodeId = "ep-2")
                fake.seek(190_000L)
                save.join()

                assertNull(
                    database.progress.getById("ep-1"),
                    "the new episode's position must not be written under the previous episode id"
                )
                assertNull(
                    database.progress.getById("ep-2"),
                    "the discarded write must not land on the new episode either"
                )

                // The heartbeat keeps working normally for the current episode.
                fake.seek(100_000L)
                testState.saveEpisodeProgress(database)

                val progress = database.progress.getById("ep-2")
                assertNotNull(progress)
                assertEquals(100_000L, progress.positionMs)
                assertFalse(progress.completed)
            }
        } finally {
            database.close()
            testState.release()
        }
    }

    @Test
    fun testAFailureReportedDuringOpenReachesTheState() {
        // The session has to be claimed *before* the engine is asked to load.
        // An engine may report from inside `open()` — this fake does, and the
        // real one does for a URL it rejects immediately — and when the engine
        // allocated the id instead, those first events were compared against the
        // previous session and dropped as stale. The failure never surfaced and
        // the spinner never cleared.
        val engine = FakeAudioPlayerEngine().apply { failOnOpen = true }
        val state = MediaPlayerState(engine)

        state.play("https://example.com/broken.mp3", "Broken", null)

        assertNotNull(
            state.error,
            "a failure emitted during open() must not be dropped as stale"
        )
    }

    // ── Session persistence of the download fallback (M7) ──

    @Test
    fun testSessionPersistsFallbackUrlAndRestoresTheStreamForADeletedDownload() {
        val database = AppDatabase.buildInMemory()
        val missingDownload = File(
            System.getProperty("java.io.tmpdir"),
            "podara-gone-${System.nanoTime()}.mp3"
        ).absolutePath
        assertFalse(File(missingDownload).exists(), "the test needs a path that is not on disk")
        val savingState = MediaPlayerState(FakeAudioPlayerEngine())
        val restoredFake = FakeAudioPlayerEngine()
        val restoredState = MediaPlayerState(restoredFake)
        try {
            runBlocking {
                savingState.playWithContext(
                    context = listOf(
                        QueueItem(
                            url = missingDownload,
                            title = "Episode 1",
                            episodeId = "ep-1",
                            isDownloaded = true,
                            fallbackUrl = "https://example.com/ep1.mp3"
                        )
                    ),
                    targetUrl = missingDownload,
                    title = "Episode 1",
                    episodeId = "ep-1"
                )
                savingState.saveSession(database)

                restoredState.restoreSession(database)
            }

            assertEquals(
                "https://example.com/ep1.mp3",
                restoredState.queue.single().fallbackUrl,
                "the stream URL must survive the session round trip"
            )
            assertEquals(
                "https://example.com/ep1.mp3",
                restoredState.currentUrl,
                "a restored download that no longer exists must load its stream, not the missing file"
            )
            assertEquals(
                listOf("https://example.com/ep1.mp3"),
                restoredFake.playedUrls,
                "the engine must have been handed the fallback URL"
            )
        } finally {
            database.close()
            savingState.release()
            restoredState.release()
        }
    }

    @Test
    fun testQueuedDownloadKeepsItsStreamUrlForTheRestOfTheSession() {
        // Deleting a download while the app is running leaves the queue item
        // pointing at a path that no longer exists. The stream URL has to travel
        // with the item, because playNext resolves from the in-memory queue and
        // there is no database row to consult.
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        val missingDownload = File(
            System.getProperty("java.io.tmpdir"),
            "podara-gone-${System.nanoTime()}.mp3"
        ).absolutePath
        try {
            testState.play(
                url = missingDownload,
                title = "Episode 1",
                episodeId = "ep-1",
                fallbackUrl = "https://example.com/ep1.mp3"
            )
            assertEquals(
                "https://example.com/ep1.mp3",
                testState.queue.single().fallbackUrl,
                "the queue entry must carry the stream URL of what is playing"
            )

            // The file is gone by the time the queue replays it.
            testState.playFromQueue(0)

            assertEquals("https://example.com/ep1.mp3", testState.currentUrl)
            assertEquals("https://example.com/ep1.mp3", fake.playedUrls.last())
        } finally {
            testState.release()
        }
    }

    @Test
    fun testPlayWithContextRecordsTheStreamUrlOnTheMatchedContextItem() {
        val fake = FakeAudioPlayerEngine()
        val testState = MediaPlayerState(fake)
        val missingDownload = File(
            System.getProperty("java.io.tmpdir"),
            "podara-gone-${System.nanoTime()}.mp3"
        ).absolutePath
        try {
            testState.playWithContext(
                context = listOf(
                    QueueItem(
                        url = missingDownload,
                        title = "Episode 1",
                        episodeId = "ep-1",
                        isDownloaded = true
                    )
                ),
                targetUrl = missingDownload,
                title = "Episode 1",
                episodeId = "ep-1",
                fallbackUrl = "https://example.com/ep1.mp3"
            )

            assertEquals(
                "https://example.com/ep1.mp3",
                testState.queue.single().fallbackUrl,
                "a matched context entry must gain the stream URL it was missing"
            )
        } finally {
            testState.release()
        }
    }

    private fun testEpisode(id: String) = PodcastEpisode(
        id = id,
        guid = id,
        origin = "https://example.com/feed.xml",
        link = "",
        title = "Episode $id",
        description = "",
        author = "",
        pubDate = 0L,
        duration = 100,
        audioUrl = "https://example.com/$id.mp3",
        podcastTitle = "Podcast"
    )

    /**
     * In-memory engine for state-layer tests.
     *
     * Mirrors the real engine's contract rather than its implementation: it
     * assigns a session per [open], stamps every event with it, and emits
     * `TrackCompleted` for a natural end. `sessions` lets a test assert the
     * caller correlated correctly, and [completeCurrentTrack] is how the old
     * "end of episode" scenario is now expressed — no wall-clock involved.
     */
    internal class FakeAudioPlayerEngine : AudioPlayerEngine {
        val playedUrls = mutableListOf<String>()
        val openedSessions = mutableListOf<Long>()
        val pausedCalls = mutableListOf<Boolean>()

        private var handler: ((PlayerEvent) -> Unit)? = null
        private var currentSession = 0L

        /**
         * When true, [open] reports a failure synchronously.
         *
         * Models a URL the backend rejects immediately, which is the case that
         * proved the session had to be claimed before the load started.
         */
        var failOnOpen = false

        override var snapshot: PlayerSnapshot = PlayerSnapshot()
            private set

        override fun setEventHandler(handler: (PlayerEvent) -> Unit) {
            this.handler = handler
        }

        private fun emit(event: PlayerEvent) {
            handler?.invoke(event)
        }

        override fun open(session: Long, url: String, speed: Float, startPositionMs: Long, durationMs: Long): Long {
            playedUrls.add(url)
            currentSession = session
            openedSessions.add(currentSession)
            snapshot = PlayerSnapshot(PlaybackState.LOADING, startPositionMs, durationMs, currentSession)
            if (failOnOpen) {
                snapshot = snapshot.copy(state = PlaybackState.ERROR)
                emit(PlayerEvent.TrackFailed(currentSession, PlaybackErrorCategory.GENERIC))
                return currentSession
            }
            emit(PlayerEvent.StateChanged(currentSession, PlaybackState.PLAYING))
            return currentSession
        }

        override fun setPaused(paused: Boolean) {
            pausedCalls.add(paused)
            snapshot = snapshot.copy(state = if (paused) PlaybackState.PAUSED else PlaybackState.PLAYING)
            emit(PlayerEvent.StateChanged(currentSession, snapshot.state))
        }

        override fun stop() {
            snapshot = snapshot.copy(state = PlaybackState.STOPPED)
            emit(PlayerEvent.StateChanged(currentSession, PlaybackState.STOPPED))
        }

        override fun seek(positionMs: Long) {
            snapshot = snapshot.copy(positionMs = positionMs)
            emit(PlayerEvent.Progress(currentSession, positionMs, snapshot.durationMs))
        }

        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(vol: Int) = Unit
        override fun release() = Unit

        /**
         * Reports that the current track played to its natural end.
         *
         * This is the replacement for "advance 4 seconds on the wall clock and
         * fire a false play-state": completion is now an explicit event tied to a
         * session, so there is no timing to get wrong.
         */
        fun completeCurrentTrack() {
            snapshot = snapshot.copy(state = PlaybackState.STOPPED)
            emit(PlayerEvent.TrackCompleted(currentSession))
        }

        /** Reports that the current track failed to load or dropped mid-stream. */
        fun failCurrentTrack(category: String = PlaybackErrorCategory.NETWORK) {
            snapshot = snapshot.copy(state = PlaybackState.ERROR)
            emit(PlayerEvent.TrackFailed(currentSession, category))
        }

        /** Emits an event for a session that is no longer active. */
        fun emitStaleCompletion(session: Long) {
            emit(PlayerEvent.TrackCompleted(session))
        }
    }
}
