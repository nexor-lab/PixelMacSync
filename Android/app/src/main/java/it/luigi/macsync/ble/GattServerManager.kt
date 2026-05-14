package it.luigi.macsync.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import it.luigi.macsync.GattConfig
import java.util.UUID

@SuppressLint("MissingPermission") // Gestiremo i permessi a runtime nella UI
class GattServerManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null

    // Callback del Server: intercetta le connessioni e le richieste del Mac
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("MacSync-BLE", "Mac Connesso: ${device?.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("MacSync-BLE", "Mac Disconnesso")
            }
        }

        // Qui riceveremo i comandi di "Scrittura" (es. Toggle Hotspot, Media Cmd)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            Log.d("MacSync-BLE", "Ricevuta scrittura su UUID: ${characteristic?.uuid}")
            // TODO: Aggiungere la logica per smistare i comandi

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    fun startServer() {
        if (adapter?.isEnabled != true) {
            Log.e("MacSync-BLE", "Bluetooth disattivato o non disponibile")
            return
        }

        // 1. Apriamo il Server
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        // --- 2. SERVIZIO SYSTEM ---
        val systemService = BluetoothGattService(GattConfig.SYSTEM_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        systemService.addCharacteristic(createChar(GattConfig.PROTOCOL_VERSION_CHAR, BluetoothGattCharacteristic.PROPERTY_READ))
        systemService.addCharacteristic(createChar(GattConfig.BATTERY_DETAIL_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        systemService.addCharacteristic(createChar(GattConfig.NETWORK_STATE_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        systemService.addCharacteristic(createChar(GattConfig.AUDIO_PROFILE_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        systemService.addCharacteristic(createChar(GattConfig.DND_MODE_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        systemService.addCharacteristic(createChar(GattConfig.HOTSPOT_TOGGLE_CHAR, BluetoothGattCharacteristic.PROPERTY_WRITE, isWritable = true))

        // --- 3. SERVIZIO NOTIFICATIONS ---
        val notifService = BluetoothGattService(GattConfig.NOTIF_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        notifService.addCharacteristic(createChar(GattConfig.ACTIVE_NOTIF_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY))

        // --- 4. SERVIZIO MEDIA ---
        val mediaService = BluetoothGattService(GattConfig.MEDIA_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        mediaService.addCharacteristic(createChar(GattConfig.MEDIA_STATE_CHAR, BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        mediaService.addCharacteristic(createChar(GattConfig.MEDIA_COMMAND_CHAR, BluetoothGattCharacteristic.PROPERTY_WRITE, isWritable = true))

        // Aggiungiamo tutti i servizi al server
        gattServer?.addService(systemService)
        gattServer?.addService(notifService)
        gattServer?.addService(mediaService)

        // 5. Iniziamo l'Advertising
        startAdvertising()
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e("MacSync-BLE", "BLE Advertiser non supportato su questo dispositivo")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) // Ottimizzato per la batteria
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(GattConfig.SYSTEM_SERVICE_UUID))
            .build()

        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d("MacSync-BLE", "Advertising avviato con successo! Il Pixel è visibile.")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e("MacSync-BLE", "Errore avvio Advertising: $errorCode")
            }
        })
    }

    // Helper per creare le caratteristiche con il Descrittore CCCD per le notifiche
    private fun createChar(uuid: UUID, property: Int, isWritable: Boolean = false): BluetoothGattCharacteristic {
        val permission = if (isWritable) BluetoothGattCharacteristic.PERMISSION_WRITE else BluetoothGattCharacteristic.PERMISSION_READ
        return BluetoothGattCharacteristic(uuid, property, permission).apply {
            if (property and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                // Descrittore standard (0x2902) necessario ad Apple per "iscriversi" alle notifiche BLE
                val descriptor = BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                    BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
                )
                addDescriptor(descriptor)
            }
        }
    }
}