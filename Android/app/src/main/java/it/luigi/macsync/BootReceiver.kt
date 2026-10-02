package it.luigi.macsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the background sync after a reboot or an app update.
 *
 * Android 15 note: an FGS of type `connectedDevice` may be started from
 * BOOT_COMPLETED (the Android 15 boot restriction applies to dataSync, camera,
 * mediaPlayback, phoneCall, microphone and location — not connectedDevice).
 * We still guard with try/catch and log the real system outcome, and we never
 * attempt to bypass the system. On HyperOS an additional user-granted
 * "Autostart" is required for BOOT_COMPLETED to be delivered at all.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val prefs = context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("auto_start", true)) {
                    Log.d("MacSync", "Avvio automatico disabilitato dall'utente (${intent.action}).")
                    return
                }

                val serviceIntent = Intent(context, MacSyncBleService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("MacSync", "Richiesto avvio FGS dopo ${intent.action}.")
                } catch (e: Exception) {
                    // e.g. ForegroundServiceStartNotAllowedException on some OEMs
                    Log.e("MacSync", "Avvio FGS da ${intent.action} non consentito: ${e.message}")
                }
            }
        }
    }
}
