# YouTube playback audit

Date: 2026-10-21. Environment: headless Chromium (Playwright 1.56.1) on a
cloud datacenter IP. **No phone, WebView, DHU or car.** Every result here is
from Chromium, which is close to Android WebView but not the same thing: it
has no H.264/AAC (open-source build), while WebView decodes those in hardware.

Scripts: `tools/pagecheck/youtube-playability.js`, `tv-flow.js`,
`tv-gate.js`, `check.js`. Re-run them with `PLAYWRIGHT_MODULE=<path to playwright>`.

## Summary

| Path | Result here | Verdict |
|---|---|---|
| **Direct link, IFrame Player API** | Config accepted (no error 153). YouTube reports `playableInEmbed: true` and starts streaming, then a **second player check** returns `UNPLAYABLE: "This content isn't available."`, which the API surfaces as **error 150** | **Unproven.** Not an owner embedding block; likely an anti-bot re-check against this environment. Must be tested on the phone's network. Kept as the default, with automatic fallback to YouTube TV |
| **YouTube TV, `youtube.com/tv` + deep link** | Same IP: **video decodes and plays** (Me at the zoo 320 px, Big Buck Bunny 1280 px) after one trusted tap on "Watch as guest". Deep link alone never plays | **Viable path**, now automated in the app. 5/5 + 3/3 runs played; about 6–11 s from load |
| **YouTube TV pairing** ("Link with TV code") | Reachable in a guest session: Settings → Link with TV code → 12-digit code shown | Passenger-phone side untested |

## 1. Is error 150 "embedding disallowed"?

Google's IFrame API reference documents 150 as the same as 101: the owner
doesn't allow the video in embedded players. The evidence below shows that
isn't what happened here.

| Evidence | Result |
|---|---|
| oEmbed (`/oembed?url=…`), which returns 401 for videos with embedding disabled | **200** for all four test videos (jNQXAC9IVRw, aqz-KE-bpKQ, M7lc1UVf-VE, dQw4w9WgXcQ) |
| First `/youtubei/v1/player` response inside the embed | `status: OK`, **`playableInEmbed: true`**, streaming data present |
| Media segments | `/videoplayback` requests returned **200** before the refusal |
| Second `/youtubei/v1/player` response | `status: UNPLAYABLE`, reason "Video unavailable", interstitial "This content isn't available." |
| Owner-disabled embed message (for comparison) | "Playback on other websites has been disabled by the video owner", with a "Watch on YouTube" link. **Not shown** |
| Same videos on the desktop watch page, same IP | `LOGIN_REQUIRED: "Sign in to confirm you're not a bot"` |
| Referer / origin | Player requests carry `origin=https://io.github.hassanjaveed84.aatube.lab`; without an https base URL the player returns **153** instead |

**Conclusion:** YouTube itself reports these videos as embeddable. The
refusal comes from a later check, and the same IP is bot-flagged on the watch
page. Datacenter-IP bot detection is therefore the most likely cause. **It's
still an inference, not proof.** The decisive test is the phone on mobile data
or home Wi-Fi.

An earlier version of FEASIBILITY.md called this "most likely" an IP issue
before this evidence existed; that wording was ahead of the data.

### What the app now does on 101/150/152

1. Logs the error code.
2. Queries oEmbed for the video and logs one of:
   - `oEmbed 200`: the video allows embedding, so YouTube refused this player
     for another reason (sign-in/bot check, age or region);
   - `oEmbed 401/403`: the owner disabled embedding;
   - `oEmbed 404`: private or removed.
3. Retries the same video in YouTube TV mode automatically.

The log line is `[player] embed diagnosis for <id prefix>: error 150, oEmbed HTTP 200 -> …`.

## 2. YouTube TV path

### Findings

| Step | Result |
|---|---|
| `youtube.com/tv` + Tizen TV UA | Leanback UI served |
| Plain `/tv`, first run | "Get started" welcome → account picker |
| `/tv#/watch?v=ID`, cold | Redirected to the account picker (`Add account / Add a kid account / Watch as guest`); one variant shows a "Guest" tile |
| Re-setting `#/watch?v=` without choosing a profile | Bounces back to the picker every time; **no player request is made** |
| Alternate routes (`#/watch?v=…&resume`, `#/watch/video/control?v=…`, `/tv?v=…`) | Same bounce |
| JS `element.click()` or synthetic Enter on "Watch as guest" | **Ignored**: the TV UI acts only on trusted input |
| **Trusted tap** on "Watch as guest" (Playwright touch or mouse; the app uses a native MotionEvent) | **Goes straight into the deep-linked video and plays** |
| Player response for the deep link | `CONTENT_CHECK_REQUIRED` ("…requested from outside of the YouTube app. Are you sure…?"), yet playback proceeded with no dialog in these runs |
| Codecs (Chromium) | VP9, AV1, Opus yes; H.264/AAC no. WebView should have all of them |

### Defect found and fixed

The previous app code retried the deep link by re-setting the hash every 4 s.
The probes show that re-setting the hash just returns to the account picker,
so **a shared video would never have opened** in TV mode. The new flow, in
`CarPlayerDisplay` + `PlayerPages.TV_CONTROL_SCRIPT`:

1. Every 2.5 s (up to 12 steps), the page script's `nextStep()` reports what's on screen:
   - "Get started" → tap it;
   - "Watch as guest"/"Guest" and **no signed-in profiles** → tap it;
   - a picker **with signed-in profiles** → wait for you to choose on the car
     screen (logged once);
   - otherwise → re-apply `#/watch?v=ID` until the video plays.
2. Taps are native MotionEvents at the element's position, mapped from page
   fractions to WebView pixels.
3. Paused while the parked gate is closed: no automated input during movement.

**Verification of the shipped script** (`tools/pagecheck/tv-flow.js`, the
same JS the app injects, with trusted taps standing in for MotionEvents):

| Run | Start | Steps | Playing after |
|---|---|---|---|
| 1–3 | `/tv#/watch?v=jNQXAC9IVRw` (cold) | tap "Watch as guest" | 6 s each, `videoWidth 320` |
| 4 | `/tv` (first-run welcome), video aqz-KE-bpKQ | set hash → tap "Watch as guest" | 8 s, `videoWidth 1280` |

**Gate on real TV playback** (`tools/pagecheck/tv-gate.js`): `setBlocked(true)`
paused **and** muted within 0.5 s. A play attempt while blocked advanced only
0.08 s before being paused again. Unblocking didn't resume; an explicit play did.

### Not verified, and why it matters

- **Android WebView inside a Presentation on a VirtualDisplay.** Native
  MotionEvents into this WebView already work for car-screen taps in AA
  Suite. Whether the TV UI accepts them exactly as it accepts Chromium's
  trusted touch is a strong expectation, not a measurement.
- **Signed-in profiles:** automation deliberately stops and waits for you.
- **Passenger pairing end to end.**
- **Hardware decode of H.264 in WebView**: expected, unmeasured.
- **YouTube changes:** both paths depend on YouTube's web clients, which
  change without notice.

## 3. Recommendation

1. Keep **Direct** as the first attempt (cheaper, starts at the shared time
   offset). On embed refusal, the app falls back to TV automatically.
2. Treat **YouTube TV** as the reliable path. If Direct fails on your phone
   too, switch the default mode to YouTube TV (one radio button on the phone).
3. The first DHU run answers the open question: does Direct play on your
   network? Look for `[player] embed diagnosis` lines in the summary.
