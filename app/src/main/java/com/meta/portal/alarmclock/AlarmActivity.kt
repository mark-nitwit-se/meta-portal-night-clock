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
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.portal.alarmclock.ui.theme.NightClockTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The screen shown while an alarm rings: large time, Dismiss / Snooze. The sound itself
 * lives in [AlarmRingService], so this activity being killed (Portal OS does that to
 * full-screen activities after ~15s) doesn't silence the alarm.
 */
class AlarmActivity : ComponentActivity() {

  private val ringStopped =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          // Dismissed/snoozed elsewhere (notification action or auto-snooze).
          backToClock()
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Show over the lock screen and turn the display on (back-compat for the manifest flags).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
              WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
    val label = intent.getStringExtra(EXTRA_LABEL) ?: getString(R.string.alarm_default_label)

    val filter = IntentFilter(AlarmRingService.ACTION_RING_STOPPED)
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(ringStopped, filter, RECEIVER_NOT_EXPORTED)
    } else {
      registerReceiver(ringStopped, filter)
    }

    setContent {
      NightClockTheme(darkTheme = true) {
        RingingScreen(
            label = label,
            is24Hour = SettingsStore.use24h(this),
            onDismiss = {
              command(AlarmRingService.ACTION_DISMISS)
              backToClock()
            },
            onSnooze = {
              command(AlarmRingService.ACTION_SNOOZE)
              backToClock()
            },
        )
      }
    }
  }

  private fun command(action: String) {
    startService(Intent(this, AlarmRingService::class.java).setAction(action))
  }

  /**
   * This activity rings in its own task, so plain finish() would drop to the launcher.
   * A bedside clock should land back on the clock face instead. SINGLE_TOP reuses the
   * live MainActivity, which keeps its composable state — including night mode.
   */
  private fun backToClock() {
    startActivity(
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    finish()
  }

  override fun onDestroy() {
    runCatching { unregisterReceiver(ringStopped) }
    super.onDestroy()
  }

  companion object {
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_LABEL = "label"
  }
}

@Composable
private fun RingingScreen(
    label: String,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(1_000)
    }
  }
  val timeFmt =
      remember(is24Hour) { SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault()) }

  Box(
      modifier = Modifier.fillMaxSize().background(Color.Black),
      contentAlignment = Alignment.Center,
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(32.dp),
    ) {
      Text(
          text = timeFmt.format(Date(now)),
          color = Color(0xFFFF7A1A),
          fontSize = 140.sp,
          fontWeight = FontWeight.Bold,
      )
      Text(text = label, color = Color(0xFFC25A12), fontSize = 28.sp, fontWeight = FontWeight.Medium)
      Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        OutlinedButton(
            onClick = onSnooze,
            modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 200.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
          Text(
              text = androidx.compose.ui.res.stringResource(R.string.snooze),
              fontSize = 20.sp,
              color = Color(0xFFFF7A1A),
          )
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 200.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A3D00)),
        ) {
          Text(
              text = androidx.compose.ui.res.stringResource(R.string.dismiss),
              fontSize = 20.sp,
              color = Color(0xFFF0E0D0),
          )
        }
      }
    }
  }
}
