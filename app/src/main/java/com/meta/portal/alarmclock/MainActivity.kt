/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.portal.alarmclock

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.meta.portal.alarmclock.ui.theme.NightAmber
import com.meta.portal.alarmclock.ui.theme.NightAmberDim
import com.meta.portal.alarmclock.ui.theme.NightClockTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Bedside display: never let the screen sleep while the clock is showing.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    enableEdgeToEdge()
    // Belt and braces: re-arm whatever the store says should be armed (covers the cases
    // BootReceiver can miss, like a force-stop or a sideload reinstall).
    AlarmScheduler.rescheduleAll(this)
    setContent { NightClockTheme(darkTheme = true) { NightClockApp() } }
  }
}

@Composable
fun NightClockApp() {
  val context = LocalContext.current
  var is24Hour by remember { mutableStateOf(SettingsStore.use24h(context)) }
  var volume by remember { mutableStateOf(SettingsStore.volume(context)) }

  var alarms by remember { mutableStateOf(AlarmStore.load(context)) }
  var snoozes by remember { mutableStateOf(AlarmStore.loadSnoozes(context)) }
  var nightMode by remember { mutableStateOf(false) }
  var showAlarms by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<Alarm?>(null) }
  var showEditor by remember { mutableStateOf(false) }

  // One ticking clock shared by every view.
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(1_000)
    }
  }

  // Rings and snoozes mutate the store from AlarmActivity/AlarmReceiver; pick that up
  // whenever the clock comes back to the foreground (e.g. after Dismiss/Snooze).
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        alarms = AlarmStore.load(context)
        snoozes = AlarmStore.loadSnoozes(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  fun persist(list: List<Alarm>) {
    val sorted = list.sortedWith(compareBy({ it.hour }, { it.minute }))
    alarms = sorted
    AlarmStore.save(context, sorted)
  }

  fun upsert(alarm: Alarm) {
    val list = alarms.filterNot { it.id == alarm.id } + alarm
    persist(list)
    if (alarm.enabled) AlarmScheduler.schedule(context, alarm)
    else AlarmScheduler.cancel(context, alarm.id)
    snoozes = AlarmStore.loadSnoozes(context)
  }

  fun toggle(alarm: Alarm, enabled: Boolean) = upsert(alarm.copy(enabled = enabled))

  fun delete(alarm: Alarm) {
    AlarmScheduler.cancel(context, alarm.id)
    persist(alarms.filterNot { it.id == alarm.id })
    snoozes = AlarmStore.loadSnoozes(context)
  }

  fun cancelSnooze(alarmId: Int) {
    AlarmScheduler.cancelSnooze(context, alarmId)
    snoozes = AlarmStore.loadSnoozes(context)
  }

  val armed = alarms.filter { it.enabled }.sortedBy { it.nextTriggerMillis(now) }
  val activeSnoozes = snoozes.filterValues { it.until > now }.toList().sortedBy { it.second.until }

  if (nightMode) {
    NightFace(
        now = now,
        is24Hour = is24Hour,
        nextAlarm = armed.firstOrNull(),
        snoozeUntil = activeSnoozes.firstOrNull()?.second?.until,
    ) {
      nightMode = false
    }
  } else {
    ClockFace(
        now = now,
        is24Hour = is24Hour,
        armed = armed,
        snoozes = activeSnoozes,
        alarms = alarms,
        onNightMode = { nightMode = true },
        onOpenAlarms = { showAlarms = true },
        onTurnOff = { toggle(it, false) },
        onCancelSnooze = ::cancelSnooze,
    )
  }

  if (showAlarms) {
    AlarmsDialog(
        alarms = alarms,
        is24Hour = is24Hour,
        onUse24h = {
          is24Hour = it
          SettingsStore.setUse24h(context, it)
        },
        volume = volume,
        onVolume = {
          volume = it
          SettingsStore.setVolume(context, it)
        },
        onAdd = {
          editing = null
          showEditor = true
        },
        onEdit = {
          editing = it
          showEditor = true
        },
        onToggle = ::toggle,
        onDelete = ::delete,
        onClose = { showAlarms = false },
    )
  }

  if (showEditor) {
    AlarmEditorDialog(
        existing = editing,
        is24Hour = is24Hour,
        onDismiss = { showEditor = false },
        onSave = { hour, minute, label ->
          val base = editing
          val alarm =
              base?.copy(hour = hour, minute = minute, label = label, enabled = true)
                  ?: Alarm(
                      id = AlarmStore.nextId(alarms),
                      hour = hour,
                      minute = minute,
                      label = label,
                  )
          upsert(alarm)
          showEditor = false
        },
    )
  }
}

