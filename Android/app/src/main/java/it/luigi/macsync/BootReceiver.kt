package it.luigi.macsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            // Leggiamo la preferenza per l'avvio automatico (di default è ON / true)
            val prefs = context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
            val isAutoStartEnabled = prefs.getBoolean("auto_start", true)

            if (isAutoStartEnabled) {
                Log.d("MacSync", "Avvio automatico consentito. Lancio il servizio BLE in background...")
                val serviceIntent = Intent(context, MacSyncBleService::class.java)
                context.startForegroundService(serviceIntent)
            } else {
                Log.d("MacSync", "Avvio automatico disabilitato dall'utente.")
            }
        }
    }
}