package it.luigi.macsync

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MacSyncNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // Filtriamo le notifiche di sistema
        if (packageName == "android" || packageName == "com.android.systemui") return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: "Notifica"
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // NOVITÀ: Usiamo il separatore invisibile Unicode (Unit Separator)
        val separator = "\u001F"
        val payload = "$packageName$separator$title$separator$text"

        Log.d("MacSync", "Intercettata notifica da Android: $payload")

        val intent = Intent("it.luigi.macsync.NEW_NOTIFICATION")
        intent.putExtra("payload", payload)
        intent.setPackage(applicationContext.packageName)
        sendBroadcast(intent)
    }
}