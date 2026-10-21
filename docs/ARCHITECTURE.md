# Architecture

```
            ┌──────────── phone process (one app) ─────────────────────────────┐
 Share  ──▶ │ MainActivity ──┐                                                 │
 sheet      │  (phone UI)    │ AppEvent / PlayerCommand                        │
            │                ▼                                                 │
            │            AppHub ── StateFlow<AppState> ──┬─▶ PlayerScreen       │──▶ action strip
            │   (reducer, log, commands)                 │   (NavigationTemplate)│    (touch / rotary)
            │                ▲                           └─▶ CarPlayerDisplay ───│──▶ car Surface
            │   GateChanged  │                               VirtualDisplay     │    (via host)
            │                │                               └ Presentation     │
            │          SpeedMonitor ◀── CarInfo speed (host)   ├ WebView        │
            │          + ParkedGate ◀── phone GPS speed        └ status card    │
            │                                                                   │
            │ TubeSession: owns SurfaceCallback, SpeedMonitor, keep-alive FGS   │
            └───────────────────────────────────────────────────────────────────┘
```

## Packages

| Package | Contents | Android-free? |
|---|---|---|
| `core/` | `YouTubeUrl`, `ParkedGate`, `AppState` + `AppReducer`, `AppHub`, `StatusText`, `PlayerError`, `LogRedactor`, `TransitionLog`, `AspectFit`, `WebViewport`, `KeyScroll` | Yes, unit-tested |
| `player/` | `PlayerPages` (generated HTML/JS, unit-tested), `CarPlayerDisplay` (VirtualDisplay/Presentation/WebView) | Pages yes, display no |
| `motion/` | `SpeedMonitor` (CarInfo + LocationManager → gate), `LocationKeepAliveService` | No |
| `car/` | `TubeCarAppService`, `TubeSession`, `PlayerScreen` | No |
| `phone/` | `MainActivity` | No |
| `lab/` | `LabLog` (session log file, lab flavor only) | No |

## Surface and session

- `TubeCarAppService` is registered in the **NAVIGATION** category with
  `ACCESS_SURFACE` and `NAVIGATION_TEMPLATES`. Debug builds accept any host
  (needed for the DHU). Release builds use the Car App Library's host
  allowlist (`hosts_allowlist_sample`).
- `TubeSession` registers **exactly one** `SurfaceCallback`. The host doesn't
  re-deliver `onSurfaceAvailable` if the callback is swapped; AA Suite
  documented this. Surface events go to `AppHub` (state) and
  `CarPlayerDisplay` (rendering). Visible and stable area changes are logged.
- `CarPlayerDisplay.attach` creates a `VirtualDisplay` the size of the
  surface (the host paints it pixel for pixel; a larger one would only crop),
  and a `Presentation` holding a `FrameLayout`:
  `WebView` (bottom) → opaque status `TextView` → lab diagnostics strip.
- On `onSurfaceDestroyed` or session end, everything is released. The chosen
  video and mode survive in `AppHub`, so a wireless reconnect reloads them.

## Car display state vs player state

`AppState` keeps them apart. `carConnected` and `surface` describe the
projection, `player` the web player, and `gate` decides whether they may meet
(`videoAllowed = gate is Open && surface != null`). `AppReducer` is pure:

- Gate closing while loading/buffering/playing → `Paused(byGate = true)`.
- Gate reopening **does not** change the player state (no auto-resume).
- A "playing" report while the gate is closed is stored as paused-by-gate
  (the display enforces the pause anyway).
- Session end resets car-side fields and keeps mode and video.

## Video renderer

`PlayerPages` generates every page and gives them all the same `window.aatube`
API (`play`, `pause`, `setBlocked`, `back`, `arrows`). Pages report back
through the `AATube` JavaScript interface, which accepts status reports only
(`onState`, `onError`, `onStats`) and never takes instructions from the page.

