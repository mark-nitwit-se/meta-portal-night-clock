/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.portal.alarmclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Owns the actual ringing. Portal OS pushes full-screen activities back to the launcher
 * after ~15 seconds, which would silence an activity-owned player — a foreground service
 * keeps the alarm sounding until the user acts.
 *
 * The alarm is the bundled ambient melody (~1:23): it fades in from silence to the set
 * volume over [RAMP_MS], plays once to the end, then auto-snoozes. Each snooze — manual
 * or automatic — draws from the same budget of [AlarmScheduler.MAX_SNOOZES]; when it runs
 * out the alarm simply stays off, so a forgotten alarm can't ring all day.
 */
class AlarmRingService : Service() {

  private var player: MediaPlayer? = null
  private var fallbackTone: Ringtone? = null
  private var vibrator: Vibrator? = null
  private val handler = Handler(Looper.getMainLooper())
  private var alarmId = -1
  private var targetVolume = 1f
  private var rampStart = 0L

  private val rampTick =
      object : Runnable {
        override fun run() {
          val t = (System.currentTimeMillis() - rampStart) / RAMP_MS.toFloat()
          val v = targetVolume * t.coerceIn(0f, 1f)
          runCatching { player?.setVolume(v, v) }
          if (t < 1f) handler.postDelayed(this, 250)
        }
      }

  private val fallbackAutoSnooze = Runnable { autoSnoozeOrOff() }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_DISMISS -> {
        if (alarmId != -1) AlarmScheduler.cancelSnooze(this, alarmId)
        stopRing()
      }
      ACTION_SNOOZE -> {
        if (alarmId != -1) AlarmScheduler.snooze(this, alarmId, SNOOZE_MINUTES)
        stopRing()
      }
      else -> {
        alarmId = intent?.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1) ?: -1
        val label =
            intent?.getStringExtra(EXTRA_LABEL) ?: getString(R.string.alarm_default_label)
        startForeground(NOTIF_ID, buildNotification(label))
        startRinging()
      }
    }
    return START_NOT_STICKY
  }

  /** The melody ended (or the fallback timer fired) with nobody answering. */
  private fun autoSnoozeOrOff() {
    if (alarmId != -1) AlarmScheduler.snooze(this, alarmId, SNOOZE_MINUTES)
    stopRing()
  }

  private fun stopRing() {
    handler.removeCallbacks(rampTick)
    handler.removeCallbacks(fallbackAutoSnooze)
    stopRinging()
    // Tell an open AlarmActivity to leave; harmless if none is showing.
    sendBroadcast(Intent(ACTION_RING_STOPPED).setPackage(packageName))
    // And bring the clock face back ourselves — after an auto-snooze the ringing UI is
    // usually gone already (Portal OS reaps it mid-melody), leaving the launcher on top.
    runCatching {
      startActivity(
          Intent(this, MainActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    stopForeground(true)
    stopSelf()
  }

  private fun buildNotification(label: String): Notification {
    ensureChannel(this)

    val fullScreen =
        Intent(this, AlarmActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
          putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)
          putExtra(AlarmActivity.EXTRA_LABEL, label)
        }
    val fullScreenPi =
        PendingIntent.getActivity(
            this, alarmId, fullScreen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun action(act: String, title: String, requestOffset: Int): Notification.Action {
      val pi =
          PendingIntent.getService(
              this,
              requestOffset + alarmId,
              Intent(this, AlarmRingService::class.java).setAction(act),
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
      return Notification.Action.Builder(null, title, pi).build()
    }

    return Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(label)
        .setCategory(Notification.CATEGORY_ALARM)
        .setOngoing(true)
        .setContentIntent(fullScreenPi)
        .setFullScreenIntent(fullScreenPi, true)
        .addAction(action(ACTION_SNOOZE, getString(R.string.snooze), 200_000))
        .addAction(action(ACTION_DISMISS, getString(R.string.dismiss), 300_000))
        .build()
  }

  private fun startRinging() {
    if (player != null || fallbackTone != null) return
    targetVolume = SettingsStore.volume(this)

    val attrs =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    val session = getSystemService(AudioManager::class.java).generateAudioSessionId()
    player =
        runCatching { MediaPlayer.create(this, R.raw.ambient1, attrs, session) }.getOrNull()

    val p = player
    if (p != null) {
      p.setOnCompletionListener { autoSnoozeOrOff() }
      p.setOnErrorListener { _, _, _ ->
        autoSnoozeOrOff()
        true
      }
      p.setVolume(0f, 0f)
      p.start()
      rampStart = System.currentTimeMillis()
      handler.post(rampTick)
    } else {
      // The bundled melody failed to load — an alarm that stays silent is the one
      // unacceptable failure, so fall back to the system tone on a timer.
      startFallbackTone()
      handler.postDelayed(fallbackAutoSnooze, FALLBACK_RING_MS)
    }

    @Suppress("DEPRECATION")
    vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
    val pattern = longArrayOf(0, 600, 800)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    } else {
      @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
    }
  }

  private fun startFallbackTone() {
    val uri =
        RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    fallbackTone =
        RingtoneManager.getRingtone(this, uri)?.apply {
          audioAttributes =
              AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_ALARM)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                  .build()
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            isLooping = true
            setVolume(targetVolume)
          }
          play()
        }
  }

  private fun stopRinging() {
    runCatching {
      player?.stop()
      player?.release()
    }
    player = null
    runCatching { fallbackTone?.stop() }
    fallbackTone = null
    runCatching { vibrator?.cancel() }
    vibrator = null
  }

  override fun onDestroy() {
    handler.removeCallbacks(rampTick)
    handler.removeCallbacks(fallbackAutoSnooze)
    stopRinging()
    super.onDestroy()
  }

  companion object {
    const val ACTION_DISMISS = "com.meta.portal.alarmclock.action.DISMISS"
    const val ACTION_SNOOZE = "com.meta.portal.alarmclock.action.SNOOZE"
    const val ACTION_RING_STOPPED = "com.meta.portal.alarmclock.action.RING_STOPPED"
    const val EXTRA_LABEL = "label"
    const val SNOOZE_MINUTES = 5
    private const val RAMP_MS = 10_000L
    private const val FALLBACK_RING_MS = 90_000L
    private const val CHANNEL_ID = "alarm_ring"
    private const val NOTIF_ID = 7001

    fun ensureChannel(context: Context) {
      val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      if (nm.getNotificationChannel(CHANNEL_ID) == null) {
        val channel =
            NotificationChannel(CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
              description = "Ringing alarms"
              setBypassDnd(true)
              // The service owns the sound; keep the notification silent.
              setSound(null, null)
              enableVibration(false)
            }
        nm.createNotificationChannel(channel)
      }
    }
  }
}
