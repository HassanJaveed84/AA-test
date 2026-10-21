# Moving-state lab (DHU)

**Status: protocol only, not yet executed.** No DHU run has been performed;
every result cell below is NOT TESTED until you fill it in from a real run.

## Question, restated

The spec asked: *does the AA Suite-derived rendering keep a working video
surface and audio session when Android Auto applies moving-state restrictions,
and does DHU differ from the BMW?*

This project splits that into two parts so it can be answered without
showing video in motion:

1. **Host behaviour:** does the Android Auto host keep supplying, and
   compositing, the navigation surface under driving restrictions and speed?
   Measured with the lab build's **status card heartbeat** (a counter redrawn
   once per second on the surface while the gate is closed) plus surface
   callbacks.
2. **App behaviour:** does the parked-only gate close fast and reliably on
   movement, and does it stay closed until the car has stopped?

`restrict all` on a desk changes host restrictions but not speed, so the gate
stays open. Video playing in that state is a legitimate desk observation
(nobody is driving), labelled **"DHU video frames observed under simulated
driving restrictions"**, and it answers part 1 directly.

## Automated run

`tools/device/dhu-scenario.sh` performs the steps below (A1/A2, B1, B2, B4, B6
per mode) with STEP markers, DHU screenshot pairs and automatic log checks;
see [DEVICE_TESTING.md](DEVICE_TESTING.md). The manual procedure below remains
the reference.

### Evidence layers (keep them separate)

| Layer | Evidence | Source |
|---|---|---|
| Surface available | `[surface] display attached`, `level` ≠ NO_SURFACE | app log |
| App drawing into it | `level=APP_DRAWING`/`MEDIA_DECODING`, `draws>0` | app log (lab) |
| Media decoding | `level=MEDIA_DECODING`: media time advancing, presented frames, segment requests | app log (lab) |
| Host displays changing frames | DHU screenshot pair `changing` | DHU screenshots |
| Audio audible | your ears / car speakers | manual |

Moving-state video is **not tested by design**: the gate hides and pauses it.
Under `speed 8` the expected evidence is surface available + app drawing (the
status card heartbeat) + **no** media decoding + DHU screenshots `changing`
(heartbeat) or `static`. Any `GATE_VIOLATION` line is a defect.

## Setup

| Item | Record |
|---|---|
| DHU version (`desktop-head-unit --version` or the window title) | |
| Android Auto app version (lab log header) | |
| Phone model, Android build (lab log header) | |
| WebView version (lab log header) | |
| AA Tube build (`lab` flavor, version) | |

```bash
./gradlew installLabDebug
# Phone: Android Auto → Developer settings → Start head unit server
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit -c docs/dhu/headunit.ini
# In a second terminal, keep a filtered logcat:
adb logcat -v time | grep -E "CarPlayerDisplay|chromium|CarApp" > dhu-logcat.txt
```

`docs/dhu/headunit.ini` enables the `speed`, `gear`, `parking_brake`,
`location` and `driving_status` sensors. Without them the DHU ignores the
corresponding commands (per the official DHU docs).

Open **AA Tube Lab** on the DHU. On the phone, grant permissions, choose a mode
and send a link. The DHU sends car speed only after the first `speed` command.

Use one real YouTube video (Direct mode, a public, embeddable video) and the
**Test pattern** separately for every row.

## Procedure

Type each command into the DHU console. After each step wait 5 s, run
`screenshot <name>.png`, and note the lab strip (gate, player state,
fps/mediaTime) and the status card heartbeat.

| # | Command(s) | Expected app behaviour | Observe |
|---|---|---|---|
| A1 | `restrict none`, `speed 0` | Gate `OPEN by CAR`; video plays | fps > 0, mediaTime advancing |
| A2 | `restrict all` *(while playing)* | Gate stays open (speed 0). **Desk-only observation** | Does the host blank, cover or freeze the surface? fps? audio? surface callbacks? |
| A3 | `restrict none` | Unchanged | Recovery |
| A4 | Relaunch AA Tube with `restrict all` already set | Gate open; video loads | Does the host allow first render under restriction? |
| B1 | `speed 8` *(while playing)* | Gate `CLOSED MOVING` within ≤ 0.5 s; card replaces video; audio stops | Time from command to card (screen-record the DHU); heartbeat counting = host still compositing the surface |
| B2 | `speed 0` | `CLOSED SETTLING` for 5 s, then `OPEN by CAR`; **video stays paused** | No auto-resume; press play to resume |
| B3 | `speed 0.4` | Stays open (below 0.5 m/s) | Threshold check |
| B4 | `speed 8`, then press ▶ on the DHU action strip | Toast "Vehicle moving: video paused"; nothing plays | PLAY refused |
| B5 | `speed 8` in YouTube TV mode; cast a video from a second phone | Video element held paused + muted; card shown | Cast-while-moving path |
| B6 | `speed` (no value = sensor off) | Gate falls back to GPS: with GPS fresh and stationary → open; else `NO_SPEED_SIGNAL` | Fallback and fail-closed |
| B7 | `restrict all` + `speed 8` + `gear 100` + `parking_brake false` | Closed | Combined driving simulation |
| C1 | Phone airplane mode on/off while playing | Network error card, then recovery on reload | |
| C2 | Kill DHU, restart | New session: gate starts closed until speed arrives; video reloads | Reconnect |

Note: `gear` and `parking_brake` aren't read by the Car App Library on
Android Auto (only speed is exposed), so B7 mainly checks that nothing else
reopens the gate.

## Evidence to collect

- DHU screenshots per step; a screen recording of B1/B2 with a visible clock.
- `adb logcat` excerpt (`dhu-logcat.txt`).
- Lab log: phone → AA Tube Lab → **Export lab log**. Every line is timestamped:
  `[gate]` transitions, `[speed]` samples, `[surface]` callbacks,
  `[display]` hide/show, `[frames]` per-second stats, `[input]` ignored taps.

## Result matrix (fill in)

Result per cell: **PASS / FAIL / NOT TESTED**. For host behaviour use
**surface kept / surface blanked / app closed by host / other (describe)**.

| Step | Real YouTube (Direct) | Test pattern | Host surface behaviour | Notes |
|---|---|---|---|---|
| A1 | NOT TESTED | NOT TESTED | | |
| A2 | NOT TESTED | NOT TESTED | | |
| A3 | NOT TESTED | NOT TESTED | | |
| A4 | NOT TESTED | NOT TESTED | | |
| B1 | NOT TESTED | NOT TESTED | | close latency: |
| B2 | NOT TESTED | NOT TESTED | | |
| B3 | NOT TESTED | NOT TESTED | | |
| B4 | NOT TESTED | NOT TESTED | | |
| B5 | NOT TESTED (TV) | n/a | | |
| B6 | NOT TESTED | NOT TESTED | | |
| B7 | NOT TESTED | NOT TESTED | | |
| C1 | NOT TESTED | n/a | | |
| C2 | NOT TESTED | NOT TESTED | | |

## Interpreting results

- **A2 shows frames continuing:** write "DHU video frames observed under
  simulated driving restrictions (desk, speed 0)". This means the host doesn't
  interlock navigation surfaces. It does **not** mean the BMW behaves the same.
- **A2 shows the host blanking or covering the surface:** record which layer
  (the host draws over the surface, the surface is destroyed via
  `onSurfaceDestroyed`, or the app is closed) from the surface callbacks and
  screenshots.
- **B1 heartbeat keeps counting:** the host keeps compositing the surface
  while "moving"; only the app gate stops video.
- Never infer real-car behaviour from DHU alone.