| Mode | How | Notes |
|---|---|---|
| Direct | IFrame Player API page via `loadDataWithBaseURL("https://<applicationId>/")`; the 16:9 box is computed with `AspectFit` (pillarbox on the wide BMW screen) | Without the https base, YouTube returns error 153 |
| YouTube TV | `youtube.com/tv` (+ `#/watch?v=ID` if a link was shared), Tizen TV UA, WebView zoomed out to a 1280 px logical width; control script installed at document start via `WebViewCompat.addDocumentStartJavaScript` and again on page finish | Passenger pairing and casting are YouTube's own |
| Test pattern | `assets/testpattern.html`: canvas bars, frame counter, 1 Hz flash + 880 Hz beep | Offline; for frame-continuity and A/V-sync checks |

Errors map to `PlayerError` with an actionable message (embed blocked → "try
YouTube TV mode", network, not found, …). The car screen always shows either
video or a status card, never a blank black screen.

## Parked-only gate (all builds)

`ParkedGate` is a pure state machine fed by `SpeedMonitor` on the main thread
and evaluated every 500 ms and on every reading:

| Input | Freshness | Moving if |
|---|---|---|
| Car `CarInfo` raw speed (car API ≥ 3, `CAR_SPEED`) | No expiry (the host reports on change) | > 0.5 m/s (abs, so reverse counts) |
| Phone GPS `Location.speed` | ≤ 10 s | > 1.4 m/s |

Order: any fresh source moving → `Closed(MOVING)`; no usable source →
`Closed(NO_SPEED_SIGNAL)`; less than 5 s since last movement →
`Closed(SETTLING)`; otherwise `Open(source)`.

Enforcement in `CarPlayerDisplay` while closed:

1. `WebView.visibility = INVISIBLE`; the opaque status card covers the surface.
2. `setBlocked(true)` → pause and mute every video. In TV mode a capture-phase
   `play`/`playing` listener holds any video that starts, such as a passenger's cast.
3. Re-asserted every second (the "enforcer").
4. Taps, scrolls, reload and back are ignored; `PLAY` is refused in `AppHub`
   with the reason shown on the phone and as a car toast.
5. A page is never loaded for the first time while closed.

`LocationKeepAliveService` (a location-type foreground service) is started
for the session, so GPS updates keep arriving with the phone screen off. If
Android refuses to start it from the background, the refusal is logged and
the gate relies on car speed, or closes.

## Input

- Touch: `SurfaceCallback.onClick` → synthetic `ACTION_DOWN/UP` dispatched into
  the Presentation's view tree (reaches the YouTube iframe/TV UI).
- Drag/rotary scroll: `onScroll` (the template offers `Action.PAN`) →
  `KeyScroll` → arrow-key events in the page (YouTube TV is d-pad driven).
- Rotary/iDrive: the controller moves focus between action-strip buttons
  (play/pause, mode, back or reload); the host handles that focus.

## Audio

The WebView plays through the phone's media stream; Android Auto routes phone
media audio to the car. Chromium/WebView requests audio focus for media
playback. **Unverified on this setup**: check focus handling when Spotify or
the BMW radio is active, and measure A/V sync with the test pattern
(TEST_REPORT).

## Errors and reconnects

| Event | Handling |
|---|---|
| Wireless drop | Session destroyed → display released, gate reset to closed. Video and mode kept |
| Reconnect | New session → new surface → page reloads (gate permitting) |
| Surface recreated (e.g. app switch on the car) | Detach/attach. WebView rebuilt. TV pairing survives in WebView cookies |
| Process death | All in-memory state lost; nothing persisted except WebView cookies |
| Main-frame network error | `PlayerError.NETWORK` card |

## Builds

| Flavor | App id | Differences |
|---|---|---|
| `daily` | `io.github.hassanjaveed84.aatube` | Normal use |
| `lab` | `….aatube.lab` | Diagnostics strip on the car screen, surface heartbeat on the status card, per-second frame stats, session log in `files/lab/` exportable from the phone |

Both carry the identical gate. There is deliberately no flavor, flag or
setting that disables it.

## Privacy

No telemetry or analytics. Nothing is persisted by the app itself; WebView
cookies hold the YouTube TV session. Log lines pass through `LogRedactor`
(URL queries stripped, video ids shortened to 3 characters). The lab log
leaves the phone only if the user shares it.
