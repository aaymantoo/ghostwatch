# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (with ProGuard/resource shrinking)
./gradlew assembleRelease

# Install on connected device/emulator
./gradlew installDebug

# Run unit tests
./gradlew test

# Clean build artifacts
./gradlew clean
```

## Architecture

GhostWatch is an Android Kotlin app (minSdk 26, targetSdk 34) that runs as a persistent background monitoring service controlled remotely via SMS commands.

### Component Overview

**`GhostConfig.kt`** — Single source of truth for all configuration. Edit this before building: trusted phone number, SMS commands, audio detection threshold, reply messages, and sample interval.

**`GhostService`** (foreground service) — Core engine. Holds a `PARTIAL_WAKE_LOCK`, runs a microphone monitoring thread that samples PCM audio (44.1 kHz, 16-bit mono), computes RMS amplitude, and sends an SMS alert to `TRUSTED_NUMBER` when the threshold is exceeded. Enforces a 60-second cooldown between alerts. Also handles status reports (battery %, uptime, alert count).

**`SmsReceiver`** — Intercepts incoming SMS, normalizes the sender number against `TRUSTED_NUMBER` (strips country codes, formatting), and dispatches `GHOST ON` / `GHOST OFF` / `GHOST STATUS` commands to `GhostService` via `Intent`.

**`CallReceiver`** — Monitors phone state; auto-answers calls from `TRUSTED_NUMBER` and SMS-notifies of all other incoming callers (with contact name lookup from `READ_CONTACTS`).

**`BootReceiver`** — Restarts `GhostService` on device reboot if the service was previously active (persisted in `SharedPreferences`).

**`MainActivity`** — Control panel UI for local activation/deactivation, permission grants, and requesting battery optimization exemption. Supports stealth mode (excludes app from recents).

### Data Flow

```
SMS "GHOST ON"  →  SmsReceiver  →  validate trusted number  →  start GhostService
                                                              ↘ microphone thread
                                                                → RMS > threshold
                                                                → SMS alert to TRUSTED_NUMBER

Incoming call   →  CallReceiver →  trusted?  → auto-answer (live audio)
                                  untrusted? → SMS caller-ID notification
```

### State Persistence

All runtime state (active flag, alert counters) lives in `SharedPreferences`. `GhostService` reads this on `BootReceiver` restart.

## Key Configuration (`GhostConfig.kt`)

- `TRUSTED_NUMBER` — the only number allowed to issue commands or receive alerts
- `SOUND_THRESHOLD` — RMS amplitude threshold (default `2000`); lower = more sensitive
- `SAMPLE_INTERVAL_MS` — polling interval for the audio thread
- `CMD_ON` / `CMD_OFF` / `CMD_STATUS` — SMS command strings

## Permissions Required

`RECORD_AUDIO`, `RECEIVE_SMS`, `SEND_SMS`, `READ_SMS`, `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`, `MODIFY_AUDIO_SETTINGS`, `READ_CONTACTS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `FOREGROUND_SERVICE_MICROPHONE`, `RECEIVE_BOOT_COMPLETED`.

## Build Notes

- View Binding is enabled — use `ActivityMainBinding` rather than `findViewById`.
- Release builds enable ProGuard (`minifyEnabled true`) and resource shrinking.
- JVM heap is set to 2 GB in `gradle.properties` (`org.gradle.jvmargs=-Xmx2048m`).
- Java 17 source/target compatibility is required.
