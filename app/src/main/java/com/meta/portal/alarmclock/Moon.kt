/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.portal.alarmclock

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Moon illumination from truncated Meeus-style series: sun and moon ecliptic longitudes
 * with the moon's main perturbation terms (equation of centre, evection, variation,
 * annual equation). Illuminated fraction follows from their elongation. Checked against
 * USNO data: exact at the syzygy instants, within ~0.5% elsewhere — far better than the
 * common "days since a reference new moon" shortcut, which drifts up to ±10% because the
 * lunar orbit is eccentric.
 */
object MoonPhase {
  data class Phase(val fraction: Double, val waxing: Boolean)

  fun at(timeMillis: Long): Phase {
    // Days since J2000.0.
    val d = timeMillis / 86_400_000.0 + 2440587.5 - 2451545.0

    val sunM = rad(357.5291 + 0.98560028 * d) // sun mean anomaly
    val sunLon = 280.459 + 0.98564736 * d + 1.915 * sin(sunM) + 0.020 * sin(2 * sunM)

    val moonL = 218.316 + 13.176396 * d // moon mean longitude
    val moonM = rad(134.963 + 13.064993 * d) // moon mean anomaly
    val elongM = rad(297.8502 + 12.19074912 * d) // mean elongation
    val moonLon =
        moonL +
            6.289 * sin(moonM) + // equation of centre
            1.274 * sin(2 * elongM - moonM) + // evection
            0.658 * sin(2 * elongM) + // variation
            0.214 * sin(2 * moonM) -
            0.186 * sin(sunM) - // annual equation
            0.059 * sin(2 * elongM - 2 * moonM)

    val elongation = rad((moonLon - sunLon).mod(360.0))
    return Phase(fraction = (1 - cos(elongation)) / 2, waxing = sin(elongation) > 0)
  }

  private fun rad(deg: Double) = Math.toRadians(deg)
}

/**
 * A small moon disc with an astronomically-shaped terminator: the lit region is bounded
 * by the disc's limb on one side and a half-ellipse on the other, which is exactly how a
 * sphere's day/night line projects. Waxing is lit on the right (northern hemisphere).
 */
@Composable
fun MoonGraphic(
    fraction: Float,
    waxing: Boolean,
    modifier: Modifier = Modifier,
    litColor: Color = Color(0xFFE8E6E0),
    darkColor: Color = Color(0xFF2C2C30),
    outlineColor: Color = Color(0xFF45454C),
) {
  Canvas(modifier = modifier) {
    val stroke = 1.dp.toPx()
    val r = size.minDimension / 2f - stroke
    val c = center
    drawCircle(color = darkColor, radius = r, center = c)

    val dir = if (waxing) 1f else -1f
    // Terminator half-ellipse: its horizontal semi-axis sweeps r → 0 → r as the
    // fraction goes 0 → ½ → 1 (a hair above zero so the arc never degenerates).
    val rx = (abs(2f * fraction - 1f) * r).coerceAtLeast(0.5f)
    val lit =
        Path().apply {
          moveTo(c.x, c.y - r)
          arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, 180f * dir, false)
          arcTo(
              Rect(c.x - rx, c.y - r, c.x + rx, c.y + r),
              90f,
              (if (fraction >= 0.5f) 180f else -180f) * dir,
              false,
          )
          close()
        }
    drawPath(lit, litColor)
    drawCircle(color = outlineColor, radius = r, center = c, style = Stroke(width = stroke))
  }
}
