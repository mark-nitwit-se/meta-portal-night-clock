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

/** AlarmManager schedules are cleared on reboot and app update — re-arm everything. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
        intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
      AlarmScheduler.rescheduleAll(context)
    }
  }
}
