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
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import it.luigi.macsync.HotspotController
import it.luigi.macsync.MediaSessionMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    private val SERVICE_UUID = UUID.fromString("58DF214B-9942-45A5-BAF9-7B24F5D0232C")
    private val TELEMETRY_UUID = UUID.fromString("CEFB6548-6C8A-4D25-A086-C8A69D3F6625")
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val NOTIFICATIONS_UUID = UUID.fromString("6E6C9609-9FFA-42E2-A882-B0C4398D58DE")
    private val COMMAND_UUID = UUID.fromString("586B06E6-CCC5-44B8-BFD9-5D2514A67842")
    private var notificationsCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(context.getString(it.luigi.macsync.R.string.status_waiting))
    val connectionState: StateFlow<String> = _connectionState

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    // --- VARIABILI DI STATO ---
    private var currentBatteryLevel = 0
    private var isCharging = false
    private var currentCellularNetwork = "--"
    private var currentSignal = 0
    private var isWifiConnected = false
    private var wifiSSID = "Wi-Fi"
    @Volatile private var isHotspotActive = false

    // --- STATO CHIAMATE ---
    private var wasRinging = false
    private var lastKnownNumber: String = ""
    private val callScope = CoroutineScope(Dispatchers.IO)

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager


    // 1. Ascoltatore del Wi-Fi (Versione Caching Ottimizzata + Fix SSID)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            isWifiConnected = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

            if (isWifiConnected) {
                try {
                    // Usiamo il WifiManager interno all'evento per bypassare i blocchi del callback
                    if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        val info = wifiManager.connectionInfo
                        val rawSsid = info?.ssid

                        wifiSSID = if (rawSsid != null && rawSsid != "<unknown ssid>") {
                            rawSsid.removeSurrounding("\"")
                        } else {
                            "Wi-Fi"
                        }
                    } else {
                        wifiSSID = "Wi-Fi"
                    }
                } catch (e: Exception) {
                    Log.e("MacSync", "Errore estrazione Wi-Fi: ${e.message}")
                    wifiSSID = "Wi-Fi"
                }
            }
            notifyMacTelemetry()
        }

        override fun onLost(network: Network) {
            isWifiConnected = false
            wifiSSID = "Wi-Fi" // Resettiamo la cache
            notifyMacTelemetry()
        }
    }
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

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            if (state == TelephonyManager.DATA_CONNECTED) {
                currentCellularNetwork = getNetworkString(networkType)
                notifyMacTelemetry()
            }
        }
    }

    // 2b. Ascoltatore dello stato delle chiamate (telefono in entrata/uscita)
    private val callStateCallback = object : TelephonyCallback(),
        TelephonyCallback.CallStateListener {

        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    wasRinging = true
                    // Piccola attesa per far arrivare il numero dal broadcast PHONE_STATE.
                    callScope.launch {
                        delay(200)
                        val name = resolveContactName(lastKnownNumber)
                        sendCallEvent("RINGING", lastKnownNumber, name)
                    }
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    wasRinging = false
                    sendCallEvent("OFFHOOK", lastKnownNumber, "")
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (wasRinging) {
                        sendCallEvent("MISSED", lastKnownNumber, "")
                        wasRinging = false
                    } else {
                        sendCallEvent("IDLE", lastKnownNumber, "")
                    }
                    lastKnownNumber = ""
                }
            }
        }
    }

    // 2c. Broadcast di sistema: ci fornisce il numero in arrivo (best effort).
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (state == TelephonyManager.EXTRA_STATE_RINGING && !number.isNullOrBlank()) {
                    lastKnownNumber = number
                }
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

    // 4. Ascoltatore Hotspot (Tethering)
    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.net.wifi.WIFI_AP_STATE_CHANGED") {
                val state = intent.getIntExtra("wifi_state", 11)
                if (state == 12 || state == 13) {
                    if (!isHotspotActive) {
                        isHotspotActive = true
                        notifyMacTelemetry()
                    }
                } else if (state == 10 || state == 11) {
                    if (isHotspotActive) {
                        isHotspotActive = false
                        notifyMacTelemetry()
                    }
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

    private fun notifyMacTelemetry() {
        val mac = connectedMac
        val characteristic = telemetryCharacteristic

        if (mac != null && characteristic != null && gattServer != null) {
            val networkStringToUse = if (isWifiConnected) wifiSSID else currentCellularNetwork
            val payload = "$currentBatteryLevel\u001F$isCharging\u001F$networkStringToUse\u001F$currentSignal\u001F$isWifiConnected\u001F$isHotspotActive\u001F${android.os.Build.MODEL}"
            val data = payload.toByteArray(Charsets.UTF_8)
            gattServer?.notifyCharacteristicChanged(mac, characteristic, false, data)
        }
    }

    fun sendNotificationToMac(payload: String) {
        val mac = connectedMac
        val characteristic = notificationsCharacteristic

        if (mac != null && characteristic != null && gattServer != null) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            // Il valore viene troncato per restare entro l'MTU (max ~180 byte).
            val safePayload = if (bytes.size > 180) bytes.copyOf(180) else bytes
            gattServer?.notifyCharacteristicChanged(mac, characteristic, false, safePayload)
        }
    }

    /**
     * Evento chiamata verso il Mac.
     * Formato: CALL\u001F<event>\u001F<number>\u001F<name>
     * event ∈ { RINGING, OFFHOOK, IDLE, MISSED }
     */
    // --- CONTROLLO HOTSPOT (root) ---
    // Invia lo stato REALE (dal broadcast WIFI_AP_STATE_CHANGED), mai ottimistico.
    private fun sendHotspotState() {
        val state = if (isHotspotActive) "ON" else "OFF"
        sendNotificationToMac("HOTSPOT_STATE\u001F$state")
        Log.d("MacSync", "Stato hotspot inviato: $state")
    }

    private fun handleHotspotCommand(enable: Boolean) {
        callScope.launch {
            Log.d("MacSync", "Comando hotspot: ${if (enable) "ENABLE" else "DISABLE"}")
            val ok = if (enable) HotspotController.enable(context) else HotspotController.disable(context)
            if (!ok) {
                sendNotificationToMac("HOTSPOT_ERROR\u001F${if (enable) "enable_failed" else "disable_failed"}")
                return@launch
            }
            // Attende che lo stato reale del sistema si allinei.
            var matched = false
            for (i in 0 until 14) {
                if (isHotspotActive == enable) { matched = true; break }
                delay(500)
            }
            if (matched) sendHotspotState()
            else sendNotificationToMac("HOTSPOT_ERROR\u001Fstate_mismatch")
        }
    }

    private fun sendCallEvent(event: String, number: String, name: String) {
        val payload = "CALL\u001F$event\u001F$number\u001F$name"
        Log.d("MacSync", "Invio evento chiamata: $event ($number / $name)")
        sendNotificationToMac(payload)
    }

    private fun resolveContactName(number: String): String {
        if (number.isBlank()) return ""
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ""
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
            } ?: ""
        } catch (e: Exception) {
            Log.e("MacSync", "Errore risoluzione contatto: ${e.message}")
            ""
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedMac = device
                _connected.value = true
                _connectionState.value = context.getString(it.luigi.macsync.R.string.status_connected)
                notifyMacTelemetry()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedMac = null
                _connected.value = false
                _connectionState.value = context.getString(it.luigi.macsync.R.string.status_disconnected)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic.uuid == TELEMETRY_UUID) {
                val networkStringToUse = if (isWifiConnected) wifiSSID else currentCellularNetwork
                val payload = "$currentBatteryLevel\u001F$isCharging\u001F$networkStringToUse\u001F$currentSignal\u001F$isWifiConnected\u001F$isHotspotActive\u001F${android.os.Build.MODEL}"
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

        // --- ⚙️ GESTIONE COMANDI IN INGRESSO DAL MAC ---
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

            if (characteristic.uuid == COMMAND_UUID && value != null) {
                val fullCommand = String(value, Charsets.UTF_8)
                Log.d("MacSync", "Ricevuto pacchetto comandi dal Mac: $fullCommand")

                val parts = fullCommand.split("\u001F")
                val commandType = parts[0]

                when (commandType) {
                    // Root-controlled real system hotspot (HyperOS/Android 15).
                    "HOTSPOT_ON", "HOTSPOT_ENABLE" -> handleHotspotCommand(true)
                    "HOTSPOT_OFF", "HOTSPOT_DISABLE" -> handleHotspotCommand(false)
                    "HOTSPOT_STATUS" -> sendHotspotState()
                    "KILL" -> {
                        if (parts.size >= 2) {
                            val notifIdToKill = parts[1]
                            Log.d("MacSync", "Comando KILL ricevuto. Chiedo l'eliminazione per ID: $notifIdToKill")
                            val intent = Intent("it.luigi.macsync.KILL_NOTIFICATION")
                            intent.putExtra("macNotifId", notifIdToKill)
                            intent.setPackage(context.applicationContext.packageName)
                            context.sendBroadcast(intent)
                        }
                    }
                    // --- NUOVO COMANDO: RICHIESTA DI SINCRONIZZAZIONE A FREDDO ---
                    "SYNC_REQ" -> {
                        Log.d("MacSync", "Il Mac ha richiesto la sincronizzazione a freddo!")
                        val intent = Intent("it.luigi.macsync.SYNC_REQUEST")
                        intent.setPackage(context.applicationContext.packageName)
                        context.sendBroadcast(intent)
                    }
                    // --- CONTROLLO MUSICA (MediaSession) ---
                    "MUSIC_PLAY" -> MediaSessionMonitor.play()
                    "MUSIC_PAUSE" -> MediaSessionMonitor.pause()
                    "MUSIC_NEXT" -> MediaSessionMonitor.next()
                    "MUSIC_PREV" -> MediaSessionMonitor.previous()
                    "MUSIC_SEEK" -> {
                        val ms = parts.getOrNull(1)?.toLongOrNull()
                        if (ms != null) MediaSessionMonitor.seekTo(ms)
                    }
                    "MUSIC_STATUS" -> MediaSessionMonitor.requestState()
                    "MUSIC_VOLUME_SET" -> {
                        val percent = parts.getOrNull(1)?.toIntOrNull()
                        if (percent != null) MediaSessionMonitor.setVolume(percent)
                    }
                    // --- RISPOSTA INLINE A UNA NOTIFICA (Mac -> telefono) ---
                    // Formato: REPLY US <macNotifId> US <base64(testo)>
                    "REPLY" -> {
                        if (parts.size >= 3) {
                            val macNotifId = parts[1]
                            val text = try {
                                String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8)
                            } catch (e: Exception) {
                                Log.e("MacSync", "REPLY: base64 non valido: ${e.message}")
                                null
                            }
                            if (!text.isNullOrEmpty()) {
                                val intent = Intent("it.luigi.macsync.REPLY_NOTIFICATION")
                                intent.putExtra("macNotifId", macNotifId)
                                intent.putExtra("replyText", text)
                                intent.setPackage(context.applicationContext.packageName)
                                context.sendBroadcast(intent)
                            }
                        }
                    }
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
        }
    }

    private var isServerRunning = false

    fun startServer() {
        if (isServerRunning) return
        isServerRunning = true

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        setupService()

        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        context.registerReceiver(notificationReceiver, IntentFilter("it.luigi.macsync.NEW_NOTIFICATION"), Context.RECEIVER_NOT_EXPORTED)
        context.registerReceiver(hotspotReceiver, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))

        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
            currentCellularNetwork = getNetworkString(telephonyManager.dataNetworkType)

            // Stato chiamate + numero in arrivo (Android 15/16 → API 35+).
            try {
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callStateCallback)
            } catch (e: Exception) {
                Log.e("MacSync", "Impossibile registrare CallStateListener: ${e.message}")
            }
            try {
                context.registerReceiver(
                    phoneStateReceiver,
                    IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                    Context.RECEIVER_EXPORTED
                )
            } catch (e: Exception) {
                Log.e("MacSync", "Impossibile registrare phoneStateReceiver: ${e.message}")
            }
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

        val commandCharacteristic = BluetoothGattCharacteristic(
            COMMAND_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(commandCharacteristic)

        localTelemetryCharacteristic.addDescriptor(clientConfigDescriptor)
        service.addCharacteristic(localTelemetryCharacteristic)
        gattServer?.addService(service)

        telemetryCharacteristic = localTelemetryCharacteristic
    }

    fun stopServer() {
        if (!isServerRunning) return
        isServerRunning = false

        context.unregisterReceiver(batteryReceiver)
        context.unregisterReceiver(notificationReceiver)
        context.unregisterReceiver(hotspotReceiver)

        telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        try { telephonyManager.unregisterTelephonyCallback(callStateCallback) } catch (_: Exception) {}
        try { context.unregisterReceiver(phoneStateReceiver) } catch (_: Exception) {}
        connectivityManager.unregisterNetworkCallback(networkCallback)
        gattServer?.close()
    }
}
