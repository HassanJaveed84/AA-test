# Feasibility: YouTube on a 2022 BMW M340i via wireless Android Auto

Date: 2026-10-21. Phone: Galaxy S24 Ultra, Android 16. Car: 2022 M340i,
factory wireless Android Auto. This assessment is based on documentation, on
source inspection of AA Suite, and on probes run from a cloud container. No
DHU, phone or car was available to the author. Each claim is tagged
**Verified** (with how), **Doc** (official documentation says so) or
**Assumption**.

## 1. Is parked YouTube officially supported on Android 16 Android Auto?

**No.**

- **Doc:** Google's May 2026 post
  ([Android for Cars](https://android-developers.googleblog.com/2026/05/android-for-cars-unifying-platforms-premium-experiences.html))
  announces parked video in Android Auto "for users with phones running Android
  17 and higher", rolling out "to compatible vehicles later this year".
  It doesn't cover Android 16 phones and doesn't name vehicles.
- **Doc:** Android Auto's supported app categories (media, messaging,
  navigation, POI, IoT, weather, …) don't include third-party video for
  projection on Android 16. Video apps exist for *Android Automotive OS*,
  the OS that runs natively in some cars (parked only), not for Android Auto
  projection from a phone.
- **Android Auto vs Android Automotive OS:** the BMW runs BMW's own
  iDrive OS and *projects* Android Auto from the phone. Everything here runs on
  the phone; nothing is installed in the car.

So the only route on this phone today is unofficial: AA Suite's technique of
registering as a **navigation** app (the only category granted
`androidx.car.app.ACCESS_SURFACE`) and drawing a WebView on that surface.

**Decision point when Android 17 reaches the S24 Ultra:** check whether the
official YouTube app offers parked video on the BMW. If it does, use it
instead of this project.

## 2. AA Suite inspection (iAlias/AASuite @ e449095, v0.9)

| Item | Finding |
|---|---|
| Licence | MIT © 2026 iAlias. **Verified** (LICENSE file). |
| Category | `androidx.car.app.category.NAVIGATION` with `NAVIGATION_TEMPLATES` + `ACCESS_SURFACE`. **Verified** (manifest). |
| Rendering | Single `SurfaceCallback` → `VirtualDisplay` on the host Surface → `Presentation` → `WebView`. **Verified** (source). |
| YouTube | `youtube.com/tv` + Tizen smart-TV UA, WebView zoomed to a 1280 px logical width; back key injected as JS key events; scroll → arrow keys. **Verified** (source). |
| Extra scope | Screen mirroring (MediaProjection + accessibility), web browser, LAN share server, rotation-lock overlay. Not needed here. |
| Host validation | `ALLOW_ALL_HOSTS_VALIDATOR` in all builds. |
| Tested on | Nissan Qashqai J12 (USB Android Auto) and DHU, per its README. **Not** BMW, **not** wireless. |
| Distribution note | Its README already says template apps don't appear in real cars unless installed from Play (internal testing). Consistent with the official docs (§4). |

**Fork or extract?** Extract. AA Tube reuses only the surface/WebView
technique, the TV UA/viewport approach, and the `AspectFit`/`WebViewport`/
`KeyScroll` helpers (attributed). It drops mirroring, accessibility,
MediaProjection, overlay and the LAN server. That keeps the permission set
small, as the spec requires, and makes room for the parked gate.

## 3. YouTube surfaces: probe results (headless Chromium, 2026-10-21)

Run from a cloud datacenter IP with `tools/pagecheck/youtube-probe.js`.

| Probe | Result |
|---|---|
| `youtube.com/tv` with Tizen TV UA | **Verified:** HTTP 200, leanback UI ("YouTube on TV", `ytlr` elements), *Get started* → *Sign in with your phone* (`yt.be/activate` + code), *Add a kid account*, *Watch as guest*. Screenshot: `docs/evidence/youtube-tv-leanback-chromium.png`. |
| TV pairing to a phone ("Link with TV code") | **Not verified** (needs a phone). AA Suite reports it working. |
| TV deep link `#/watch?v=ID` | **Not working in the probe:** a fresh guest session was routed to the account picker, and setting the hash after choosing guest didn't start the video. AA Tube retries the deep link 3 times; on a real device a signed-in or already-chosen profile may behave differently. **Unverified.** |
| IFrame Player API, page with no Referer | **Verified:** error **153** (player configuration error). The app therefore loads the page with `loadDataWithBaseURL("https://<applicationId>/")`. |
| IFrame Player API from an https base URL | Player loads and reaches buffering, then error **150** for three different public videos, including "Me at the zoo". Most likely YouTube's sign-in/bot check against datacenter IPs rather than a real embed restriction. **Must be retested on the phone's mobile/Wi-Fi connection.** Screenshot: `docs/evidence/direct-iframe-datacenter-error150.png`. |
| WebView specifically (vs Chromium) | **Not verified.** Android System WebView is Chromium-based, but YouTube may treat WebView user agents differently. |

Terms and reliability: both surfaces are YouTube's own web players, with no
stream extraction and no ad blocking. YouTube can change either at any
time; `youtube.com/tv` in particular is meant for TVs. Treat both as best
effort.

## 4. Install and test methods

| Method | DHU | Real BMW | Source |
|---|---|---|---|
| `adb install` debug APK | Yes (debug build accepts any host) | **Not expected to appear**: Android Auto's *Unknown sources* option "doesn't apply to apps built using the Android for Cars App Library" | **Doc** ([testing](https://developer.android.com/training/cars/testing)) |
| Play Internal App Sharing | Yes | Officially a trusted source for developer testing; acceptance of a navigation-category video app **not guaranteed** | **Doc** + **Assumption** |
| Play Internal Testing track | Yes | Same as above | **Doc** + **Assumption** |
| Production / open testing | n/a | **Do not.** Miscategorised app; violates Play's Android Auto policies | — |

No installer spoofing, no signature tricks, no host patching.

## 5. Phase gates (none done until shown)

| # | Gate | Status |
|---|---|---|
| 1 | Builds and unit tests | **PASS** (this container) |
| 2 | Works on phone (UI, share intake, permissions) | NOT TESTED |
| 3 | Renders in DHU | NOT TESTED |
| 4 | Appears in the BMW wireless AA launcher | NOT TESTED; requires internal-testing install |
| 5 | Plays YouTube video on the BMW screen while parked | NOT TESTED |
| 6 | Audio through BMW speakers | NOT TESTED |
| 7 | Gate closes on movement (DHU `speed`) | NOT TESTED |
| 8 | Gate closes on movement (BMW, closed site) | NOT TESTED; conditional protocol in CLOSED_COURSE_TRIAL.md |

## 6. Go / no-go for the exact setup

| Question | Verdict | Confidence | Why |
|---|---|---|---|
| Can a phone app draw arbitrary content on Android Auto? | **Go** (unofficial) | Medium-high | AA Suite does it on a real head unit; it's the navigation-surface API working as designed |
| Will the app appear in the BMW launcher? | **Unknown → likely only via Play internal testing** | Medium | Official docs: Car App Library apps need a trusted source |
| Does wireless vs USB matter? | Probably not | Medium | The Car App Library is transport-agnostic; the surface comes from the host either way. Unverified on BMW wireless |
| YouTube TV in WebView | **Go** (best effort) | Medium | Loads in Chromium today; AA Suite uses it |
| Direct IFrame playback | **Unknown** | Low-medium | Error 150 from a datacenter IP; needs a phone test |
| Car speed from the BMW via CarInfo | **Unknown** | Low-medium | Documented for Android Auto (car API ≥ 3); whether BMW's head unit supplies it is untested. GPS fallback covers it when outdoors |
| Video while moving | **No-go by design** | High | See §7 |
| Play Store compliance | **No** | High | Navigation category misused for video |

**Overall:** credible experimental path for **parked** playback, blocked mainly
by distribution (needs a Play Console internal test) and by YouTube embed
behaviour (needs a phone test).

## 7. Moving-state finding (from documentation and design)

- **Doc/design:** navigation apps must keep showing a map while driving, so
  Android Auto doesn't take a navigation app's surface away when driving
  restrictions apply. DHU's `restrict all` is documented to simulate things
  such as "disabling the keyboard and disallowing phone configurations".
  Blanking a navigation surface isn't in that list.
- **Consequence:** for this architecture, *the host is not a video
  interlock*. With no app-level cutoff, video would very likely keep playing
  to the driver.
- **What the project does instead:** a fail-closed parked gate in every build
  (README → *The parked-only gate*). The lab build measures what the host does
  with the surface during simulated and real movement, using a static status
  card and a heartbeat counter rather than video. That answers the
  research question ("does the host keep supplying the surface?") without
  showing video in motion.
- **Law (Texas, informational):** Tex. Transp. Code §547.611 allows video
  equipment visible from the operator's seat only when the vehicle is in park
  or the parking brake is applied, with narrow exceptions (for example navigation
  displays). The gate is aligned with that rule. This is not legal advice.
