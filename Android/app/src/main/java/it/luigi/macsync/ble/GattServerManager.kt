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
import it.luigi.macsync.BuildConfig
import it.luigi.macsync.CallController
import it.luigi.macsync.ContactsRepository
import it.luigi.macsync.Diagnostics
import it.luigi.macsync.Dialer
import it.luigi.macsync.HotspotController
import it.luigi.macsync.MacRegistry
import it.luigi.macsync.MediaSessionMonitor
import it.luigi.macsync.SavedMac
import it.luigi.macsync.unlock.PairingController
import it.luigi.macsync.unlock.TrustedMacStore
import it.luigi.macsync.unlock.UnlockController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class GattServerManager private constructor(private val context: Context) {

    companion object {
        // How long a connected link may stay without completing the handshake
        // before it is dropped (a real Mac sends HELLO right after discovery).
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L

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

    // --- STATO SESSIONE (BUG-001) ---
    // `_connected` is TRUE only after a full application session, not merely a
    // GATT link:
    //   BLE link -> notifications subscribed -> HELLO handshake -> session.
    // Until then the UI shows "verifying", never "connected".
    @Volatile private var handshakeMacId: String? = null
    @Volatile private var notificationsSubscribed = false
    @Volatile private var macCommandSeen = false
    @Volatile private var sessionReadySent = false
    @Volatile private var currentMacId: String? = null
    @Volatile private var contactsSentThisSession = false
    private var handshakeTimeoutJob: Job? = null

    // --- MULTI-MAC (Phase 1) ---
    private val _savedMacs = MutableStateFlow<List<SavedMac>>(emptyList())
    val savedMacs: StateFlow<List<SavedMac>> = _savedMacs
    private val _activeMacId = MutableStateFlow<String?>(null)
    val activeMacId: StateFlow<String?> = _activeMacId
    private val _userDisconnectedMacId = MutableStateFlow<String?>(null)
    val userDisconnectedMacId: StateFlow<String?> = _userDisconnectedMacId
    private val _connectedMacId = MutableStateFlow<String?>(null)
    val connectedMacId: StateFlow<String?> = _connectedMacId

    // Mac model / CPU (from the HELLO handshake) for the About device info.
    private val _macModel = MutableStateFlow("")
    val macModel: StateFlow<String> = _macModel
    private val _macCpu = MutableStateFlow("")
    val macCpu: StateFlow<String> = _macCpu

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

    // --- SIMULAZIONE CHIAMATA (solo build DEBUG, per test senza 2° telefono) ---
    @Volatile private var simulatedCall = false
    @Volatile private var simulatedMuted = false
    private var simulatedNumber: String = ""
    private var simulatedName: String = ""

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

    /** 8th telemetry field: the phone's Bluetooth name (for HFP auto-selection). */
    private fun bluetoothName(): String = try {
        bluetoothManager.adapter?.name ?: android.os.Build.MODEL
    } catch (_: Exception) {
        android.os.Build.MODEL
    }

    private fun telemetryPayload(): String {
        val networkStringToUse = if (isWifiConnected) wifiSSID else currentCellularNetwork
        // 9th field: the phone-side "remote dialing" switch, so the Mac can
        // disable its dial UI when the user has turned it off.
        val remoteDial = if (ContactsRepository.remoteDialEnabled(context)) "1" else "0"
        return "$currentBatteryLevel\u001F$isCharging\u001F$networkStringToUse\u001F$currentSignal" +
            "\u001F$isWifiConnected\u001F$isHotspotActive\u001F${android.os.Build.MODEL}" +
            "\u001F${bluetoothName()}\u001F$remoteDial"
    }

    /** Force a telemetry push now (bypasses the ~1/s throttle), e.g. after a toggle. */
    fun refreshTelemetryNow() {
        lastTelemetrySentMs = 0L
        notifyMacTelemetry()
    }

    @Volatile private var lastTelemetrySentMs = 0L

    private fun notifyMacTelemetry() {
        // Coalesce high-frequency updates (battery/wifi/signal) to ~1/s: a flaky
        // Intel/Broadcom link benefits from fewer notification packets.
        val now = System.currentTimeMillis()
        if (now - lastTelemetrySentMs < 1000) return
        lastTelemetrySentMs = now

        val mac = connectedMac
        val characteristic = telemetryCharacteristic

        if (mac != null && characteristic != null && gattServer != null) {
            val data = telemetryPayload().toByteArray(Charsets.UTF_8)
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
    // --- CONTATTI / RUBRICA (contatti cifrati sul Mac) ---

    /** Pushes the selected contacts automatically on connect when enabled. */
    private fun maybePushContacts() {
        if (contactsSentThisSession) return
        if (!ContactsRepository.autoSync(context)) return
        if (ContactsRepository.selectedIds(context).isEmpty()) return
        contactsSentThisSession = true
        sendSelectedContacts()
    }

    /** Sends the user-selected contacts to the Mac (CONTACT_BEGIN/DATA/END). */
    private fun sendSelectedContacts() {
        callScope.launch {
            val contacts = ContactsRepository.selectedContacts(context)
            Diagnostics.log("contacts send n=${contacts.size}")
            sendNotificationToMac("CONTACT_BEGIN\u001F${contacts.size}")
            for (c in contacts) {
                // Truncate the name to keep the packet within the BLE budget.
                val name = c.name.replace("\u001F", " ").take(48)
                sendNotificationToMac("CONTACT\u001F${c.id}\u001F$name\u001F${c.number}")
            }
            sendNotificationToMac("CONTACT_END")
        }
    }

    /** Remote dial from the Mac. The number is validated; it is never logged. */
    private fun handleDial(parts: List<String>) {
        // The phone-side "远程拨号" switch is authoritative: when off, the Mac
        // cannot place calls, regardless of what it sends.
        if (!ContactsRepository.remoteDialEnabled(context)) {
            Diagnostics.log("dial -> disabled")
            sendNotificationToMac("DIAL_RESULT\u001Fdisabled")
            return
        }
        val number = parts.getOrNull(1).orEmpty()
        // The system never reports outgoing numbers to us, so remember the number
        // we dialed: the ensuing OFFHOOK/IDLE call event then carries it and the
        // Mac does not show "Unknown number".
        Dialer.sanitize(number)?.let { lastKnownNumber = it }
        callScope.launch {
            val status = Dialer.dial(context, number)
            Diagnostics.log("dial -> $status")
            sendNotificationToMac("DIAL_RESULT\u001F$status")
        }
    }

    // --- CONTROLLO HOTSPOT (root/Shizuku) ---
    // Invia lo stato REALE, mai ottimistico.
    private fun sendHotspotState() {
        val state = if (isHotspotActive) "ON" else "OFF"
        sendNotificationToMac("HOTSPOT_STATE\u001F$state")
        Log.d("MacSync", "Stato hotspot inviato: $state")
    }

    /** Idempotent command result: OK / ALREADY_ON / ALREADY_OFF (never an error). */
    private fun sendHotspotResult(result: String) {
        sendNotificationToMac("HOTSPOT_RESULT\u001F$result")
        Log.d("MacSync", "Risultato hotspot: $result")
    }

    /**
     * Refreshes the real hotspot state from the system and reports it. Used for
     * HOTSPOT_STATUS (after reconnect) so a stale cached flag can't mislead.
     */
    private fun refreshHotspotState() {
        callScope.launch {
            isHotspotActive = HotspotController.isEnabled(context)
            sendHotspotState()
        }
    }

    private fun handleHotspotCommand(enable: Boolean) {
        callScope.launch {
            Log.d("MacSync", "Comando hotspot: ${if (enable) "ENABLE" else "DISABLE"}")
            val outcome = if (enable) HotspotController.enable(context) else HotspotController.disable(context)
            Diagnostics.log("hotspot ${if (enable) "ON" else "OFF"} -> $outcome")
            when (outcome) {
                HotspotController.Outcome.OK -> {
                    isHotspotActive = enable
                    notifyMacTelemetry()
                    sendHotspotState()
                    sendHotspotResult("OK")
                }
                HotspotController.Outcome.ALREADY_ON -> {
                    isHotspotActive = true
                    sendHotspotState()
                    sendHotspotResult("ALREADY_ON")
                }
                HotspotController.Outcome.ALREADY_OFF -> {
                    isHotspotActive = false
                    sendHotspotState()
                    sendHotspotResult("ALREADY_OFF")
                }
                HotspotController.Outcome.FAILED -> {
                    sendNotificationToMac(
                        "HOTSPOT_ERROR\u001F${if (enable) "enable_failed" else "disable_failed"}")
                }
            }
        }
    }

    private fun sendCallEvent(event: String, number: String, name: String) {
        val payload = "CALL\u001F$event\u001F$number\u001F$name"
        Log.d("MacSync", "Invio evento chiamata: $event ($number / $name)")
        sendNotificationToMac(payload)
    }

    /**
     * Call control command from the Mac. In a DEBUG build with a simulated call
     * active (see [simulateCallEvent]) it advances the simulated call instead of
     * touching real telephony; otherwise it runs the privileged `input keyevent`.
     */
    private fun handleCallCommand(action: String) {
        callScope.launch {
            Log.d("MacSync", "Comando chiamata: $action")
            if (BuildConfig.DEBUG && simulatedCall) {
                when (action) {
                    "answer" -> sendCallEvent("OFFHOOK", simulatedNumber, simulatedName)
                    "end" -> {
                        sendCallEvent("IDLE", simulatedNumber, simulatedName)
                        simulatedCall = false
                    }
                    "mute" -> {
                        simulatedMuted = !simulatedMuted
                        sendCallMuteState()
                    }
                }
                sendCallResult(action, "ok")
                return@launch
            }
            val ok = when (action) {
                "answer" -> CallController.answer(context)
                "end" -> CallController.end(context)
                "mute" -> CallController.toggleMute(context)
                else -> false
            }
            Diagnostics.log("call $action -> ${if (ok) "ok" else "failed"}")
            sendCallResult(action, if (ok) "ok" else "failed")
        }
    }

    private fun sendCallResult(action: String, status: String) {
        sendNotificationToMac("CALL_RESULT\u001F$action\u001F$status")
    }

    private fun sendCallMuteState() {
        sendNotificationToMac("CALL_MUTE_STATE\u001F${if (simulatedMuted) "ON" else "OFF"}")
    }

    /**
     * DEBUG-ONLY entry point used by `CallSimReceiver` to inject a fake call so the
     * whole Mac↔phone call flow can be tested without a second phone. No-op in
     * release builds.
     */
    fun simulateCallEvent(event: String, number: String, name: String) {
        if (!BuildConfig.DEBUG) return
        val e = event.uppercase()
        when (e) {
            "RINGING" -> {
                simulatedCall = true
                simulatedMuted = false
                simulatedNumber = number
                simulatedName = name
            }
            "IDLE", "MISSED" -> simulatedCall = false
        }
        sendCallEvent(e, number, name)
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

    // --- SESSIONE / HANDSHAKE (BUG-001) ---

    /**
     * Recomputes the application session from the three required conditions:
     *   1. a connected BLE/GATT link,
     *   2. the Mac subscribed to the notifications channel,
     *   3. the Mac completed the HELLO handshake (identity confirmed).
     * `_connected` is published only when all three hold; otherwise the link may
     * still be up but the UI shows "verifying" rather than "connected".
     */
    private fun updateSession() {
        val link = connectedMac != null
        // A real session needs a Mac activity on top of the link + notification
        // subscription: either an explicit HELLO (new macOS builds) OR a recognized
        // application command (legacy v2.2 builds send SYNC_REQ/MUSIC_*/HOTSPOT_*
        // but no HELLO). A bare/stray BLE link alone never qualifies.
        val peer = handshakeMacId ?: if (macCommandSeen) "legacy" else null
        val ready = link && notificationsSubscribed && peer != null
        // Only an *identified* Mac is tracked in the list; a legacy client (no
        // HELLO) can't be told apart and would duplicate the same computer, so it
        // is never recorded as a saved Mac.
        _connectedMacId.value = if (ready) handshakeMacId else null
        Diagnostics.log("session link=$link notif=$notificationsSubscribed peer=$peer ready=$ready")
        if (ready) {
            _connected.value = true
            _connectionState.value = context.getString(it.luigi.macsync.R.string.status_connected)
            if (!sessionReadySent) {
                sessionReadySent = true
                sendNotificationToMac("SESSION_READY\u001F${android.os.Build.MODEL}\u001F$peer")
                Log.d("MacSync", "Sessione stabilita con $peer")
                // Push the current remote-unlock policy to the Mac.
                UnlockController.sendPolicy(context)
            }
            notifyMacTelemetry()
            maybePushContacts()
        } else if (link) {
            _connected.value = false
            _connectionState.value = context.getString(it.luigi.macsync.R.string.status_handshaking)
        } else {
            _connected.value = false
            _connectionState.value = context.getString(it.luigi.macsync.R.string.status_disconnected)
        }
    }

    /** Handles the Mac's `HELLO US <macId> [US <name>]` identity announcement. */
    private fun handleHello(device: BluetoothDevice, parts: List<String>) {
        if (connectedMac != device) return
        val macId = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: MacRegistry.LEGACY_ID
        val macName = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: macId
        MacRegistry.record(context, macId, macName)
        // Mac model / CPU for the (Beta-only) device-info section.
        val macModel = parts.getOrNull(3)?.takeIf { it.isNotBlank() }.orEmpty()
        val macCpu = parts.getOrNull(4)?.takeIf { it.isNotBlank() }.orEmpty()
        if (macModel.isNotEmpty() || macCpu.isNotEmpty()) {
            _macModel.value = macModel
            _macCpu.value = macCpu
            context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE).edit()
                .putString("mac_model", macModel)
                .putString("mac_cpu", macCpu)
                .apply()
        }

        // Multi-Mac: an identified Mac takes over as active if none (or only the
        // legacy client) is active; otherwise only the active Mac is accepted.
        val active = MacRegistry.activeId(context)
        if (active == null || active == MacRegistry.LEGACY_ID) {
            MacRegistry.setActive(context, macId)
            MacRegistry.setUserDisconnected(context, null)
        }
        refreshRegistryFlows()

        val isUserDisconnected = MacRegistry.userDisconnectedId(context) == macId
        if (MacRegistry.activeId(context) != macId || isUserDisconnected) {
            val reason = if (isUserDisconnected) "user_disconnected" else "not_active"
            Diagnostics.log("HELLO rejected ($reason) id=$macId")
            Log.d("MacSync", "HELLO rifiutato ($reason) mac=$macId")
            sendNotificationToMac("SESSION_REJECTED\u001F$reason")
            callScope.launch {
                delay(400)
                if (connectedMac == device) gattServer?.cancelConnection(device)
            }
            return
        }

        currentMacId = macId
        handshakeMacId = macId
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
        Log.d("MacSync", "HELLO ricevuto (mac=$macId / $macName)")
        Diagnostics.log("HELLO peer=$macId name=$macName")
        updateSession()
    }

    // --- MULTI-MAC public API (used by the UI) ---

    private fun refreshRegistryFlows() {
        _savedMacs.value = MacRegistry.saved(context)
        _activeMacId.value = MacRegistry.activeId(context)
        _userDisconnectedMacId.value = MacRegistry.userDisconnectedId(context)
    }

    /** Makes [macId] the active Mac (clears any user-disconnect) and drops another link. */
    fun setActiveMac(macId: String) {
        MacRegistry.setActive(context, macId)
        MacRegistry.setUserDisconnected(context, null)
        refreshRegistryFlows()
        if (currentMacId != null && currentMacId != macId) {
            connectedMac?.let { gattServer?.cancelConnection(it) }
        }
    }

    /** User-initiated disconnect of the active Mac; it will not auto-reconnect. */
    fun disconnectActiveMac() {
        val id = _connectedMacId.value ?: MacRegistry.activeId(context) ?: return
        MacRegistry.setUserDisconnected(context, id)
        refreshRegistryFlows()
        connectedMac?.let { gattServer?.cancelConnection(it) }
    }

    /** Forgets a saved Mac (and disconnects it if linked). */
    fun forgetMac(macId: String) {
        MacRegistry.remove(context, macId)
        refreshRegistryFlows()
        if (currentMacId == macId) {
            connectedMac?.let { gattServer?.cancelConnection(it) }
        }
    }

    /**
     * A link that never completes the handshake (e.g. a random BLE client) must
     * never be presented as "connected". After the timeout we cancel it and let
     * the regular reconnection path retry.
     */
    private fun startHandshakeTimeout(device: BluetoothDevice) {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = callScope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (connectedMac == device && !_connected.value) {
                Log.w("MacSync", "Handshake timeout: nessuna sessione, annullo il link.")
                Diagnostics.log("handshake timeout -> cancel link")
                gattServer?.cancelConnection(device)
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // The BLE/GATT link is up, but no application session exists yet.
                // Reset any previous session state and wait for the handshake.
                connectedMac = device
                currentMacId = null
                handshakeMacId = null
                notificationsSubscribed = false
                macCommandSeen = false
                sessionReadySent = false
                contactsSentThisSession = false
                updateSession()
                startHandshakeTimeout(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handshakeTimeoutJob?.cancel()
                handshakeTimeoutJob = null
                connectedMac = null
                currentMacId = null
                handshakeMacId = null
                notificationsSubscribed = false
                macCommandSeen = false
                sessionReadySent = false
                updateSession()
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic.uuid == TELEMETRY_UUID) {
                val data = telemetryPayload().toByteArray(Charsets.UTF_8)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                // Track whether the Mac subscribed to the notifications channel;
                // a session is only valid once it can actually receive replies.
                if (descriptor.characteristic?.uuid == NOTIFICATIONS_UUID) {
                    notificationsSubscribed =
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    updateSession()
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
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

                // A write on the command channel proves a real Mac peer. HELLO
                // carries its own identity and is dispatched below; any other
                // command marks a legacy peer (no HELLO) for the session gate.
                val parts = fullCommand.split("\u001F")
                val commandType = parts[0]
                // Log only the command type (never payloads: no numbers/bodies).
                Diagnostics.log("cmd $commandType")

                // Legacy macOS clients (no HELLO) still establish a session once
                // they send a command; HELLO is handled by its own branch below.
                if (commandType != "HELLO") {
                    macCommandSeen = true
                    updateSession()
                }

                when (commandType) {
                    // Session handshake (BUG-001): the Mac identifies itself; the
                    // session is confirmed back with SESSION_READY once the link,
                    // notification subscription and identity are all present.
                    "HELLO" -> handleHello(device, parts)
                    // Call control (Mac -> phone). In DEBUG builds with a simulation
                    // running these drive the simulated call instead of telephony.
                    "CALL_ANSWER" -> handleCallCommand("answer")
                    "CALL_END", "CALL_REJECT" -> handleCallCommand("end")
                    "CALL_MUTE" -> handleCallCommand("mute")
                    // Contacts sync (Mac asks; Android replies with selected contacts).
                    "CONTACT_SYNC" -> {
                        contactsSentThisSession = true
                        sendSelectedContacts()
                    }
                    // Remote dial (Mac -> phone).
                    "DIAL" -> handleDial(parts)
                    // Root-controlled real system hotspot (HyperOS/Android 15).
                    "HOTSPOT_ON", "HOTSPOT_ENABLE" -> handleHotspotCommand(true)
                    "HOTSPOT_OFF", "HOTSPOT_DISABLE" -> handleHotspotCommand(false)
                    "HOTSPOT_STATUS" -> refreshHotspotState()
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
                    // --- MAC BIOMETRIC UNLOCK / PAIRING (see UNLOCK_DESIGN.md) ---
                    // PAIR_BEGIN US 1 US <mac_id> US <mac_pub_b64> US <nonce_m_b64>
                    "PAIR_BEGIN" -> PairingController.onBegin(context, parts)
                    "PAIR_DONE" -> PairingController.onDone(context, parts.getOrNull(2) ?: "")
                    "PAIR_CANCEL" -> PairingController.cancel(context)
                    // UNLOCK_REQUEST US 1 US <session_id_b64> US <challenge_b64> US <expires_at_ms> US <mac_sig_b64>
                    "UNLOCK_REQUEST" -> handleUnlockRequest(parts)
                    "UNLOCK_CANCEL" -> UnlockController.cancel()
                    // The Mac revoked this phone (or vice versa).
                    "TRUST_REVOKED" -> parts.getOrNull(1)?.let { TrustedMacStore.setTrusted(context, it, false) }
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

        refreshRegistryFlows()
        val p = context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
        _macModel.value = p.getString("mac_model", "").orEmpty()
        _macCpu.value = p.getString("mac_cpu", "").orEmpty()
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

    private fun handleUnlockRequest(parts: List<String>) {
        val version = parts.getOrNull(1)?.toIntOrNull() ?: return
        val sessionId = parts.getOrNull(2) ?: return
        val challenge = parts.getOrNull(3) ?: return
        val expiresAt = parts.getOrNull(4)?.toLongOrNull() ?: return
        val macSig = parts.getOrNull(5) ?: return
        val macId = currentMacId ?: handshakeMacId ?: MacRegistry.LEGACY_ID
        UnlockController.onUnlockRequest(context, version, sessionId, macId, challenge, expiresAt, macSig)
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
