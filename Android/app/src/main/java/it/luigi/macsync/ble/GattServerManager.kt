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
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class GattServerManager private constructor(private val context: Context) {

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
    private var connectedMac: BluetoothDevice? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null

    private val SERVICE_UUID = UUID.fromString("E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
    private val TELEMETRY_UUID = UUID.fromString("33333333-73F5-4BC4-A12F-17D1AD07A961")
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val NOTIFICATIONS_UUID = UUID.fromString("22222222-73F5-4BC4-A12F-17D1AD07A961")
    private var notificationsCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow("In attesa di connessione...")
    val connectionState: StateFlow<String> = _connectionState

    // --- VARIABILI DI STATO ---
    private var currentBatteryLevel = 0
    private var isCharging = false
    private var currentCellularNetwork = "--"
    private var currentSignal = 0
    private var isWifiConnected = false
    private var wifiSSID = "Wi-Fi"

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // 1. Ascoltatore del Wi-Fi
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            isWifiConnected = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (isWifiConnected) {
                wifiSSID = getWifiName()
            }
            notifyMacTelemetry()
        }

        override fun onLost(network: Network) {
            isWifiConnected = false
            wifiSSID = "Wi-Fi" // O stringa vuota "", a seconda di come la gestisci su Mac
            notifyMacTelemetry()
        }
    }

    // Estrazione del nome della rete Wi-Fi (Richiede GPS Acceso su Android!)
    private fun getWifiName(): String {
        try {
            if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val info = wifiManager.connectionInfo
                // Android restituisce "<unknown ssid>" se il GPS è spento
                if (info != null && info.ssid != null && info.ssid != "<unknown ssid>") {
                    return info.ssid.replace("\"", "")
                }
            }
        } catch (e: Exception) {
            Log.e("MacSync", "Errore estrazione Wi-Fi: ${e.message}")
        }
        return "Wi-Fi"
    }

    // Traduttore dei codici di rete di Android
    private fun getNetworkString(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "E"
            else -> "--"
        }
    }

    // 2. Ascoltatore Segnale Cellulare MIGLIORATO
    private val telephonyCallback = object : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.DataConnectionStateListener {

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            currentSignal = signalStrength.level
            notifyMacTelemetry()
        }

        // Questo scatta per le reti avanzate (es. 5G NSA o 4G+)
        @SuppressLint("MissingPermission")
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            currentCellularNetwork = when (telephonyDisplayInfo.overrideNetworkType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G+"
                else -> getNetworkString(telephonyDisplayInfo.networkType)
            }
            notifyMacTelemetry()
        }

        // Questo scatta ISTANTANEAMENTE appena si attivano i dati mobili
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            if (state == TelephonyManager.DATA_CONNECTED) {
                currentCellularNetwork = getNetworkString(networkType)
                notifyMacTelemetry()
            }
        }
    }

    // 3. Ascoltatore Batteria
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                if (level != -1 && scale != -1) {
                    currentBatteryLevel = (level * 100) / scale
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    notifyMacTelemetry()
                }
            }
        }
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "it.luigi.macsync.NEW_NOTIFICATION") {
                val payload = intent.getStringExtra("payload")
                if (payload != null) sendNotificationToMac(payload)
            }
        }
    }

    // --- INVIO PACCHETTO AL MAC ---
    private fun notifyMacTelemetry() {
        val mac = connectedMac
        val characteristic = telemetryCharacteristic

        if (mac != null && characteristic != null && gattServer != null) {
            val networkStringToUse = if (isWifiConnected) wifiSSID else currentCellularNetwork
            val payload = "$currentBatteryLevel\u001F$isCharging\u001F$networkStringToUse\u001F$currentSignal\u001F$isWifiConnected"
            val data = payload.toByteArray(Charsets.UTF_8)

            gattServer?.notifyCharacteristicChanged(mac, characteristic, false, data)
        }
    }

    fun sendNotificationToMac(payload: String) {
        val mac = connectedMac
        val characteristic = notificationsCharacteristic

        if (mac != null && characteristic != null && gattServer != null) {
            val safePayload = payload.take(150).toByteArray()
            gattServer?.notifyCharacteristicChanged(mac, characteristic, false, safePayload)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedMac = device
                _connectionState.value = "Connesso al Mac! \uD83C\uDF4F"
                // Forziamo un aggiornamento immediato della telemetria appena si connette
                notifyMacTelemetry()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedMac = null
                _connectionState.value = "Disconnesso. In attesa..."
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic.uuid == TELEMETRY_UUID) {
                val networkStringToUse = if (isWifiConnected) wifiSSID else currentCellularNetwork
                val payload = "$currentBatteryLevel\u001F$isCharging\u001F$networkStringToUse\u001F$currentSignal\u001F$isWifiConnected"
                val data = payload.toByteArray(Charsets.UTF_8)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            if (descriptor.uuid == CCC_DESCRIPTOR_UUID && responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    fun startServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        setupService()

        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        context.registerReceiver(notificationReceiver, IntentFilter("it.luigi.macsync.NEW_NOTIFICATION"), Context.RECEIVER_NOT_EXPORTED)
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
            // Inizializza il valore di partenza per evitare il "--" iniziale
            currentCellularNetwork = getNetworkString(telephonyManager.dataNetworkType)
        }
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
        context.unregisterReceiver(batteryReceiver)
        context.unregisterReceiver(notificationReceiver)
        telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        gattServer?.close()
    }
}