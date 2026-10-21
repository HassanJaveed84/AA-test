# Next steps, by priority

Ordered by what unlocks real playback in the car soonest. Checklist:
[TEST_CHECKLIST.md](TEST_CHECKLIST.md). Commands: [DEVICE_TESTING.md](DEVICE_TESTING.md).

1. **DHU run (≈45 min).** `tools/device/preflight.sh` →
   `tools/device/install.sh lab` → Start head unit server →
   `tools/device/dhu-scenario.sh`. This answers the three biggest unknowns:
   - Does **Direct** playback work from your network? (Error 150 here was an anti-bot check.)
   - Does the **YouTube TV** picker automation work in the real WebView?
   - Does the DHU's speed sensor reach the app as `speedSource=CAR`?
2. **Send the archive back** ([RETURNING_RESULTS.md](RETURNING_RESULTS.md)).
   Fixes for whatever fails come next, before the car.
3. **Internal App Sharing install (≈30 min, once).** Play Console account →
   create the app → upload `app-lab-debug.apk` → enable internal app sharing
   in the Play Store → uninstall the adb copy → install from the link.
   [INSTALL_BMW.md](INSTALL_BMW.md).
4. **BMW, parked:** checklist section C. Key question: is AA Tube Lab
   listed, and does the BMW supply car speed?
5. **Optional:** closed-site gate validation (CLOSED_COURSE_TRIAL.md), only
   under its conditions.

## Contingencies

| If… | Then |
|---|---|
| Direct still errors 150 on your phone | Make YouTube TV the default (phone → mode radio); the automatic fallback already does this per video |
| TV automation stops at the picker (`[tv] … choose one on the car screen`) | You have a signed-in profile on the TV client: tap it on the car screen once |
| `[tv] auto-continue stopped after 12 steps` | Send the run; it means the TV UI's text or layout differs from what was tested |
| DHU screenshots "missing" | Re-run with `--manual` |
| AA Tube missing from the BMW launcher after an IAS install | Try the internal testing track + Android Auto form factor; if still missing, it's a platform block (record the AA version) |
| `speedSource=NONE` in the car | The BMW doesn't send speed and GPS has no fix: park outdoors. Fail-closed is intended |
| `keepAlive=REFUSED` | GPS may stop with the screen off; car speed still drives the gate. Report it |
| Android 17 reaches the S24 Ultra | Check whether the official YouTube app offers parked video on the BMW, and prefer it |
