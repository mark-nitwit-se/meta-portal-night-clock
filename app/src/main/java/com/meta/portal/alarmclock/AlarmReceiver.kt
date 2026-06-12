/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.portal.alarmclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by [AlarmManager] at alarm time. Applies the one-shot bookkeeping, then hands the
 * actual ringing to [AlarmRingService] (whose full-screen-intent notification wakes the
 * screen and launches [AlarmActivity]).
 */
class AlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
    val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)
    if (alarmId == -1) return

    val alarm = AlarmStore.load(context).firstOrNull { it.id == alarmId } ?: return
    // One-shot bedside model: firing consumes the arm, so the alarm goes back to "off"
    // (snoozing keeps it alive via its snooze record until dismissed). A snooze fire
    // therefore rings even though the alarm is disabled by then.
    if (!isSnooze) {
      if (!alarm.enabled) return
      AlarmStore.setEnabled(context, alarmId, false)
      // Fresh fire starts a fresh snooze chain (and budget).
      AlarmStore.clearSnooze(context, alarmId)
    }
    val label = alarm.label.takeIf { it.isNotBlank() } ?: context.getString(R.string.alarm_default_label)

    val ring =
        Intent(context, AlarmRingService::class.java).apply {
          putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
          putExtra(AlarmRingService.EXTRA_LABEL, label)
        }
    context.startForegroundService(ring)

    // Best-effort direct launch of the ringing UI; the full-screen intent is the fallback.
    val fullScreen =
        Intent(context, AlarmActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
          putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
          putExtra(AlarmActivity.EXTRA_LABEL, label)
        }
    runCatching { context.startActivity(fullScreen) }
  }
}
