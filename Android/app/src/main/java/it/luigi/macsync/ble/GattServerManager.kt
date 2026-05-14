package it.luigi.macsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor // FIX: Aggiunta l'importazione mancante per il Descrittore
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
class GattServerManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null

    // Salviamo il riferimento al Mac connesso e alla caratteristica
    private var connectedMac: BluetoothDevice? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null

    private val SERVICE_UUID = UUID.fromString("E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
    private val TELEMETRY_UUID = UUID.fromString("33333333-73F5-4BC4-A12F-17D1AD07A961")
    // Descrittore standard BLE per abilitare le Notifiche (CCCD)
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _connectionState = MutableStateFlow("In attesa di connessione...")
    val connectionState: StateFlow<String> = _connectionState

    // 1. NOVITÀ: Ricevitore che ascolta i cambiamenti della batteria da Android
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

                if (level != -1 && scale != -1) {
                    val batteryPct = (level * 100) / scale
                    notifyMacTelemetry(batteryPct)
                }
            }
        }
    }

    // 2. NOVITÀ: Funzione che "spinge" il nuovo dato al Mac
    private fun notifyMacTelemetry(batteryLevel: Int) {
        val mac = connectedMac
        val characteristic = telemetryCharacteristic

        if (mac != null && characteristic != null && gattServer != null) {
            val data = batteryLevel.toString().toByteArray()
            // Invia la notifica (usando la sintassi moderna per API 33+)
            gattServer?.notifyCharacteristicChanged(mac, characteristic, false, data)
            Log.d("MacSync", "Aggiornamento batteria push inviato: $batteryLevel%")
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedMac = device // Memorizziamo chi si è connesso
                _connectionState.value = "Connesso al Mac! 🍏"
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
                val batteryLevel = getBatteryLevel()
                val data = batteryLevel.toString().toByteArray()
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
            }
        }

        // 4. NOVITÀ: Il Mac scrive nel descrittore per attivare le notifiche
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)

            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                Log.d("MacSync", "Il Mac si è iscritto alle notifiche della batteria! 🚀")
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    fun startServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        setupService()

        // Registriamo il ricevitore per ascoltare i cambi di batteria
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun setupService() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // FIX: Usiamo una costante locale per evitare l'errore di nullabilità su "telemetryCharacteristic?.addDescriptor"
        val localTelemetryCharacteristic = BluetoothGattCharacteristic(
            TELEMETRY_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // Creiamo il "registro delle iscrizioni" e lo attacchiamo alla caratteristica
        val clientConfigDescriptor = BluetoothGattDescriptor(
            CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )

        // Assegniamo il descrittore alla variabile locale, che è sicuramente non nulla
        localTelemetryCharacteristic.addDescriptor(clientConfigDescriptor)

        // Aggiungiamo la caratteristica al servizio
        service.addCharacteristic(localTelemetryCharacteristic)
        gattServer?.addService(service)

        // Infine salviamo il riferimento globale per usarlo in notifyMacTelemetry()
        telemetryCharacteristic = localTelemetryCharacteristic
    }

    fun stopServer() {
        // Pulizia quando spegniamo il server
        context.unregisterReceiver(batteryReceiver)
        gattServer?.close()
    }
}