/** The whole screen is clock; armed alarms and live snoozes sit in a strip along the bottom. */
@Composable
private fun ClockFace(
    now: Long,
    is24Hour: Boolean,
    armed: List<Alarm>,
    snoozes: List<Pair<Int, Snooze>>,
    alarms: List<Alarm>,
    onNightMode: () -> Unit,
    onOpenAlarms: () -> Unit,
    onTurnOff: (Alarm) -> Unit,
    onCancelSnooze: (Int) -> Unit,
) {
  val timeFmt = remember(is24Hour) { SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm", Locale.getDefault()) }
  val secFmt = remember { SimpleDateFormat("ss", Locale.getDefault()) }
  val ampmFmt = remember { SimpleDateFormat("a", Locale.getDefault()) }
  val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
  val date = Date(now)

  Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = timeFmt.format(date),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 200.sp,
            fontWeight = FontWeight.Bold,
        )
        // Fixed width: the digits aren't monospaced, so without it the whole row re-centers
        // (and the big time shifts) every time the seconds change width.
        Column(modifier = Modifier.padding(start = 12.dp, bottom = 40.dp).width(84.dp)) {
          if (!is24Hour) {
            Text(text = ampmFmt.format(date), color = MaterialTheme.colorScheme.primary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
          }
          Text(text = secFmt.format(date), color = MaterialTheme.colorScheme.primary, fontSize = 48.sp, fontWeight = FontWeight.Medium)
        }
      }
      Text(
          text = dateFmt.format(date),
          color = MaterialTheme.colorScheme.onBackground,
          fontSize = 28.sp,
          fontWeight = FontWeight.Medium,
      )
      // Moon phase changes ~0.4%/hour; once a minute is plenty.
      val moon = remember(now / 60_000) { MoonPhase.at(now) }
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.padding(top = 14.dp),
      ) {
        MoonGraphic(
            fraction = moon.fraction.toFloat(),
            waxing = moon.waxing,
            modifier = Modifier.size(38.dp),
        )
        Text(
            text = "${(moon.fraction * 100).roundToInt()}%",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 18.sp,
        )
      }
    }

    // Settings cog, kept below the 64dp system overlay strip.
    TextButton(
        onClick = onOpenAlarms,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 24.dp).heightIn(min = 52.dp),
    ) {
      Text(text = "⚙", fontSize = 30.sp, color = MaterialTheme.colorScheme.onBackground)
    }

    // Bottom strip: night mode on the left, alarm/snooze status chips on the right.
    Row(
        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onNightMode, modifier = Modifier.heightIn(min = 52.dp)) {
        Text(text = "🌙  " + stringResource(R.string.night_mode), fontSize = 18.sp)
      }
      Spacer(Modifier.width(16.dp))
      Row(
          modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        if (armed.isEmpty() && snoozes.isEmpty()) {
          Text(
              text = stringResource(R.string.no_alarms_hint),
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
              fontSize = 18.sp,
          )
        }
        snoozes.forEach { (id, snooze) ->
          val label = alarms.firstOrNull { it.id == id }?.label?.takeIf { it.isNotBlank() }
          StatusChip(
              text = "💤  " + stringResource(R.string.snoozed_until, formatClock(snooze.until, is24Hour)) +
                  (label?.let { " · $it" } ?: "") +
                  "  ·  ${snooze.count}/${AlarmScheduler.MAX_SNOOZES}",
              onOff = { onCancelSnooze(id) },
          )
        }
        armed.forEach { alarm ->
          val chipLabel = alarm.label.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
          StatusChip(
              text = "⏰  " + formatHm(alarm.hour, alarm.minute, is24Hour) + chipLabel +
                  " · " + stringResource(R.string.alarm_in, formatDelta(alarm.nextTriggerMillis(now) - now)),
              onOff = { onTurnOff(alarm) },
          )
        }
      }
    }
  }
}

/** A pill showing one armed alarm or live snooze, with an inline ✕ to turn it off. */
@Composable
private fun StatusChip(text: String, onOff: () -> Unit) {
  Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 52.dp).padding(start = 20.dp, end = 4.dp),
    ) {
      Text(text = text, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
      TextButton(onClick = onOff, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text = "✕", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
      }
    }
  }
}

