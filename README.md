# Night Clock

A bedside alarm clock for the (unlocked) Meta Portal, modeled on the BlackBerry OS 10
bedside mode. Bootstrapped from Meta's [portal-samples](https://github.com/meta-quest/portal-samples)
app, which is also where the MIT license and file headers come from.

## What it does

- **Full-screen clock** — large time and date, optional 24-hour format, screen never sleeps.
- **Night mode** — pure black face with a dim amber clock; drops the panel backlight to
  minimum. Tap anywhere to exit.
- **One-shot alarms** (the BB10 model) — enabling an alarm arms its next occurrence only.
  When it rings and is dismissed, it switches off again; preset times stay in the list to
  re-arm each night. No auto-repeating daily alarms.
- **Status chips** on the clock face show armed alarms (`⏰ 07:00 · in 8h 12m ✕`) and live
  snoozes (`💤 Snoozed until 07:05 · 1/10 ✕`), each dismissible in place.
- **Alarm sound** is the bundled ambient melody (`app/src/main/res/raw/ambient1.mp3`,
  ~1:23): fades in from silence over 10 s, plays once to the end, then auto-snoozes.
- **Snooze budget** — manual and automatic snoozes share a cap of 10, after which the
  alarm switches off, so a forgotten alarm can't ring all day.
- **Settings** behind the ⚙ button: alarm list, 24-hour clock, alarm volume (with an
  audible preview of the real melody).

## Architecture notes

- `AlarmScheduler` uses `AlarmManager.setAlarmClock` (exact, doze-exempt, user-facing).
- `AlarmRingService` is a foreground service that owns playback: Portal OS reaps
  full-screen activities after ~15 s, so sound must not live in the activity.
  `AlarmActivity` is a thin UI over it; the notification carries Snooze/Dismiss actions
  as a fallback. The service returns to the clock face whenever a ring ends.
- `BootReceiver` re-arms alarms and surviving snoozes after reboot and app updates;
  `MainActivity` re-arms on every launch as belt and braces.
- State is plain SharedPreferences + JSON (`AlarmStore`, `SettingsStore`) — no GMS,
  which Portal devices don't have.

## Building

Requires JDK 17 and an Android SDK (AGP auto-provisions missing platform/build-tools).

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.meta.portal.alarmclock/.MainActivity
```

Target device: Portal Mini — SDK 29 (Android 10), 1280×800 landscape, mdpi.
