# GhostWatch — Complete Guide

> DIY remote security system for monitoring a vacant flat.
> Installed on an old phone left at the Delhi flat, controlled via SMS from Kashmir.

---

## What Is GhostWatch?

GhostWatch is an Android app that turns an old phone into a silent remote monitoring device. It runs as a persistent background service, controlled entirely by SMS from your trusted number. No internet required — works over the cellular network.

---

## Capabilities

### 1. Sound Detection (Ambient Monitoring)
- Continuously samples mic audio at 44.1 kHz
- Every 500ms computes RMS amplitude of the audio buffer
- If amplitude ≥ `SOUND_THRESHOLD` (default 2000), sends an SMS alert to your Kashmir number
- 60-second cooldown between alerts to prevent spam
- Alert includes sound level % and timestamp

### 2. Remote SMS Control
- Intercepts incoming SMS at highest priority (999)
- Only responds to messages from `TRUSTED_NUMBER`
- Commands (case-insensitive):

| Command | Effect |
|---|---|
| `GHOST ON` | Start mic monitoring, sends confirmation SMS |
| `GHOST OFF` | Stop monitoring |
| `GHOST STATUS` | Reply with: active state, battery %, uptime, alert count |
| `CALL ON` | Enable call watch (log all calls) |
| `CALL OFF` | Disable call watch |
| `GHOST HIDE` | Remove app icon from launcher (app keeps running) |
| `GHOST SHOW` | Restore app icon to launcher |

### 3. Live Audio — Auto-Answer Your Calls
- When you call the Delhi phone from your Kashmir number, it auto-answers silently
- Ringer is muted before first ring completes
- Auto-answers after 1.5s delay using 4 fallback methods:
  1. TelecomManager API (standard Android)
  2. `ACTION_ANSWER` intent (some MIUI versions)
  3. Headset hook broadcast (legacy fallback)
  4. AccessibilityService click (MIUI/Xiaomi specific)
- Earpiece mode + call volume set to 0 (no audio leaks in the flat)
- Screen locks immediately after answer (hides active call)
- You hear the flat's ambient audio live

> **Note:** Auto-answer works independently of `GHOST ON/OFF`. `CallReceiver` is always active as a manifest-registered broadcast receiver. You can call the Delhi phone and listen live at any time, even without activating GHOST.

### 4. Call Watch
- When `CALL ON` is active, every incoming AND outgoing call on the Delhi phone sends you an SMS
- SMS includes: caller/recipient name (from contacts), number, time, and answered/not answered status

### 5. Icon Hiding (Stealth)
- The app icon can be hidden from the launcher so it won't appear in the app drawer
- The service keeps running unaffected — only the shortcut is removed
- Can be triggered locally via the **HIDE ICON FROM LAUNCHER** button in the app UI
- Or remotely via SMS `GHOST HIDE` from Kashmir
- Restore the icon at any time by sending `GHOST SHOW` — the SMS receiver stays active even when the icon is hidden

### 6. Persistence — Survives Reboots & Kills
- `BootReceiver` auto-restarts the service on device reboot if it was previously active
- `START_STICKY` — Android restarts the service if it gets killed
- `PARTIAL_WAKE_LOCK` keeps the CPU awake for the mic thread (24h max, reacquired on restart)
- Battery optimization exemption requested on first launch

---

## Feature Independence Summary

| Feature | Requires `GHOST ON`? |
|---|---|
| Mic monitoring (sound alerts) | Yes |
| Auto-answer your call (live audio) | **No — always active** |
| Call watch (log all calls) | **No — toggle with `CALL ON/OFF`** |
| Icon hiding / showing | **No — works any time via SMS** |

---

## Configuration (Before Building)

Edit `app/src/main/java/com/ghostwatch/app/GhostConfig.kt`:

```kotlin
// Your Kashmir number — only this number can issue commands and receive alerts
const val TRUSTED_NUMBER = "+91XXXXXXXXXX"

// Sound sensitivity (RMS amplitude 0–32767):
//   800  = very sensitive (whispers, footsteps)
//   2000 = moderate (talking, movement)  ← default
//   5000 = loud only (shouting, banging)
const val SOUND_THRESHOLD = 2000

// Seconds between sound alert SMS (avoid spam)
const val ALERT_COOLDOWN_SECONDS = 60L

// Mic polling interval
const val SAMPLE_INTERVAL_MS = 500L

// Foreground notification text (keep it generic/boring)
const val NOTIFICATION_TITLE = "System Service"
const val NOTIFICATION_TEXT  = "Running in background"

// Auto-answer settings
const val AUTO_ANSWER_ENABLED  = true
const val AUTO_ANSWER_DELAY_MS = 1500L   // rings before answering
const val MERGE_DELAY_MS       = 3000L   // delay before merging if call already active
```

