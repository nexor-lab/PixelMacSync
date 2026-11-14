package it.luigi.macsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import it.luigi.macsync.ble.GattServerManager

/**
 * DEBUG-ONLY call simulator. Lets a developer/automation inject a fake call so the
 * full Mac↔phone call flow can be exercised without a second phone.
 *
 * Usage (from adb):
 *   adb shell am broadcast -a it.luigi.macsync.DEBUG_SIM_CALL \
 *       --es event RINGING --es number 13800001234 --es name "测试来电"
 *   adb shell am broadcast -a it.luigi.macsync.DEBUG_SIM_CALL --es event IDLE
 *
 * Present only in debug builds (source set `src/debug`).
 */
class CallSimReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "it.luigi.macsync.DEBUG_SIM_CALL") return
        val event = intent.getStringExtra("event") ?: "RINGING"
        val number = intent.getStringExtra("number") ?: "13800001234"
        val name = intent.getStringExtra("name") ?: "测试来电"
        Log.d("MacSync", "SIM chiamata: $event")
        GattServerManager.getInstance(context.applicationContext)
            .simulateCallEvent(event, number, name)
    }
}
