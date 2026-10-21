# AA Tube — YouTube on the Android Auto screen, parked only (experimental)

A personal-use, **experimental** Android app that shows YouTube on a car's
Android Auto display while the car is **parked**. The target setup is a 2022
BMW M340i with factory wireless Android Auto and a Samsung Galaxy S24 Ultra on
Android 16.

> **Status (2026-10-21):** builds, 73 unit tests pass, and the player pages
> were checked in Chromium. **Nothing has run on the DHU, the phone or the
> BMW yet.** Those steps need hardware; see [docs/NEXT_STEPS.md](docs/NEXT_STEPS.md).
> The rest of this README describes intended behaviour. What has actually been
> verified is listed in [docs/TEST_REPORT.md](docs/TEST_REPORT.md).

> **Not for publication.** AA Tube registers as an Android Auto *navigation*
> app, because that is the only category the host gives a drawable surface
> (`ACCESS_SURFACE`). Using that category for video is not a supported or
> Play-compliant way to distribute an app. Install it only through a private
> Play Console internal test, and never publish it.

## What it does

| | |
|---|---|
| **Direct video** | Paste or share a YouTube link (watch, `youtu.be`, Shorts, live, embed) on the phone; it plays on the car screen through YouTube's official IFrame Player API. |
| **YouTube TV** | Loads `youtube.com/tv` with a smart-TV user agent. A passenger can pair their phone (YouTube → Cast → *Link with TV code*) and cast to the car. Technique from [AA Suite](https://github.com/iAlias/AASuite) (MIT). |
| **Test pattern** | An offline, unprotected animation with a frame counter and a once-per-second flash + beep, for checking frame continuity and A/V sync. |
| **Parked-only gate** | Video is shown only when the car is **confirmed stationary**. See below. |
| **Phone controls** | Status, permissions, link field, share-sheet target, play/pause/stop, mode. |
| **Lab build** | Same app plus an on-screen diagnostics strip and an exportable, redacted session log. |

## The parked-only gate (all builds)

Android Auto keeps a navigation app's surface live while the car moves,
because a map has to stay on screen. The host therefore cannot tell this app's
video from a map, and **would not blank it**. AA Tube enforces the parked
condition itself, in every build:

- **Inputs:** the car's own speed through the Car App Library (`CarInfo`, car
  API level ≥ 3, permission `com.google.android.gms.permission.CAR_SPEED`).
  If the car doesn't report speed, the phone's GPS speed is used instead
  (fresh within 10 s).
- **Closes** on any source above its threshold (car > 0.5 m/s, GPS > 1.4 m/s).
- **Fails closed:** with no usable speed source, video stays off and the car
  screen says why.
- **Settles:** after movement it reopens only after 5 s of continuous standstill.
- **While closed:** the WebView is hidden behind an opaque status card, every
  video is paused **and** muted (including a video cast from a passenger's
  phone mid-drive), taps aren't forwarded, and no new page is loaded.
- **Never auto-resumes:** after the gate reopens, playback starts again only
  when someone presses play.

The specification asked for a research build with no app-level cutoff, so the
host could decide. That build isn't provided. On a navigation surface the
host doesn't decide, so such a build would simply play video to the driver
while driving. The research question is still answered, without video in
motion: see [docs/MOVING_STATE_LAB.md](docs/MOVING_STATE_LAB.md).

## Prerequisites

- JDK 17+ (21 used here), Android SDK with platform 36 and build-tools 36.
- Android Studio (optional) and the **Android Auto Desktop Head Unit** (SDK
  Manager → SDK Tools → *Android Auto Desktop Head Unit Emulator*).
