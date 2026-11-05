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

    // BALANCED (~250 ms) is still discovered by the Mac in well under a second,
    // but uses far less radio/CPU than LOW_LATENCY. HIGH TX power is unnecessary
    // for a phone that is usually within a metre of the Mac.
    private val advertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .setConnectable(true)
        .build()

    // The 8-byte stable device id travels in *service data* inside the main
    // advertisement. The scan response is not requested on every scan window,
    // so relying on it alone made the Mac wait tens of seconds before it could
    // reconnect. The 128-bit service UUID list cannot be added as well: service
    // data (26 B) + UUID list (18 B) + flags (3 B) exceeds the 31-byte legacy
    // limit and makes startAdvertising fail. Manufacturer data stays in the
    // scan response for older Mac builds.
    private val advertiseData: AdvertiseData by lazy {
        AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(serviceUUID), deviceIdBytes)
            .build()
    }

    // Stable 64-bit prefix of the phone device id, so the Mac can reconnect the
    // remembered device even if its BLE address / CoreBluetooth UUID changes.
    private val deviceIdBytes: ByteArray = run {
        val short = TrustedMacStore.deviceId(appContext).take(16)
        ByteArray(short.length / 2) { i ->
            short.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // The scan response keeps the 128-bit service UUID + manufacturer data so
    // older Mac builds (v2.3-beta.3 and earlier) that scan with
    // `withServices: [serviceUUID]` can still discover and identify this phone.
    // Payload: UUID list 18 B + manufacturer 12 B = 30 B (legacy limit 31 B).
    private val scanResponse = AdvertiseData.Builder()
        .addServiceUuid(ParcelUuid(serviceUUID))
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
