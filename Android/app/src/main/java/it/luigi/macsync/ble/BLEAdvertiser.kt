package it.luigi.macsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

class BLEAdvertiser(context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    // LO STESSO IDENTICO UUID DEL MAC
    private val serviceUUID = UUID.fromString("E20A39F4-73F5-4BC4-A12F-17D1AD07A961")

    // Impostiamo la trasmissione (bassa latenza, ed è "connectable" per permettere al Mac di agganciarsi)
    private val advertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .build()

    // Diciamo al mondo "Questo è l'UUID che offro"
    private val advertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false) // Mettiamo false per risparmiare byte preziosi nel pacchetto BLE
        .addServiceUuid(ParcelUuid(serviceUUID))
        .build()

    // Callback per sapere se la trasmissione è partita con successo
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("PixelSync", "Advertising avviato con successo! Il Mac dovrebbe vedermi.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("PixelSync", "Errore nell'avvio dell'advertising: $errorCode")
        }
    }

    @SuppressLint("MissingPermission") // Ignoriamo il warning se hai già gestito i permessi a runtime
    fun startAdvertising() {
        if (advertiser == null) {
            Log.e("PixelSync", "Bluetooth LE non supportato o spento su questo dispositivo.")
            return
        }

        Log.d("PixelSync", "Avvio advertising...")
        advertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
    }
}