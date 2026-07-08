/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.portal.alarmclock

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sunrise / sunset for a fixed location via the standard "sunrise equation" — pure astronomy,
 * no dependency, no GMS. Checked against the US Naval Observatory: within ~1 minute across the
 * year, which is plenty to flip night mode a quarter-hour around dusk and dawn.
 *
 * Night mode engages [OFFSET_MIN] minutes after sunset and lifts [OFFSET_MIN] minutes before
 * sunrise. Location is Stockholm; swap [LAT]/[LNG] to move it.
 */
object SunSchedule {
  private const val LAT = 59.3293 // Stockholm, Sweden
  private const val LNG = 18.0686
  const val OFFSET_MIN = 15L

  data class Times(val sunriseMillis: Long, val sunsetMillis: Long)

  /** Is it night now — after sunset+offset, or before sunrise−offset? */
  fun isNight(now: Long): Boolean {
    val t = forDay(now) ?: return false // no rise/set (polar day/night): treat as "not night"
    val off = OFFSET_MIN * 60_000L
    return now > t.sunsetMillis + off || now < t.sunriseMillis - off
  }

  /** Sunrise/sunset (epoch millis) for the local calendar day containing [now]. */
  fun forDay(now: Long): Times? {
    val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = now }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    val day = cal.get(Calendar.DAY_OF_MONTH)

    // Julian day number for the date.
    val a = (14 - month) / 12
    val y = year + 4800 - a
    val m = month + 12 * a - 3
    val jdn = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045

    val n = jdn - 2451545.0 + 0.0008
    val jStar = n - LNG / 360.0 // mean solar noon
    val meanAnomaly = rad((357.5291 + 0.98560028 * jStar).mod(360.0))
    val center =
        1.9148 * sin(meanAnomaly) + 0.0200 * sin(2 * meanAnomaly) + 0.0003 * sin(3 * meanAnomaly)
    val lambda = rad((deg(meanAnomaly) + center + 180.0 + 102.9372).mod(360.0))
    val jTransit =
        2451545.0 + jStar + 0.0053 * sin(meanAnomaly) - 0.0069 * sin(2 * lambda)
    val declination = asin(sin(lambda) * sin(rad(23.44)))

    val cosH =
        (sin(rad(-0.833)) - sin(rad(LAT)) * sin(declination)) / (cos(rad(LAT)) * cos(declination))
    if (cosH > 1 || cosH < -1) return null // sun never rises / never sets this day
    val h = deg(acos(cosH))

    return Times(
        sunriseMillis = julianToMillis(jTransit - h / 360.0),
        sunsetMillis = julianToMillis(jTransit + h / 360.0),
    )
  }

  private fun julianToMillis(jd: Double): Long = ((jd - 2440587.5) * 86_400_000.0).toLong()

  private fun rad(deg: Double) = Math.toRadians(deg)

  private fun deg(rad: Double) = Math.toDegrees(rad)
}
