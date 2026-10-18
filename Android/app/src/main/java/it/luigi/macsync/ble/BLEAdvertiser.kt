package it.luigi.macsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import it.luigi.macsync.unlock.TrustedMacStore
import java.util.UUID

class BLEAdvertiser(context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // Usiamo il manager moderno per recuperare l'antenna fresca ad ogni chiamata senza warning!
    private val advertiser: BluetoothLeAdvertiser?
        get() = bluetoothManager.adapter?.bluetoothLeAdvertiser

    // Variabile di sicurezza per non avviare due volte l'antenna
    private var isAdvertising = false

    private val serviceUUID = UUID.fromString("58DF214B-9942-45A5-BAF9-7B24F5D0232C")

    private val advertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .build()

    private val advertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .addServiceUuid(ParcelUuid(serviceUUID))
        .build()

    // Stable 64-bit prefix of the phone device id, so the Mac can reconnect the
    // remembered device even if its BLE address / CoreBluetooth UUID changes.
    private val deviceIdBytes: ByteArray = run {
        val short = TrustedMacStore.deviceId(appContext).take(16)
        ByteArray(short.length / 2) { i ->
            short.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private val scanResponse = AdvertiseData.Builder()
        .addManufacturerData(0xFFFF, deviceIdBytes)
        .build()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("PixelSync", "Advertising avviato con successo! Il Mac dovrebbe vedermi.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("PixelSync", "Errore nell'avvio dell'advertising: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (isAdvertising) return // Impedisce i doppi avvii

        val currentAdvertiser = advertiser
        if (currentAdvertiser == null) {
            Log.e("PixelSync", "Bluetooth LE non supportato o spento su questo dispositivo.")
            return
        }

        Log.d("PixelSync", "Avvio advertising...")
        currentAdvertiser.startAdvertising(advertiseSettings, advertiseData, scanResponse, advertiseCallback)
        isAdvertising = true
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!isAdvertising) return // Impedisce crash per spegnimenti di cose già spente
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
    }
}
