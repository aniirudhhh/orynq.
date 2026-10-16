# orynq. performance and regression audit

Date: 17 October 2026. Physical device: Samsung SM-S921E. Package: `com.theveloper.pixelplay`.

## Assessment

The tested release is usable for online playback, cached Home browsing, Search, and song menus. This audit does **not** establish public-release readiness: seven unit tests fail, release lint remains incomplete, and sustained background playback CPU needs profiling. No application code was changed during this audit.

The device was connected over USB; charging state changed during testing. Connectivity stayed on throughout. No account disconnection, data clearing, playlist mutation, or network-setting change was performed. Test playback was explicitly authorized and may be reported to the connected YouTube Music account.

## Automated regression results

Command: `:app:testDebugUnitTest :app:lintRelease --console=plain`.

Unit-test XML: **521 tests, 514 passed, 7 failed, 0 skipped, 93 suites**. These seven failures match the previously documented baseline; they are not newly introduced failures from this audit.

| Area | Tests | Failures |
| --- | ---: | ---: |
| YouTube repository, feeds, cache, Search, artwork, metadata, radio, sync | 102 | 0 |
| Online Home/Search presentation | 7 | 0 |
| Playlist operations | 6 | 0 |
| Playback service/player helpers | 37 | 1 |
| Performance diagnostics helpers | 24 | 0 |

These groups are a subset of the complete 521 tests. Passing deterministic tests does not prove live endpoint availability or device behavior.

### Failures requiring triage

1. `BackupSectionTest`: expects 11 sections, implementation exposes 12. Establish intended backup contract, then update the assertion or implementation.
2. `LoadControlBufferProfileTest`: expects 2000 ms playback buffer; implementation returns 1000 ms. Confirm the intended startup/rebuffer tradeoff before changing either.
3. `LibraryScreenFolderNavigationAnimationTest`: parent navigation expects -1; implementation returns 1. Verify actual parent/back transition direction.
4. `PlayerViewModelTest.isCurrentSongFavorite...`: missing mock answer for `YouTubeRepository.rememberOnlineSong`.
5. `PlayerViewModelTest.toggleFavorite...`: same missing mock answer. These two are harness failures, not evidence that live favourites failed.
6. `AudioMetaUtilsTest`: invalid JUnit method declarations prevent that test class from executing correctly.
7. `LocalArtworkUriTest`: expected `pixelplay_local_art://song/99`, received null. Investigate the real local-artwork case before classifying as a stale expectation.

The combined run stopped making progress after unit-test failure while lint analysis was outstanding; it was cancelled. Release lint was restarted separately with `--max-workers=2`. Its final status is recorded below when available. Unit-test results above were collected from completed XML, not inferred from the interrupted Gradle exit status.

## Physical-device measurements

All measurements below are short observational samples of the installed signed release, not AndroidX Macrobenchmark results. They should not be compared against other devices as controlled benchmarks.

| Scenario | Result | Interpretation |
| --- | --- | --- |
| Three process-cold launches using `am start -W -S` | 517, 428, 381 ms; median 428 ms | Activity initial display only; not time to all recommendations or fresh network completion. Saved app data retained. |
| Home scrolling, three down/up swipe pairs | 504 frames; 6 janky (1.19%); p90/p95 9 ms; p99 11 ms | Good short scroll sample. No claim about a long browsing session. |
| Settings open/close, three cycles | 343 frames; 15 janky (4.37%); p90 14 ms; p95 17 ms; p99 38 ms | Some transition misses remain; sample combines opening and closing. |
| Full-player playback PSS | 576,518 KB, about 563 MiB | Includes artwork/UI/process allocations; not a leak measurement. |
| Background playback PSS, earlier sample | 376,098 KB, about 367 MiB | Lower after backgrounding. |
| Background playback PSS after cold restart and controlled sample | 246,095 KB, about 240 MiB | Different process lifetime; cannot infer a monotonic memory trend. |
| Controlled background playback CPU, 30.12 seconds | 16.4% of one CPU core | Needs sustained matched profiling; not 16.4% of the whole eight-core device. |
| Paused comparison CPU, 30 seconds | 0.33% of one core | Foreground state was not controlled; useful observation, not a strictly matched A/B benchmark. |

CPU was calculated from process user+system ticks in `/proc/<pid>/stat`, `CLK_TCK`, and measured wall time. An earlier 54.21% sample overlapped unlocking/UI inspection and is excluded from steady-state interpretation. Instantaneous `top` snapshots are also excluded from the conclusions.

Battery temperature observations ranged from 35.8 to 37.4 degrees C during USB-connected testing. Charging state, screen interaction, and short duration confound attribution. No battery-drain-per-hour or thermal-safety conclusion is supported by this run.

