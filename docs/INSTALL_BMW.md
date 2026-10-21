# Installing AA Tube for the BMW (Play Console)

## Why Play is required

Android's car-app testing docs say real vehicles only list apps installed
from a trusted source such as Google Play. Android Auto's *Unknown sources*
developer option covers media, messaging and parked apps, **not Car App
Library apps** like AA Tube. An `adb install` works on the DHU but isn't
expected to appear on the BMW.
([source](https://developer.android.com/training/cars/testing))

## Shortest route: Internal App Sharing with the lab debug APK

Per [Google's help page](https://support.google.com/googleplay/android-developer/answer/9844679),
Internal App Sharing accepts **debuggable APKs signed with any key**, re-signs
them with an internal-sharing key, doesn't require new version codes, and
links expire after 60 days. Per [Android's distribution page](https://developer.android.com/training/cars/distribute),
internal sharing gets **no Android Auto form-factor review**.

That means no keystore and no release build are needed for the first car test.

### Steps

1. **Play Console account** (one-time, US$25) at <https://play.google.com/console>.
   New personal accounts must complete identity verification, and may be asked
   to verify an Android device, before uploads work.
2. **Create the app:** *Create app* → name "AA Tube Lab (private)", App, Free
   → accept the declarations. Don't fill in a store listing; nothing will be
   published.
3. **Internal app sharing settings** (Play Console → *Internal app sharing*):
   - *Uploaders*: your account (anyone with "Release apps to testing tracks"
     can upload by default).
   - *Downloaders*: restrict downloads to an email list containing the Google
     account signed in on the S24 (the default is "anyone with the link").
4. **Build and upload:**
   ```bash
   ./gradlew assembleLabDebug
   # upload app/build/outputs/apk/lab/debug/app-lab-debug.apk at
   # https://play.google.com/console/internal-app-sharing
   ```
   Copy the generated link.
5. **Phone, once:** Play Store → profile → **Settings → About** → tap
   **Play Store version** 7× → back in Settings → **General → Internal app
   sharing** → on.
6. **Remove any adb-installed copy** (different signature, so the install would fail):
   `adb uninstall io.github.hassanjaveed84.aatube.lab`
7. Open the link on the S24 → **Install**.
8. Open AA Tube Lab on the phone → **Grant permissions**.
9. Android Auto → Settings → **Customise launcher** → AA Tube Lab enabled.
10. In the car (parked): open AA Tube Lab from the BMW screen.

Debug-build advantages: logs can be pulled with `run-as` (`tools/device/capture.sh`),
and the debug host validator accepts any Android Auto host.

## Alternative: Internal testing track (signed release AAB)

Use this if Internal App Sharing doesn't make the app appear, or you want
updates through the normal Play Store.

1. Create an upload key **outside the repo** (or in the gitignored `keystore/`):
   ```bash
   mkdir -p keystore
   keytool -genkeypair -v -keystore keystore/aatube-upload.jks -alias aatube \
     -keyalg RSA -keysize 4096 -validity 10000
   cat > keystore/keystore.properties <<'P'
   storeFile=keystore/aatube-upload.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=aatube
   keyPassword=YOUR_KEY_PASSWORD
   P
   ```
2. Build (increase the version code on every upload):
   ```bash
   ./gradlew bundleLabRelease -PaatubeVersionCode=2
   # -> app/build/outputs/bundle/labRelease/app-lab-release.aab
   ```
3. Play Console → *Testing → Internal testing* → *Create new release* → accept
   **Play App Signing** → upload the AAB → release notes → *Save* → *Review
   release* → *Start rollout*.
4. *Testers* tab: create an email list with your account, then open the
   **opt-in link** on the S24 and accept.
5. If the app doesn't show up in the car afterwards: *Advanced settings →
   Form factors → Add form factor → Android Auto*, then roll out a new
   internal release.

Release builds validate the host against the Car App Library's allowlist
(`hosts_allowlist_sample`), which includes Android Auto.

## Likely problems

| Symptom | Likely cause | Fix |
|---|---|---|
| Upload rejected: "debuggable APK" | Uploaded a debug build to a **track** | Tracks need `bundleLabRelease`; debug builds are only for Internal App Sharing |
| "Version code already used" | Track upload with the same versionCode | `-PaatubeVersionCode=<higher>` |
| "You need a signed bundle" | No `keystore/keystore.properties` | Create it (above); `bundleLabRelease` is unsigned without it |
| IAS link: "App not available" / "no access" | Internal app sharing not enabled in the Play Store, the account isn't on the downloader list, or wrong Google account on the phone | Steps 3 and 5 above; check the active Play account |
| Install fails over an existing copy | Signature differs (adb debug vs IAS key vs Play App Signing) | Uninstall first; stick to one channel per package |
| Installed but not in the BMW launcher | Hidden in *Customise launcher*; or installed via adb; or AA hasn't refreshed | Enable it in Customise launcher; reinstall via Play; reconnect AA or reboot the phone |
| Still not in the launcher after a Play install | Android Auto rejecting a navigation-category app with unusual content, or a host-version mismatch | Record it as **BLOCKED** with the AA version from `device.txt`; try the internal track + form factor |
| Opens, but the car screen shows "Cannot confirm the car is parked" | The BMW doesn't send speed to Android Auto apps, and there's no GPS fix (garage) | Park outdoors; the lab log shows `car: setup=… status=…` |
| Play policy email after a **track** upload | Automated policy checks still run on testing tracks | Keep it internal; never promote to closed/open/production |

## What not to do

- Don't publish beyond internal testing. The navigation category is misused
  for video, which violates Play's Android Auto policies.
- Don't spoof the installer, use re-signing tricks, or patch Android Auto.
- Don't commit `keystore/` (already gitignored).
