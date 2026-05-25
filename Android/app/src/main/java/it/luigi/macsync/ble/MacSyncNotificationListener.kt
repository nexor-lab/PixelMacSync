package it.luigi.macsync.ble

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.UUID

class MacSyncNotificationListener : NotificationListenerService() {

    companion object {
        // CORREZIONE: Mappa rinominata per evitare conflitti con i metodi di sistema Android!
        private val macNotificationIds = mutableMapOf<String, MutableList<String>>()
    }

    // --- 1. QUANDO ARRIVA UNA NOTIFICA ---
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // SCUDO ANTI-CONTENITORE
        val isGroupSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) {
            Log.d("MacSync", "Ignorata notifica di riepilogo gruppo: $packageName")
            return
        }

        if (packageName == "android" || packageName == "com.android.systemui") return

        val prefs = applicationContext.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
        val enabledApps = prefs.getStringSet("enabled_apps", setOf())
        if (enabledApps?.contains(packageName) == false) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) return

        // 1. Generiamo un ID totalmente univoco per QUESTO singolo messaggio
        val uniqueMacId = UUID.randomUUID().toString()

        // 2. Salviamo l'ID nella mappa collegandolo alla chiave di sistema
        val key = sbn.key
        val idList = macNotificationIds[key] ?: mutableListOf()
        idList.add(uniqueMacId)
        macNotificationIds[key] = idList

        val separator = "\u001F"
        val payload = "POST$separator$uniqueMacId$separator$packageName$separator$title$separator$text"

        Log.d("MacSync", "Inoltro nuova notifica al Mac: $payload")
        sendToGattServer(payload)
    }

    // --- 2. QUANDO LA NOTIFICA VIENE RIMOSSA ---
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        val packageName = sbn.packageName

        if (packageName == "android" || packageName == "com.android.systemui") return

        // Identifichiamo i motivi derivati da un'azione diretta dell'utente:
        // REASON_CLICK (1) = L'utente ha tappato la notifica
        // REASON_CANCEL (2) = L'utente ha fatto swipe per scartarla
        // REASON_CANCEL_ALL (3) = L'utente ha premuto "Cancella tutto"
        val isUserAction = reason == REASON_CLICK || reason == REASON_CANCEL || reason == REASON_CANCEL_ALL

        // Se l'ha cancellata l'app in background (es. Telegram/Instagram), la ignoriamo!
        if (!isUserAction) {
            Log.d("MacSync", "Rimozione ignorata (Riorganizzazione dell'App). Reason: $reason")
            return
        }

        val key = sbn.key

        // 3. Recuperiamo tutti gli ID univoci associati a questa chat e li rimuoviamo dal Mac
        val idsToRemove = macNotificationIds.remove(key)

        idsToRemove?.forEach { uniqueMacId ->
            val separator = "\u001F"
            val payload = "REMOVE$separator$uniqueMacId"
            Log.d("MacSync", "Inoltro rimozione notifica al Mac (ID: $uniqueMacId)")
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