- Phone: Android 10+ (`minSdk 29`). Target: Galaxy S24 Ultra, Android 16.
- A Google Play Console developer account, needed to get the app into the real
  car (see [Installing in the car](#installing-in-the-car)).

## Build and test

```bash
./gradlew testDailyDebugUnitTest testLabDebugUnitTest   # 73 JVM unit tests per flavor
./gradlew assembleDailyDebug assembleLabDebug           # debug APKs
./gradlew installLabDebug                                # phone on USB debugging
./gradlew lintLabDebug
```

APKs land in `app/build/outputs/apk/{daily,lab}/debug/`. The two flavors
install side by side (`io.github.hassanjaveed84.aatube` and `….aatube.lab`).

Optional page checks, which load the generated player pages in Chromium with Playwright:

```bash
./gradlew testLabDebugUnitTest      # also writes app/build/player-pages/
node tools/pagecheck/check.js app/build/player-pages app/src/main/assets /tmp/out
```

### Release / AAB for internal testing

Signing material never goes into the repository. Create `keystore/keystore.properties`
(the folder is gitignored):

```properties
storeFile=keystore/aatube.jks
storePassword=…
keyAlias=aatube
keyPassword=…
```

Then run `./gradlew bundleLabRelease` (or `bundleDailyRelease`) and upload the
AAB to Play Console **Internal app sharing** or an **Internal testing** track.
Never promote it beyond internal testing.

## Android 16 permission flow

On first launch, tap **Grant permissions** on the phone:

| Permission | Why | Without it |
|---|---|---|
| Car speed (`CAR_SPEED`) | Primary parked signal from the BMW | GPS fallback only |
| Precise location | GPS-speed fallback | Car speed only; if the car doesn't report speed, video stays off |
| Notifications | Shows the foreground-service notice that keeps GPS running while the screen is off | Service still runs; the notice is hidden |

The car session also asks for missing permissions on the car screen; Android
Auto forwards that request to the phone. Nothing else is requested: no
accessibility service, no screen capture, no overlay permission.

## Desktop Head Unit (no car)

```bash
# Phone: Android Auto settings → tap Version 10× → ⋮ → Developer settings;
#        then ⋮ → Start head unit server
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit -c docs/dhu/headunit.ini
```

`docs/dhu/headunit.ini` enables the speed, gear, parking-brake and location
sensors so the gate can be exercised (`speed 0`, `speed 8`, …). See
[docs/MOVING_STATE_LAB.md](docs/MOVING_STATE_LAB.md) for the full protocol.
Debug builds accept any host, so the DHU works with an adb-installed APK.

## Installing in the car

According to [Android's testing docs](https://developer.android.com/training/cars/testing),
real vehicles only list apps installed from a trusted source such as Google
Play. Android Auto's *Unknown sources* developer option covers media,
messaging and parked apps, **but not apps built with the Car App Library**.
AA Tube is a Car App Library app, so:

1. A local `adb install` works on the DHU but **is not expected to appear** in
   the BMW's launcher.
2. Use **Play Console → Internal app sharing** (fastest) or an **Internal
   testing** track to install a signed build on the S24.
3. Even then, acceptance of this miscategorised app isn't guaranteed. If it
   still doesn't appear, record that as a blocker in
   [docs/TEST_REPORT.md](docs/TEST_REPORT.md).

Then, in the car (wireless AA as usual): Android Auto settings → *Customise
launcher* → enable AA Tube → open it from the BMW screen.

## Failure diagnostics

| Symptom | Likely cause | What to do |
|---|---|---|
| AA Tube missing from the BMW launcher | Installed via adb (untrusted source) | Install via Internal app sharing / internal testing |
| Car screen: "Cannot confirm the car is parked" | No car speed and no GPS fix | Grant both permissions; in a garage GPS may not fix, so check the lab log for `car speed … status=` |
| "YouTube refused to play this video in an embedded player" | IFrame error 101/150/152: owner disabled embedding, age/region gate, or a sign-in check | Switch to YouTube TV mode |
| Error 153 | Missing Referer: an app bug | Report it; the page must load from the https base URL |
| YouTube TV shows the account picker instead of the shared video | Guest session drops the `#/watch` deep link | Pick an account or *Watch as guest* on the car screen; the app retries the link 3× |
| No sound | Audio route/focus | Check that the BMW source is Android Auto; see the audio items in TEST_REPORT |
| Black screen | Should never stay black: a status card is always drawn | Export the lab log (lab build) and check the `surface` lines |

## Documents

- [docs/FEASIBILITY.md](docs/FEASIBILITY.md): support matrix, go/no-go, verified versus assumed
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): surface, player, gate, input, audio, reconnect
- [docs/TEST_REPORT.md](docs/TEST_REPORT.md): what has and hasn't been tested
- [docs/MOVING_STATE_LAB.md](docs/MOVING_STATE_LAB.md): DHU simulated-motion protocol
- [docs/CLOSED_COURSE_TRIAL.md](docs/CLOSED_COURSE_TRIAL.md): conditional closed-site gate-validation protocol
- [docs/REAR_PASSENGER_OPTIONS.md](docs/REAR_PASSENGER_OPTIONS.md): video for passengers on normal trips
- [docs/NEXT_STEPS.md](docs/NEXT_STEPS.md): what you need to do next, in priority order

## Licence and attribution

The VirtualDisplay + Presentation rendering technique, the TV user agent and
viewport approach, `AspectFit`/`WebViewport` and `KeyScroll` are adapted from
[AA Suite](https://github.com/iAlias/AASuite) © 2026 iAlias, MIT License
([docs/AASUITE_LICENSE.txt](docs/AASUITE_LICENSE.txt)). YouTube playback uses
YouTube's own web surfaces (IFrame Player API, youtube.com/tv) and is subject
to YouTube's Terms of Service. There is no stream extraction, no DRM
circumvention and no ad blocking.
