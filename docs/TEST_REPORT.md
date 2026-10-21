# Test report

Last updated: 2026-10-21 (second audit). Environment: Linux cloud container,
OpenJDK 21, Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.21, Android SDK 36,
headless Chromium (Playwright 1.56.1) on a datacenter IP. **No Android
device, emulator (no KVM), DHU or car was available.**

Legend: **PASS** / **FAIL** = measured here. **NOT TESTED** = needs your
hardware. **ASSUMED** = expected from documentation or code, not measured.
No hardware or playback item is marked verified until results come back from
the device, DHU or car.

## 1. Verified here

### Build and static checks

| Check | Result |
|---|---|
| `assembleDailyDebug`, `assembleLabDebug` | **PASS** |
| `testDailyDebugUnitTest`, `testLabDebugUnitTest` | **PASS**: 97 tests, 0 failures, per flavor |
| `lintLabDebug` | **PASS**: 0 errors (warnings: dependency versions, button style, lab-strip strings, StaticFieldLeak for views the display singleton releases on detach, exported CarAppService required by the host) |
| Device scripts, bash syntax | **PASS** (`bash -n`) |
| Device scripts end to end against a **fake** adb/DHU | **PASS**: FIFO console, STEP markers, screenshot pairs, checks, summary, archive. This proves the plumbing only; the fake's "app" lines are canned. It found and fixed two script bugs (a `pipefail` abort on missing lookups, and the attach wait ignoring an already-open app) |

### Unit-test coverage (JVM)

| Class | Tests | Covers |
|---|---|---|
| `YouTubeUrlTest` | 12 | URL shapes, share text, timestamps, rejections |
| `ParkedGateTest` | 16 | fail-closed, sources, expiry, thresholds, settle, evaluation record |
| `GateDiagnosticsTest` | 6 | `speedSource` CAR / PHONE_GPS / both / NONE, ages, permissions, keep-alive |
| `RenderPipelineTest` | 9 | pipeline levels, jitter, gate-violation flag, line format |
| `EmbedStatusTest` | 3 | oEmbed code mapping, URL, id validation |
| `AppReducerTest` | 12 | gate × surface, no auto-resume, reconnect, re-send reload |
| `AppHubTest` | 6 | PLAY refusal reasons, logging |
| `PlayerPagesTest` | 14 | page generation, TV step and hash JS, injection guards, state mapping |
| `GeometryAndInputTest` | 7 | aspect fit, TV viewport, scroll → keys |
| `StatusTextTest`, `LogRedactorTest`, `TransitionLogTest`, `PlayerPagesExportTest` | 12 | messages, redaction, log de-dup, page export |

### Chromium page and YouTube checks

Details and raw evidence: [YOUTUBE_PLAYBACK_AUDIT.md](YOUTUBE_PLAYBACK_AUDIT.md).

| Check | Result |
|---|---|
| Test pattern: 60 fps open, frozen while blocked, play refused while blocked, resumes | **PASS** |
| Test pattern pillarboxed, not stretched | **PASS** |
| Direct page: IFrame API loads, bridge gets ready/state/error/stats (incl. `pageFps`) | **PASS** |
| Direct page blocked at load: no autoplay, muted | **PASS** |
| Direct link actually plays | **FAIL here**: error 150 from YouTube's second (anti-bot) check; `playableInEmbed: true`, oEmbed 200 |
| YouTube TV deep link, shipped script + trusted taps | **PASS**: 3/3 cold deep-link runs (6 s) + first-run welcome (8 s); decoded frames (`videoWidth` 320 / 1280) |
| Gate on real YouTube TV playback | **PASS**: paused + muted ≤ 0.5 s, held against a play attempt, no auto-resume, user play resumes |
| TV pairing screen ("Link with TV code") reachable as guest | **PASS** (code shown; phone side untested) |

## 2. Defects found and fixed in this round

