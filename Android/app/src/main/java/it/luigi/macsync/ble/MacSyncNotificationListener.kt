package it.luigi.macsync.ble

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MacSyncNotificationListener : NotificationListenerService() {

    // --- 1. QUANDO ARRIVA UNA NOTIFICA ---
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // SCUDO ANTI-CONTENITORE: Blocca la "scatola vuota" di Instagram/WhatsApp
        val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) {
            Log.d("MacSync", "Ignorata notifica di riepilogo gruppo: $packageName")
            return
        }

        // Filtriamo notifiche di sistema
        if (packageName == "android" || packageName == "com.android.systemui") return

        // Controllo White-list
        val prefs = applicationContext.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
        val enabledApps = prefs.getStringSet("enabled_apps", setOf())
        if (enabledApps?.contains(packageName) == false) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        // Eliminiamo notifiche totalmente vuote
        if (title.isBlank() && text.isBlank()) return

        // Generiamo un ID numerico corto e univoco per questa notifica
        val notifId = sbn.key.hashCode().toString()
        val separator = "\u001F"

        // Costruiamo il payload con l'azione "POST" (Aggiungi)
        val payload = "POST$separator$notifId$separator$packageName$separator$title$separator$text"

        Log.d("MacSync", "Inoltro nuova notifica al Mac: $payload")
        sendToGattServer(payload)
    }

    // --- 2. QUANDO CANCELLI UNA NOTIFICA (Swipe) ---
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // Filtriamo le notifiche di sistema anche per la rimozione
        if (packageName == "android" || packageName == "com.android.systemui") return

        // Controllo White-list
        val prefs = applicationContext.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
        val enabledApps = prefs.getStringSet("enabled_apps", setOf())
        if (enabledApps?.contains(packageName) == false) return

        // Generiamo lo stesso ID univoco usato per la creazione
        val notifId = sbn.key.hashCode().toString()
        val separator = "\u001F"

        // Costruiamo il payload con l'azione "REMOVE" (Rimuovi)
        val payload = "REMOVE$separator$notifId"

        Log.d("MacSync", "Inoltro rimozione notifica al Mac (ID: $notifId)")
        sendToGattServer(payload)
    }

    // Funzione di utilità per inviare il dato al nostro server BLE
    private fun sendToGattServer(payload: String) {
        val intent = Intent("it.luigi.macsync.NEW_NOTIFICATION")
        intent.putExtra("payload", payload)
        intent.setPackage(applicationContext.packageName)
        sendBroadcast(intent)
    }
}