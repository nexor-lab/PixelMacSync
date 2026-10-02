package it.luigi.macsync.ble

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import it.luigi.macsync.AppIconSender

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
        it.luigi.macsync.NotificationFilter.ensureInitialized(applicationContext)
        val killFilter = IntentFilter("it.luigi.macsync.KILL_NOTIFICATION")
        registerReceiver(killReceiver, killFilter, Context.RECEIVER_NOT_EXPORTED)

        val syncFilter = IntentFilter("it.luigi.macsync.SYNC_REQUEST")
        registerReceiver(syncReceiver, syncFilter, Context.RECEIVER_NOT_EXPORTED)

        startMediaMonitor()
        Log.d("MacSync", "MacSyncNotificationListener avviato: Ricevitori armati.")
    }

    override fun onDestroy() {
        unregisterReceiver(killReceiver)
        unregisterReceiver(syncReceiver)
        it.luigi.macsync.MediaSessionMonitor.stop()
        Log.d("MacSync", "MacSyncNotificationListener terminato: Ricevitori rimossi.")
        super.onDestroy()
    }

    private fun startMediaMonitor() {
        // The listener identity is what authorises MediaSessionManager access.
        val component = ComponentName(this, MacSyncNotificationListener::class.java)
        it.luigi.macsync.MediaSessionMonitor.start(applicationContext, component)
    }

    // --- CICLO DI VITA DELL'ASCOLTO (Android 8+) ---
    // Alcuni OEM (HyperOS/MIUI) possono lasciare il listener "enabled ma non
    // live": in quel caso il sistema non consegna le notifiche. onListenerConnected
    // ci conferma che siamo realmente collegati; su disconnessione chiediamo un
    // rebind esplicito (API 24+).
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MacSync", "NotificationListener CONNESSO (live).")
        it.luigi.macsync.NotificationFilter.ensureInitialized(applicationContext)
        startMediaMonitor()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d("MacSync", "NotificationListener DISCONNESSO: richiedo il rebind...")
        // Media access is granted through the listener; drop it while offline.
        it.luigi.macsync.MediaSessionMonitor.stop()
        try {
            requestRebind(ComponentName(this, MacSyncNotificationListener::class.java))
        } catch (e: Exception) {
            Log.e("MacSync", "requestRebind non riuscito: ${e.message}")
        }
    }

    // --- 2. LA FOTOGRAFIA E L'INVIO AL MAC ---
    private fun sendSnapshotToMac() {
        val enabledApps = it.luigi.macsync.NotificationFilter.enabledApps(applicationContext)
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

            // ID stabile e deterministico: lo stesso key → lo stesso ID sul Mac,
            // così reinvii/snapshot non generano notifiche duplicate.
            val uniqueMacId = stableId(sbn)
            macNotificationIds[sbn.key] = mutableListOf(uniqueMacId)

            // Inviamo il pacchetto esattamente come se fosse una notifica normale
            val separator = "\u001F"
            val appLabel = appLabel(packageName)
            val payload = "POST$separator$uniqueMacId$separator$packageName$separator$title$separator$text$separator$appLabel"
            sendToGattServer(payload)
            AppIconSender.sendIfNeeded(applicationContext, packageName) { sendToGattServer(it) }
        }
        Log.d("MacSync", "Snapshot inviato con successo al Mac.")
    }

    // --- 3. QUANDO ARRIVA UNA NOTIFICA NORMALE ---
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        if (packageName == "android" || packageName == "com.android.systemui") return

        val enabledApps = it.luigi.macsync.NotificationFilter.enabledApps(applicationContext)
        if (!enabledApps.contains(packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val uniqueMacId = stableId(sbn)
        macNotificationIds[sbn.key] = mutableListOf(uniqueMacId)

        val separator = "\u001F"
        val appLabel = appLabel(packageName)
        val payload = "POST$separator$uniqueMacId$separator$packageName$separator$title$separator$text$separator$appLabel"
        sendToGattServer(payload)
        AppIconSender.sendIfNeeded(applicationContext, packageName) { sendToGattServer(it) }
    }

    /** Human-readable app name shown as the macOS notification header. */
    private fun appLabel(packageName: String): String =
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
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

    // ID deterministico basato sul key della notifica: evita duplicati al Mac
    // anche in caso di reinvio o di snapshot dopo una riconnessione.
    private fun stableId(sbn: StatusBarNotification): String =
        "n" + Integer.toHexString(sbn.key.hashCode())

    private fun sendToGattServer(payload: String) {
        val intent = Intent("it.luigi.macsync.NEW_NOTIFICATION")
        intent.putExtra("payload", payload)
        intent.setPackage(applicationContext.packageName)
        sendBroadcast(intent)
    }
}