/** The alarm list and app settings, presented as a modal sheet from the ⚙ button. */
@Composable
private fun AlarmsDialog(
    alarms: List<Alarm>,
    is24Hour: Boolean,
    onUse24h: (Boolean) -> Unit,
    volume: Float,
    onVolume: (Float) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Alarm) -> Unit,
    onToggle: (Alarm, Boolean) -> Unit,
    onDelete: (Alarm) -> Unit,
    onClose: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
  var previewJob by remember { mutableStateOf<Job?>(null) }
  fun stopPreview() {
    runCatching {
      previewPlayer?.stop()
      previewPlayer?.release()
    }
    previewPlayer = null
  }
  DisposableEffect(Unit) { onDispose { stopPreview() } }

  // A short burst of the actual alarm melody at the chosen level (no fade-in),
  // so loudness can be judged by ear.
  fun previewVolume(level: Float) {
    previewJob?.cancel()
    stopPreview()
    val attrs =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    val session = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
    previewPlayer =
        runCatching { MediaPlayer.create(context, R.raw.ambient1, attrs, session) }.getOrNull()
            ?.apply {
              setVolume(level, level)
              start()
            }
    previewJob =
        scope.launch {
          delay(2_500)
          stopPreview()
        }
  }

  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(
        modifier = Modifier.width(820.dp).height(620.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              text = stringResource(R.string.alarms_title),
              color = MaterialTheme.colorScheme.onSurface,
              fontSize = 28.sp,
              fontWeight = FontWeight.Bold,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAdd, modifier = Modifier.heightIn(min = 52.dp)) {
              Text(text = stringResource(R.string.add_alarm), fontSize = 18.sp)
            }
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 52.dp)) {
              Text(text = stringResource(R.string.close), fontSize = 18.sp)
            }
          }
        }
        Text(
            text = stringResource(R.string.one_shot_note),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.use_24h),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
            )
            Switch(checked = is24Hour, onCheckedChange = onUse24h)
          }
          Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              modifier = Modifier.weight(1f),
          ) {
            Text(
                text = "🔊 " + stringResource(R.string.alarm_volume),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
            )
            Slider(
                value = volume,
                onValueChange = onVolume,
                onValueChangeFinished = { previewVolume(volume) },
                modifier = Modifier.weight(1f),
            )
          }
        }
        Spacer(Modifier.size(16.dp))
        if (alarms.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.no_alarms),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
            )
          }
        } else {
          Column(
              modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            alarms.forEach { alarm ->
              AlarmRow(
                  alarm = alarm,
                  is24Hour = is24Hour,
                  onClick = { onEdit(alarm) },
                  onToggle = { onToggle(alarm, it) },
                  onDelete = { onDelete(alarm) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AlarmRow(
    alarm: Alarm,
    is24Hour: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
  val text = formatHm(alarm.hour, alarm.minute, is24Hour)
  Card(
      modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
        )
        if (alarm.label.isNotBlank()) {
          Text(text = alarm.label, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
        }
      }
      Switch(checked = alarm.enabled, onCheckedChange = onToggle)
      TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 52.dp)) {
        Text(text = stringResource(R.string.delete), color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
      }
    }
  }
}

@Composable
private fun NightFace(
    now: Long,
    is24Hour: Boolean,
    nextAlarm: Alarm?,
    snoozeUntil: Long?,
    onExit: () -> Unit,
) {
  val context = LocalContext.current
  // Dim the panel backlight to a minimum while in night mode; restore on exit.
  DisposableEffect(Unit) {
    val activity = context as? ComponentActivity
    val attrs = activity?.window?.attributes
    val previous = attrs?.screenBrightness
    if (activity != null && attrs != null) {
      attrs.screenBrightness = 0.02f
      activity.window.attributes = attrs
    }
    onDispose {
      if (activity != null && attrs != null && previous != null) {
        attrs.screenBrightness = previous
        activity.window.attributes = attrs
      }
    }
  }

  val timeFmt = remember(is24Hour) { SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm", Locale.getDefault()) }
  Box(
      modifier = Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onExit),
      contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(text = timeFmt.format(Date(now)), color = NightAmber, fontSize = 180.sp, fontWeight = FontWeight.Bold)
      val nextText =
          when {
            snoozeUntil != null -> stringResource(R.string.snoozed_until, formatClock(snoozeUntil, is24Hour))
            nextAlarm != null -> stringResource(R.string.next_alarm, formatHm(nextAlarm.hour, nextAlarm.minute, is24Hour))
            else -> stringResource(R.string.no_next_alarm)
          }
      Text(text = nextText, color = NightAmberDim, fontSize = 24.sp)
      Text(text = stringResource(R.string.exit_night_hint), color = NightAmberDim, fontSize = 16.sp)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditorDialog(
    existing: Alarm?,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onSave: (hour: Int, minute: Int, label: String) -> Unit,
) {
  val timeState =
      rememberTimePickerState(
          initialHour = existing?.hour ?: 7,
          initialMinute = existing?.minute ?: 0,
          is24Hour = is24Hour,
      )
  var label by remember { mutableStateOf(existing?.label ?: "") }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = stringResource(R.string.set_alarm), fontSize = 22.sp, fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          TimeInput(state = timeState)
          OutlinedTextField(
              value = label,
              onValueChange = { label = it },
              label = { Text(stringResource(R.string.label_optional)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )
        }
      },
      confirmButton = {
        Button(
            onClick = { onSave(timeState.hour, timeState.minute, label.trim()) },
            modifier = Modifier.heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(),
        ) {
          Text(text = stringResource(R.string.save), fontSize = 18.sp)
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 52.dp)) {
          Text(text = stringResource(R.string.cancel), fontSize = 18.sp)
        }
      },
  )
}

private fun formatHm(hour: Int, minute: Int, is24Hour: Boolean): String {
  if (is24Hour) return String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
  val h = when (hour % 12) {
    0 -> 12
    else -> hour % 12
  }
  val ampm = if (hour < 12) "AM" else "PM"
  return String.format(Locale.getDefault(), "%d:%02d %s", h, minute, ampm)
}

private fun formatClock(millis: Long, is24Hour: Boolean): String =
    SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(millis))

/** "8h 12m" / "42m" until an alarm, rounded up so it never claims 0. */
private fun formatDelta(ms: Long): String {
  val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(1)
  val h = totalMin / 60
  val m = totalMin % 60
  return if (h > 0) "${h}h ${m}m" else "${m}m"
}