---

## Build Instructions

Requires: Android Studio or JDK 17 + Android SDK on your dev machine.

```bash
# Debug APK (easier to install, larger file)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (smaller, ProGuard optimized)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk

# Direct install via USB (if ADB available)
./gradlew installDebug

# Clean build artifacts
./gradlew clean
```

---

## Installation on the Delhi Phone

### Step 1 — Configure & Build
Edit `GhostConfig.kt` with your Kashmir number, then build the APK as above.

### Step 2 — Transfer APK to Delhi Phone
Options:
- Email it to yourself → download on Delhi phone
- USB cable: `adb install app-debug.apk`
- Google Drive, WhatsApp, or any file transfer method

### Step 3 — Enable Unknown Sources
On the Delhi phone:
**Settings → Security → Install Unknown Apps** → allow for your browser or file manager.

### Step 4 — Install & Launch
Tap the APK file to install. Open **GhostWatch**. It will immediately prompt for all required permissions — grant all of them:

- Microphone
- Receive SMS / Send SMS / Read SMS
- Read Phone State
- Answer Phone Calls
- Read Contacts
- Modify Audio Settings

### Step 5 — Grant Special Permissions (Critical)

These must be done manually inside the app UI — they cannot be auto-granted:

| Button in App | What It Enables | Why Needed |
|---|---|---|
| **Enable Device Admin** | `lockNow()` permission | Locks screen after auto-answering to hide active call |
| **Enable Accessibility** | Click MIUI answer button | MIUI/Xiaomi blocks standard auto-answer APIs |
| **Battery Optimization** | Exempt from doze mode | Keeps service alive when phone is idle/screen off |
| **Hide Icon from Launcher** | Removes app from app drawer | Stealth — icon gone but service keeps running; restore with `GHOST SHOW` |

> **MIUI / Xiaomi phones:** The Accessibility Service step is **mandatory**. MIUI blocks `TelecomManager.acceptRingingCall()` — without the accessibility service, auto-answer will not work.

### Step 6 — Battery Optimization (if not prompted automatically)
**Settings → Battery → App battery saver → GhostWatch → No restrictions**

On MIUI: **Settings → Apps → Manage apps → GhostWatch → Battery saver → No restrictions**

### Step 7 — Activate & Test
Either:
- Tap **ACTIVATE** in the app UI, or
- Send `GHOST ON` SMS from your Kashmir number

You should receive a confirmation SMS back:
```
✓ GhostWatch activated. Monitoring started.
```

Test sound alerts by making noise near the phone — you should get an alert SMS within ~60 seconds.

Test live audio by simply calling the Delhi number from Kashmir — it should answer silently.

---

## Day-to-Day Usage (from Kashmir)

### SMS Commands

```
GHOST ON       → Start sound monitoring
GHOST OFF      → Stop monitoring
GHOST STATUS   → Get status report (battery, uptime, alerts sent)
CALL ON        → All calls on the Delhi phone will notify you
CALL OFF       → Stop call notifications
GHOST HIDE     → Remove app icon from launcher (service keeps running)
GHOST SHOW     → Restore app icon to launcher
```

### Status Reply Format
```
GhostWatch Status
Active: YES
Battery: 84%
Alerts sent: 3
Uptime: 6h 42m
```

### Sound Alert Format
```
⚠ SOUND DETECTED
Level: 34%
Time: 14:23:07 18/04
Location: Delhi Flat
```

### Call Notification Format (when CALL ON)
```
📞 CALL — Delhi Flat
Name: Raju Electrician
Num:  +919XXXXXXXXX
Time: 11:05 18/04
→ Not answered
```

### Live Audio
Just call the Delhi phone number from your Kashmir number. It silently auto-answers. No commands needed, works regardless of GHOST ON/OFF state.

### Icon Visibility

| Action | Method |
|---|---|
| Hide icon from app drawer | Tap **HIDE ICON FROM LAUNCHER** in app UI, or SMS `GHOST HIDE` |
| Restore icon | SMS `GHOST SHOW` from Kashmir number |

