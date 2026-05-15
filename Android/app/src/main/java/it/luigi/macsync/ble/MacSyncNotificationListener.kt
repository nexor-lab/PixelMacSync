package it.luigi.macsync.ble

import android.app.Notification
import android.content.Context // <-- FIX: Aggiunta l'importazione del Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MacSyncNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // Filtriamo a prescindere le notifiche di sistema critiche
        if (packageName == "android" || packageName == "com.android.systemui") return

        // NOVITÀ: Controllo White-list nelle SharedPreferences
        val prefs = applicationContext.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)

        // Recuperiamo il set di app abilitate
        val enabledApps = prefs.getStringSet("enabled_apps", setOf())

        // Se l'app non è nella lista delle abilitate, fermiamo l'esecuzione
        if (enabledApps?.contains(packageName) == false) {
            Log.d("MacSync", "Notifica ignorata (non in White-list): $packageName")
            return
        }

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: "Notifica"
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Usiamo il separatore invisibile Unicode
        val separator = "\u001F"
        val payload = "$packageName$separator$title$separator$text"

        Log.d("MacSync", "Intercettata notifica da Android: $payload")

        val intent = Intent("it.luigi.macsync.NEW_NOTIFICATION")
        intent.putExtra("payload", payload)
        intent.setPackage(applicationContext.packageName)
        sendBroadcast(intent)
    }
}