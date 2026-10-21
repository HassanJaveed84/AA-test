# Test checklist

Tick each item and keep the generated archive. Details: [DEVICE_TESTING.md](DEVICE_TESTING.md).
Nothing counts as verified without a result from the device, DHU or car.

## A. Phone + DHU setup (once)

- [ ] `tools/device/preflight.sh` reports no `[!!]`
- [ ] `tools/device/install.sh lab`: permissions show `granted=true` for
      ACCESS_FINE_LOCATION (CAR_SPEED may need the in-app button)
- [ ] Android Auto developer settings unlocked; **Start head unit server** running

## B. DHU run (automated: `tools/device/dhu-scenario.sh`)

If you run it manually, the DHU commands are in brackets.

- [ ] AA Tube Lab appears in the DHU launcher and opens (card: "Choose a video…")
- [ ] Gate opens on `[speed 0]`: lab strip says **Parked (car speed 0)**
- [ ] **Test pattern** plays: frame counter moves, flash + beep each second
- [ ] **Direct link** (Big Buck Bunny): plays, **or** the log shows
      `embed diagnosis … oEmbed …` followed by an automatic switch to YouTube TV
- [ ] **YouTube TV + link**: the app taps through "Watch as guest" by itself and the video plays
      (`[tv] deep-linked video playing`)
- [ ] `[restrict all]` at speed 0: record whether video keeps playing (desk observation)
- [ ] `[speed 8]`: status card **"Vehicle moving: video paused"** within about 1 s, no audio
- [ ] PLAY while moving is refused (car toast / phone toast)
- [ ] `[speed 0]`: "Stopped…", reopens after about 5 s, **does not resume by itself**
- [ ] PLAY resumes
- [ ] `[speed]` (sensor off): the gate's `speedSource` changes to `PHONE_GPS` or `NONE`
- [ ] Touch on the DHU screen works in YouTube TV (select a tile)
- [ ] Rotary: DHU `[dpad rotate right]` / `[dpad click]` reaches the action-strip buttons
- [ ] Sound comes out of the computer (DHU audio)
- [ ] `summary.md`: **no** `GATE_VIOLATION`, **no** `FATAL EXCEPTION`

## C. BMW, parked only (`tools/device/capture.sh bmw-parked` if a laptop is in the car)

- [ ] Installed via Internal App Sharing ([INSTALL_BMW.md](INSTALL_BMW.md))
- [ ] AA Tube Lab appears in the BMW launcher (if not: **BLOCKED**, note the AA version)
- [ ] Lab strip: `Parked (car speed 0)` → BMW provides speed; `Parked (phone GPS)` → it doesn't
- [ ] Test pattern; then Direct; then YouTube TV
- [ ] Audio through the car speakers; A/V sync (flash vs beep)
- [ ] BMW touchscreen taps work; iDrive knob moves between action-strip buttons
- [ ] Reconnect: car off/on → AA Tube Lab comes back and reloads
- [ ] Export lab log (phone) **or** stop `capture.sh`

No driving tests here. The optional closed-site gate check is in CLOSED_COURSE_TRIAL.md.

## D. Send results

See [RETURNING_RESULTS.md](RETURNING_RESULTS.md).