## Functional device checks

| Check | Outcome |
| --- | --- |
| Home scroll and horizontal-content coexistence | Home vertical scroll exercised without crash; dedicated horizontal paging not remeasured in this run. |
| Retained Home after process-cold restarts | Home content available after restart; no network throttling, so cache-before-network ordering was not independently timed. |
| Search query | `Ishq Mubarak` returned expected official song among results. |
| All / Songs / Albums / Artists / Playlists pills | Each returned the corresponding section/content type. Relevance ranking is server-dependent. |
| Streaming | ISHQ MUBARAK advanced to at least 02:01; player displayed OPUS, 48.0 kHz. This does not measure bitrate or audibility. |
| Song radio | Queue identified as `Radio: ISHQ MUBARAK`, 41 tracks, with related songs rather than the original Home list. |
| Background / screen-off continuity | Playback remained active and advanced after backgrounding and an automatic screen lock; no precise interruption trace was collected. |
| Shared Search song menu | Play, favourite, Share, shuffle, repeat, queue, next, playlist, Options and Info controls rendered. Opening the menu did not start paused playback. Mutating every action was not repeated in this run. |
| Song Info | 04:57; TUM BIN 2; ARIJIT SINGH, ANKIT TIWARI; provider YouTube Music. |
| Lyrics | Lyrics loaded for the paused test song, but visible lines contained raw `{agent:v1}` markers. This is a confirmed presentation/parser defect to fix before release. |
| Navigation | Repeated Settings open/back; Search/Home navigation; sheet dismissal. |
| Crash observation | No fatal exception in sampled app-only `AndroidRuntime:E` log windows. This is not a historical crash/ANR census. |
| Cleanup | Test query cleared, Home restored, playback paused; USB stay-awake setting remained 0. |

The player's accessibility control descriptions included Spanish labels such as Reproducir/Pausar while surrounding UI was English. Review locale consistency and TalkBack semantics in a dedicated accessibility pass; this run did not operate TalkBack.

## Privacy/configuration spot checks

Source manifest disables cleartext traffic. Backup and device-transfer rules exclude the YouTube account preferences and login WebView directory. These are source spot checks, not a penetration test or verification of every generated manifest component and backup path. No cookies, passwords, or private account payloads were captured for this report.

## Coverage still required before a public release

- Release lint completion and triage of actual findings.
- Resolve all seven failing tests with meaningful assertions; do not simply disable them.
- Strip or interpret structured lyric agent markers such as `{agent:v1}` before displaying lines; add a regression fixture for this provider format.
- 20–30 minute uninterrupted screen-off playback profile, including process CPU, wake locks, thread sampling, thermal state, network requests, and matching paused baseline. Investigate repeated work before adjusting buffer sizes.
- A repeated memory soak: 20+ navigation/search/player cycles and post-idle retained memory comparison. Current PSS snapshots do not prove or disprove leaks.
- Instrumented Room migration/DAO tests on a test installation; these were not run against the user's personal release installation.
- Controlled offline/reconnect, slow network, dropped stream, expiry, and retry tests on an emulator/test device. Phone connectivity was intentionally unchanged.
- Audio focus, incoming call, Bluetooth disconnect/reconnect, wired headset controls, notification controls, and process-kill recovery during playback.
- Fresh install/onboarding, upgrade-with-data migration, account change/isolation, expired login, long playlist pagination, and longer sync retry/duplicate-report testing.
- TalkBack, large font/display scaling, contrast/touch targets, mixed-language labels, rotation where supported, and multiple Android/device versions.
- AndroidX Macrobenchmark startup/frame timing and repeatable performance baselines on a dedicated test device. Existing benchmark module was inspected but not executed; it can install a separate tested build and alter compilation state.

## Evidence

- Full unit-test log: `../release-audit-tests.log`.
- Separate release lint log: `../release-audit-lint.log`.
- Unit-test report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Unit-test XML: `app/build/test-results/testDebugUnitTest/`.
- Preserved audit XML copy: `../.tmp/performance-audit-2026-10-17/` (93 suites).
- Tested local release APK SHA-256: `855AE6905F25074DBE6C050D7BBB87A0FDA5666219F100A4E2B09F832F2625E4`.
- Installed phone `base.apk` SHA-256 was independently read and matched the local release exactly.

## Release lint outcome

**Inconclusive.** The separate run remained in `:app:lintAnalyzeRelease` for approximately eight minutes without producing a lint report and was cancelled at the audit cutoff. A build-thread sample showed Kotlin/UAST analysis inside the lifecycle lint detector; this does not establish a deadlock or identify an application defect. No lint rules were disabled. A clean lint result still needs a completed run; no pass/failure count is available.
