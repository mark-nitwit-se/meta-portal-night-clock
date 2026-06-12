/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.portal.alarmclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * A bedside one-shot alarm: while [enabled] it is armed for the next occurrence of
 * [hour]:[minute] only. Firing switches it off again; re-enable it to arm the next day.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val label: String = "",
) {
  fun toJson(): JSONObject =
      JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("enabled", enabled)
        put("label", label)
      }

  companion object {
    fun fromJson(o: JSONObject): Alarm =
        Alarm(
            id = o.getInt("id"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            enabled = o.optBoolean("enabled", true),
            label = o.optString("label", ""),
        )
  }
}

/** A live snooze: when it rings next, and how many snoozes (manual or auto) it has used. */
data class Snooze(val until: Long, val count: Int)

/** Computes the next epoch-millis this alarm should fire (today if still ahead, else tomorrow). */
fun Alarm.nextTriggerMillis(now: Long = System.currentTimeMillis()): Long {
  val c =
      Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
  if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
  return c.timeInMillis
}

/** SharedPreferences-backed alarm list. Plain JSON — no GMS, works on all Portal devices. */
object AlarmStore {
  private const val PREFS = "night_clock_prefs"
  private const val KEY_ALARMS = "alarms"
  private const val KEY_SNOOZES = "snoozes"

  private fun prefs(context: Context) =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): List<Alarm> {
    val raw = prefs(context).getString(KEY_ALARMS, null) ?: return emptyList()
    return runCatching {
          val arr = JSONArray(raw)
          (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
        }
        .getOrDefault(emptyList())
        .sortedWith(compareBy({ it.hour }, { it.minute }))
  }

  fun save(context: Context, alarms: List<Alarm>) {
    val arr = JSONArray()
    alarms.forEach { arr.put(it.toJson()) }
    prefs(context).edit().putString(KEY_ALARMS, arr.toString()).apply()
  }

  fun nextId(alarms: List<Alarm>): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1

  /** Live snooze records: alarm id → when it rings next and how often it has snoozed. */
  fun loadSnoozes(context: Context): Map<Int, Snooze> {
    val raw = prefs(context).getString(KEY_SNOOZES, null) ?: return emptyMap()
    return runCatching {
          val o = JSONObject(raw)
          o.keys().asSequence().associate { key ->
            val v = o.getJSONObject(key)
            key.toInt() to Snooze(until = v.getLong("until"), count = v.getInt("count"))
          }
        }
        .getOrDefault(emptyMap())
  }

  fun setSnooze(context: Context, alarmId: Int, snooze: Snooze) =
      saveSnoozes(context, loadSnoozes(context) + (alarmId to snooze))

  fun clearSnooze(context: Context, alarmId: Int) =
      saveSnoozes(context, loadSnoozes(context) - alarmId)

  private fun saveSnoozes(context: Context, snoozes: Map<Int, Snooze>) {
    val o = JSONObject()
    snoozes.forEach { (id, s) ->
      o.put(id.toString(), JSONObject().put("until", s.until).put("count", s.count))
    }
    prefs(context).edit().putString(KEY_SNOOZES, o.toString()).apply()
  }

  fun setEnabled(context: Context, alarmId: Int, enabled: Boolean) {
    save(context, load(context).map { if (it.id == alarmId) it.copy(enabled = enabled) else it })
  }
}

/** Arms and cancels alarms through [AlarmManager] using the user-facing setAlarmClock API. */
object AlarmScheduler {
  const val EXTRA_ALARM_ID = "alarm_id"
  const val EXTRA_SNOOZE = "snooze"
  const val MAX_SNOOZES = 10
  private const val SNOOZE_REQUEST_OFFSET = 100_000

  fun rescheduleAll(context: Context) {
    // After a reboot no PendingIntents survive, so only (re)arming is needed. A snoozing
    // alarm is disabled (one-shot fired already) but its snooze record must still re-arm.
    AlarmStore.load(context).forEach { if (it.enabled) schedule(context, it) }
    val now = System.currentTimeMillis()
    AlarmStore.loadSnoozes(context).forEach { (id, s) ->
      if (s.until > now) scheduleSnoozeAt(context, id, s.until) else AlarmStore.clearSnooze(context, id)
    }
  }

  fun schedule(context: Context, alarm: Alarm) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val triggerAt = alarm.nextTriggerMillis()
    val operation = alarmPendingIntent(context, alarm.id, snooze = false)
    // A tap on the status-bar alarm icon opens the app.
    val show =
        PendingIntent.getActivity(
            context,
            alarm.id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
  }

  /** Cancels both the armed occurrence and any pending snooze, and clears the snooze record. */
  fun cancel(context: Context, alarmId: Int) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    am.cancel(alarmPendingIntent(context, alarmId, snooze = false))
    am.cancel(alarmPendingIntent(context, alarmId, snooze = true))
    AlarmStore.clearSnooze(context, alarmId)
  }

  /**
   * One-shot ring [minutes] from now, recorded so the clock face can show and cancel it.
   * Returns false once [MAX_SNOOZES] is used up — the alarm then stays off, so a forgotten
   * alarm can't keep ringing all day.
   */
  fun snooze(context: Context, alarmId: Int, minutes: Int): Boolean {
    val count = (AlarmStore.loadSnoozes(context)[alarmId]?.count ?: 0) + 1
    if (count > MAX_SNOOZES) {
      AlarmStore.clearSnooze(context, alarmId)
      return false
    }
    val triggerAt = System.currentTimeMillis() + minutes * 60_000L
    AlarmStore.setSnooze(context, alarmId, Snooze(until = triggerAt, count = count))
    scheduleSnoozeAt(context, alarmId, triggerAt)
    return true
  }

  fun cancelSnooze(context: Context, alarmId: Int) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    am.cancel(alarmPendingIntent(context, alarmId, snooze = true))
    AlarmStore.clearSnooze(context, alarmId)
  }

  private fun scheduleSnoozeAt(context: Context, alarmId: Int, triggerAt: Long) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val operation = alarmPendingIntent(context, alarmId, snooze = true)
    val show =
        PendingIntent.getActivity(
            context,
            alarmId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
  }

  private fun alarmPendingIntent(context: Context, alarmId: Int, snooze: Boolean): PendingIntent {
    val intent =
        Intent(context, AlarmReceiver::class.java).apply {
          putExtra(EXTRA_ALARM_ID, alarmId)
          putExtra(EXTRA_SNOOZE, snooze)
        }
    val requestCode = if (snooze) SNOOZE_REQUEST_OFFSET + alarmId else alarmId
    return PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