| Defect | Effect | Fix |
|---|---|---|
| TV deep link only re-set the hash | Shared videos never opened in YouTube TV mode (stuck on the account picker) | Picker automation with native taps; verified in Chromium |
| `restartSources()` cancelled the gate ticker | After a mid-session permission grant: stale GPS never expired (gate could stay **open**), and SETTLING cleared only on the next reading | Ticker kept running; sources restarted separately |
| Direct page generated with a stale blocked flag | Autoplay depended on callback timing | Gate settled before any page load |
| No record of whether the keep-alive service actually ran; lab timestamps without date | Weak evidence | `keepAlive=` state in diagnostics; ISO-8601 timestamps |
| Pipeline could count pre-transition stats | False `GATE_VIOLATION` at every gate close | Stats trusted only once they fully post-date the gate change |

## 3. Assumed (not measured)

| Assumption | Basis | Settled by |
|---|---|---|
| Native MotionEvents into the WebView on the virtual display are accepted by the TV UI like Chromium's trusted taps | AA Suite forwards car taps the same way; Chromium accepts trusted taps | DHU run, YouTube TV step (`[tv] tapped …` then `deep-linked video playing`) |
| Direct IFrame playback works from a residential/mobile IP | Evidence points to IP-based refusal here | DHU run, Direct step (`embed diagnosis` absent, `MEDIA_DECODING`) |
| WebView decodes YouTube's H.264/VP9 in hardware | Standard WebView behaviour | `dumpsys/*-codecs.txt` during playback |
| The DHU `speed` sensor reaches the Car App Library `CarInfo` speed listener | DHU docs + Car App docs | DHU run: `speedSource=CAR` after `speed 0` |
| The BMW supplies car speed to Android Auto apps | Unknown | BMW stationary test: `car: … status=SUCCESS` |
| An Internal App Sharing install appears in the BMW launcher | Official docs list IAS as a trusted test channel | BMW test |
| The location foreground service may start from the car session | Android 14+ background-start rules are uncertain for car apps | `keepAlive=RUNNING` vs `REFUSED` in logs |
| Android Auto keeps a navigation surface alive while "moving" | Navigation apps must show maps while driving | DHU `speed 8` step: surface lines + heartbeat screenshots |

## 4. Not tested (needs your hardware)

### Phone (S24 Ultra, Android 16)

| Test | Result |
|---|---|
| Install lab + daily; permission flow (incl. CAR_SPEED) | NOT TESTED |
| Share from YouTube app → link parsed | NOT TESTED |
| Process death / relaunch; battery-optimisation impact; network switch | NOT TESTED |
| Lab log export | NOT TESTED |

### DHU (`tools/device/dhu-scenario.sh`)

| Test | Result |
|---|---|
| App in launcher; status card instead of black screen | NOT TESTED |
| Test pattern / Direct / YouTube TV play while parked (`speed 0`) | NOT TESTED |
| `restrict all` at speed 0: does media keep decoding / does the host keep showing it | NOT TESTED |
| `speed 8`: gate closes, no media decoding, surface still held, heartbeat visible | NOT TESTED |
| PLAY refused while moving; no auto-resume; resume on PLAY | NOT TESTED |
| Sensor off → `speedSource` falls back | NOT TESTED |
| Touch, rotary (`dpad`), audio, reconnect | NOT TESTED |

### BMW (2022 M340i, wireless Android Auto)

| Test | Result |
|---|---|
| IAS / internal-testing install; listed in launcher | NOT TESTED |
| Car speed via CarInfo | NOT TESTED |
| Parked video, audio through speakers, A/V sync | NOT TESTED |
| Touchscreen, iDrive rotary, wireless reconnect | NOT TESTED |
| Closed-course gate validation | NOT TESTED (conditional; CLOSED_COURSE_TRIAL.md) |

**Moving video is not tested and won't be:** the gate hides and pauses
video whenever a speed source reports movement. No claim is made that video
works while moving.
