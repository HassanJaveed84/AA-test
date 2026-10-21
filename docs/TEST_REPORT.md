# Test report

Date: 2026-10-21. Environment: Linux cloud container, OpenJDK 21.0.12,
Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.21, Android SDK platform 36 / build-tools
36.0.0, headless Chromium (Playwright 1.56.1). **No Android device, emulator
(no KVM), DHU or car was available.**

Legend: **PASS**, **FAIL**, **NOT TESTED** (needs hardware you have).

## 1. Build

| Check | Result | Evidence |
|---|---|---|
| `assembleDailyDebug`, `assembleLabDebug` | **PASS** | `app-daily-debug.apk`, `app-lab-debug.apk` (~11 MB each) |
| `lintLabDebug` | **PASS**: 0 errors, 32 warnings | Warnings: newer dependency versions available, button-bar style, `SetTextI18n` in the lab-only strip, `StaticFieldLeak` for views held by the `CarPlayerDisplay` singleton (released on detach), exported `CarAppService` (required by the host), `InlinedApi` for the `POST_NOTIFICATIONS` constant |
| Release AAB | NOT TESTED | Needs your keystore (`keystore/keystore.properties`) |

## 2. Unit tests (JVM)

`./gradlew testDailyDebugUnitTest testLabDebugUnitTest`: **74 tests, 0
failures, 0 errors** per flavor.

| Class | Tests | Covers |
|---|---|---|
| `YouTubeUrlTest` | 12 | watch / m. / music. / youtu.be / Shorts / live / embed / nocookie, share-sheet text, bare host, bare id, timestamps (`90`, `1m30s`, `1h2m3s`, `#t=`), playlists, punctuation, rejections incl. look-alike hosts |
| `ParkedGateTest` | 15 | fail-closed, car speed open/close/no-expiry, reverse, unavailable, GPS fallback and expiry, fix-without-speed, any-moving-wins, GPS jitter, 5 s settle, settle restart, signal loss |
| `AppReducerTest` | 12 | gate × surface, queued request, gate pauses, **no auto-resume**, re-send reloads, play-while-closed, surface loss, session end/reconnect, mode, stop, failure |
| `AppHubTest` | 6 | PLAY refused with reason (no surface, moving), allowed when parked, pause always allowed, gate transition log, redacted log listener |
| `PlayerPagesTest` | 10 | direct page id/start/blocked flag, 16:9 pillarbox on 1920×720, id-injection rejected, base URL, TV URLs, TV hold script, state/error mapping, guarded JS |
| `GeometryAndInputTest` | 7 | aspect fit (pillar/letterbox, ratio kept, bad input), TV viewport zoom, scroll → arrow keys, press cap |
| `StatusTextTest` | 5 | missing-telemetry message, source naming, paused-by-gate, connection text, IFrame error mapping |
| `LogRedactorTest` | 4 | URL query/id masking |
| `TransitionLogTest` | 2 | capacity, gate de-duplication |
| `PlayerPagesExportTest` | 1 | writes the generated pages for the browser check |

## 3. Page checks in Chromium (`tools/pagecheck/check.js`)

These run the app's generated pages in Chromium with a stub `AATube`
bridge. Chromium is close to Android WebView but **not** the same thing.

| Check | Result | Evidence |
|---|---|---|
| Test pattern runs at display rate when open | **PASS**: 60 fps | `docs/evidence/testpattern-playing.png` |
| Test pattern frozen while blocked (frames 151 → 151), `play()` refused while blocked | **PASS** | `docs/evidence/testpattern-blocked.png` |
| Test pattern resumes after unblock + play | **PASS**: 60 fps | — |
| 16:9 never stretched on a 960×360 (2.67:1) viewport | **PASS**: pillarboxed | `testpattern-playing.png` |
| TV control script installs on `youtube.com/tv` with no page errors | **PASS** | — |
| TV: media element started while blocked is paused **and** muted | **PASS** (`paused:true, muted:true`) | — |
| TV: after unblock, playback allowed and unmuted | **PASS** | — |
| Direct page: IFrame API loads, bridge receives ready/state/error | **PASS** (`state:-2, -1, 3, error:150`) | `docs/evidence/direct-iframe-datacenter-error150.png` |
| Direct page blocked at load: no autoplay, muted | **PASS** (`state:5, muted:true`) | — |
| Direct page actually plays YouTube video | **FAIL in this environment**: error 150 on all videos from a datacenter IP (see FEASIBILITY §3). Retest on the phone | — |
| `youtube.com/tv` serves leanback to TV UA | **PASS** | `docs/evidence/youtube-tv-leanback-chromium.png` |
| TV `#/watch?v=` deep link starts the video | **FAIL in probe** (routed to account picker as guest) | FEASIBILITY §3 |

## 4. Android 16 phone (S24 Ultra)

| Test | Result |
|---|---|
| Install lab + daily side by side | NOT TESTED |
| Permission flow (car speed, location, notifications) | NOT TESTED |
| Share from YouTube app → AA Tube parses link | NOT TESTED |
| Paste watch / youtu.be / Shorts links | NOT TESTED |
| Location foreground service allowed to start from car session | NOT TESTED (refusal is logged) |
| Process relaunch / process death recovery | NOT TESTED |
| Battery-optimisation impact during a session | NOT TESTED |
| Switching phone network mid-playback | NOT TESTED |
| Lab log export | NOT TESTED |

## 5. DHU

| Test | Result |
|---|---|
| Connection, AA Tube in launcher, enter/exit | NOT TESTED |
| Status card shown (never black) before a video is chosen | NOT TESTED |
| Direct video plays while parked (`speed 0`) | NOT TESTED |
| YouTube TV loads, pairing code reachable | NOT TESTED |
| Test pattern 60 fps, A/V sync | NOT TESTED |
| Orientation / scale / pillarbox | NOT TESTED |
| Touch and `dpad` rotary on action strip | NOT TESTED |
| Media audio and focus | NOT TESTED |
| Reconnect | NOT TESTED |
| Moving-state lab (A1–C2) | NOT TESTED; see MOVING_STATE_LAB.md |

## 6. Real BMW (2022 M340i, wireless Android Auto)

| Test | Result |
|---|---|
| Install via Play Internal App Sharing / internal testing | NOT TESTED |
| AA Tube appears in BMW launcher | NOT TESTED |
| BMW reports speed via CarInfo (lab log `car speed … status=SUCCESS`) | NOT TESTED |
| Video renders while parked | NOT TESTED |
| Audio through BMW speakers; latency/sync | NOT TESTED |
| Touchscreen taps; iDrive rotary focus on action strip | NOT TESTED |
| Wireless reconnect | NOT TESTED |
| Closed-course gate validation | NOT TESTED; conditional, see CLOSED_COURSE_TRIAL.md |

Record each result with date, Android Auto version and phone build (all three
are in the lab log header).
