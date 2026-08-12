package com.jaemin.ddocdocmacro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Prefs.enabled(context)) {
            AlarmScheduler.scheduleNext(context)
        }
    }
}
