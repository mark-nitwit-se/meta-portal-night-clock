/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.portal.alarmclock

import android.content.Context
import android.text.format.DateFormat

/** App settings, stored in the same prefs file as the alarms. */
object SettingsStore {
  private const val PREFS = "night_clock_prefs"
  private const val KEY_USE_24H = "use_24h"
  private const val KEY_VOLUME = "alarm_volume"

  private fun prefs(context: Context) =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Defaults to the device's locale preference until the user chooses explicitly. */
  fun use24h(context: Context): Boolean {
    val p = prefs(context)
    return if (p.contains(KEY_USE_24H)) p.getBoolean(KEY_USE_24H, true)
    else DateFormat.is24HourFormat(context)
  }

  fun setUse24h(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean(KEY_USE_24H, value).apply()
  }

  /** Alarm loudness 0..1, applied to the ringtone player rather than the system volume. */
  fun volume(context: Context): Float = prefs(context).getFloat(KEY_VOLUME, 0.7f)

  fun setVolume(context: Context, value: Float) {
    prefs(context).edit().putFloat(KEY_VOLUME, value).apply()
  }
}
