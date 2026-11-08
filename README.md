# INVT EV Charger — Bluetooth tracker (Android, phone-only build)

A small Android app that connects to your INVT EVC16 charger over **Bluetooth
(BLE)**, shows live charging status, and logs each session to a CSV file.
No INVT cloud account, no WiFi needed — it talks straight to the charger.

Confirmed working against charger `3UC243403423`:
- write status query `55 07 00 61 00 33 AA` to characteristic **0xC303**
- receive replies on NOTIFY characteristic **0xC305**
- same `0x55…0xAA` frame protocol as the WiFi local mode.

You have **no PC**, so the app is compiled **in the cloud by GitHub Actions**.
You push these files to a free GitHub repo, GitHub builds the APK, you download
and install it. ~15 minutes, all from your phone.

---

## Step 1 — Make a GitHub account (if you don't have one)
Go to https://github.com and sign up (free). Verify your email.

## Step 2 — Create a new repository
1. Top-right **+** → **New repository**.
2. Name it e.g. `invt-charger-bt`.
3. Set it **Private** (your choice) and tick **Add a README** so the repo isn't empty.
4. **Create repository.**

## Step 3 — Upload these project files
The whole folder structure matters, so upload keeping the paths intact.

Easiest from a phone:
1. On the repo page, tap **Add file → Upload files**.
2. GitHub's web uploader lets you pick multiple files. Upload them **preserving
   folders** — if the uploader flattens them, use the method below instead.

If the web uploader won't keep folders, use the GitHub mobile app or create each
file by hand (**Add file → Create new file**, then type the path like
`app/src/main/java/com/radhi/invtcharger/MainActivity.kt` — GitHub makes the
folders as you type the `/`). The files you need, with their exact paths:

```
build.gradle
settings.gradle
gradle.properties
.gitignore
.github/workflows/build.yml
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/java/com/radhi/invtcharger/InvtProtocol.kt
app/src/main/java/com/radhi/invtcharger/MainActivity.kt
```

(The `gradle/wrapper/gradle-wrapper.properties` is optional — the build
generates the wrapper itself.)

Commit the uploads (**Commit changes**).

## Step 4 — Let GitHub build it
1. As soon as you commit to the `main` branch, the build starts automatically.
   (Or go to the **Actions** tab → **Build APK** → **Run workflow**.)
2. Open the **Actions** tab. You'll see a run called **Build APK** with a spinner.
3. Wait ~3–6 minutes for it to finish with a green check.
4. If it fails (red X): open the run, tap the failed step, copy the error, and
   send it to me — I'll fix it. First builds sometimes need a small tweak.

## Step 5 — Download the APK
1. Open the finished (green) run in the **Actions** tab.
2. Scroll down to **Artifacts**.
3. Tap **invt-charger-bt-apk** to download a `.zip`.
4. On your phone, open the zip and extract **app-debug.apk**.

## Step 6 — Install the APK
1. Tap `app-debug.apk`. Android will ask to allow installing from this source —
   **Allow**.
2. Install it. It appears as **INVT Charger BT**.

## Step 7 — Use it
1. Open **INVT Charger BT**.
2. It asks for **Bluetooth** (and on older Android, Location) permission — **Allow**.
   (Location permission is required by Android for BLE scanning; the app doesn't
   use your location.)
3. Make sure the charger is powered and **not currently connected in another app**
   (nRF Connect, the INVT app) — BLE allows one connection at a time. Disconnect
   those first.
4. Tap **Connect**. It scans for a device whose name starts with `3UC`, connects,
   and starts polling.
5. Live values appear: state, power, energy, voltage, current, cost, duration.
6. Each charging session is saved to a CSV at:
   `Android/data/com.radhi.invtcharger/files/invt_sessions.csv`
   (reachable with a file manager; or we can add an in-app export later).

---

## Troubleshooting
- **"Charger service not found"** — the charger exposes service 0xA002; if this
  shows, the device connected isn't the charger or its name doesn't start `3UC`.
  Tell me the exact advertised name and I'll adjust the filter.
- **Connects then disconnects** — another app holds the BLE link. Close nRF
  Connect / INVT app, toggle Bluetooth off/on, try again.
- **Values look wrong** (e.g. cost scaled oddly) — send me a screenshot; every
  field's scaling is known and quick to adjust.
- **Reply only shows short data** — the app reassembles BLE fragments
  automatically, so it won't have the `39 AA` problem nRF had by hand.

## What each file does
- `InvtProtocol.kt` — builds the status-query frame, decodes replies, reassembles
  BLE fragments. Pure logic, already verified.
- `MainActivity.kt` — the BLE connection, polling loop, live UI, CSV logging.
- `build.gradle` / `settings.gradle` / `app/build.gradle` — Gradle project setup.
- `.github/workflows/build.yml` — the cloud build that produces the APK.
