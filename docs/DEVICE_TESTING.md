# Device testing: S24 Ultra + Android Auto DHU

Exact commands for testing AA Tube on your Galaxy S24 Ultra (Android 16)
connected to the Desktop Head Unit (DHU) on your computer. Everything is
captured automatically into `test-results/<timestamp>-<name>/` and zipped.

Scripts are bash: **macOS and Linux** natively. On **Windows** use Git Bash
(everything except the piped DHU console, so use `--manual`) or WSL with
USB passthrough.

## 0. One-time setup (≈15 min)

On the computer:

1. Android Studio → **SDK Manager → SDK Tools** → tick **Android SDK
   Platform-Tools** and **Android Auto Desktop Head Unit Emulator** → Apply.
2. JDK 17+ (Android Studio's bundled JDK works: set `JAVA_HOME` to it).
3. `export ANDROID_HOME=~/Library/Android/sdk` (macOS) or `~/Android/Sdk` (Linux).

On the phone:

1. Settings → About phone → Software information → tap **Build number** 7×
   → Developer options → **USB debugging** on.
2. Android Auto (Settings → Connected devices → Android Auto) → tap
   **Version** (under About) 10× → OK. Developer settings are now in the ⋮ menu.
3. Connect the USB cable, unlock, accept "Allow USB debugging?" (tick *Always allow*).

## 1. Preflight and install

```bash
cd AA-test
tools/device/preflight.sh          # checks adb, DHU, phone, Android Auto, location
tools/device/install.sh lab        # builds, installs AA Tube Lab, grants permissions over adb
```

`install.sh` prints the permission state. If `CAR_SPEED` can't be granted
over adb, open AA Tube Lab on the phone → **Grant permissions**.

If install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, an Internal App
Sharing build is installed (different signature):
`adb uninstall io.github.hassanjaveed84.aatube.lab`, then re-run.

## 2. Start the head unit server (every session)

On the phone: Android Auto → ⋮ → **Start head unit server**. A notification
confirms it's running.

## 3a. Automated DHU run (recommended; macOS/Linux)

```bash
tools/device/dhu-scenario.sh                 # test pattern, direct link, YouTube TV
# options:
#   --video "https://youtu.be/<id>"          # a different public video (default: Big Buck Bunny)
#   --modes "TEST_PATTERN YOUTUBE_TV"        # subset of modes
#   --manual                                 # you type the DHU commands (Windows, or if piping fails)
#   --full-logcat                            # also keep unfiltered logcat (contains other apps' logs)
```

What it does:

1. Forwards `tcp:5277`, starts the DHU with `docs/dhu/headunit.ini` (speed,
   gear, parking brake, location sensors enabled) and drives its console.
2. Asks you to open **AA Tube Lab** in the DHU launcher, and waits for
   `[surface] display attached`.
3. For each mode, it runs steps and drops a `STEP` marker into logcat for each one:

   | Step | DHU console | App (over adb) | Automatic checks |
   |---|---|---|---|
   | parked | `restrict none`, `speed 0` | set mode, share link | media progressing |
   | restrict-all-speed0 | `restrict all` | — | media still progressing (desk observation) |
   | moving | `speed 8` | — | gate `CLOSED MOVING`; **no** `GATE_VIOLATION` |
   | play-while-moving | — | PLAY | `PLAY refused`; no `GATE_VIOLATION` |
   | stopped | `speed 0` | — | gate reopens; **no** automatic resume |
   | resume | — | PLAY | media progressing |
   | sensor-off | `speed` (no value) | — | gate logs its new speed source |

4. Takes **DHU screenshot pairs 1 s apart** (`screenshot <file>` in the DHU
   console) and records whether they differ. This is the only host-side
   evidence that the head unit displays changing frames.
5. Collects filtered logcat, dumpsys snapshots (services, gfxinfo, codecs,
   audio focus, media session, displays, location), phone screenshots and
   the lab log, then writes `summary.md` and a zip.

If the DHU ignores piped commands (screenshots reported "missing"), re-run
with `--manual`. The script then prints each command for you to type into the
DHU terminal.

## 3b. Manual DHU run with capture

```bash
# terminal 1
adb forward tcp:5277 tcp:5277
cd "$ANDROID_HOME/extras/google/auto" && ./desktop-head-unit -c /path/to/AA-test/docs/dhu/headunit.ini

# terminal 2
tools/device/capture.sh manual-dhu      # type a note + Enter at each step; s = phone screenshot; q = finish
```

Then follow [TEST_CHECKLIST.md](TEST_CHECKLIST.md) section B, typing the DHU
commands in terminal 1 and a note in terminal 2 at each step. DHU
screenshots: type `screenshot /absolute/path/name.png` in terminal 1.

## 4. What the evidence means

The lab build logs one `[pipeline]` line per second:

```
[pipeline] level=MEDIA_DECODING surface=yes attached=yes webview=visible gate=open draws=58/s pageFps=60 media=+1.00s frames=30 segments=2 statsAge=310ms
```

| Level | Meaning | Proves |
|---|---|---|
| `NO_SURFACE` | No car surface, or the virtual display isn't attached | — |
| `SURFACE_ONLY` | Surface held, nothing drawn this second | Host gave the surface |
| `APP_DRAWING` | The app drew into the surface (status card, page), but no media progressed | App renders into the host surface |
| `MEDIA_DECODING` | Media time advanced ≥ 0.5 s or video frames were presented in the WebView | Decoding/playback in the app |
| `GATE_VIOLATION` flag | Media progressed while the gate was closed | **A gate bug: report it** |

Host display is a separate question: only the **DHU screenshot pairs**
(`changing` vs `static`) or your own eyes show what the head unit displays.
"Surface available" and "video decoding" are therefore never treated as
"video visible on the head unit".

Speed/state lines:

```
[gate-transition] decision=CLOSED MOVING speedSource=CAR movingBy=CAR | car: api=7 setup=STARTED perm=granted last=8.00m/s(age 0.02s) status=SUCCESS callbacks=14 | gps: perm fine=granted coarse=granted setup=STARTED provider=enabled last=0.00m/s(age 0.40s) acc=3.90m fixes=55 | keepAlive=RUNNING notifications=granted
```

- `speedSource` = `CAR`, `PHONE_GPS`, `CAR+PHONE_GPS` or `NONE`.
- `setup` explains a missing source: `API_LEVEL_TOO_LOW`, `NO_PERMISSION`,
  `REFUSED`, `UNSUPPORTED`, `PROVIDER_MISSING`.
- `[gate-snapshot]` repeats every 10 s; `[speed]` shows raw samples;
  `[permission]` shows grants at session start and on change.

## 5. Logs without the scripts

```bash
adb logcat -v threadtime -s AATube AATubeTest      # every app log line, live (any build)
adb exec-out run-as io.github.hassanjaveed84.aatube.lab ls files/lab      # lab log files (debug builds)
```

Or, on the phone: AA Tube Lab → **Export lab log**.