> The SMS receiver stays active even when the icon is hidden, so you can always send `GHOST SHOW` to get it back.

---

## Architecture Overview

```
SMS "GHOST ON"  →  SmsReceiver  →  validate TRUSTED_NUMBER  →  start GhostService
                                                                ↓
                                                         mic thread (44.1kHz PCM)
                                                                ↓
                                                         RMS > SOUND_THRESHOLD?
                                                                ↓
                                                         SMS alert → Kashmir number

You call Delhi  →  CallReceiver  →  isTrustedNumber()?  →  mute ringer
                                                         →  auto-answer (4 methods)
                                                         →  set volume = 0
                                                         →  lock screen
                                                         →  you hear flat live

Any call rings  →  CallReceiver  →  callWatchActive?    →  SMS caller ID → Kashmir

SMS "GHOST HIDE" →  SmsReceiver  →  setComponentEnabled(MainActivity, DISABLED)
                                  →  icon gone from launcher, service unaffected
SMS "GHOST SHOW" →  SmsReceiver  →  setComponentEnabled(MainActivity, ENABLED)
                                  →  icon restored
```

### Component Summary

| File | Role |
|---|---|
| `GhostConfig.kt` | Single source of truth — all config lives here |
| `GhostService.kt` | Foreground service, mic thread, RMS calculation, SMS alerts |
| `SmsReceiver.kt` | Intercepts SMS, validates trusted number, dispatches commands |
| `CallReceiver.kt` | Auto-answers trusted calls, call watch, screen lock after answer |
| `BootReceiver.kt` | Restarts service on device reboot |
| `GhostAccessibilityService.kt` | Clicks MIUI answer button when standard APIs fail |
| `GhostDeviceAdmin.kt` | Device admin receiver — enables `lockNow()` |
| `MainActivity.kt` | Local UI for activation, permission grants, status display |

---

## Permissions Required

| Permission | Used For |
|---|---|
| `RECORD_AUDIO` | Mic monitoring |
| `RECEIVE_SMS` / `READ_SMS` | Intercept command SMS |
| `SEND_SMS` | Send alerts and replies |
| `READ_PHONE_STATE` | Detect incoming calls |
| `ANSWER_PHONE_CALLS` | Auto-answer API |
| `READ_CONTACTS` | Look up caller name for call notifications |
| `MODIFY_AUDIO_SETTINGS` | Mute ringer, set earpiece mode |
| `WAKE_LOCK` | Keep CPU awake for mic thread |
| `RECEIVE_BOOT_COMPLETED` | Restart after reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Exempt from doze |
| `FOREGROUND_SERVICE_MICROPHONE` | Required for mic in foreground service (Android 12+) |

---

## Tips & Troubleshooting

**Keep the phone plugged in.**
The 24h wake lock + continuous mic sampling will drain a battery-only phone in hours.

**Threshold tuning.**
Start at 2000. After a day, send `GHOST STATUS` to check alert count. If too many false alerts from street noise, HVAC, etc., raise threshold to 3000–5000.

**Auto-answer not working on MIUI?**
Accessibility Service must be enabled. Go to app UI → tap **ENABLE** next to Accessibility. Find "GhostWatch" in the system accessibility list and enable it.

**Service getting killed?**
- Grant battery optimization exemption (Step 6)
- On MIUI: also disable "MIUI optimization" for the app under Developer Options
- Check that Device Admin is active (prevents some force-stop scenarios)

**SMS commands not working?**
- Confirm `TRUSTED_NUMBER` in `GhostConfig.kt` includes country code: `+91XXXXXXXXXX`
- The app normalizes numbers (strips +, 91, spaces) but always use the full format when configuring

**No sound alerts despite noise?**
- Check `GHOST STATUS` — confirm Active: YES
- Lower `SOUND_THRESHOLD` (try 800–1000 for a quiet flat)
- Confirm microphone permission is granted

**Icon hidden and need to get back in?**
- Send `GHOST SHOW` from your Kashmir number — the icon reappears in the launcher instantly
- The service is always running in the background so the SMS command always works

---

## Build Notes

- `minSdk 26` (Android 8.0), `targetSdk 34`
- Kotlin, View Binding enabled (`ActivityMainBinding`)
- Java 17 source/target compatibility
- JVM heap: 2GB (`org.gradle.jvmargs=-Xmx2048m`)
- Release builds: ProGuard + resource shrinking enabled

---

*GhostWatch — personal property monitoring, Delhi flat.*
