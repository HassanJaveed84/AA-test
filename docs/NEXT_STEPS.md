# Next steps (what only you can do), by priority

Ordered by what unblocks real playback in the car soonest.

## 1. Desk check on the phone (15 min)

```bash
./gradlew installLabDebug        # S24 Ultra on USB debugging
```
- Open **AA Tube Lab**, tap **Grant permissions**, allow all.
- Share a YouTube video from the YouTube app to AA Tube Lab; it should say
  "Saved…" (not connected yet).

## 2. DHU run (30–60 min)

- Install the DHU (SDK Manager → SDK Tools → Android Auto Desktop Head Unit).
- Android Auto → tap Version 10× → ⋮ Developer settings → **Start head unit server**.
- `adb forward tcp:5277 tcp:5277` then `desktop-head-unit -c docs/dhu/headunit.ini`.
- In the DHU console: `speed 0`. Open AA Tube Lab. Play the **test pattern**,
  then a **Direct** video, then **YouTube TV**.
- **Key unknown to resolve:** does Direct (IFrame) playback work from your
  phone's network, or does it return error 150 like it did from the
  datacenter? Record the result in TEST_REPORT §5.
- Run the moving-state lab (MOVING_STATE_LAB.md), at least A1, A2, B1, B2, B4.
- Export the lab log and keep it with your screenshots.

## 3. Get a trusted install for the car (one-off, ~1 h)

The car won't list an adb-installed Car App Library app (official docs).
1. Create an upload keystore **outside the repo**, then `keystore/keystore.properties` (README).
2. `./gradlew bundleLabRelease` (and/or `bundleDailyRelease`).
3. Play Console → create a private app → **Internal app sharing** → upload
   the AAB → open the link on the S24 Ultra and install. (Or use an
   Internal testing track with only yourself as tester.)
4. Never promote it to closed, open or production testing.

## 4. BMW stationary validation (30 min, parked)

- Connect wireless Android Auto; AA settings → Customise launcher → AA Tube
  enabled → open it on the iDrive screen.
- Does it appear? (If not, record **BLOCKED: not listed**, with the AA version.)
- Lab strip: does it say `Parked (car speed 0)` (BMW reports speed) or
  `Parked (phone GPS)`?
- Video, audio through speakers, A/V sync (test pattern flash vs beep), touch,
  iDrive rotary, reconnect (car off/on).
- Fill in TEST_REPORT §6.

## 5. Optional: closed-course gate validation

Only if every condition in CLOSED_COURSE_TRIAL.md is met (authorized, genuinely
closed site, separate driver and operator). It validates that video stops on
movement and records what the host does with the surface.

## 6. Contingencies

| If… | Then |
|---|---|
| Direct mode always errors 150/152 on the phone | Use YouTube TV mode; pair the passenger phone while parked |
| TV deep link keeps landing on the account picker | Pick a profile once on the car screen; cookies keep it. Or cast from the phone instead of sharing a link |
| AA Tube isn't listed in the BMW even from internal testing | Record it as blocked. Remaining options: wait for Android 17 parked video (official), or use a rear-seat tablet (REAR_PASSENGER_OPTIONS.md) |
| Gate shows `Cannot confirm the car is parked` in a garage | The BMW doesn't send speed and GPS has no fix. Park outdoors; this fail-closed behaviour is intended |
| Location FGS refused (lab log) | Car speed alone drives the gate; report it so the start point can be moved |
| Android 17 reaches the S24 Ultra | Check whether the official YouTube app offers parked video on the BMW. If so, prefer it |
