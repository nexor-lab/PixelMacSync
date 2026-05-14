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

    // Creiamo un UUID nuovo per la Caratteristica (il canale dove passeranno gli appunti)
    // Ho cambiato solo la prima parte per comodità
    private val CLIPBOARD_CHARACTERISTIC_UUID = UUID.fromString("11111111-73F5-4BC4-A12F-17D1AD07A961")

    // Variabile reattiva per far aggiornare la UI di Compose
    private val _connectionState = MutableStateFlow("In attesa di connessione...")
    val connectionState: StateFlow<String> = _connectionState

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

        // 2. Il Mac prova a scriverci dentro un dato (es. testo copiato)
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

        // Creiamo la caratteristica che permette sia la Lettura che la Scrittura
        val clipboardCharacteristic = BluetoothGattCharacteristic(
            CLIPBOARD_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(clipboardCharacteristic)
        gattServer?.addService(service)
        Log.d("MacSync", "Servizio e Caratteristica configurati nel server.")
    }

    fun stopServer() {
        gattServer?.close()
    }
}