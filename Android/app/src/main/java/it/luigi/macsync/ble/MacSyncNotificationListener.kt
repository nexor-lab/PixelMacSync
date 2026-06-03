package it.luigi.macsync.ble

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MacSyncNotificationListener : NotificationListenerService() {

    companion object {
        private val macNotificationIds = mutableMapOf<String, MutableList<String>>()
    }

    // --- 0. RICEVITORE DEL COMANDO "KILL" DAL MAC ---
    private val killReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "it.luigi.macsync.KILL_NOTIFICATION") {
                val targetMacId = intent.getStringExtra("macNotifId") ?: return
                var targetKey: String? = null
                for ((key, ids) in macNotificationIds) {
                    if (ids.contains(targetMacId)) {
                        targetKey = key
                        break
                    }
                }
                if (targetKey != null) {
                    cancelNotification(targetKey)
                    macNotificationIds[targetKey]?.remove(targetMacId)
                }
            }
        }
    }

    // --- 1. RICEVITORE DELLA RICHIESTA DI SINCRONIZZAZIONE A FREDDO ---
    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "it.luigi.macsync.SYNC_REQUEST") {
                Log.d("MacSync", "Inizio generazione Snapshot delle notifiche...")
                sendSnapshotToMac()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val killFilter = IntentFilter("it.luigi.macsync.KILL_NOTIFICATION")
        registerReceiver(killReceiver, killFilter, Context.RECEIVER_NOT_EXPORTED)

        val syncFilter = IntentFilter("it.luigi.macsync.SYNC_REQUEST")
        registerReceiver(syncReceiver, syncFilter, Context.RECEIVER_NOT_EXPORTED)

        Log.d("MacSync", "MacSyncNotificationListener avviato: Ricevitori armati.")
    }

    override fun onDestroy() {
        unregisterReceiver(killReceiver)
        unregisterReceiver(syncReceiver)
        Log.d("MacSync", "MacSyncNotificationListener terminato: Ricevitori rimossi.")
        super.onDestroy()
    }

    // --- 2. LA FOTOGRAFIA E L'INVIO AL MAC ---
    private fun sendSnapshotToMac() {
        val prefs = applicationContext.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
        val enabledApps = prefs.getStringSet("enabled_apps", setOf()) ?: setOf()
        val currentNotifications = this.activeNotifications ?: return

        for (sbn in currentNotifications) {
            val packageName = sbn.packageName

            if (packageName == "android" || packageName == "com.android.systemui") continue
            if (!enabledApps.contains(packageName)) continue

            // Filtro anti-doppioni
            val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            if (isGroupSummary) continue

            val extras = sbn.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

            if (title.isBlank() && text.isBlank()) continue

            // Rigeneriamo un ID per il Mac e lo salviamo nella mappa
            val uniqueMacId = System.currentTimeMillis().toString(36).takeLast(5) + (10..99).random().toString()
            val key = sbn.key
            val idList = macNotificationIds[key] ?: mutableListOf()
            idList.add(uniqueMacId)
            macNotificationIds[key] = idList

            // Inviamo il pacchetto esattamente come se fosse una notifica normale
            val separator = "\u001F"
            val payload = "POST$separator$uniqueMacId$separator$packageName$separator$title$separator$text"
            sendToGattServer(payload)
        }
        Log.d("MacSync", "Snapshot inviato con successo al Mac.")
    }

    // --- 3. QUANDO ARRIVA UNA NOTIFICA NORMALE ---
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        if (packageName == "android" || packageName == "com.android.systemui") return

        val prefs = applicationContext.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
        val enabledApps = prefs.getStringSet("enabled_apps", setOf())
        if (enabledApps?.contains(packageName) == false) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val uniqueMacId = System.currentTimeMillis().toString(36).takeLast(5) + (10..99).random().toString()
        val key = sbn.key
        val idList = macNotificationIds[key] ?: mutableListOf()
        idList.add(uniqueMacId)
        macNotificationIds[key] = idList

        val separator = "\u001F"
        val payload = "POST$separator$uniqueMacId$separator$packageName$separator$title$separator$text"
        sendToGattServer(payload)
    }

    // --- 4. QUANDO LA NOTIFICA VIENE RIMOSSA DAL TELEFONO ---
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        val packageName = sbn.packageName

        if (packageName == "android" || packageName == "com.android.systemui") return

        val isUserAction = reason == REASON_CLICK || reason == REASON_CANCEL || reason == REASON_CANCEL_ALL
        if (!isUserAction) return

        val key = sbn.key
        val idsToRemove = macNotificationIds.remove(key)

        idsToRemove?.forEach { uniqueMacId ->
            val separator = "\u001F"
            val payload = "REMOVE$separator$uniqueMacId"
            sendToGattServer(payload)
        }
    }

    private fun sendToGattServer(payload: String) {
        val intent = Intent("it.luigi.macsync.NEW_NOTIFICATION")
        intent.putExtra("payload", payload)
        intent.setPackage(applicationContext.packageName)
        sendBroadcast(intent)
    }
}