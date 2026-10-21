# Returning test results

Every script run produces `test-results/<UTC timestamp>-<name>/` and an archive
next to it (`.zip`, or `.tar.gz` if `zip` is missing).

## What's in a run

| File | Contents |
|---|---|
| `summary.md` | Device/app versions, permissions, crashes, gate transitions with speed source, pipeline levels per step, DHU screenshot verdicts, automatic checks, step timeline |
| `logcat-aatube.txt` | Filtered logcat: AA Tube, AATubeTest markers, WebView/Chromium, car app, codecs, crashes |
| `lab-log/*.log` | The app's own session log (ISO timestamps) |
| `screens/` | DHU screenshot pairs and phone screenshots |
| `dumpsys/` | Services, gfxinfo, codec instances, audio focus, media session, displays, location |
| `dhu-console.txt` | DHU's own output (automated runs) |
| `device.txt`, `permissions.txt`, `steps.txt`, `checks.txt`, `screens.txt` | Inputs to the summary |

**Privacy:** log lines from the app are redacted (URL queries removed, video
ids cut to 3 characters). The filtered logcat can still contain device model,
build and Android Auto version. Phone screenshots show whatever was on the
phone screen. `--full-logcat` adds other apps' logs: only share that if you
have reviewed it.

## How to send them back (pick one)

1. **Attach the archive in this chat** (or a new Claude Code session on this
   repo). I read the zip directly.
2. **Commit it to the branch** (results are gitignored by default, so force-add):
   ```bash
   git add -f test-results/<run>/summary.md test-results/<run>/logcat-aatube.txt test-results/<run>/lab-log test-results/<run>/screens
   git commit -m "Device test results: <run>"
   git push
   ```
   Then tell me the run name.
3. **Quick triage:** paste `summary.md` into the chat. Attach the archive if I ask for more.

## Please also tell me

- What you saw on the DHU/car screen at each step (video, card, black, frozen).
- Whether you heard audio, and from where.
- Anything the summary can't know: dialogs on the phone, Android Auto
  messages, the car's behaviour.

## From the BMW without a laptop

Phone → AA Tube Lab → **Export lab log** → send the file to yourself → attach
it here. It contains the same gate/speed/surface/player/pipeline lines with
timestamps.
