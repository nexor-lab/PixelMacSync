package it.luigi.macsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import it.luigi.macsync.ble.BLEAdvertiser
import it.luigi.macsync.ble.GattServerManager

class MacSyncBleService : Service() {

    private lateinit var gattServerManager: GattServerManager
    private lateinit var bleAdvertiser: BLEAdvertiser

    override fun onCreate() {
        super.onCreate()

        // Creiamo la notifica persistente (obbligatoria per i Foreground Service)
        val channelId = "macsync_bg_channel"
        val channel = NotificationChannel(channelId, "Sincronizzazione Mac", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Mantiene attiva la connessione BLE con il Mac"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MacSync Attivo")
            .setContentText("In ascolto in background...")
            .setSmallIcon(R.mipmap.ic_launcher) // Sostituiremo poi con un'iconina vettoriale pulita
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        // Avviamo i nostri motori BLE
        gattServerManager = GattServerManager.getInstance(this)
        bleAdvertiser = BLEAdvertiser(this)

        gattServerManager.startServer()
        bleAdvertiser.startAdvertising()
    }

    override fun onDestroy() {
        gattServerManager.stopServer()
        bleAdvertiser.stopAdvertising()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}