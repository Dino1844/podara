package app.podara

// Single source for the user-visible version string. packageVersion in
// desktop/build.gradle.kts is bumped in lockstep; routing the About page and
// its tests through this constant is what stops the two from drifting (the
// About page used to show 0.1.0 while the installer said 1.0.2).
internal const val APP_VERSION = "1.0.3"
