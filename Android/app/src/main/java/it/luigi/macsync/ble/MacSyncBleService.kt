package it.luigi.macsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import it.luigi.macsync.ble.BLEAdvertiser
import it.luigi.macsync.ble.GattServerManager

/**
 * Foreground service that owns the BLE GATT server + advertiser.
 *
 * Decoupled from the Activity: its lifetime is independent of the UI. It is a
 * *started* foreground service, so it keeps running when the task is removed
 * from Recents. START_STICKY lets the system recreate it after an OOM kill.
 */
class MacSyncBleService : Service() {

    private lateinit var gattServerManager: GattServerManager
    private lateinit var bleAdvertiser: BLEAdvertiser

    private val channelId = "macsync_bg_channel"

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d("MacSync", "Bluetooth riacceso da Android! Attendo 2 secondi...")
                        Handler(Looper.getMainLooper()).postDelayed({
                            ensureBleRunning()
                        }, 2000)
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d("MacSync", "Bluetooth spento da Android! Fermo i motori BLE...")
                        gattServerManager.stopServer()
                        bleAdvertiser.stopAdvertising()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        createChannel()
        startForegroundCompat()

        gattServerManager = GattServerManager.getInstance(this)
        bleAdvertiser = BLEAdvertiser(this)

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)

        ensureBleRunning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MacSync", "onStartCommand (startId=$startId, sticky)")
        // Re-assert foreground + make sure BLE is up (idempotent).
        startForegroundCompat()
        ensureBleRunning()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped the app from Recents. Do NOT stop the sync service:
        // re-assert the foreground state and keep BLE/notification sync running.
        Log.d("MacSync", "Task rimosso dalle recenti: mantengo attivo il servizio BLE/notifiche.")
        startForegroundCompat()
        ensureBleRunning()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) {}
        gattServerManager.stopServer()
        bleAdvertiser.stopAdvertising()
        Log.d("MacSync", "MacSyncBleService distrutto.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            channelId,
            getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.fgs_channel_desc)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun startForegroundCompat() {
        // The manifest declares a single foregroundServiceType
        // (connectedDevice), so the framework uses it on API 34+.
        startForeground(1, buildNotification())
    }

    private fun ensureBleRunning() {
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) {
            gattServerManager.startServer()
            bleAdvertiser.startAdvertising()
        }
    }
}
