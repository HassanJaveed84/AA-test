# Closed-course trial: parked-gate validation (conditional protocol)

**This is a conditional manual protocol. No physical trial has been performed.
Claude cannot drive a vehicle and has no access to the BMW or a test site.**

## Purpose

To confirm, on the real 2022 M340i with stock wireless Android Auto, that:

1. The **parked-only gate closes** as soon as the car starts moving, and stays
   closed until the car has stopped for 5 s.
2. What the **BMW/Android Auto host does with the navigation surface** in
   motion (keeps compositing it, blanks it, or closes the app). This is read
   from the lab build's status-card heartbeat and surface log, without video
   playing.

It is **not** a test of watching video while moving. The app has no mode
that shows video in motion. Nothing here makes driver-visible video legal or
safe on a road.

## Go / no-go conditions (all required)

- [ ] Written or explicit permission from the site owner/operator for a vehicle test.
- [ ] The site is **genuinely closed**: no public access, no other vehicles,
      no pedestrians, no animals; access points controlled or watched. A
      publicly accessible or "abandoned" parking lot does **not** qualify.
- [ ] Local law checked independently for private-property vehicle testing
      (for reference, Tex. Transp. Code §547.611 restricts operator-visible
      video to when the vehicle is in park or the parking brake is set).
- [ ] Daylight, dry surface, clear sightlines, a straight run of at least 60 m.
- [ ] Two adults: **Driver** and **Operator** (below). No children needed; any
      child present rides in a correctly installed, appropriate restraint.
- [ ] Phone mounted or held by the Operator, never by the Driver.
- [ ] Stationary baseline (Stage 1) passed on the same day.

If any box is unticked, do not move the car. Run Stage 1 only.

## Roles

| Role | Does | Never |
|---|---|---|
| **Driver** | Operates the car only. Eyes on the course. Calls "moving" and "stopped". | Looks at the central display, touches the phone or the iDrive controls. |
| **Operator** (front passenger) | Starts playback while parked, watches the display, records the screen with a second phone, notes times, calls STOP. | Gives the Driver anything to read or do. |

## Stage 1: stationary baseline (park, engine on)

1. Install **AA Tube Lab** from your Play internal-testing link. Grant the car
   speed and location permissions. Connect wireless Android Auto and open
   AA Tube Lab on the BMW screen.
2. Check that the lab strip shows `Parked (car speed 0)`. If it shows
   `Parked (phone GPS)`, the BMW doesn't report speed through Android Auto:
   record that. If it shows `Cannot confirm…`, stop and fix permissions or GPS.
3. Play the **test pattern**, then a real YouTube video. Record: picture OK,
   audio from car speakers, A/V sync (flash vs beep), touch works, iDrive
   rotary moves between action-strip buttons.
4. Wireless reconnect: switch the car off and on (or toggle phone Bluetooth).
   Record whether AA Tube returns and reloads.

## Stage 2: low-speed gate validation

Start the second phone recording the BMW display and the speedometer/clock in
one frame. The Operator starts a real YouTube video **while parked**.

| Step | Driver | Operator records |
|---|---|---|
| 2.1 | Foot on brake, select D, release brake, creep forward; call "moving" | Time from first movement to status card "Vehicle moving: video paused"; was any audio heard after the card appeared? |
| 2.2 | Hold walking pace (≤ 10 km/h, ≈ 2.8 m/s) for ~10 s in a straight line | Card stays; heartbeat counter keeps counting (host compositing) **or** surface blanked/closed by the host (note which) |
| 2.3 | Stop; select P; call "stopped" | Card shows "Stopped: video available in a few seconds", then reopens ~5 s later; **video must not resume by itself** |
| 2.4 | (Parked) Operator presses play | Video resumes |
| 2.5 | Repeat 2.1–2.3 twice | Consistency |
| 2.6 | Optional: disable the location permission, repeat 2.1 | Car speed alone closes the gate (if the BMW reports speed) |

Speed is read from the speedometer by the Operator only if that is safe.
Don't capture or alter CAN data or any vehicle signal.

### Stop criteria (end the trial immediately)

- Anyone enters the site, or conditions change.
- The Driver feels distracted for any reason.
- **The video is still visible or audible more than 2 s after the car starts
  moving.** This is a gate failure: park, export the log, file it as a
  blocker, and don't repeat.
- Any warning on the BMW, any unexpected vehicle behaviour.

## Recovery / reset

Park, select P, apply the parking brake. Close AA Tube Lab on the car screen.
Phone → AA Tube Lab → **Export lab log**. Stop the screen recording. If the
car display misbehaves, disconnect Android Auto and reconnect. No vehicle
settings are changed by this app, so there is nothing else to reset.

## Report template

| Field | Value |
|---|---|
| Date / time / site / permission reference | |
| Driver / Operator (initials) | |
| Phone build, Android Auto version, WebView version (from log header) | |
| AA Tube Lab version | |
| BMW reports speed via Android Auto? (Stage 1.2) | yes / no (GPS fallback) |

| Observation | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Stationary: video OK / audio via speakers / sync | | | |
| First movement → card (seconds) | | | |
| Audio after card? | | | |
| Steady low speed: host surface behaviour (kept + heartbeat / blanked / app closed) | | | |
| Return to stationary: settle then reopen, no auto-resume | | | |
| Visible errors | | | |
| Result | PASS / FAIL | | |

Conclusions must be kept in separate categories: **supported in DHU**,
**supported in the BMW while stationary**, **gate observed during controlled
movement**, **not verified**, **blocked**. None of them authorizes
driver-visible video on public roads.
