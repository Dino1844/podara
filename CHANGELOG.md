# Changelog

All notable changes to podara will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.6] - 2026-10-26

### Added
- Subscribe by pasting a feed URL into the Discover search field. The query is first run through a pure predicate: an explicit `http(s)://` address is used as-is, and a bare domain with a letter TLD (say `example.com/feed.xml`) is completed to `https://` — deliberately strict rules, so an ordinary keyword such as `npr`, a Chinese phrase or `3.5` is never mistaken for an address. A URL subscribes straight through the existing `addPodcast(origin)` path and opens the podcast's page on success; a feed that will not fetch or parse raises the existing error banner instead of blanking the results. Podcasts absent from Apple's catalogue — independent Chinese shows, self-hosted feeds — previously had no way into the app at all
- Per-podcast playback speed. `podcastSubscription` gains `playbackSpeedOverride` (schema v6; NULL follows the global session speed). Every load path applies the current show's override through one `applySpeed` choke point with a stale-episode guard, and changing speed in the player writes the value back to that show. A "clear speed memory" entry appears in the podcast's `…` menu only while an override exists
- Chapter support. After a file loads, the engine reads mpv's `chapter-list` as an `mpv_node` (parsing it while the memory is held and freeing it unconditionally afterwards; a malformed node degrades to "no chapters" rather than killing the worker), and observes the `chapter` property for the current index. The full player gains a chapters drawer beside the queue — title, start time in tabular figures, click to seek, and the current chapter highlighted in the sidebar's selected language — with the entry point hidden entirely when the file has no chapters
- Per-podcast download retention. `podcastSubscription` gains `keepDownloads` (schema v7; 0 never prunes and is the default, tiers offered are 0/5/10/20). After a refresh that actually found new episodes, and again after each download completes, that show's downloads beyond the newest N by download time are deleted through the existing deletion path: the episode currently playing is exempt, and a file Windows refuses to release keeps its database row for the next sweep, so the library and the disk stay consistent
- Skip-silence and loudness-boost toggles in the full player, beside the speed control. Both overwrite mpv's `af` property at runtime through a single pure filter-chain builder — the whole chain is recomputed from the two switch states, so repeated toggling can never stack filters, and a last-applied guard skips no-op writes; switching does not restart the stream, verified against the real mpv-1.dll mid-playback. Skip silence uses `silenceremove` with `stop_threshold=-30dB` (the option mpv actually accepts; the more common `threshold=` spelling does not exist there), and loudness boost uses a fixed `volume=6dB` filter rather than `loudnorm`, which pumps on speech and adds latency. Both persist in Settings and are replayed into the engine on launch. One inherent cost to know: skip-silence shortens the timeline mpv reports, so the progress bar and the resume point follow the filtered timeline
- The app now ships its own typeface instead of the platform default: three subsetted weights of Noto Sans SC (400/500/700, about 1.8 MB each, OFL notice alongside) load from resources and every Material typography style is re-pointed to them, so Chinese text gets a true drawn bold instead of the algorithmic one Windows synthesizes for `SansSerif`. The subset — every character of both string tables plus a 3,500-common-hanzi union and ASCII — is built by the reproducible pipeline in `tools/font-subset/`, and a coverage test parses `Strings.kt` and asserts every glyph exists in all three weights via AWT, so a future rare character fails the build instead of silently falling back to the system font; an ink-mass assertion also pins that the 700 weight is really drawn heavier
- Every icon in the app is now drawn outlined instead of filled. On the same 20dp grid the filled family carries roughly three to four times the ink, so every glyph read as heavier than the 13sp label it served — the weight relation between icon and text was inverted. Play/pause stays filled by recorded exception, and a coverage test scans desktopMain so `Icons.Default.*` can only return through an explicit file+glyph whitelist
- Theme finishing: the sidebar navigation sits in a track; the dark theme answers selection with the same elevated pill as the light one instead of a gold wash, leaving gold to "now playing" alone; toolbar pills and menu rows select through neutral washes instead of red; the Discover hero title drops to Medium like every other page title
- A persisted "reduce motion" switch in Settings. Screen pushes, the full-player slide, the queue drawer and dialogs degrade to plain fades when it is on; tooltip popovers were verified to already be fades
- Loading states are skeletons instead of spinners on Home, Discover and podcast detail, with the same row geometry as the loaded list so nothing jumps when data lands; the player's own spinner is untouched
- Episode-row secondary actions start hidden and fade in on hover or keyboard focus (both, or focusability would regress), implemented once in the shared row component so every screen inherits it
- The player separates a stall from a failure. The engine observes mpv's `paused-for-cache` — which stays invisible otherwise, because mpv pauses internally and the `pause` property never moves — and the full player shows a quiet buffering bar in the error bar's container language, without a retry affordance: a stall recovers by itself, a failure does not. The flag is cleared by a new load, a failure, a user pause during a stall, and stop
- The sidebar speaks in two labelled groups, Browse and Library, and the Subscriptions row carries a badge with the total of new episodes across shows — one aggregate query, recomputed on every refresh/open/unsubscribe event, cleared the same moments the per-show badges are
- Show notes keep their own reading width: prose stops at ~40em (560dp) while the 860dp sheet width stays a scanning measure for lists — a full-width notes box ran 60+ CJK glyphs per line, and Chinese is comfortable at 35–45
- Icon optical alignment is a rule now, not a one-off nudge: `DesignTokens.IconBox` lists the glyphs allowed a compensation (the play triangle's rightward shift) and the glyph renderer reads it from there — any new offset has to join the list, not appear at a call site
- Audit follow-ups from the visual wave: the audio-enhancement glyphs sit one grey step below their labels (the accent is carried by border and label only, never a wash); the player's compact pills (speed, enhancements, retry) share one named height token instead of three inline 30dp values; both drawers' top corners sit on the radius scale (16dp) instead of an off-scale 12; the chapters drawer adopts the queue drawer's 28dp dismiss disc, header rhythm and one shared close string; its list gained the queue's bottom inset so the last row's hover fill clears the corner; and every speed readout — mini pill, full-player pill, preset menu — renders in tabular figures

### Tests
- `BufferingStateTest`: the flag tracks the event, a new load clears an in-flight stall, a failure replaces it, a user pause during a stall drops it, and stale-session events are ignored
- `IconMigrationCoverageTest` (the outlined-only gate with a file+glyph whitelist), `ReducedMotionSettingsTest` and `ReducedMotionTransitionTest`, `EpisodeListSkeletonTest` (skeleton rows match loaded-row heights) and `EpisodeRowActionRevealTest` (hover and focus both reveal actions)
- `BundledFontCoverageTest` (existence, size budget, AWT load, full string-table coverage per weight, ink-mass bold check) and `IconsContrastMockupTest`, which renders the sidebar, toolbar pills and episode actions filled-above/outlined-below in both themes into `mockups/icons-light.png` and `mockups/icons-dark.png` — the decision artifact for the outlined-icon migration
- `SearchInputFeedUrlTest` and three new end-to-end Discover cases over a MockEngine (a pasted URL subscribes through the feed fetch rather than iTunes search, a bare domain is completed to `https://`, a failing feed shows the error banner); `PodcastSpeedOverrideTest`; schema migration tests for v6 and v7; `KeepDownloadsCleanupTest` (ordering, tie-breaks, exemptions, occupied-file consistency, trigger conditions); `MpvChapterParsingTest` against hand-built JNA node structures, `MediaPlayerStateChaptersTest` and `FullPlayerChaptersTest`; `AudioFilterChainTest`, `AudioEnhancementsTest` and `FullPlayerAudioEnhancementsTest`, including two real-mpv integration cases — toggling filters mid-playback keeps time advancing with no failure events, and writing the chain twice does not stack filters

## [1.0.5] - 2026-10-24

### Fixed
- Searching Discover no longer crashes the screen. iTunes legitimately returns several catalogue entries for one feed (two "Planet Money" entries share `feeds.npr.org/510289/podcast.xml`, "radiolab" and "history" return the same shape, and Chinese terms do too), and the results list is keyed on `fetchUrl` — Compose throws `Key was already used` on a duplicate, so an ordinary query such as `npr` blanked Discover entirely. Results are now deduplicated at the search route, where the duplicate enters the app, and again at the single point where both the charts and search results become the list's data source, so neither source can take the screen down. Covered by tests that fail without the deduplication, including one that drives the real route over a MockEngine with a verbatim duplicate payload
- A search with no matches now says so. It used to render the same "Search podcasts" hint shown before anyone typed, so a query that found nothing looked exactly like the search had never run. Empty results now name the query and suggest a different title or keyword
- Playback and the progress bar work again. Two mistakes in the libmpv event binding, both invisible to a green test suite, disabled the transport entirely: an observed property arrives wrapped in an `mpv_event_property`, and its value hangs off that struct's own `data` field, but the code read the wrapper's header as if it were the value — so `time-pos` and `duration` were always 0, the bar never moved, and dragging it had nothing to seek against
- Clicking play/pause now toggles both ways. `MPV_FORMAT_FLAG` and `MPV_FORMAT_DOUBLE` were declared as 2 and 4; mpv's real values are 3 and 5 (`OSD_STRING` and `STRING` occupy the gap). `mpv_observe_property` therefore rejected `pause` and `eof-reached` with -9, so the engine never learned it had been paused — the play button stayed on "pause" and every click logged a pause of something already paused
- Auto-advance works again, for the same reason: no `eof-reached` event ever reached the queue, so an episode ending did nothing at all. The end-of-track latch is also reset when mpv clears the flag, so replaying an episode reports its end a second time instead of finishing in silence
- A failed `mpv_observe_property` is now logged. Both of the above were silent by construction: the calls returned an error that nothing checked
- Restoring the last session no longer starts it playing on launch. `start` (the resume position) is now written before `loadfile` — mpv reads it when the file opens, so writing it afterwards applied to nothing — and a resumed load stays paused instead of being unpaused and then raced by the `pause` that follows
- Opening a file no longer reports "playing" when it was loaded paused. `FILE_LOADED` means the source opened, not that audio is running; it now asks mpv for the real pause state, which is what made a restored session show a pause icon over silence
- Going back from a podcast opened from search now returns to the search results. The query, the results and the "already searched" flag were `remember`ed inside the search screen, and the screen swap disposes that subtree — so returning rebuilt the screen with an empty query and an empty list, and the back button appeared to drop the user on Home. They now live in the shell and survive the trip, and the screen no longer re-fetches on return, which was silently replacing the results with a second, possibly different, page
- Returning from a podcast detail leaves the list where it was. Each screen's `LazyListState` was remembered inside the screen it belonged to, so the same disposal threw away the scroll cursor. The shell now owns one list state per screen, keyed by screen rather than by stack position, so a list that is re-entered at a different depth still resumes where it was left
- A podcast list no longer jumps back to the top after a podcast detail. The screen's search text and its list state now survive together; keeping one and resetting the other is what produced a list scrolled to a position the filter no longer justified
- The engine no longer attributes the previous file's events to the next one. Switching episodes left `mpv` with events still queued for the file being replaced, and the first of them arrived after the new session was already current — so a leftover end-of-file advanced the queue past an episode the user had just started, and a leftover failure put an error on a track that was playing fine. Each `open` now drains what mpv has already queued before loading, and the state layer ignores anything stamped with a superseded session
- A load that fails immediately now shows the failure. The session id was allocated by the engine, so events it emitted from inside `open()` were compared against the *previous* session and dropped as stale: no error appeared, and the spinner stayed up for ever. The caller now claims the id before calling `open`, so the first event of a load is already known to belong to it
- A crash during playback no longer resumes a different episode at the wrong position. The progress heartbeat wrote the position but left the session row's queue index and episode id describing whatever was playing at the last clean exit, so restoring that pair resumed the wrong episode part-way through. The heartbeat now writes all three, and a restore drops the position when the queue row and the episode id disagree instead of trusting it
- Refreshing a podcast no longer wipes its new-episode badge. Every refresh wrote the count unconditionally, so a refresh that found nothing new cleared the badge a previous refresh had just set — and the detail screen, "Refresh all" and the startup refresh could each undo the others
- Episodes with no guid and no link no longer collapse into one row. The fallback chain ended at the audio URL only after trying a title-based key that was identical for every episode of a feed that publishes neither, so a daily show with a bare feed collapsed into a single episode that was overwritten on every refresh
- "Forward 10 seconds" no longer jumps to the start of the episode while it is loading. The seek clamped against a duration of 0, which is what the duration is until the file reports one, so the clamp turned every forward seek into "back to zero"
- The speed shortcut now steps to the neighbouring preset. It matched the current speed against the preset list with an exact comparison, so a speed that was not exactly on a preset (1.1× from a restored session) skipped a step and jumped two
- Un-muting restores the last audible volume instead of the volume from the previous mute. Dragging the slider to zero and then pressing mute used to bring back whatever level was current the last time mute was pressed, which could be a level abandoned long before
- A stopped player no longer shows the previous episode's show name. The subtitle is the show, and it was left behind by `stop()`, so a stopped player read "no title / still that show"
- Pressing play with nothing loaded no longer pretends to play. Unpausing an engine that holds no file still produced a `pause=false` property event, which flipped the transport to "pause" over silence; it now starts the queued track when there is one and does nothing when there is not
- Deleting a download that Windows refuses to delete no longer loses the record. `File.delete()` returns a boolean and it was discarded, so a file held open by the player — the common case, since it is the episode being listened to — stayed on disk while its database row went away, leaving an orphan the app could no longer clean up
- Adding a podcast that is already subscribed now repairs the missing subscription row. The duplicate path returned early from three different places, so a podcast whose subscription row had been lost (an import from a build that never wrote one) stayed permanently unrefreshed and permanently "not subscribed" in its own detail page
- HTML entities in show notes are decoded before the markup is stripped. The order was backwards, so `&amp;` and `&#8217;` were left in the text after the tags were gone and the reader showed them literally
- Cancelling or pausing a download takes about a second instead of up to thirty. The flags were only read between `inputStream.read()` calls, and a stalled server blocks that read for the full read timeout — `disconnect()` does not interrupt an already-blocked body read on this JDK, which was verified rather than assumed. The transfer now reads with a one-second timeout and re-checks the flags on each timeout, with a stall budget so a genuinely dead server still fails instead of retrying for ever; the connect-and-read-headers step retries with a growing timeout, because a timed-out header read leaves that connection unusable
- Favouriting an episode and recording a resume point are now single transactions. Both read a row and then wrote a value derived from it, in two separate autocommitted statements, so two quick toggles could both see the same starting state and leave the opposite of what was asked for, and a completion could be written back to "in progress"
- The playback queue can no longer be corrupted by the engine thread. `handleEngineEvent` runs on the mpv worker and auto-advances the queue, while the UI thread adds, removes, reorders and clears it; concurrent structural mutation of a snapshot list can lose an element or throw. Every queue read-modify-write now happens under one lock, and the engine and database calls always happen after it is released
- Shutting down now says when the engine thread refuses to stop. `release()` returned after the join timeout regardless, so a caller believed the native handle was destroyed while `mpv_wait_event` was still running against it. Shutdown now interrupts and waits again, reports at error level with the worker's stack, and never throws out of `release()`
- The Downloads screen no longer reads the filesystem from the database thread. Opening it stat'ed every downloaded file inside the single database dispatcher, so the whole UI waited on the disk and every unrelated query queued behind it; the rows come from the dispatcher, the stat happens off it, and stale rows are deleted in one batched statement instead of one round trip each
- A queue entry whose download was deleted now falls back to streaming. The queue item recorded only the local path, so after a restart the restored session had nothing else to play and failed with a FILE error; the stream URL is now stored on the queue row (schema v5) and threaded through `play`, `playWithContext` and `addToQueue`, so a missing local file falls back instead of failing
- Playing a downloaded episode no longer rewrites its stored stream URL. The episode row was upserted with the resolved local path in `audioUrl`, so after one listen the database said the episode lived at `C:\...\episode.mp3` — History, the reloaded detail list and anything else reading that row then had no stream to fall back to
- Searching History or Favorites now finds words that only appear in the show notes. Both screens filtered their loaded rows with `title.contains`, so an episode whose notes mentioned a topic was not found by searching for it even though the full-text index had recorded it. The index is now queried for the ids the screens already hold, and the in-memory match is kept as a fallback for history rows whose episode row is gone
- Switching episodes no longer leaves the previous episode's description on screen. The full player cleared its episode and origin state only when the id changed, and a failed lookup left the old pair in place, so the notes panel could describe the episode that had just finished

### Added
- End-to-end Discover search tests: one types a query into the real search field and presses Enter, then asserts the results render; the other does the same for a query that matches nothing. They pin the whole path — field focus, Enter-to-submit, the network route and the results list — rather than any one layer of it
- `RealPlaybackIntegrationTest`: end-to-end playback checks against the real `mpv-1.dll` and a generated WAV. The previous engine tests faked the event stream, so they asserted that events arrived and never that the numbers inside them were right — the exact gap both binding bugs lived in
- `DatabaseSchemaMigrationTest` and `DatabaseTransactionTest`, which pin the schema versions and the transaction contract — nesting joins the owner's transaction, only the owner commits, and a rollback discards the nested work
- `QueueConcurrencyTest`: a fake engine fires end-of-track from a background thread while the test thread mutates the queue, which is the interleaving that used to corrupt it
- `DownloadCancelTest`: a local server that sends headers and then stalls, so cancel and pause are asserted to return promptly rather than at the read timeout — plus the opposite case, a slow but steady transfer that must still complete
- `ThreadShutdownTest`, `ArtworkSeedTest`, `SearchDaoMatchingIdsTest` and `DownloadDaoFileIoTest`, covering the shutdown escalation, the colour seed, the membership query and the download reads

### Changed
- Test runs no longer play audio out loud. The integration tests decode real files, so with a real output device every run of the suite played a burst of test tone through the speakers. The test task now sets `podara.test.silentAudio`, which builds the engine against mpv's `null` output driver: identical decoding, identical events, no device. The engine also logs whether it ended up silent (`test playback is silent: ao=null`), so the next time this regresses it shows up as a log line rather than a surprise, and two tests assert the switch is on
- Artwork placeholders are tinted per show. `imageSeedColor` has been in the schema and in every model since the first version, but every write path passed 0, so the placeholder was one grey rectangle for the whole library. The seed is now derived from the feed's identity — stable across launches, spread across shows — and mixed into the placeholder surface at low weight so it stays a surface in both themes
- The full-text index is no longer write-only. It was maintained by triggers on every episode write while no screen ever read it; History and Favorites now query it, which is what makes show-note matches findable
- Database schema is at version 5: `playerQueueItem.fallbackUrl` for the deleted-download fallback, and the `syncAction` table dropped

### Removed
- `SyncManager`, `SyncActionDao` and the `syncAction` table. The manager was never instantiated and nothing ever enqueued an action, so the class, the DAO and the table were three pieces of dead wiring kept alive by each other

## [1.0.4] - 2026-10-19

### Added
- Keyboard control for playback: Space plays and pauses, the arrow keys seek and change volume, F opens the full player, Q opens the queue, and `-`/`=` step through the speed presets. Escape closes the topmost overlay first, so it backs out of the queue, then the player, then a dialog, instead of doing nothing
- A visible keyboard focus indicator on every control. Controls were previously unfocusable, so Tab reached nothing at all and the app could only be driven with a mouse; the ring is now drawn only after Tab is pressed, because showing it after a mouse click looked like a rendering glitch
- Per-podcast new-episode notifications, delivered through the tray icon. The subscription already had an `enableNotifications` column but nothing read or wrote it, so the toggle in a podcast's menu had no effect
- A "new episodes" badge on subscription cards that finally populates. The count was stored in a column that no write ever touched, so the badge could never appear; it now reflects the episodes each refresh added and clears when the podcast is opened
- A resume progress bar along the bottom of every started episode row, so a partly heard episode is distinguishable from an untouched one at a glance and a finished one is visibly done
- Per-episode resume points, so the "Continue listening" card remembers the last episode actually in progress. It previously read the single player-session row, so switching episodes wiped the card's memory
- Full-text search over episode titles and descriptions, backed by SQLite's FTS5 with an index kept in step by triggers. Searching previously loaded every row and filtered it in memory, which pulled each episode's multi-kilobyte HTML description in to run a substring test
- A design system with real scales: six type sizes, six grayscale steps, three corner radii, three elevation levels and a five-step spacing scale. Seventeen type sizes and nine radii were previously chosen per component, so nothing read as more important than anything else

### Changed
- JNA is now resolved from Maven Central at 5.14.0 instead of two 5.6.0 jars checked into `libs/`. The old jars are still in the repository but no longer used by the build
- The installer's runtime image now links an explicit module set instead of the entire JDK, bringing the JRE down from roughly 155 MB of a 180 MB installer
- Search fields across Discover, Subscriptions, History and Favorites are now one shared component. Only Discover's had ever wired up Enter-to-submit, so pressing Enter in the other three silently did nothing
- Timestamp formatting, remaining-time calculation and progress fractions now live in one place instead of four character-for-character copies
- History, Favorites, Downloads and Settings now have a working back button. Each declared an `onBack` parameter that was passed in and never used, with no way back off the page
- The playback queue drawer is now a lazy list keyed by episode, so it no longer composes every queued card up front
- The audio engine now owns libmpv from a single thread and pushes events. It previously polled four properties every 250 ms while the UI thread could destroy the handle underneath the poller, and end-of-track was inferred by comparing wall-clock time against a three-second window, which misfires on short episodes and slow loads
- Page titles are set at 32px instead of 20px. At 1.5× body size a title read as a slightly larger row rather than the page's subject
- Section headings sit one grayscale step below page titles instead of sharing their colour, so a heading no longer competes with the title above it
- Light-theme muted text was darkened from `#86868B` to `#6E6E73`, taking contrast from 3.62:1 to 5.07:1 on white and clearing WCAG AA for the smallest text in the scheme
- Choosing a language in Settings now persists immediately. The selection previously lived only in memory and was written by a separate Change button, so picking a language and navigating away reverted on the next launch
- The download manager is created once and retargeted when the download path or speed limit changes, instead of being rebuilt. Changing either setting used to strand every running transfer, with the UI pausing an object while the old one kept writing
- The main window now has a minimum size of 900×600, derived from the fixed sidebar, the search bar and a full list row. The window is undecorated, so with no floor the layout squeezed the episode title and timestamps to nothing instead of shrinking the fixed-size player buttons
- Download progress is throttled to about 4 Hz instead of recomposing on every 8 KiB read
- The downloaded-episodes list now composes one lazy row per episode instead of drawing an entire podcast's back catalogue in a single item, and the file existence/size checks it needs are taken off the UI thread once per refresh rather than re-run in composition on every scroll. A show with 500 downloads no longer stutters while scrolling

### Fixed
- Launching a second copy no longer crashes the app. SQLite had no busy timeout, so the second copy hit "database is locked" during schema creation and the exception took down composition; a file-lock single-instance guard and WAL with a 5 second timeout now make contention wait instead of fail
- Quitting no longer races the libmpv poll thread. `release()` interrupted the thread but never joined it before destroying the handle, and `interrupt()` only breaks `Thread.sleep`, so a thread inside `mpv_get_property_string` kept running against freed memory. Shutdown now raises a flag, interrupts, joins, and only then terminates
- Downloads that fail no longer get saved as finished audio. Any response body was written to disk regardless of status, so a 404 or 500 page became a "completed" download and an HTML error page served with a 200 became an `.mp3`. Responses are now validated by status and by content type
- Double-clicking download no longer opens the same file twice. The guard read a Compose snapshot, so two calls in one frame both passed it and interleaved bytes into the same path; they are now reported as already downloading
- Deleting a downloaded episode no longer loses track of the file. The delete ignored `File.delete()`'s result and dropped the database record anyway, which on Windows left an orphan on disk whenever the file was still open — most likely because it was the episode playing
- Deleting a single downloaded episode now asks for confirmation. The dialog existed but its flag was never set, so a single delete happened immediately while batch delete confirmed first
- Retrying a failed load in an unsubscribed podcast's preview now actually retries. It called a path that returns "not subscribed" on its first line
- The tray menu's Play/Pause label is no longer inverted. It was written to the previous menu's item, which had already been disposed, so the entry read "Play" while audio was playing and the click then paused it
- "Select all" in subscriptions now only counts podcasts the current search actually shows, and batch unsubscribe only removes those. A filter applied after selecting left hidden shows in the selection, so the dialog could say it would remove 3 and remove 5 with no way to undo
- The full player's timeline slider no longer freezes mid-drag when a drag is cancelled — alt-tabbing away mid-drag left it stuck at the dragged position while the episode played on
- Adding an episode to the queue twice no longer crashes the queue drawer. The drawer keys its list on episode id, and Compose throws on a duplicate key, which blanked the drawer; the same deduplication now applies to context queues built from Favorites or History, which can legitimately hold two rows for one episode
- Seeking, volume and speed shortcuts no longer fire while typing. They run on a preview handler at the root of the app, so a space typed into the search box would previously pause playback and every arrow press would seek
- Podcast feeds declared as GBK, ISO-8859-1 or another non-UTF-8 charset are now decoded correctly instead of arriving as mojibake. The charset comes from the response `Content-Type`, then the XML declaration, then UTF-8
- Feeds and downloads now identify the app with its own User-Agent instead of looking like a bare HTTP library. Acast, Megaphone, Art19 and other hosts answer 403 to the latter, so a feed that opened fine in a browser could refuse to subscribe
- The top-charts cache is now written through a temp file and a rename, so a crash mid-write can no longer leave a truncated file that reads as "no cache" and silently costs the next start its instant render
- Stopping playback no longer strands the player. It left the playing flag set and the duration stale, so a stopped episode sat there with a paused icon and a play button that stayed disabled
- Playing an episode from the queue whose download has since been deleted now falls back to the stream URL instead of failing on a path that no longer exists
- History, Favorites and episode lists can no longer reorder arbitrarily between reloads. Ordering was by timestamp or publication date alone with ties resolved by whatever the query plan produced; it is now total, with the id as the tiebreaker
- List rows keep their selection, hover and download state when a sorted or filtered list is re-populated. The lists had no item keys, so Compose reused slots by position and that state jumped to whichever item took the old index
- A quit that raced a still-running fetch no longer leaves the settings file truncated; it is written to a temp file and renamed
- The About page and the installer can no longer disagree about the version. The page hardcoded `0.1.0` while the installer said `1.0.2`, and the two tests asserting that text hardcoded it as well, so every release bump broke them. Both now read a single constant

### Tests
- Added regression coverage for keyboard shortcut mapping, playback-speed stepping, window minimum geometry, tray Play/Pause labelling, queue deduplication, feed charset detection, and language persistence
- Added coverage for the client User-Agent on every Apple request, the top-charts cache's temp-then-rename write, download `newEpisodes` accounting, single-download delete confirmation, and the flattened downloads list
- Test runs no longer write into the developer's own `~/.podara/debug.log`. A full suite run appended roughly 10k lines of fake download paths and failures to the real log; the log directory is now redirected per test JVM

## [1.0.2] - 2026-07-28

### Changed
- Release builds now verify that the Git tag matches `packageVersion`, run the complete test suite, and smoke-test the packaged application before producing installers
- CI now runs the complete desktop test suite instead of maintaining an explicit test allowlist

### Fixed
- OPML import now rejects oversized documents, DOCTYPE declarations, external entities, external DTDs, and entity-expansion payloads
- Podcast feed fetching now falls back to a conditional GET when HEAD is rejected or fails, and limits response bodies to 10 MiB
- Download paths now use stable feed and episode hashes, preventing same-title overwrites, directory traversal, invalid Windows device names, and title changes breaking paused downloads
- Podcast creation, subscription refresh, cache metadata updates, and queue replacement now use database transactions so partial failures roll back cleanly
- JDBC statements and result sets are now closed deterministically across all database DAOs
- System window close requests now use the same close handler as the custom title bar, so Alt+F4/taskbar close respects saved close behavior and persists the playback session before quitting
- Removing the currently playing queue item now preserves the next item's subtitle, artwork, podcast artwork, and episode id when playback advances
- Resuming a paused download now restarts from scratch if the server ignores the `Range` request instead of appending a full response to the partial file
- Download file extensions are now derived from the URL path and restricted to known audio extensions, avoiding invalid filenames for extensionless CDN URLs
- Podcast refresh now updates subscribed podcast metadata and existing episode metadata while preserving local play-state records and podcast user settings
- RSS refresh no longer treats matching `Content-Length` alone as unchanged; it still relies on 304/ETag/Last-Modified signals before skipping a GET
- Database DAO calls now run through a single database dispatcher to serialize access to the shared SQLite connection across coroutines

### Tests
- Added regression coverage for malicious and oversized OPML, HEAD fallback, feed-size limits, download path safety, same-title downloads, and transactional rollback
- Replaced external MP3 dependencies in download tests with deterministic local HTTP servers
- Added regression tests for queue metadata preservation after deleting the current item, Range-ignored download resume, safe download extension parsing, subscription metadata refresh, Content-Length cache handling, and concurrent database DAO access

## [1.0.1] - 2026-07-13

### Fixed
- Queue episode cards now fall back to the podcast artwork when an episode has no cover image, consistent with the episode list behavior (applies to playback initiated from PodcastDetail, History, Favorites, and Downloads)
- Add Podcast dialog now shows a centered loading spinner with "Adding…" while the RSS feed is being fetched, and only closes on success

## [1.0.0] - 2026-07-11

### Changed
- Refined the visual system with subtle metallic surfaces and consistent hover, pressed, border, and accent states across primary controls
- Redesigned subscription, History, Favorites, and Downloads views around consistent episode cards, selection toolbars, empty states, spacing, and action affordances
- Refined the playback queue and full-player controls for clearer hierarchy and consistent pill-style actions
- Unified Settings controls and in-app confirmation dialogs with shared layouts, action styles, and visual states
- Standardized dropdown menus and toolbar actions across list-management workflows

### Fixed
- Fixed the currently playing History entry so the selected play record is highlighted accurately
- Fixed paused and active download task transitions to keep each task visible once with the correct progress, state, and actions
- Fixed “Recent Update” subscription sorting to use each podcast’s latest episode date, with deterministic tie-breaking
- Fixed playing an episode from Favorites so it is recorded in listening history

### Tests
- Added regression coverage for History selection state, download-task state transitions and ordering, latest-episode subscription sorting, favorite playback history recording, and updated Compose screen interactions

## [1.0.0-alpha5] - 2026-07-09

### Added
- **Episode favorites** — added persistent episode-level favorites with a new Favorites sidebar page matching the History page layout and styling
- **Favorite buttons across episode lists** — users can favorite/unfavorite episodes from PodcastDetailScreen, History, Downloads, FullPlayer recommendations/current episode, and QueueDrawer where a concrete episode is available
- **Favorites persistence** — new `podcastFavorite` table, `PodcastFavorite` model, and `FavoriteDao` store favorites by `episodeId` with snapshot metadata so favorites remain visible even when the source episode row is missing
- **Favorite UI components** — shared `EpisodeActionIconButton` and `FavoriteEpisodeButton` for consistent 36dp circular hover actions across screens
- **Context-aware playback queue** — playing an episode from History, Favorites, PodcastDetail, or Downloads now replaces the entire queue with that page's episode list, so users can naturally play through the context. Manual `addToQueue()` still appends after context items
- **Fly-away animation for queue button** — a new `AddToQueueButton` composable shows a translucent PlaylistAdd icon flying downward and slightly rightward with fade-out, giving visual feedback when an episode is added to the queue

### Changed
- Sidebar navigation now includes Favorites between Subscriptions and History
- CI test allowlists now include `FavoriteDaoTest` and `FavoritesScreenTest`
- FullPlayer queue button now shares the same `QueueDrawer` as MiniPlayer (slides from right with scrim overlay), replacing the standalone internal drawer that couldn't close via scrim click
- DownloadsScreen completed rows now play with proper cover art (episode artwork loaded from DB on context build)

### Fixed
- DownloadsScreen completed download playback missing cover art in MiniPlayer and FullPlayer — now queries episode imageUrl from database and passes it through playWithContext

### Tests
- Added `FavoriteDaoTest` for insert, replace, delete, toggle, clear, snapshot fallback, and persistence across database reopen
- Added `FavoritesScreenTest` for empty state, title/search rendering, hidden clear action when empty, and saved episode rendering
- Updated History, Downloads, and App GUI tests for the new favorites-aware screen signatures

## [1.0.0-alpha4] - 2026-07-08

### Added
- **Real-time seek preview** — dragging the progress slider in MiniPlayer and FullPlayer now shows the scrub position live in the time display, instead of only revealing the position after releasing the drag
- **Playback progress persistence** — app now saves playback position every 30 seconds and on quit; reopening resumes from where you left off. Queue and playback state (speed, volume) are also persisted across sessions

### Fixed
- **FullPlayer episode details missing after queue switch** — collapsing the FullPlayer, selecting a different episode from the playback queue (MiniPlayer's Queue button → QueueDrawer), then re-expanding the FullPlayer would show no description, publish date, or duration metadata. Three-part fix:
  - `MediaPlayerState.play()` now stores `episodeId` on `QueueItem` when adding to the queue, so `playFromQueue()` retrieves it later
  - When `play()` finds an existing URL in the queue, it updates the stale `QueueItem.episodeId` (handles cross-session DB restore where pre-fix items had null ids)
  - `restoreSession()` falls back to `session.currentEpisodeId` when the queue item's `episodeId` is null
- **Auto-play-next not firing after track completion** — three-part fix:
  - `keep-open=yes` mpv option prevents mpv from unloading the file at EOF, ensuring `eof-reached=yes` is detectable on the next poll tick
  - Replaced `pendingPlay` flag with a timestamp-based guard (3s window) that reliably distinguishes false start-up transitions from real EOF, while also preventing the pause handler on the same poll tick from overriding the state just set by `playNext()`
  - Added PAUSED→PLAYING resume detection in the pause handler so that `isPlaying` is restored after the brief `pause=yes` during initial file loading, ensuring the EOF handler can fire later
- **Play/Pause button icon stuck after track ends** — caused by the same root issue: EOF was never detected so `isPlaying` never transitioned to `false`; fixed by the three-part fix above

### Changed
- `lastPlayStartMs` timestamp recorded in `MediaPlayerState.play()` for discriminating stale vs real state transitions

## [1.0.0-alpha3] - 2026-07-07

### Changed
- **Project renamed to Podara** — full rename across the codebase:
  - Package: `app.podiumpodcasts.podium` → `app.podara`
  - Theme: `PodiumTheme` → `PodaraTheme`, `PodiumColors` → `PodaraColors`
  - App display name: "Podium" → "Podara"
  - Data directory: `~/.podium/` → `~/.podara/`
  - Database file: `podium.db` → `podara.db`
  - GitHub URLs updated to `kazimics/podara`
  - Remote URL, CI test paths, AND documentation all synced
- README / README_zh: removed Custom UI, Playback controls, Design system from Notable Features
- README / README_zh: fixed EXE description (it is an installer, not a portable executable)

## [1.0.0-alpha2] - 2026-07-06

### Added
- System tray icon with app logo (`SystemTrayManager.kt`) — always present while the app is running
- Tray right-click menu: Show/Hide Podara, Play/Pause, Previous, Next, Quit (Swing JDialog with Podara dark theme styling)
- Close behavior dialog: on first close, asks whether to quit or minimize to tray; "Remember my choice" checkbox persists the decision
- Close behavior setting in Settings → Close Behavior: "Ask me every time" / "Quit the app" / "Minimize to tray"
- `close_action` / `close_action_remembered` settings keys in `Settings.kt`
- 15 new i18n string keys for tray menu, close dialog, and close behavior settings (EN/ZH)
- `logo-16.png` resource for high-quality tray icon rendering

### Changed
- Close button no longer immediately exits — checks remembered close action or shows the close behavior dialog
- Window can be hidden to system tray while audio continues playing (requires "Minimize to tray" setting)
- Tray left-click restores hidden window and brings it to front

## [1.0.0-alpha1] - 2026-07-05

### Added
- Release workflow (`.github/workflows/release.yml`): triggered by `v*` tags, builds MSI + EXE installers and uploads to GitHub Releases as draft
- Custom app logo and window icon: `desktop/src/desktopMain/resources/` — replaces default Material `GraphicEq` icons and Java coffee-cup taskbar icon

### Changed
- `packageVersion` bumped from `0.1.0` to `1.0.0-alpha`
- Sidebar logo: replaced `Icons.Default.GraphicEq` with `logo-64.png` loaded via `BitmapPainter` + `decodeToImageBitmap()`, enlarged from 32dp → 40dp
- Sidebar title: "Podify" → `Strings["app_name"]` ("Podara"), enlarged from 17sp → 20sp
- Custom title bar icon: replaced `Icons.Default.GraphicEq` with `logo-64.png` (16dp)
- Window/taskbar icon: `logo-256.png` set via `Window` composable `icon` parameter
- Updated README / README_zh for 1.0.0-alpha release: removed "WIP" warnings, added download table, updated badge URLs to `kazimics/podara`

### Removed
- Old `LaunchedEffect` + `ImageIO` window-icon fallback (replaced by `Window.icon` parameter)

## [0.1.0] - 2026-07-05

### Added
- Custom app logo and window icon: `desktop/src/desktopMain/resources/` — replaces default Material `GraphicEq` icons and Java coffee-cup taskbar icon

### Changed
- Sidebar logo: replaced `Icons.Default.GraphicEq` with `logo-64.png` loaded via `BitmapPainter` + `decodeToImageBitmap()`, enlarged from 32dp → 40dp
- Sidebar title: "Podify" → `Strings["app_name"]` ("Podara"), enlarged from 17sp → 20sp
- Custom title bar icon: replaced `Icons.Default.GraphicEq` with `logo-64.png` (16dp)
- Window/taskbar icon: `logo-256.png` set via `Window` composable `icon` parameter (`appIcon` file-level lazy `BitmapPainter`)
- Removed 5 empty `feature-*` modules (discover, home, library, player, settings)
- Reorganized source into feature-based sub-packages: `screen/`, `player/`, `data/`, `api/`, `manager/`, `theme/`, `util/`
- Moved `App.kt` + `Main.kt` to root package `app.podiumpodcasts.podium`
- `ui/theme/` → `theme/`, `utils/` → `util/`, `desktop/player/` → `player/`
- Updated 60+ import paths across all source and test files

### Removed
- `feature-discover/`, `feature-home/`, `feature-library/`, `feature-player/`, `feature-settings/` (empty shells)
- `shared/` module (KMP split no longer needed for Windows-only app)
- Dead Android legacy code: `EpisodeListItem.kt`, `PodcastListItem.kt`, `ListItem.kt`, `ListModel.kt`
- `designer/` directory and `AGENTS.md`

## [0.1.0] - 2026-07-04

### Added
- Download speed limit setting with per-task rate limiter (Settings → Downloads → Download Speed Limit)
- Custom numerical input dialog for speed limit with validation (non-negative integer, KB/s unit)
- `Settings.getDownloadSpeedLimitKbps()` / `setDownloadSpeedLimitKbps()` for persisting speed limit
- `RateLimiter` class with cumulative byte tracking for per-task download throttling
- `HistoryDao.getLatestTimestampPerOrigin()` — GROUP BY query for last-listen timestamps
- Subscription list sorting: Name A-Z, Name Z-A, Recent Update (RSS feed), Recently Listened
- Sort dropdown menu with accent highlight on active option
- Drag-to-reorder for queue items using `detectDragGestures` on DragHandle
- Real-time drag animation — shifted items part to show insertion gap during drag
- `MediaPlayerState.moveQueueItem()` for queue reordering
- Select/Cancel mode toggle in QueueDrawer header
- FeaturedCard full-card hover overlay (subtle White 4% brightening)

### Changed
- SettingsScreen fully redesigned: MD3 `Scaffold` + `TopAppBar` + `ListItem` replaced with custom `Column` + `SectionHeader` + `SettingsRow`, matching app design system
- All SettingsScreen dialogs now use `colors.surface` container, 0-radius corners, explicit text colors
- `TextButton` → `SettingsActionText` (plain text + clickable, no MD3 hover/ripple) on all settings rows
- HomeScreen edit mode toolbar redesigned: MD3 `IconButton` + `Checkbox` → custom 32dp buttons + `SettingsActionText` select-all
- Edit/manage button hidden during editing mode
- Batch unsubscribe dialog styled with `containerColor = colors.surface` + explicit colors
- QueueDrawer redesigned: MD3 `TextButton` → plain text, `Checkbox` → custom 20dp circle, hover effects, DesignTokens spacing/colors
- Queue row height 80dp → 72dp, cover 56dp → 48dp, active row accent tint
- `LazyColumn` → `Column` + `verticalScroll` in QueueDrawer for per-item offset control during drag
- FeaturedCard nav button padding 12dp → 20dp to stay within content boundaries
- `DownloadManager` speed limit constructor parameter added, `RateLimiter` throttle during download loop

### Fixed
- Download speed limit unit mismatch (KB/s treated as bytes/s — 128x slowdown)
- Pause unresponsive during `RateLimiter` delay (delay split into 200ms chunks with `shouldStop` check)
- `Icons.Default.Speed` → `Icons.Default.Star` (icon not in default Material set)
- Missing outer Row closing brace in HomeScreen toolbar after edit mode refactor

### Removed
- Back navigation button from SettingsScreen (sidebar provides navigation)
- `CircleShape` / `ArrowBack` / `clip` / `collectIsHoveredAsState` unused imports from SettingsScreen
- `testSettingsBackButton` test (back button removed)

### Tests
- SettingsScreenTest: removed `testSettingsBackButton`
- 187 tests pass (all existing + new structure compatible)

### Added
- Download Management page with three-section layout (Summary + In Progress + Completed)
- True pause/resume for downloads using HTTP Range headers and partial file persistence
- Single episode delete and batch delete by podcast on downloaded content
- Download task table (`downloadTask`) for persistent pause/resume state tracking
- Download directory path display and storage usage statistics in Summary card
- Download progress ring with click toggle (pause / resume) on PodcastDetailScreen episode rows
- `DownloadTaskDao` with CRUD operations for active download task management
- DB-backed active download check in PodcastDetailScreen for robust state across page navigation
- `cleanupPausedTask()` method for cleaning up paused/failed download partial files
- ConcurrentHashMap-based thread-safe pause/cancel tracking
- 20+ new i18n string keys: `downloads_subtitle`, `downloads_in_progress`, `downloads_completed`, `downloads_summary_downloads`, `downloads_summary_storage`, `downloads_summary_path`, `downloads_pause`, `downloads_resume`, `downloads_cancel`, `downloads_status_paused`, `downloads_status_failed`, `downloads_retry`, `downloads_delete`, `downloads_delete_confirm`, `downloads_delete_podcast_confirm`, `downloads_delete_all`, `downloads_file_missing`, `downloads_open_folder`

### Changed
- DownloadsScreen promoted from placeholder to full download management page
- DownloadManager rewritten with pause/resume/cancel/delete support
- `podcastDownload` data class extended with `episodeTitle` field
- `DownloadDao.getAllValid()` and related methods added for download list queries
- StartDownload guard prevents duplicate downloads for the same episode
- PodcastDetailScreen episode row cursor changed to default (removed row-wide hand cursor)

### Fixed
- Download state lost across page navigation (now checked from DB on re-mount)
- Paused downloads showing download button after page switch (now shows progress ring)
- Empty progress ring for paused tasks (now reads progress from DB)
- Cancel inactive for paused tasks (now cleans up DB task and partial file)
- Thread safety issue in pausedDownloads/cancelledDownloads MutableSet (now ConcurrentHashMap)

### Tests
- DownloadManagerTest: 19 tests (9 new: pause, cancel, cleanupPausedTask, delete single/by-origin, getAllValid filter, total bytes, task CRUD, missing file delete)
- DownloadsScreenTest: 12 tests (new file: empty state, title/subtitle, summary path, completed list, file size, delete callback, in-progress section, pause button, cancel button, mixed state, batch delete button)

### Added
- FullPlayer redesign: large cover (160dp), hero episode title (24sp), podcast name, description, metadata (date/duration), timeline slider, playback controls (speed/rewind-10s/play-pause-56dp/forward-10s/sleep-timer)
- Episode Notes section with HTML rendering (`<b>`, `<i>`, `<a>`, `<p>`, `<br>` support via `parseSimpleHtml()`)
- "You might also like" recommendations section (other episodes from same podcast)
- Download action button and More dropdown in FullPlayer
- MiniPlayer body click to toggle FullPlayer (with no hover/ripple effects)
- `currentEpisodeId` tracking in `MediaPlayerState` — FullPlayer queries DB for full episode info
- `playAndRecordHistory()` now accepts optional `podcastImageUrl` fallback for unsubscribed podcasts
- Episode is inserted into database on play to ensure FullPlayer can retrieve metadata
- `database` parameter in FullPlayer for episode/recommendation queries
- 8 new i18n string keys: `player_follow`, `player_following`, `player_episode_notes`, `player_show_more`, `player_show_less`, `player_you_might_also_like`, `player_rewind_15`, `player_forward_30`
- Subscriptions page redesign: custom header (title+count, subtitle, search bar, manage button), subscription cards (64dp cover, title, author, new-episode badge, episode count, more menu)
- New i18n keys: `home_subscriptions`, `home_subscriptions_desc`, `home_search_placeholder`, `home_manage`, `home_episode_count`, `home_new_badge`, `home_new_count`

### Changed
- FullPlayer controls size reduced to match DesignTokens (cover 160dp, play 56dp, circle buttons 40dp, speed 38×30, gaps 20dp)
- FullPlayer padding/spacing normalized (24dp between sections, 28dp horizontal padding)
- Forward/rewind buttons unified with MiniPlayer (10s, `Replay10`/`Forward10` icons)
- Speed selector uses `Popup` + custom `PopupPositionProvider` for reliable positioning below button
- MiniPlayer expand button disabled when no track is loaded
- HistoryScreen play calls now pass `episodeId` for FullPlayer lookup
- Removed FollowingButton from FullPlayer (action buttons now only Download + More)
- HomeScreen redesigned: custom header replaces TopAppBar, Material ListItems replaced with custom SubscriptionCards, added local search and manage mode

### Tests
- `testCurrentEpisodeIdIsNullByDefault` — verifies initial null state
- `testCurrentEpisodeIdSetWhenPlaying` — verifies episodeId tracking after play
- `testCurrentEpisodeIdClearedOnStop` — verifies cleanup on stop
- `testMiniPlayerSeekButtonsDoNotTriggerBodyClick` — body toggle not triggered by controls
- `testMiniPlayerPlayButtonDoesNotTriggerBodyClick` — body toggle not triggered by play/pause
- `testMiniPlayerExpandButtonDisabledWhenNoPlayback` — expand disabled with no URL
- `testFullPlayerShowsCloseButton`, `testFullPlayerShowsPodcastName` — basic rendering

## [0.1.0] - 2026-07-03

### Changed
- HistoryScreen redesigned: unified Column padding (28dp top, 32dp sides, 8dp bottom) matching Subscriptions page layout, serif PageHeader (32sp) + subtitle, date grouping (Today/Yesterday/This Week/Earlier), search bar (320dp), hover-highlighted cards with duration badges and action buttons, relative time display, clear-all dialog
- PodcastDetailScreen redesigned: removed Scaffold+TopAppBar in favor of Column layout with back button Row, consistent 32dp page margins, episode rows with hover effects (elevated background + hand cursor), unified 32dp padding before background for proper margin-aware hover, header cover aligned with episode row covers at 44dp (32dp margin + 12dp inset)
- Download completed CheckCircle icon unified to `colors.success` (green) across PodcastDetailScreen and FullPlayer
- Added `pointerHoverIcon(HAND_CURSOR)` to HistoryItem action buttons (queue, remove) and podcast name link

### Added
- History search field with text filtering by episode/podcast title
- Relative timestamps ("Just now", "Xm ago", "Xh ago", "Yesterday", "MMM dd") in history list
- Date-grouped sections in history (Today/Yesterday/This Week/Earlier)
- `history_subtitle`, `history_search_placeholder`, `history_today`, `history_yesterday`, `history_this_week`, `history_earlier`, `history_count` i18n keys (EN/ZH)

### Fixed
- HistoryScreen hover background spanning full width (padding moved before background, matching Subscriptions page pattern)
- HistoryScreen dividers lacking right margin (end padding added)
- HistoryScreen episode count toolbar position inconsistent with Subscriptions page (unified Column padding eliminates jitter when switching pages)
- PodcastDetailScreen episode row hover background spanning full width (32dp horizontal padding applied before background)
- PodcastDetailScreen downloading progress indicator color set to `colors.accent`
- Material theme primary color reference (`colorScheme.primary`) replaced with PodaraTheme semantic colors

### Removed
- `@OptIn(ExperimentalMaterial3Api)` from PodcastDetailScreen (no longer needed after Scaffold removal)
- Back navigation button from HistoryScreen (page is sidebar-navigated, no TopAppBar)

### Tests
- HistoryScreenTest: removed `testHistoryBackButton` (page no longer has back button), added `testSearchBarIsDisplayed`, `testSubtitleText`

### Added
- Downloads placeholder page with empty state (navigated from sidebar)
- List toolbar on Subscriptions page: subscription count, sort icon button, manage icon button
- Horizontal divider above subscription list
- `cursorBrush` on subscriptions search bar for visible blinking cursor
- New i18n keys: `nav_subscriptions`, `home_subscription_count`, `home_sort`, `downloads_empty`, `downloads_empty_hint`

### Changed
- HomeScreen header redesigned to match Discover page: Serif title font, accent search icon, Surface+border search bar, top padding 28dp, removed count badge from title row
- SubscriptionCard: removed fixed height constraint on description (was clipping text), added `lineHeight = 14.sp` to author text
- EpisodeRow in DiscoverScreen: added `lineHeight = 14.sp` to author text for tighter vertical rhythm
- Sidebar navigation: second item relabeled from "Shows" to "Subscriptions" (中文: 订阅), third item relabeled to "History" (中文: 历史), downloads now navigates to dedicated DownloadsScreen
- Search bar Manage button removed from header (moved to list toolbar as icon)
- Sort and Manage buttons changed from text to icon buttons (UnfoldMore / Edit)
- `testFullPlayerShowsEpisodeNotesWithDataInDb` — notes section with DB data
- `testFullPlayerShowsYouMightAlsoLikeWithRecommendations` — recs with DB data
- `testNewStringKeysResolveToNonEmptyValues` — 4 new string keys verified
- All FullPlayer tests updated to pass `database` parameter

### Added
- Podcast detail page header with large cover (180dp), title, author, description, and three action buttons (Play Latest, Subscribe, More with "Copy RSS URL")
- Improved episode list layout: publication date, description text, 72dp cover filling row height
- `formatDate()` utility for formatting episode publication dates
- `stripHtml()` utility for cleaning HTML tags from RSS descriptions
- `onSubscribed` callback for PodcastDetailScreen to sync subscription state across screens
- `discoverRefreshKey` mechanism to reload DiscoverScreen subscription status on return
- EpisodeRow click navigation to podcast detail page (Trending Podcasts + search results)
- TopAppBar `containerColor = Color.Transparent` for consistent background across all screens

### Tests
- `PodcastDetailUtilTest` — unit tests for `stripHtml()` (plain text, nested tags, whitespace, empty input) and `formatDate()` (zero/negative, format pattern)
- `DiscoverScreenTest.testDiscoverScreenAcceptsRefreshKey` — verifies DiscoverScreen renders with `discoverRefreshKey` parameter
- `DiscoverScreenTest.testEpisodeRowFiresOnShowDetailWhenClicked` — verifies clicking EpisodeRow triggers the detail navigation callback

### Added
- Tests for `SectionHeader` `showAll` parameter behavior
- Language-aware country code selection for Top Podcasts feed (CN for zh, US for en)
- New i18n string keys for sidebar, title bar, discover screen, player, settings, and error messages
- Chinese translations (zh) for all previously missing UI strings
- `Strings.get()` format-parameter usage for error messages in DiscoverScreen and AddPodcastDialog
- Lightweight subscribe via `PodcastManager.addPodcastFromPreview()` — podcast created from Apple Podcast preview data without downloading RSS feed (instant subscribe)
- `onSubscribed` callback in DiscoverScreen to refresh subscription list immediately
- Retry button on podcast detail screen when episode loading fails
- Automatic RSS refresh when entering podcast detail screen (deferred episode loading)
- Play state initialization in `SubscriptionManager.updatePodcast()` for newly fetched episodes
- Hover cursor pointer for MiniPlayer controls (speed selector, seek, play, volume, queue, expand)
- iTunes episode lookup API (`entity=podcastEpisode`) — lightweight episode metadata fetch without full RSS download
- `PodcastEpisodeLookupResult` model and `lookupLatestEpisodes()` method on `ApplePodcastClient.Lookup`
- `RssConverter.parseFetchResult()` — shared helper for parsing RSS fetch results without exposing rssparser types
- `podcastItunesLookup` database table for persistent `itunes-lookup:xxx → RSS URL` mapping across restarts
- `ItunesLookupDao` for CRUD on the iTunes URL mapping table
- `loading_episodes` i18n string (EN/ZH)
- "Play Latest Episode" button on FeaturedCard — plays via iTunes API, no subscription required
- Entire FeaturedCard cover + text area clickable → navigates to episode list
- Subscription state feedback on FeaturedCard: spinner during subscribing, check icon when subscribed
- In-memory + persistent cache for `itunes-lookup:xxx` subscription status check across sessions
- Podcast artwork fallback: MiniPlayer now shows podcast cover when the episode has no artwork

### Changed
- "New Episodes" section renamed to "Trending Podcasts" (EN) / "热门播客" (ZH) to reflect actual content (Apple Top Podcasts chart)
- FeaturedCard now cycles through first 5 podcasts instead of the entire list
- Trending Podcasts list starts from index 6 (podcasts.drop(5)) instead of index 2 (drop(1))
- `SectionHeader` visibility changed from `private` to `internal` for testability
- Migrated all hardcoded English UI strings to `Strings["key"]` localization system (App, Discover, Player, Settings, History screens)
- Sidebar nav items now use string keys instead of literals for full i18n support
- Error messages in DiscoverScreen and AddPodcastDialog use `Strings.get()` with format parameters
- Window title in Main.kt uses `Strings["app_name"]` instead of hardcoded "Podara"
- All Chinese code comments translated to English
- `FetchPodcastClient.fetch()` made `open` for testability
- `SubscriptionManager.unsubscribe()` now also cleans up the iTunes lookup mapping
- `PodcastDetailScreen` receives `FetchPodcastClient` for unsubscribed preview mode with built-in loading
- FeaturedCard subscribe button now has three visual states: Add (unsubscribed), spinner (subscribing), Check (subscribed)

### Removed
- "Show All" button from Trending Podcasts section header
- Ctrl+K search shortcut badge test (UI element was removed in previous refactor)
- Auto-subscribe side effect when navigating to podcast detail from FeaturedCard

### Fixed
- DiscoverScreenTest compilation failure: missing `onPlayLatestEpisode` and `onShowDetail` parameters from FeaturedCard buttons rewrite
- Tests now use `Strings[...]` instead of hardcoded English text, making them locale-independent (fixes failures when system language is set to Chinese)
- `AppGUITest.testSettingsScreenAppName` and `SettingsScreenTest.testSettingsAppName` now use `Strings["settings_about_desc"]` instead of the raw English string
- MiniPlayer now falls back to podcast cover art when the played episode has no image of its own
- Audio stays paused when playing a new episode after pausing the previous one (mpv retains pause state across `loadfile`; polling thread's transition false no longer triggers `playNext()`)
- Podcast detail page unsubscribe button now correctly removes the podcast from the subscription list (was refreshing the database but not the in-memory list)
- Navigate to other sidebar tabs no longer blocked when inside podcast episode list
- Infinite loading spinner when RSS fetch fails in PodcastDetailScreen
- iTunes lookup URL (`itunes-lookup:`) causing HTTP error during episode refresh — now resolved to real feed URL at subscribe time
- DiscoverScreen subscription status incorrect for iTunes-sourced podcasts after refresh
- FeaturedCard "Latest Episode" button incorrectly called subscribe instead of playing the latest episode
- FeaturedCard right/left navigation arrows blocked by content overlay (z-order issue)
- `onShowDetail` previously auto-subscribed the podcast — now fetches RSS in preview mode only
- Podcast detail screen now handles unsubscribed (preview) mode — fetches RSS directly, hides unsubscribe button
- Subscribed podcast status lost after re-entering DiscoverScreen — now also checks via RSS URL cache
- Subscribed podcast status lost after app restart — now persists iTunes URL → RSS URL mapping in database
- Subscription status not synced when subscribing from podcast detail page (missing `podcast` record in DB, missing itunes-lookup mapping, missing `onSubscribed` callback)
- Subscription status not refreshed when returning to DiscoverScreen from podcast detail page (`discoverRefreshKey` mechanism now triggers reload)

## [0.1.0] - 2026-07-01

### Fixed
- MiniPlayer skip step inconsistent with FullPlayer (MiniPlayer 15s/30s → unified to 10s)
- MiniPlayer play button clickable when no track loaded, now disabled
- FullPlayer background not filling content area, switched to PodaraTheme dark background
- FullPlayer sidebar navigation blocked while expanded, clicking nav items now closes FullPlayer
- FullPlayer text and icon colors not applying dark theme palette

### Changed
- Volume button changed from `Icon.clickable` to `IconButton`, hover shape matches queue/expand buttons (circle), icon size reduced from 22dp to 18dp
- MiniPlayer shows podcast name below episode title (added `currentSubtitle` field)
- `QueueItem` data class now includes `subtitle` field
- `MediaPlayerState.play()` accepts new `subtitle` parameter
- Search interaction redesigned: click search icon to trigger search, Enter key triggers search, removed redundant "Search" button and Ctrl+K badge
- Search results only shown after user triggers a search, keeps Top Podcasts view when idle

## [0.1.0] - 2026-06-30

### Added
- Design system (`DesignTokens.kt`) — centralized design tokens for spacing, colors, typography, and component dimensions
- Custom immersive title bar with undecorated window (minimize, maximize, close buttons with hover effects)
- Window dragging via `WindowDraggableArea`
- Premium gradient button system (`Button.Gradient`, `Button.InnerHighlight`)
- Card background gradient (`Card.Gradient` — #1C1C1E → #15171B)
- Sidebar navigation component with accent highlighting and hover cursor
- Featured card on Discover page with cover art, description, and action buttons
- Podcast card horizontal scroll section ("Trending This Week")
- Episode row component for podcast lists
- Queue panel as fixed right-side drawer with cover art per item
- MiniPlayer redesign: cover art, speed selector, 15s/30s skip, volume slider, progress slider

### Changed
- Theme system upgraded: custom `PodaraColors` data class with `LocalPodaraColors` CompositionLocal
- Default screen changed from Home to Discover
- Color palette updated to match design system (warm gold accent, dark surface tones)
- All hardcoded dp/sp values in Sidebar, DiscoverScreen, PlayerUI replaced with `DesignTokens` references
- MiniPlayer layout: single Row with three-section layout (left: cover+title, center: controls, right: time+slider+volume)
- QueueDrawer redesigned with 320px fixed panel, 80px row height, cover thumbnails

### Removed
- TopAppBar navigation from Discover screen (replaced by Sidebar)
- Material3 default color scheme overrides (replaced by design system tokens)

## [0.1.0] - 2026-06-29

### Added
- `AudioPlayerEngine` interface for abstracting audio playback implementations
- `MpvAudioPlayerEngine` — new audio engine using libmpv via JNA
- `MpvApi` — JNA interface for libmpv C API
- `MpvNativeLoader` — native library loader for mpv-1.dll
- `MpvAudioPlayerEngineTest` — unit tests for mpv engine
- `PlaybackState` enum and `PlayerMetadata` data class for engine state tracking

### Changed
- Audio playback engine switched from Java Sound + Rubberband to libmpv
- `MediaPlayerState` now uses `MpvAudioPlayerEngine` as default engine
- Default engine can be overridden via constructor parameter
- Removed JavaFX MediaPlayer dependency (was unused for audio)

### Removed
- `JavaAudioPlayerEngine` (legacy Java Sound + Rubberband implementation)
- `RubberbandApi`, `RubberbandStretcher`, `RubberbandNativeLoader`
- `AudioDecoder`, `Mp3Decoder`, `M4aDecoder`, `SpiDecoder`, `JAADecAudioDecoder`
- `PitchPlayer`, `WsolaTimeStretch`, `JfxMediaPlayer`, `RubberbandPlayer`
- `PlaybackTest` (legacy JavaFX media test)
- Native libraries: rubberband.dll, libfftw3-3.dll, libsamplerate-0.dll, libstdc++-6.dll, libgcc_s_seh-1.dll, libwinpthread-1.dll
- JARs: jlayer.jar, jaad-0.9.4.jar, mp3spi.jar, javafx-*.jar

## [0.1.0] - 2026-06-26

### Added
- Podcast cover images display on Home, Discover, History screens, and episode lists
- Coil 3 image loading library for network image fetching
- Multi-language support (English / Simplified Chinese)
- Language selector in Settings screen
- Unsubscribe functionality with confirmation dialog on podcast detail screen
- Edit mode on Home screen for batch unsubscribe operations
- Subscribe button on Discover page now shows loading spinner during subscription
- Subscribe success icon aligns with add button for consistent layout
- Discover page now correctly shows subscribed podcasts with check icon across sessions

### Fixed
- Download status showing completed when local file no longer exists
- History list now falls back to podcast cover when episode has no image
- All GUI tests now pass with Coil image loading integration
- Discover page subscribe button lacked loading state feedback
- Subscribe success icon misaligned with add button icon
- Already-subscribed podcasts still showing add button on Discover page
- Imported podcasts not recognized as subscribed on Discover page

## [0.1.0] - 2026-06-25

### Fixed
- Progress bar position calculation: `totalInputSamples` unit mismatch caused position to advance at half speed for stereo audio
- Progress bar not syncing to total duration when playback finishes naturally
- Volume control now functional (was a no-op in custom audio pipeline)
- M4aDecoder resume: `seekToMs` was a no-op, now restarts playback from correct position
- Queue always showing empty: episodes now auto-added to queue when played
- Auto-advance to next queue item when track ends naturally

### Added
- Mute toggle: clicking volume icon mutes/unmutes, restoring previous volume level
- Queue button on mini player for quick access to playlist
- QueueSheet with selection mode, batch delete, and per-item download
- "Add to Queue" button on episode lists (podcast detail + history)

### Changed
- Player thread safety: added `@Volatile` to `isStopped`, `currentVolume`, `isPlaying`, `currentPosition`
- Player performance: pre-allocated buffers in playback loop (planar, byteOut) to reduce GC pressure
- Position callback throttled to 300ms intervals to reduce UI refresh overhead

## [0.1.0] - 2026-06-19

### Added
- Custom download path setting in Settings screen
- Settings persistence via `~/.podara/settings.properties`
- Download progress indicator with determinate progress
- Download state persists across navigation
- Download completed icon shows CheckCircle

### Fixed
- WSOLA time-stretch crash: IndexOutOfBounds when frame size < 2048
- WsolaTimeStretch now uses actual frame size instead of fixed 2048
- Download records persisted in database — download status survives path changes
- isDownloaded checks completedDownloads set (no file.exists() fallback)
- Play button checks database for actual file path before playing
- Download progress shows real-time incremental progress
- Download state persists when navigating away and back

### Changed
- Replaced VLC (vlcj) with JavaFX MediaPlayer for audio playback
- Removed 87MB VLC plugin bundling requirement
- Users no longer need to install VLC separately

## [0.1.0] - 2026-06-18

### Added
- Initial release of podara
- Podcast subscription via RSS feed URL
- Discover page with Apple Podcasts search and top podcasts
- Podcast detail page with episode list
- Mini player and full player UI
- Playback queue management
- Sleep timer
- Listening history tracking
- OPML import/export
- Episode download for offline listening
- Debug logging module (`~/.podara/debug.log`) for troubleshooting
- CHANGELOG.md
- Comprehensive test suite (67+ tests across 8 test classes)

### Fixed
- Serialization plugin missing in shared module causing Discover page to fail
- VLC path resolution fails in packaged app (URI is not hierarchical)
- JavaFX Media local file path not converted to URI format
- JavaFX Toolkit not initialized before MediaPlayer creation
- JavaFX native library loading from JAR with multiple fallback paths

### Added
- Pitch-preserving speed control using WSOLA time-stretching algorithm
- When speed != 1.0x, switches to PitchPlayer (JLayer decode + WSOLA + javax.sound output)
- When speed == 1.0x, uses JavaFX MediaPlayer normally
- Updated CI workflow to run new test classes
- Removed unused Android deploy workflow (deploy.yaml)
- Removed build artifacts from git tracking
