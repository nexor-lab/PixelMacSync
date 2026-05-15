package it.luigi.macsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class GattServerManager private constructor(private val context: Context) {

    // Singleton pattern
    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: GattServerManager? = null

        fun getInstance(context: Context): GattServerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GattServerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null

    // Riferimenti al Mac e alle caratteristiche
    private var connectedMac: BluetoothDevice? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null

    // UUID
    private val SERVICE_UUID = UUID.fromString("E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
    private val TELEMETRY_UUID = UUID.fromString("33333333-73F5-4BC4-A12F-17D1AD07A961")
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val NOTIFICATIONS_UUID = UUID.fromString("22222222-73F5-4BC4-A12F-17D1AD07A961")
    private var notificationsCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow("In attesa di connessione...")
    val connectionState: StateFlow<String> = _connectionState

    // --- NUOVE VARIABILI DI STATO ---
    private var currentBatteryLevel = 0
    private var isCharging = false
    private var currentNetwork = "5G" // Placeholder
    private var currentSignal = 3     // Placeholder da 0 a 4

    // 1. Ricevitore che ascolta i cambiamenti della batteria e ricarica
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                if (level != -1 && scale != -1) {
                    currentBatteryLevel = (level * 100) / scale
                    // Controlliamo se è in carica
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                    // Spingiamo il pacchetto completo al Mac
                    notifyMacTelemetry()
                }
            }
        }
    }

    // 2. Ricevitore che ascolta le notifiche intercettate dal Listener
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "it.luigi.macsync.NEW_NOTIFICATION") {
                val payload = intent.getStringExtra("payload")
                if (payload != null) {
                    sendNotificationToMac(payload)
                }
            }
        }
    }

    // --- FUNZIONE PER INVIARE LA TELEMETRIA MULTIPLA ---
    private fun notifyMacTelemetry() {
        val mac = connectedMac
        val characteristic = telemetryCharacteristic

        if (mac != null && characteristic != null && gattServer != null) {
            // Assembliamo il payload col separatore invisibile
            val payload = "$currentBatteryLevel\u001F$isCharging\u001F$currentNetwork\u001F$currentSignal"
            val data = payload.toByteArray(Charsets.UTF_8)

            gattServer?.notifyCharacteristicChanged(mac, characteristic, false, data)
            Log.d("MacSync", "Telemetria inviata: $payload")
        }
    }

    // Funzione per inviare la notifica al Mac
    fun sendNotificationToMac(payload: String) {
        val mac = connectedMac
        val characteristic = notificationsCharacteristic

        if (mac != null && characteristic != null && gattServer != null) {
            // Tagliamo la stringa per sicurezza
            val safePayload = payload.take(150).toByteArray()
            gattServer?.notifyCharacteristicChanged(mac, characteristic, false, safePayload)
            Log.d("MacSync", "Notifica inoltrata al Mac: $payload")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedMac = device // Memorizziamo chi si è connesso
                _connectionState.value = "Connesso al Mac! \uD83C\uDF4F"
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedMac = null
                _connectionState.value = "Disconnesso. In attesa..."
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic.uuid == TELEMETRY_UUID) {
                // Se il Mac chiede una lettura diretta, inviamo il nuovo pacchetto multiplo
                val payload = "$currentBatteryLevel\u001F$isCharging\u001F$currentNetwork\u001F$currentSignal"
                val data = payload.toByteArray(Charsets.UTF_8)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)

            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                Log.d("MacSync", "Il Mac si è iscritto alle notifiche di una caratteristica! \uD83D\uDE80")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    fun startServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        setupService()

        // Registriamo i ricevitori
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        context.registerReceiver(
            notificationReceiver,
            IntentFilter("it.luigi.macsync.NEW_NOTIFICATION"),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun setupService() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val localTelemetryCharacteristic = BluetoothGattCharacteristic(
            TELEMETRY_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val charNotifications = BluetoothGattCharacteristic(
            NOTIFICATIONS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val notifDescriptor = BluetoothGattDescriptor(CCC_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        charNotifications.addDescriptor(notifDescriptor)
        service.addCharacteristic(charNotifications)
        notificationsCharacteristic = charNotifications

        val clientConfigDescriptor = BluetoothGattDescriptor(
            CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )

        localTelemetryCharacteristic.addDescriptor(clientConfigDescriptor)
        service.addCharacteristic(localTelemetryCharacteristic)
        gattServer?.addService(service)

        telemetryCharacteristic = localTelemetryCharacteristic
    }

    fun stopServer() {
        // Pulizia totale
        context.unregisterReceiver(batteryReceiver)
        context.unregisterReceiver(notificationReceiver)
        gattServer?.close()
    }
}