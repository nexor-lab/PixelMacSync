package it.luigi.macsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class GattServerManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null

    // Usiamo lo STESSO UUID del servizio usato finora
    private val SERVICE_UUID = UUID.fromString("E20A39F4-73F5-4BC4-A12F-17D1AD07A961")

    // Canale Appunti (Scrittura/Lettura)
    private val CLIPBOARD_CHARACTERISTIC_UUID = UUID.fromString("11111111-73F5-4BC4-A12F-17D1AD07A961")

    // Canale Telemetria (Lettura/Notifica)
    private val TELEMETRY_UUID = UUID.fromString("33333333-73F5-4BC4-A12F-17D1AD07A961")

    // Variabile reattiva per far aggiornare la UI di Compose
    private val _connectionState = MutableStateFlow("In attesa di connessione...")
    val connectionState: StateFlow<String> = _connectionState

    // Funzione di supporto per leggere la batteria in tempo reale dal sistema Android
    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    // Questo è il "centralino" che risponde quando il Mac fa qualcosa
    private val gattServerCallback = object : BluetoothGattServerCallback() {

        // 1. Il Mac si connette o si disconnette
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("MacSync", "Dispositivo connesso: ${device.address}")
                _connectionState.value = "Connesso al Mac! 🍏"
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("MacSync", "Dispositivo disconnesso")
                _connectionState.value = "Disconnesso. In attesa..."
            }
        }

        // 2. Il Mac ci chiede di LEGGERE un dato (es. Telemetria)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)

            // Se il Mac sta chiedendo il canale della Telemetria...
            if (characteristic.uuid == TELEMETRY_UUID) {
                val batteryLevel = getBatteryLevel()
                val data = batteryLevel.toString().toByteArray()

                // Rispondiamo al Mac con il valore
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
                Log.d("MacSync", "Inviato livello batteria al Mac: $batteryLevel%")
            }
        }

        // 3. Il Mac prova a SCRIVERCI dentro un dato (es. testo copiato)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

            if (characteristic.uuid == CLIPBOARD_CHARACTERISTIC_UUID) {
                val testoRicevuto = String(value)
                Log.d("MacSync", "Dal Mac ho ricevuto: $testoRicevuto")

                // Diciamo al Mac "Ok, messaggio ricevuto!"
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    fun startServer() {
        Log.d("MacSync", "Avvio del GATT Server...")
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        setupService()
    }

    private fun setupService() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // 1. Configurazione Caratteristica Appunti
        val clipboardCharacteristic = BluetoothGattCharacteristic(
            CLIPBOARD_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // 2. Configurazione Caratteristica Telemetria
        val telemetryCharacteristic = BluetoothGattCharacteristic(
            TELEMETRY_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // Aggiungiamo entrambe al servizio
        service.addCharacteristic(clipboardCharacteristic)
        service.addCharacteristic(telemetryCharacteristic)

        gattServer?.addService(service)
        Log.d("MacSync", "Servizio e Caratteristiche configurati nel server.")
    }

    fun stopServer() {
        gattServer?.close()
    }
}