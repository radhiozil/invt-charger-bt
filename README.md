# INVT Charger (BT)

A small Android app that talks to an **INVT EVC16 7 kW home EV wallbox** directly over **Bluetooth Low Energy (BLE)** — no cloud account, no internet. It reads live charging data, pulls the charger's own stored session history, charts it, auto-logs sessions, and costs them at your own electricity tariff.

Built because the official INVT Charge app's cloud server was unreachable and the older app wouldn't run on modern Android. This app speaks the charger's native BLE protocol directly.

---

## Features

- **Live tab** — real-time status while a car is plugged in: charging state, lifetime meter total (kWh), this-session kWh, voltage, current, power, duration, cost, fault code. Start/Stop controls.
- **History tab** — pulls the charger's stored charge records over BLE (date, kWh, duration, gun, fees) and keeps a permanent copy on the phone.
- **Charts** — kWh bars by **Weekly** (calendar week, Mon start), **Monthly**, or **Yearly**. Tap any bar to filter the list to that period; a chip shows the selected period with a **Clear** button.
- **Auto-log** — records are pulled automatically on connect and merged (deduplicated by start time), so history survives even after the charger recycles old entries. A session that finishes while the app is open is also logged on the spot.
- **Cost settings (⚙)** — set your electricity rate (RM/kWh); cost is then computed as `kWh × rate` on the Live tab, on every record, and as a period total in the summary.
- **Offline & private** — everything happens over BLE and local storage. No account, no network calls.

---

## Install

The app is built in the cloud by GitHub Actions and sideloaded.

1. On GitHub: **Actions → latest successful run → Artifacts → download the APK** (or the APK your workflow produces).
2. On the phone, open the downloaded `.apk` with the **system installer** (Files app → tap the APK). Do **not** use a third-party split-APK installer — this is a single APK.
3. If you see **`INSTALL_FAILED_VERIFICATION_FAILURE`**: that's Google Play Protect. Open **Play Store → profile → Play Protect → ⚙ Settings → turn off "Scan apps with Play Protect"**, install, then turn it back on. (Toggling Airplane mode during install also works.)
4. Grant **Bluetooth / Nearby devices** permission when asked.

> Builds from Actions all use the same debug signing key, so each new build upgrades the previous install cleanly. If you ever installed from a different source, uninstall the old copy first.

---

## How to use

1. Make sure the car-side app connection is free (the charger allows one BLE client at a time).
2. Tap **Connect** → the app scans for the charger (BLE name starts with `3UC`), handshakes, and starts polling.
3. **Live tab**: watch real-time values. **Start/Stop** send charge commands.
4. **History tab**: tap **↻ Refresh** (or just connecting auto-pulls) to sync stored sessions. Switch **Weekly / Monthly / Yearly**; tap a bar to drill into one period; **Clear** to reset.
5. **⚙ (gear)**: enter your RM/kWh rate. Leave empty / **Clear** to fall back to the charger's own cost figure.

---

## Build (GitHub Actions)

This repo builds in the cloud — no local Android SDK needed.

- Workflow: `.github/workflows/main.yml` — runs on push, uses JDK 17 and `./gradlew assembleDebug` on the preinstalled Android SDK, and uploads the debug APK as a build artifact.
- To change the app: edit the Kotlin/XML files on GitHub (web editor or upload), commit to `main`, and Actions rebuilds automatically. Download the new APK from the run's Artifacts.

**If a build fails:** open the failed run → **build** job → **Build debug APK** step → search the log for `.kt:` (Kotlin error with file:line) or `error:` (resource/AAPT error). That line names the exact problem.

### Project layout

```
app/src/main/
├─ AndroidManifest.xml            # permissions, launcher icon, dark theme
├─ java/com/radhi/invtcharger/
│  ├─ MainActivity.kt             # UI, BLE connection, history, charts, settings
│  └─ InvtProtocol.kt             # BLE frame encode/decode + parsers
└─ res/
   ├─ values/colors.xml           # launcher background colour
   ├─ drawable/ic_launcher_foreground.xml
   └─ mipmap-anydpi-v26/…         # adaptive launcher icon
```

No external UI libraries — the interface and charts are drawn in code to keep the build lightweight and dependency-free.

---

## BLE protocol reference

Reverse-engineered from the INVT Charge iOS app (v2.4.2). Documented here so future changes don't require re-deriving it.

**GATT**
- Service `0xA002`
- Write (commands) → characteristic `0xC304`
- Notify (replies) ← characteristic `0xC305`
- Negotiate MTU ~185, enable notifications on `0xC305`, then handshake.

**Frame format** (little-endian throughout)

```
0x55 | lenLo | lenHi | protocolVersion(0x01) | cmd | payload… | xor | 0xAA
```
- `len` = total frame length (bytes 1–2).
- `cmd` is at **byte 4** (byte 3 is the protocol version).
- `xor` = XOR of every byte before it.

**Flow:** send handshake (cmd 0) once, then poll status (cmd 1). History is pulled with cmd 16 then cmd 17 over the same connection.

**Commands**

| cmd | name | query payload |
|----:|------|---------------|
| 0 | HANDSHAKE | unix time (LE32 sec) + timezone `[sign, hours, mins]` |
| 1 | STATUS | `[0x00]` |
| 19 | START/STOP | `[1]` start / `[0]` stop |
| 16 | RECORDS_COUNT | `[0x00]` → reply = LE16 count |
| 17 | RECORDS_DATA | LE16 index → reply = one record |

**Status reply** (payload from byte 5; needs ≥ 28 bytes)

| offset | field |
|-------:|-------|
| 2–5 | lifetime capacity, LE32 /100 → kWh |
| 7 | charging status (0 idle, 1 connected, 2 charging, 3 finished, 4 fault) |
| 9 | fault code |
| 10–11 | voltage, LE16 /10 |
| 12–13 | current, LE16 /10 |
| 14–17 | session kWh, LE32 /100 |
| 18–21 | session cost, LE32 /100 |
| 22–23 | duration (min), LE16 |
| 24–25 | power, LE16 /10 |

**Record reply** (payload from byte 5; needs ≥ 43 bytes)

| offset | field |
|-------:|-------|
| 0–1 | record index, LE16 |
| 2 | gun (0=A, 1=B) |
| 3–6 | start time, LE32 unix sec |
| 7–10 | total kWh, LE32 /100 |
| 31–34 | electricity fee, LE32 /100 |
| 35–38 | service fee, LE32 /100 |
| 39–40 | duration (min), LE16 |
| 41 | stop reason |
| 42 | fault code |

> The charger stores records in a fixed ring buffer and overwrites the oldest when full, which is why the app keeps its own deduplicated copy on the phone.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Won't find the charger | Charger must be powered and not already connected to another phone/app. BLE name starts with `3UC`. |
| Connects but no data | Make sure notifications on `0xC305` are enabled and the handshake was sent (the app does this automatically — try Disconnect/Connect). |
| History empty | Tap **↻ Refresh** while connected. If still empty on your firmware, the record index may be 1-based — a one-line change in `MainActivity`. |
| Install blocked | See **Install** step 3 (Play Protect). |
| Build fails | See **Build → If a build fails**. |

---

## Notes

- Model: INVT **EVC16-AW7KGF1W(US)**, 7 kW single-phase.
- Package: `com.radhi.invtcharger`.
- This is a personal utility, not affiliated with INVT.

Built with Claude. 🔌
