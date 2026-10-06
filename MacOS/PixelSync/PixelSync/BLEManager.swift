import Foundation
import CoreBluetooth
import Combine
import UserNotifications
import AppKit
import Darwin

/// Latest music session state pushed by Android over BLE.
struct MusicState: Equatable {
    var title: String = ""
    var artist: String = ""
    var album: String = ""
    var durationMs: Int = 0
    var positionMs: Int = 0
    var isPlaying: Bool = false
    var stopped: Bool = true
    var coverKey: String = ""
    var coverPath: String? = nil
    var volumePercent: Int = 0
    var updatedAt: Date = Date()

    var hasTrack: Bool { stopped == false }
}

class BLEManager: NSObject, ObservableObject, CBCentralManagerDelegate, UNUserNotificationCenterDelegate {
    var centralManager: CBCentralManager!
    var pixelPeripheral: CBPeripheral?
    
    let serviceUUID = CBUUID(string: "58DF214B-9942-45A5-BAF9-7B24F5D0232C")
    let telemetryUUID = CBUUID(string: "CEFB6548-6C8A-4D25-A086-C8A69D3F6625")
    let notificationsUUID = CBUUID(string: "6E6C9609-9FFA-42E2-A882-B0C4398D58DE")
    let commandUUID = CBUUID(string: "586B06E6-CCC5-44B8-BFD9-5D2514A67842")
    
    var commandCharacteristic: CBCharacteristic?
    
    var connectionAttempts = 0
    /// Exponential reconnect backoff (seconds). On a flaky Intel/Broadcom radio,
    /// hammering connect/scan makes it worse; back off instead.
    private var reconnectBackoff: Double = 0
    /// Cooldown so the heavy CBCentralManager re-creation can't happen too often.
    private var lastForceRestart = Date.distantPast
    
    // --- DIZIONARIO DINAMICO DELLE APP ---
    var appMap: [String: String] = [:]

    // Buffer di riassemblaggio icone (i callback CB arrivano sulla main queue).
    private var iconBuffers: [String: [Int: String]] = [:]
    
    // 🚀 Variabile per il nome del dispositivo Android
    @Published var deviceName: String = "Telefono"
    /// The phone's Bluetooth name (from telemetry), used to auto-select its
    /// paired Classic device for "Mac 本机" calling.
    @Published var phoneBtName: String = ""

    // Stable identity for THIS Mac, sent in `HELLO` so Android can confirm the
    // application session (BUG-001). Persisted so reconnects keep the same id.
    let macId: String
    let macName: String
    let macModel: String
    let macCpu: String

    /// Phone biometric unlock (pairing + challenge/response). See UNLOCK_DESIGN.md.
    let unlockManager: UnlockManager
    let lockMonitor = LockStateMonitor()

    /// "Mac 本机" calling: the Mac as a Bluetooth hands-free unit (HFP).
    let handsFree: HandsFreeCall
    private var hfCancellables = Set<AnyCancellable>()

    /// True after the phone rejected this Mac (Multi-Mac: not active / user
    /// disconnected). While set, automatic rescanning is suppressed.
    private var rejectedByPhone = false

    // --- Mac-side interference auto-reconnect (ADR-028) ---------------------
    // On the Intel/Broadcom baseline, the Bluetooth-Classic link used by HFP can
    // knock out the BLE link. Detect it (classic paired/active + BLE down), tell
    // the user, and auto re-handshake a few times — then ask to unpair.
    static let maxAutoReconnectAttempts = 3
    private var autoReconnectAttempts = 0
    private var reconnectEpisodeActive = false
    private var awaitingRecovery = false
    private var lastReconnectAt = Date.distantPast
    private var autoReconnectWorkItem: DispatchWorkItem?
    /// Suppresses disconnect handling while we deliberately re-create the engine.
    private var engineRestarting = false

    /// True when the phone appears in the Bluetooth-Classic paired list, which is
    /// the HFP prerequisite and the known vector for BLE interference (ADR-028).
    var phoneClassicPaired: Bool {
        let paired = HandsFreeCall.pairedPhoneNames()
        guard !paired.isEmpty else { return false }
        // We can only claim "the phone is paired" when we can identify it by name.
        // Otherwise (telemetry not arrived yet) stay conservative: other Classic
        // devices (headsets, etc.) must not trigger the interference path.
        let key = phoneBtName.isEmpty ? handsFree.targetName : phoneBtName
        guard !key.isEmpty else { return false }
        return paired.contains {
            $0.localizedCaseInsensitiveContains(key) || key.localizedCaseInsensitiveContains($0)
        }
    }

    @Published var isSwitchedOn = false
    @Published var connectionState: L10n.State = .disconnected
    var connectionStatus: String { L10n.state(connectionState) }
    var isConnected: Bool { connectionState == .connected }

    // Real hotspot state (from Android), never assumed.
    @Published var hotspotState: HotspotState = .unknown
    var hotspotStateText: String { L10n.hotspotState(hotspotState) }
    var hotspotBusy: Bool { hotspotState == .enabling || hotspotState == .disabling }
    @Published var batteryLevel: String = "--%"
    @Published var isCharging: Bool = false
    @Published var networkType: String = "---"
    @Published var signalStrength: Int = 0
    @Published var isWifi: Bool = false
    @Published var isHotspotActive: Bool = false

    // Latest media session (title/artist/album/progress/cover) from Android.
    @Published var music = MusicState()

    // Call control state (Android -> Mac; Mac -> Android commands).
    enum CallPhase: Equatable { case none, ringing, active }
    @Published var callPhase: CallPhase = .none
    @Published var callMuted = false
    /// Number (or contact name) of the current call, for the popover.
    @Published var callNumber = ""
    /// True while an *outgoing* call is being placed (shows "Calling…").
    @Published var callIsOutgoing = false

    /// Last number we dialed, so an outgoing call can show it (the phone only
    /// reports incoming numbers to us).
    private var lastDialedNumber = ""

    // Remote dialing + synced (encrypted) contacts.
    enum CallMethod: String { case phone, macBluetooth }
    @Published var callMethod: CallMethod = .phone
    /// Phone-side "远程拨号" switch (from telemetry); when off, dialing is disabled.
    @Published var remoteDialEnabled: Bool = true
    @Published var contactCount: Int = 0
    private var contactBuffer: [MacContact] = []

    // Phone biometric unlock UI state.
    @Published var trustedPhones: [TrustedPhone] = []
    @Published var unlockPairingActive: Bool = false
    @Published var unlockPluginInstalled: Bool = false

    // Cover art reassembly buffer (CB callbacks arrive on the main queue).
    private var artBuffers: [String: [Int: String]] = [:]

    // macOS 12.7 refuses UNUserNotificationCenter for apps not signed with an
    // Apple-issued certificate (UNErrorDomain Code=1). When that happens we
    // deliver to Notification Center via /usr/bin/osascript instead.
    private(set) var notificationsAuthorized = false

    // Calcolo del percorso del file JSON nella cartella Documenti
    var configURL: URL? {
        let fileManager = FileManager.default
        guard let documentsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let folderURL = documentsURL.appendingPathComponent("MacSync", isDirectory: true)
        
        // Se la cartella MacSync in Documenti non esiste, la creiamo
        if !fileManager.fileExists(atPath: folderURL.path) {
            try? fileManager.createDirectory(at: folderURL, withIntermediateDirectories: true, attributes: nil)
        }
        return folderURL.appendingPathComponent("app_mappings.json")
    }

    /// Stable per-machine id derived from the host UUID, hashed to a short hex
    /// string (the raw hardware UUID is never transmitted). Because it is
    /// deterministic, reinstalling or clearing preferences does NOT create a new
    /// Mac identity — the same computer always yields the same id.
    private static func hardwareMacId() -> String? {
        var uuid = [UInt8](repeating: 0, count: 16)
        var timeout = timespec(tv_sec: 1, tv_nsec: 0)
        let rc = uuid.withUnsafeMutableBytes { raw -> Int32 in
            guard let base = raw.baseAddress else { return -1 }
            return gethostuuid(base.assumingMemoryBound(to: UInt8.self), &timeout)
        }
        guard rc == 0 else { return nil }
        let hex = uuid.map { String(format: "%02x", $0) }.joined()
        return "Mac-" + String(hex.prefix(12)).uppercased()
    }

    /// Reads a sysctl string value (e.g. "hw.model", "machdep.cpu.brand_string").
    static func sysctlString(_ name: String) -> String {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return "" }
        var buf = [CChar](repeating: 0, count: size)
        guard sysctlbyname(name, &buf, &size, nil, 0) == 0 else { return "" }
        return String(cString: buf)
    }

    /// Returns the stable Mac identifier, persisting it for convenience. Falls
    /// back to a persisted random id only if the host UUID is unavailable.
    private static func loadOrCreateMacId() -> String {
        let key = "pixelsync.macId"
        let defaults = UserDefaults.standard
        if let hw = hardwareMacId() {
            if defaults.string(forKey: key) != hw { defaults.set(hw, forKey: key) }
            return hw
        }
        if let existing = defaults.string(forKey: key), !existing.isEmpty { return existing }
        let host = Host.current().localizedName ?? "Mac"
        let id = "\(host)-\(UUID().uuidString.prefix(8))"
        defaults.set(id, forKey: key)
        return id
    }

    override init() {
        self.macId = BLEManager.loadOrCreateMacId()
        self.macName = Host.current().localizedName ?? "Mac"
        self.macModel = BLEManager.sysctlString("hw.model")
        self.macCpu = BLEManager.sysctlString("machdep.cpu.brand_string")
        self.unlockManager = UnlockManager(
            macId: self.macId, macName: self.macName, supportDir: BLEManager.supportDir())
        self.handsFree = HandsFreeCall(
            targetName: UserDefaults.standard.string(forKey: "hfPhoneName") ?? "")
        super.init()
        if let raw = UserDefaults.standard.string(forKey: "callMethod"),
           let m = CallMethod(rawValue: raw) { callMethod = m }
        contactCount = ContactsStore.shared.contacts.count
        // Only bring up the HFP unit when "Mac 本机" is the selected method.
        if callMethod == .macBluetooth, !handsFree.targetName.isEmpty { handsFree.start() }
        // Re-publish when the hands-free unit changes, so the popover updates.
        handsFree.objectWillChange
            .sink { [weak self] in self?.objectWillChange.send() }
            .store(in: &hfCancellables)
        // HFP dropping while BLE is down is another interference signal (ADR-028).
        handsFree.$connected
            .dropFirst()
            .sink { [weak self] connected in
                guard let self = self, !connected, !self.isConnected else { return }
                self.beginInterferenceReconnect(reason: "hfp-disconnected")
            }
            .store(in: &hfCancellables)
        centralManager = CBCentralManager(delegate: self, queue: nil)
        
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { [weak self] granted, error in
            self?.notificationsAuthorized = granted
            NSLog("MacSync: Permessi notifiche macOS concessi: \(granted) errore: \(String(describing: error))")
        }
        
        UNUserNotificationCenter.current().delegate = self
        registerNotificationCategories()
        
        // Carichiamo la mappa delle app dal file esterno JSON
        loadAppMap()
        
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(macDidSleep), name: NSWorkspace.willSleepNotification, object: nil)
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(macDidWake), name: NSWorkspace.didWakeNotification, object: nil)

        // Phone biometric unlock: send commands over the existing channel and
        // start the flow when the screen locks (password path stays untouched).
        unlockManager.sendCommand = { [weak self] cmd in self?.sendCommand(cmd) }
        lockMonitor.onLocked = { [weak self] in
            guard let self = self else { return }
            // Keep the screen on while the plugin waits for the phone grant.
            self.unlockManager.holdDisplayAwake()
            self.unlockManager.triggerUnlock(reason: "lock")
        }
        lockMonitor.start()

        unlockManager.onPairingStateChanged = { [weak self] active in
            DispatchQueue.main.async { self?.unlockPairingActive = active }
        }
        unlockManager.onPaired = { [weak self] _ in
            self?.unlockPairingActive = false
            self?.refreshTrust()
        }
        unlockManager.onTrustChanged = { [weak self] in self?.refreshTrust() }
        unlockManager.onUnlockGranted = { [weak self] in
            self?.deliverNotification(id: "unlock-ok-\(Date().timeIntervalSince1970)",
                                      title: L10n.unlockGrantedTitle, body: "")
        }
        unlockManager.onUnlockFailed = { [weak self] reason in
            self?.deliverNotification(id: "unlock-fail-\(Date().timeIntervalSince1970)",
                                      title: L10n.unlockFailedTitle,
                                      body: L10n.unlockFailure(reason))
        }
        unlockPluginInstalled = AuthorizationBridge.shared.pluginInstalled
        refreshTrust()
    }

    func refreshTrust() {
        DispatchQueue.main.async {
            self.trustedPhones = self.unlockManager.trustedDevices
        }
    }

    /// Application Support folder shared by the unlock stores (keys + trust list).
    static func supportDir() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support")
        return base.appendingPathComponent("PixelSync", isDirectory: true)
    }

    // --- CARICAMENTO E SALVATAGGIO CONFIGURAZIONE ---
    /// Default map: Android package -> macOS app name. Missing entries are merged
    /// into an existing file. Any legacy `http(s)` value is dropped (webpage
    /// jumping was removed; only app-name mappings are supported now).
    private static let defaultAppMap: [String: String] = [
        "com.instagram.android": "Instagram",
        "com.discord": "Discord",
        "org.telegram.messenger": "Telegram"
    ]

    func loadAppMap() {
        guard let url = configURL else { return }
        
        if FileManager.default.fileExists(atPath: url.path),
           let data = try? Data(contentsOf: url),
           let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            var merged = decoded.filter { !$0.value.hasPrefix("http://") && !$0.value.hasPrefix("https://") }
            var changed = merged.count != decoded.count
            for (key, value) in Self.defaultAppMap where merged[key] == nil {
                merged[key] = value
                changed = true
            }
            self.appMap = merged
            if changed { saveAppMap() }
            NSLog("MacSync: Mappa app caricata correttamente da JSON: \(self.appMap)")
            return
        }
        
        self.appMap = Self.defaultAppMap
        saveAppMap()
    }

    func saveAppMap() {
        guard let url = configURL else { return }
        if let data = try? JSONEncoder().encode(appMap) {
            try? data.write(to: url)
            NSLog("MacSync: Configurazione JSON aggiornata in Documenti/MacSync/")
        }
    }

    @objc func macDidSleep() {
        NSLog("MacSync: Coperchio chiuso o stop display. Sgancio il Bluetooth preventivamente.")
        if let peripheral = pixelPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        centralManager.stopScan()
    }

    @objc func macDidWake() {
        NSLog("MacSync: Sistema sveglio. Riavvio motore Bluetooth pulito.")
        
        DispatchQueue.main.async {
            self.connectionState = .waking
            self.batteryLevel = "--%"
            self.isCharging = false
            self.networkType = "---"
            self.signalStrength = 0
            self.isWifi = false
            self.isHotspotActive = false
            self.hotspotState = .unknown
            self.resetMusic()
        }

        // Diamo 4 secondi al Mac per riattivare i driver hardware
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) {
            self.forceRestartBluetooth()
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            isSwitchedOn = true
            connectionState = .scanning
            engineRestarting = false
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.startScanningOrReconnect()
            }
        } else {
            isSwitchedOn = false
            connectionState = .bluetoothOff
            
            pixelPeripheral?.delegate = nil
            pixelPeripheral = nil
            commandCharacteristic = nil
            connectionAttempts = 0
            
            batteryLevel = "--%"
            isCharging = false
            networkType = "---"
            signalStrength = 0
            isWifi = false
            isHotspotActive = false
            hotspotState = .unknown
            resetMusic()
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        guard self.pixelPeripheral == nil else { return }
        
        centralManager.stopScan()
        self.pixelPeripheral = peripheral
        self.pixelPeripheral?.delegate = self
        connectionState = .connecting
        
        connectionAttempts += 1
        // No aggressive hard reset on a few failures — back off instead (Intel/Broadcom).
        centralManager.connect(peripheral, options: nil)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) { [weak self] in
            guard let self = self else { return }
            if self.pixelPeripheral?.identifier == peripheral.identifier && peripheral.state != .connected {
                NSLog("MacSync: Timeout connessione in scansione! Il Telefono non risponde.")
                self.centralManager.cancelPeripheralConnection(peripheral)
                self.connectionState = .scanning
                
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    if self.centralManager.state == .poweredOn {
                        self.startScanningOrReconnect()
                    }
                }
            }
        }
    }
    
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectionAttempts = 0
        resetBackoff()
        // BUG-001: the GATT link is not an application session yet. Stay in a
        // "verifying" state until Android confirms with SESSION_READY.
        connectionState = .handshaking
        peripheral.discoverServices([serviceUUID])
    }
    
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        NSLog("MacSync: Dispositivo disconnesso. Ripristino stato e riavvio scansione.")
        
        DispatchQueue.main.async {
            if self.rejectedByPhone {
                // Multi-Mac: the phone refused this Mac; do NOT auto-reconnect.
                self.connectionState = .rejected
                self.batteryLevel = "--%"
                self.isCharging = false
                self.networkType = "---"
                self.signalStrength = 0
                self.isWifi = false
                self.isHotspotActive = false
                self.hotspotState = .unknown
                self.resetMusic()
                self.centralManager.stopScan()
                NSLog("MacSync: Mac non attivo sul telefono; nessuna riconnessione automatica.")
                return
            }
            if self.engineRestarting {
                // Intentional engine re-creation: ignore the stale disconnect callback.
                return
            }
            // Phone moved away / link lost: schedule an auto-lock (policy-gated).
            self.unlockManager.handleLinkLost()
            // ADR-028: a drop while Bluetooth-Classic is involved is the known
            // interference pattern -> notify the user + accelerated re-handshake.
            if self.phoneClassicPaired || self.handsFree.connected {
                self.beginInterferenceReconnect(reason: "disconnect+classic")
                return
            }
            self.connectionState = .scanning
            self.batteryLevel = "--%"
            self.isCharging = false
            self.networkType = "---"
            self.signalStrength = 0
            self.isWifi = false
            self.isHotspotActive = false
            self.hotspotState = .unknown
            self.resetMusic()
            
            self.centralManager.stopScan()
            
            DispatchQueue.main.asyncAfter(deadline: .now() + self.nextBackoff()) {
                if self.centralManager.state == .poweredOn {
                    self.startScanningOrReconnect()
                }
            }
        }
    }
    
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        NSLog("MacSync: Connessione fallita dal sistema.")
        // Do NOT tear down/re-create the central here: on this Broadcom radio rapid
        // churn is worse. Just back off and rescan.
        pixelPeripheral?.delegate = nil
        pixelPeripheral = nil
        DispatchQueue.main.async {
            self.connectionState = .scanning
            DispatchQueue.main.asyncAfter(deadline: .now() + self.nextBackoff()) {
                if self.centralManager.state == .poweredOn {
                    self.startScanningOrReconnect()
                }
            }
        }
    }
    
    /// Sends a real hotspot command over BLE; the UI updates only when Android
    /// confirms the actual system state (never optimistically).
    func setRemoteHotspot(enable: Bool) {
        guard isConnected, let peripheral = pixelPeripheral, let characteristic = commandCharacteristic else {
            NSLog("MacSync: Hotspot: dispositivo o canale non pronto.")
            return
        }
        hotspotState = enable ? .enabling : .disabling
        let commandString = enable ? "HOTSPOT_ENABLE" : "HOTSPOT_DISABLE"
        if let data = commandString.data(using: .utf8) {
            peripheral.writeValue(data, for: characteristic, type: .withResponse)
            NSLog("MacSync: Inviato comando -> \(commandString)")
            // Timeout guard: if Android does not confirm, surface an error.
            DispatchQueue.main.asyncAfter(deadline: .now() + 12.0) { [weak self] in
                guard let self = self else { return }
                if self.hotspotState == (enable ? .enabling : .disabling) {
                    self.hotspotState = .error
                    NSLog("MacSync: Hotspot timeout (nessuna conferma da Android).")
                }
            }
        }
    }

    /// Asks Android for the current hotspot state (used after reconnect).
    func queryHotspotState() {
        guard isConnected, let peripheral = pixelPeripheral, let characteristic = commandCharacteristic else { return }
        if let data = "HOTSPOT_STATUS".data(using: .utf8) {
            peripheral.writeValue(data, for: characteristic, type: .withResponse)
        }
    }

    // MARK: - Music control (Mac -> Android)

    /// Sends a raw command to Android if the command channel is ready.
    func sendCommand(_ command: String) {
        guard isConnected, let peripheral = pixelPeripheral, let characteristic = commandCharacteristic else {
            return
        }
        if let data = command.data(using: .utf8) {
            peripheral.writeValue(data, for: characteristic, type: .withResponse)
            NSLog("MacSync: Inviato comando -> \(command)")
        }
    }

    func musicPlay() { sendCommand("MUSIC_PLAY") }
    func musicPause() { sendCommand("MUSIC_PAUSE") }
    func musicToggle() { music.isPlaying ? musicPause() : musicPlay() }
    func musicNext() { sendCommand("MUSIC_NEXT") }
    func musicPrevious() { sendCommand("MUSIC_PREV") }

    /// Seeks and optimistically advances the local progress clock.
    func musicSeek(toMs ms: Int) {
        let clamped = max(0, min(ms, music.durationMs))
        sendCommand("MUSIC_SEEK\u{1F}\(clamped)")
        DispatchQueue.main.async {
            self.music.positionMs = clamped
            self.music.updatedAt = Date()
        }
    }

    /// Asks Android to re-send the current metadata + cover (after reconnect).
    func queryMusicStatus() {
        sendCommand("MUSIC_STATUS")
    }

    /// Sets the phone's media volume (0..100). Android echoes the real value.
    func setMusicVolume(percent: Int) {
        let clamped = max(0, min(100, percent))
        sendCommand("MUSIC_VOLUME_SET\u{1F}\(clamped)")
        DispatchQueue.main.async {
            self.music.volumePercent = clamped
        }
    }

    /// Clears the music panel (used on disconnect / bluetooth reset).
    private func resetMusic() {
        DispatchQueue.main.async {
            self.music = MusicState()
            self.artBuffers.removeAll()
            self.callPhase = .none
            self.callMuted = false
            self.callNumber = ""
            self.callIsOutgoing = false
        }
    }

    // MARK: - Call control (Mac -> Android)

    /// Answers the ringing call on the phone.
    func callAnswer() {
        if callMethod == .macBluetooth { handsFree.answer(); return }
        sendCommand("CALL_ANSWER")
    }
    /// Ends / rejects the call on the phone.
    func callEnd() {
        if callMethod == .macBluetooth { handsFree.end(); return }
        sendCommand("CALL_END")
    }
    /// Toggles microphone mute.
    func callMuteToggle() {
        if callMethod == .macBluetooth {
            callMuted.toggle()
            handsFree.setMuted(callMuted)
            return
        }
        sendCommand("CALL_MUTE")
    }

    // MARK: - Remote dialing + contacts

    /// Remote dial. With "Mac 本机" the call is placed through the Mac's HFP unit
    /// (audio on the Mac); otherwise the phone dials and keeps the audio.
    func dial(_ number: String) {
        let n = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        lastDialedNumber = n
        if callMethod == .macBluetooth { handsFree.dial(n); return }
        sendCommand("DIAL\u{1F}\(n)")
    }

    /// Connects the Mac's hands-free unit to the given paired phone.
    func connectHandsFree(phoneName: String) { handsFree.configure(name: phoneName) }

    /// Paired Bluetooth-Classic device names (for the "Mac 本机" picker).
    var pairedPhones: [String] { HandsFreeCall.pairedPhoneNames() }

    func setCallMethod(_ method: CallMethod) {
        let previous = callMethod
        callMethod = method
        UserDefaults.standard.set(method.rawValue, forKey: "callMethod")
        if method == .macBluetooth {
            handsFree.autoDetect(fromBLE: phoneBtName)
            if !handsFree.targetName.isEmpty { handsFree.start() }
        } else {
            handsFree.stop()
            // Leaving "Mac 本机": the Classic/HFP link may have knocked out BLE
            // (ADR-028) — force a clean re-handshake if the session is down.
            if previous == .macBluetooth && !isConnected {
                beginInterferenceReconnect(reason: "call-method->phone")
            }
        }
    }

    /// Contact suggestions for the dial field (number prefix / name substring).
    func contactSuggestions(_ query: String) -> [MacContact] {
        ContactsStore.shared.search(query)
    }
    
    func startScanningOrReconnect() {
        if let peripheral = pixelPeripheral {
            if peripheral.state == .connected { return }
            if peripheral.state == .connecting { return }
            centralManager.cancelPeripheralConnection(peripheral)
            peripheral.delegate = nil
        }
        
        self.pixelPeripheral = nil
        self.commandCharacteristic = nil
        
        let systemConnected = centralManager.retrieveConnectedPeripherals(withServices: [serviceUUID])
        
        if let peripheral = systemConnected.first {
            self.pixelPeripheral = peripheral
            self.pixelPeripheral?.delegate = self
            connectionState = .cacheConnecting
            connectionAttempts += 1
            centralManager.connect(peripheral, options: nil)
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) { [weak self] in
                guard let self = self else { return }
                if self.pixelPeripheral?.identifier == peripheral.identifier && peripheral.state != .connected {
                    self.centralManager.cancelPeripheralConnection(peripheral)
                    self.connectionState = .scanning
                    DispatchQueue.main.asyncAfter(deadline: .now() + self.nextBackoff()) {
                        if self.centralManager.state == .poweredOn { self.startScanningOrReconnect() }
                    }
                }
            }
            
        } else {
            connectionState = .scanning
            centralManager.stopScan()
            centralManager.scanForPeripherals(withServices: [serviceUUID], options: nil)
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 20.0) { [weak self] in
                guard let self = self else { return }
                if self.connectionState == .scanning && self.pixelPeripheral == nil {
                    // Automatic path → respect the cooldown; don't churn the radio.
                    self.forceRestartBluetooth(force: false)
                }
            }
        }
    }

    // MARK: - Reconnect tuning (per architecture)
    // Apple Silicon radios coexist with Classic + LE and connect quickly, so use
    // fast/aggressive retries. The older Intel/Broadcom baseline needs slower
    // backoff and a long cooldown to avoid link churn.

    private var backoffBase: Double { Platform.isAppleSilicon ? 0.5 : 1 }
    private var backoffMax: Double { Platform.isAppleSilicon ? 8 : 30 }
    private var restartCooldown: Double { Platform.isAppleSilicon ? 10 : 60 }

    private func nextBackoff() -> Double {
        reconnectBackoff = reconnectBackoff == 0 ? backoffBase : min(backoffMax, reconnectBackoff * 2)
        return reconnectBackoff
    }
    private func resetBackoff() { reconnectBackoff = 0 }

    /// Re-creates CBCentralManager. `force=false` is used by the automatic paths
    /// (cooldown reserved); user actions / wake pass `force=true`. `state` lets
    /// the caller show a more specific status while the engine restarts.
    func forceRestartBluetooth(force: Bool = true, state: L10n.State = .restarting) {
        let now = Date()
        if !force && now.timeIntervalSince(lastForceRestart) < restartCooldown { return }
        lastForceRestart = now
        resetBackoff()
        engineRestarting = true

        if let peripheral = pixelPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
            peripheral.delegate = nil
        }
        centralManager.stopScan()
        
        self.pixelPeripheral = nil
        self.commandCharacteristic = nil
        self.connectionAttempts = 0
        self.rejectedByPhone = false
        
        DispatchQueue.main.async {
            self.connectionState = state
            self.batteryLevel = "--%"
            self.isCharging = false
            self.networkType = "---"
            self.signalStrength = 0
            self.isWifi = false
            self.isHotspotActive = false
            self.hotspotState = .unknown
            self.resetMusic()
        }
        
        centralManager.delegate = nil
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    // MARK: - Interference auto-reconnect (Mac-side)

    /// User/URL/manual entry point: force a clean re-handshake immediately.
    /// Resets the attempt budget so a manual action always gets a fresh try.
    func reconnectNow(reason: String) {
        DispatchQueue.main.async {
            self.autoReconnectAttempts = 0
            self.reconnectEpisodeActive = true
            self.awaitingRecovery = false   // manual: no "recovered" banner
            self.cancelAutoReconnectWork()
            self.lastReconnectAt = Date()
            NSLog("MacSync: manual reconnect (\(reason))")
            self.forceRestartBluetooth(force: true, state: .autoReconnecting)
        }
    }

    /// Called when a disconnect happens while Bluetooth-Classic is involved
    /// (paired or HFP active) — the ADR-028 interference pattern.
    func beginInterferenceReconnect(reason: String) {
        DispatchQueue.main.async {
            guard !self.handsFree.callActive && !self.handsFree.scoOpen else {
                NSLog("MacSync: auto-reconnect skipped (call active)")
                return
            }
            if !self.reconnectEpisodeActive {
                self.reconnectEpisodeActive = true
                self.autoReconnectAttempts = 0
                self.awaitingRecovery = true
                self.connectionState = .autoReconnecting
                NSLog("MacSync: possible interference -> auto-reconnect (\(reason))")
                self.postSystemNotification(id: "sys.reconnect",
                                            title: L10n.reconnectTitle,
                                            body: L10n.reconnectBody)
            }
            // Release the Classic SLC (if any) so the radio frees up for BLE.
            if self.handsFree.connected && !self.handsFree.callActive && !self.handsFree.scoOpen {
                self.handsFree.stop()
            }
            self.scheduleAutoReconnect(reason: reason)
        }
    }

    private func scheduleAutoReconnect(reason: String) {
        cancelAutoReconnectWork()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.performAutoReconnect(reason: reason)
        }
        autoReconnectWorkItem = work
        // Small delay: give the Classic link time to release before re-scanning.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
    }

    private func cancelAutoReconnectWork() {
        autoReconnectWorkItem?.cancel()
        autoReconnectWorkItem = nil
    }

    private func performAutoReconnect(reason: String) {
        guard !isConnected else { return }
        cancelAutoReconnectWork()

        if autoReconnectAttempts >= Self.maxAutoReconnectAttempts {
            // Give up and tell the user the likely cause (Classic pairing).
            if connectionState != .needsUnpair {
                connectionState = .needsUnpair
                postSystemNotification(id: "sys.unpair",
                                       title: L10n.reconnectFailedTitle,
                                       body: L10n.reconnectFailedBody)
            }
            reconnectEpisodeActive = false
            awaitingRecovery = false
            NSLog("MacSync: auto-reconnect giving up after \(autoReconnectAttempts) attempts")
            return
        }

        // Debounce the heavy CBCentralManager re-creation (larger on Intel/Broadcom).
        let minInterval: TimeInterval = Platform.isAppleSilicon ? 8 : 15
        guard Date().timeIntervalSince(lastReconnectAt) >= minInterval else {
            // Still within cooldown; try again shortly.
            let wait = minInterval - Date().timeIntervalSince(lastReconnectAt)
            DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
                self?.performAutoReconnect(reason: reason)
            }
            return
        }

        autoReconnectAttempts += 1
        lastReconnectAt = Date()
        NSLog("MacSync: auto-reconnect attempt \(autoReconnectAttempts)/\(Self.maxAutoReconnectAttempts) (\(reason))")
        forceRestartBluetooth(force: true, state: .autoReconnecting)
    }

    /// Called when the application session comes back (`SESSION_READY`).
    private func handleSessionRecovered() {
        if awaitingRecovery {
            awaitingRecovery = false
            reconnectEpisodeActive = false
            autoReconnectAttempts = 0
            cancelAutoReconnectWork()
            NSLog("MacSync: link recovered after interference")
            postSystemNotification(id: "sys.recovered",
                                   title: L10n.recoveredTitle,
                                   body: L10n.recoveredBody)
        }
        autoReconnectAttempts = 0
        reconnectEpisodeActive = false
        // Reconnected: cancel any pending away-lock and, if locked, wake+unlock.
        unlockManager.handleLinkRestored()
    }

    /// Posts a local system notification that does not depend on the BLE link.
    func postSystemNotification(id: String, title: String, body: String) {
        deliverNotification(id: id, title: title, body: body)
    }
}

// MARK: - Gestione Telemetria e Notifiche (Dati in Ingresso)
extension BLEManager: CBPeripheralDelegate {
    
    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        for service in invalidatedServices {
            if service.uuid == serviceUUID {
                centralManager.cancelPeripheralConnection(peripheral)
                break
            }
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == serviceUUID {
            // 🚀 Rimosso macStateUUID dalla richiesta di scoperta
            peripheral.discoverCharacteristics([telemetryUUID, notificationsUUID, commandUUID], for: service)
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for characteristic in characteristics {
            if characteristic.uuid == telemetryUUID {
                peripheral.readValue(for: characteristic)
                peripheral.setNotifyValue(true, for: characteristic)
            }
            if characteristic.uuid == notificationsUUID {
                peripheral.setNotifyValue(true, for: characteristic)
            }
            if characteristic.uuid == commandUUID {
                self.commandCharacteristic = characteristic

                // --- HANDSHAKE DI SESSIONE (BUG-001) ---
                // Identify this Mac; Android replies SESSION_READY only after the
                // link, notification subscription and identity are all present.
                let cpu = self.macCpu.isEmpty
                    ? (Platform.isAppleSilicon ? "Apple Silicon" : "Unknown")
                    : self.macCpu
                let model = self.macModel.isEmpty ? "Mac" : self.macModel
                let hello = "HELLO\u{1F}\(self.macId)\u{1F}\(self.macName)\u{1F}\(model)\u{1F}\(cpu)"
                if let data = hello.data(using: .utf8) {
                    peripheral.writeValue(data, for: characteristic, type: .withResponse)
                    NSLog("MacSync: HELLO inviato (\(self.macId) / \(self.macName) / \(model) / \(cpu))")
                }
                
                // --- FIX: SYNC A FREDDO (Reconnection Sync) ---
                UNUserNotificationCenter.current().removeAllDeliveredNotifications()
                NSLog("MacSync: Centro notifiche Mac svuotato per la sincronizzazione.")
                
                let syncCommand = "SYNC_REQ"
                if let data = syncCommand.data(using: .utf8) {
                    peripheral.writeValue(data, for: characteristic, type: .withResponse)
                    NSLog("MacSync: Comando SYNC_REQ inviato ad Android.")
                }
                // Ask for the real hotspot state after (re)connect.
                if let data = "HOTSPOT_STATUS".data(using: .utf8) {
                    peripheral.writeValue(data, for: characteristic, type: .withResponse)
                }
                // Ask for the current music metadata/cover after (re)connect.
                if let data = "MUSIC_STATUS".data(using: .utf8) {
                    peripheral.writeValue(data, for: characteristic, type: .withResponse)
                }
                // Ask for the selected contacts after (re)connect.
                if let data = "CONTACT_SYNC".data(using: .utf8) {
                    peripheral.writeValue(data, for: characteristic, type: .withResponse)
                }
            }
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        
        if characteristic.uuid == telemetryUUID, let data = characteristic.value,
           let fields = PixelPacket.fields(from: data),
           let telemetry = PixelPacket.parseTelemetry(fields) {

            DispatchQueue.main.async {
                self.batteryLevel = telemetry.battery
                self.isCharging = telemetry.isCharging
                self.networkType = telemetry.network
                self.signalStrength = telemetry.signal
                self.isWifi = telemetry.isWifi
                self.isHotspotActive = telemetry.isHotspot
                self.deviceName = telemetry.deviceName
                self.phoneBtName = telemetry.btName
                self.remoteDialEnabled = telemetry.remoteDialEnabled
                // Auto-select the paired Classic device matching the phone's
                // Bluetooth name (for "Mac 本机" HFP calling).
                if self.callMethod == .macBluetooth {
                    self.handsFree.autoDetect(fromBLE: telemetry.btName)
                }
                // Real hotspot state from Android telemetry (keeps macOS in sync
                // even if the hotspot is toggled on the phone).
                self.hotspotState = telemetry.isHotspot ? .on : .off
            }
        }

        if characteristic.uuid == notificationsUUID, let data = characteristic.value,
           let fields = PixelPacket.fields(from: data) {

            // Phone biometric unlock / pairing packets (Mac side).
            if self.unlockManager.handlePacket(fields) { return }

            // Multi-Mac: the phone refused this Mac (not active / user-disconnected).
            if let reason = PixelPacket.parseSessionRejected(fields) {
                DispatchQueue.main.async {
                    self.rejectedByPhone = true
                    self.connectionState = .rejected
                    // Retry periodically so that, once the phone makes this Mac the
                    // active one, it reconnects on its own.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 30.0) { [weak self] in
                        guard let self = self, self.rejectedByPhone else { return }
                        NSLog("MacSync: retry dopo rifiuto Multi-Mac.")
                        self.rejectedByPhone = false
                        if self.centralManager.state == .poweredOn { self.startScanningOrReconnect() }
                    }
                }
                NSLog("MacSync: SESSION_REJECTED (\(reason))")
                if let peripheral = self.pixelPeripheral {
                    self.centralManager.cancelPeripheralConnection(peripheral)
                }
                return
            }

            // Conferma di sessione (BUG-001): solo ora la UI mostra "Connected".
            if let session = PixelPacket.parseSessionReady(fields) {
                DispatchQueue.main.async {
                    self.connectionState = .connected
                    self.handleSessionRecovered()
                }
                NSLog("MacSync: SESSION_READY ricevuto (phone=\(session.phoneName), mac=\(session.macId))")
                return
            }

            // Sincronizzazione contatti (Android -> Mac, cifrata a riposo).
            if let _ = PixelPacket.parseContactsBegin(fields) {
                self.contactBuffer = []
                return
            }
            if let contact = PixelPacket.parseContact(fields) {
                self.contactBuffer.append(contact)
                return
            }
            if PixelPacket.isContactsEnd(fields) {
                let list = self.contactBuffer
                self.contactBuffer = []
                ContactsStore.shared.replaceAll(list)
                DispatchQueue.main.async { self.contactCount = list.count }
                NSLog("MacSync: contatti sincronizzati: \(list.count)")
                return
            }
            if let id = PixelPacket.parseContactRemove(fields) {
                ContactsStore.shared.remove(id: id)
                DispatchQueue.main.async { self.contactCount = ContactsStore.shared.contacts.count }
                return
            }
            if let status = PixelPacket.parseDialResult(fields) {
                if status != "ok" {
                    DispatchQueue.main.async {
                        self.deliverNotification(
                            id: "dial-status-\(Date().timeIntervalSince1970)",
                            title: L10n.dialFailedTitle,
                            body: L10n.dialFailure(status))
                    }
                }
                NSLog("MacSync: DIAL_RESULT -> \(status)")
                return
            }

            // Trasferimento icone app (una tantum, cache su disco).
            if let iconPacket = PixelPacket.parseIcon(fields) {
                self.handleIconPacket(iconPacket)
                return
            }

            // Copertina del brano in riproduzione (una tantum per brano).
            if let art = PixelPacket.parseArt(fields) {
                self.handleArtPacket(art)
                return
            }

            // Metadati della sessione musicale (titolo/artista/album/progresso).
            if let meta = PixelPacket.parseMusic(fields) {
                DispatchQueue.main.async {
                    let trackChanged = meta.title != self.music.title
                        || meta.artist != self.music.artist
                        || meta.album != self.music.album
                    let keyChanged = meta.coverKey != self.music.coverKey
                    self.music.coverKey = meta.coverKey
                    // Clear the previous cover on a track/key change, then show
                    // the cached one for this key immediately (no waiting for
                    // the art transfer). An out-of-order ART_END is ignored.
                    if (trackChanged || keyChanged) {
                        self.music.coverPath = nil
                    }
                    if self.music.coverPath == nil, !meta.coverKey.isEmpty,
                       let cached = self.cachedCoverURL(for: meta.coverKey) {
                        self.music.coverPath = cached.path
                    }
                    self.music.title = meta.title
                    self.music.artist = meta.artist
                    self.music.album = meta.album
                    self.music.durationMs = meta.durationMs
                    self.music.positionMs = meta.positionMs
                    self.music.isPlaying = meta.isPlaying
                    self.music.stopped = (meta.state == .stopped)
                    self.music.updatedAt = Date()
                }
                return
            }

            // Volume of the phone's media stream.
            if let volume = PixelPacket.parseVolume(fields) {
                DispatchQueue.main.async {
                    self.music.volumePercent = max(0, min(100, volume))
                }
                return
            }

            // Risposte di controllo hotspot.
            if let hs = PixelPacket.parseHotspot(fields) {
                DispatchQueue.main.async {
                    switch hs.kind {
                    case .state:
                        self.hotspotState = (hs.value == "ON") ? .on : .off
                    case .result:
                        // Idempotent outcomes are successes, never errors (BUG-002).
                        switch hs.value {
                        case "ALREADY_ON":  self.hotspotState = .on
                        case "ALREADY_OFF": self.hotspotState = .off
                        default: break // "OK": the real state follows via HOTSPOT_STATE/telemetry
                        }
                    case .error:
                        self.hotspotState = .error
                    }
                }
                NSLog("MacSync: Hotspot \(hs.kind) -> \(hs.value)")
                return
            }

            // Esito di un invio REPLY (per mostrare gli errori all'utente).
            if let result = PixelPacket.parseReplyResult(fields) {
                NSLog("MacSync: REPLY_RESULT id=\(result.id) -> \(result.status.rawValue)")
                if result.status != .ok {
                    DispatchQueue.main.async {
                        self.deliverNotification(
                            id: "reply-status-\(result.id)",
                            title: L10n.replyFailedTitle,
                            body: L10n.replyFailure(result.status.rawValue)
                        )
                    }
                }
                return
            }

            // Esito di un comando di chiamata (answer/end/mute).
            if let cr = PixelPacket.parseCallResult(fields) {
                if !cr.isOK {
                    DispatchQueue.main.async {
                        self.deliverNotification(
                            id: "call-status-\(cr.action)",
                            title: L10n.callFailedTitle,
                            body: L10n.callFailure(cr.action))
                    }
                }
                NSLog("MacSync: CALL_RESULT \(cr.action) -> \(cr.status)")
                return
            }

            // Stato mute del microfono.
            if let muted = PixelPacket.parseCallMuteState(fields) {
                DispatchQueue.main.async { self.callMuted = muted }
                NSLog("MacSync: CALL_MUTE_STATE -> \(muted ? "ON" : "OFF")")
                return
            }

            // Eventi chiamata (estensioni del protocollo).
            if let call = PixelPacket.parseCall(fields) {
                self.handleCallEvent(event: call.event.rawValue, number: call.number, name: call.name)
                return
            }

            if let notification = PixelPacket.parseNotification(fields) {
                switch notification.kind {
                case .post:
                    self.deliverNotification(id: notification.id,
                                             title: notification.title,
                                             body: notification.body,
                                             package: notification.package,
                                             appLabel: notification.appLabel,
                                             canReply: notification.canReply)
                    NSLog("MacSync: POST ricevuto id=\(notification.id) pkg=\(notification.package) title=\(notification.title) canReply=\(notification.canReply)")

                case .remove:
                    if self.notificationsAuthorized {
                        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [notification.id])
                    }
                    NSLog("MacSync: REMOVE ricevuto id=\(notification.id)")
                }
            }
        }
    }
}

// MARK: - Gestione Interazione Click ed Elusione Hardcoding
extension BLEManager {
    
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        
        let userInfo = response.notification.request.content.userInfo
        
        if let androidPackage = userInfo["androidPackage"] as? String,
           let notifId = userInfo["notifId"] as? String {
            
            // Inline reply: send the typed text back to the phone (no KILL —
            // the app usually updates/clears its own notification).
            if let textResponse = response as? UNTextInputNotificationResponse {
                NSLog("MacSync: Reply inline per \(androidPackage) (\(textResponse.userText.count) char)")
                sendReply(notifId: notifId, text: textResponse.userText)
                completionHandler()
                return
            }
            
            // Dismissed in Notification Center (swipe / clear) -> clear on phone.
            // A plain click does NOT delete: the phone notification is kept.
            if response.actionIdentifier == UNNotificationDismissActionIdentifier {
                NSLog("MacSync: Notifica rimossa dal Centro -> inoltro KILL.")
                sendKill(notifId)
                completionHandler()
                return
            }
            
            // Plain banner click: open the mapped macOS app if known, otherwise
            // do nothing. There is no app picker on notifications, so a click can
            // never open a Finder-like panel.
            if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
                if let target = appMap[androidPackage] {
                    launchMacApp(named: target)
                } else {
                    NSLog("MacSync: Nessun mapping per \(androidPackage); nessuna azione.")
                }
            }
        }
        
        completionHandler()
    }
    
    /// Clears the originating notification on the phone (reverse dismiss).
    private func sendKill(_ notifId: String) {
        let killCommand = "KILL\u{001F}\(notifId)"
        if let data = killCommand.data(using: .utf8),
           let peripheral = self.pixelPeripheral,
           let characteristic = self.commandCharacteristic {
            peripheral.writeValue(data, for: characteristic, type: .withResponse)
            NSLog("MacSync: Inviato comando di Reverse Dismiss -> \(killCommand)")
        }
    }

    private func launchMacApp(named name: String) {
        NSLog("MacSync: Avvio applicazione -> \(name)")
        let task = Process()
        task.launchPath = "/usr/bin/open"
        task.arguments = ["-a", name]
        try? task.run()
    }

    /// Sends `REPLY US <notifId> US <base64(text)>` to Android. The text is
    /// shrunk character-by-character until the whole BLE write fits the
    /// negotiated MTU (multi-byte safe: we drop whole Characters, not bytes).
    func sendReply(notifId: String, text: String) {
        guard isConnected, let peripheral = pixelPeripheral,
              let characteristic = commandCharacteristic else {
            NSLog("MacSync: REPLY non inviato: canale comando non pronto.")
            return
        }
        let maxLen = peripheral.maximumWriteValueLength(for: .withResponse)
        func command(_ s: String) -> String {
            "REPLY\u{1F}\(notifId)\u{1F}\(Data(s.utf8).base64EncodedString())"
        }
        var body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, maxLen > 0 else { return }
        var cmd = command(body)
        while cmd.utf8.count > maxLen, !body.isEmpty {
            body.removeLast()
            cmd = command(body)
        }
        if body.count < text.count {
            NSLog("MacSync: REPLY troncato a \(body.count) caratteri (max \(maxLen) byte).")
        }
        guard let data = cmd.data(using: .utf8) else { return }
        peripheral.writeValue(data, for: characteristic, type: .withResponse)
        NSLog("MacSync: Inviato REPLY (id=\(notifId), \(body.count) char)")
    }
    
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

// MARK: - Gestione eventi chiamata (Android -> Mac)
extension BLEManager {

    /// Restituisce il testo nella lingua del sistema (zh / en).
    private func loc(_ zh: String, _ en: String) -> String {
        let lang = Locale.preferredLanguages.first ?? "en"
        return lang.hasPrefix("zh") ? zh : en
    }

    /// Reassembles an app icon sent by Android and caches it at
    /// ~/Pictures/MacSyncIcons/<package>.png (used by the notification icon lookup).
    func handleIconPacket(_ packet: PixelPacket.IconPacket) {
        switch packet.kind {
        case .begin:
            iconBuffers[packet.package] = [:]
            NSLog("MacSync: Icona in arrivo per \(packet.package)")
        case .data:
            iconBuffers[packet.package, default: [:]][packet.seq] = packet.chunk
        case .end:
            guard let parts = iconBuffers.removeValue(forKey: packet.package) else { return }
            let b64 = parts.keys.sorted().compactMap { parts[$0] }.joined()
            guard let data = Data(base64Encoded: b64), !data.isEmpty else {
                NSLog("MacSync: Icona non decodificabile per \(packet.package)")
                return
            }
            guard let pictures = FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask).first else { return }
            let folder = pictures.appendingPathComponent("MacSyncIcons", isDirectory: true)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let file = folder.appendingPathComponent("\(packet.package).png")
            do {
                try data.write(to: file)
                NSLog("MacSync: Icona salvata per \(packet.package) (\(data.count) bytes) -> \(file.path)")
            } catch {
                NSLog("MacSync: Scrittura icona fallita: \(error)")
            }
        }
    }

    /// Reassembles cover art sent by Android and caches it at
    /// ~/Pictures/MacSyncCovers/<key>.jpg (JPEG bytes from Android).
    func handleArtPacket(_ packet: PixelPacket.ArtPacket) {
        switch packet.kind {
        case .begin:
            artBuffers[packet.key] = [:]
            NSLog("MacSync: Copertina in arrivo per \(packet.key)")
        case .data:
            artBuffers[packet.key, default: [:]][packet.seq] = packet.chunk
        case .end:
            guard let parts = artBuffers.removeValue(forKey: packet.key) else { return }
            let b64 = parts.keys.sorted().compactMap { parts[$0] }.joined()
            guard let data = Data(base64Encoded: b64), !data.isEmpty else {
                NSLog("MacSync: Copertina non decodificabile per \(packet.key)")
                return
            }
            guard let file = coverURL(for: packet.key) else { return }
            do {
                try data.write(to: file)
                NSLog("MacSync: Copertina salvata per \(packet.key) (\(data.count) bytes) -> \(file.path)")
                DispatchQueue.main.async {
                    // Only adopt this cover if it is the one the current track
                    // announced; stale/superseded transfers are dropped.
                    if self.music.coverKey.isEmpty || self.music.coverKey == packet.key {
                        self.music.coverPath = file.path
                    } else {
                        NSLog("MacSync: Copertina obsoleta ignorata \(packet.key) (attuale \(self.music.coverKey))")
                    }
                }
            } catch {
                NSLog("MacSync: Scrittura copertina fallita: \(error)")
            }
        }
    }

    /// Cache location for a cover key: ~/Pictures/MacSyncCovers/<key>.jpg
    private func coverURL(for key: String) -> URL? {
        guard let pictures = FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask).first else {
            return nil
        }
        let folder = pictures.appendingPathComponent("MacSyncCovers", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent("\(key).jpg")
    }

    /// Returns the cached cover URL only if a file already exists.
    private func cachedCoverURL(for key: String) -> URL? {
        guard !key.isEmpty, let url = coverURL(for: key),
              FileManager.default.fileExists(atPath: url.path) else { return nil }
        return url
    }

    func handleCallEvent(event: String, number rawNumber: String, name: String) {
        // Prefer the real number; fall back to the number we dialed (the phone
        // never reports outgoing numbers).
        let number = rawNumber.isEmpty ? lastDialedNumber : rawNumber
        let center = UNUserNotificationCenter.current()
        let subtitle = name
        let body = !number.isEmpty ? number : (name.isEmpty ? loc("未知号码", "Unknown number") : name)

        DispatchQueue.main.async {
            self.callNumber = !number.isEmpty ? number : name
            if event == "RINGING" { self.callIsOutgoing = false }
            if event == "OFFHOOK" { self.callIsOutgoing = !self.lastDialedNumber.isEmpty }
            NSLog("MacSync: callUI number='\(self.callNumber)' outgoing=\(self.callIsOutgoing) event=\(event)")
        }

        let incomingId = "call-\(number)-incoming"
        let activeId = "call-\(number)-active"

        switch event {
        case "RINGING":
            DispatchQueue.main.async { self.callPhase = .ringing; self.callMuted = false }
            postCallNotification(id: incomingId,
                                 title: loc("来电", "Incoming call"),
                                 subtitle: subtitle,
                                 body: body,
                                 sound: true)
        case "MISSED":
            DispatchQueue.main.async { self.callPhase = .none; self.callMuted = false }
            center.removeDeliveredNotifications(withIdentifiers: [incomingId])
            postCallNotification(id: "call-\(number)-missed",
                                 title: loc("未接来电", "Missed call"),
                                 subtitle: subtitle,
                                 body: body,
                                 sound: true)
        case "OFFHOOK":
            DispatchQueue.main.async { self.callPhase = .active }
            center.removeDeliveredNotifications(withIdentifiers: [incomingId])
            postCallNotification(id: activeId,
                                 title: loc("通话中", "Call answered"),
                                 subtitle: subtitle,
                                 body: body,
                                 sound: false)
        case "IDLE":
            DispatchQueue.main.async { self.callPhase = .none; self.callMuted = false }
            center.removeDeliveredNotifications(withIdentifiers: [incomingId, activeId])
        default:
            break
        }
        if event == "IDLE" || event == "MISSED" {
            lastDialedNumber = ""
            DispatchQueue.main.async { self.callNumber = ""; self.callIsOutgoing = false }
        }
        NSLog("MacSync: Evento chiamata gestito -> \(event) \(body)")
    }

    private func postCallNotification(id: String, title: String, subtitle: String, body: String, sound: Bool) {
        deliverNotification(id: id, title: title, body: body, subtitle: subtitle, sound: sound)
    }
}

// MARK: - Delivery to macOS Notification Center
extension BLEManager {

    /// Category with inline reply (origin app exposes RemoteInput) vs without.
    static let messageCategoryReply = "PIXELSYNC_MESSAGE_REPLY"
    static let messageCategoryPlain = "PIXELSYNC_MESSAGE_PLAIN"
    private static let replyActionId = "PIXELSYNC_REPLY"

    /// Registers two categories: one with an inline "Reply" text field (shown
    /// only when Android reports the app supports replies) and a plain one.
    /// No app picker is attached to notifications, so clicking a banner can
    /// never open a Finder-like panel.
    func registerNotificationCategories() {
        let replyAction = UNTextInputNotificationAction(
            identifier: Self.replyActionId,
            title: L10n.reply,
            options: [],
            textInputButtonTitle: L10n.replySend,
            textInputPlaceholder: L10n.replyPlaceholder
        )
        let withReply = UNNotificationCategory(
            identifier: Self.messageCategoryReply,
            actions: [replyAction],
            intentIdentifiers: [],
            options: []
        )
        let plain = UNNotificationCategory(
            identifier: Self.messageCategoryPlain,
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([withReply, plain])
    }

    /// Delivers a notification. Uses the native UserNotifications framework when
    /// the app is authorized; otherwise (macOS 12.7 refuses apps that are not
    /// signed with an Apple-issued certificate) falls back to Notification
    /// Center via /usr/bin/osascript.
    func deliverNotification(id: String, title: String, body: String,
                             subtitle: String = "", package: String = "",
                             appLabel: String = "", canReply: Bool = false, sound: Bool = true) {
        // Header = sender app name; subtitle = original notification title.
        let headerTitle = appLabel.isEmpty ? title : appLabel
        let headerSubtitle = appLabel.isEmpty ? subtitle : title

        if notificationsAuthorized {
            let content = UNMutableNotificationContent()
            content.title = headerTitle
            if !headerSubtitle.isEmpty { content.subtitle = headerSubtitle }
            content.body = body
            if sound { content.sound = UNNotificationSound.default }

            if !package.isEmpty {
                content.userInfo = ["androidPackage": package, "notifId": id]
                content.categoryIdentifier = canReply ? Self.messageCategoryReply : Self.messageCategoryPlain
                let fileManager = FileManager.default
                if let picturesURL = fileManager.urls(for: .picturesDirectory, in: .userDomainMask).first {
                    let iconURL = picturesURL
                        .appendingPathComponent("MacSyncIcons", isDirectory: true)
                        .appendingPathComponent("\(package).png")
                    if fileManager.fileExists(atPath: iconURL.path),
                       let attachment = try? UNNotificationAttachment(identifier: package, url: iconURL, options: nil) {
                        content.attachments = [attachment]
                    }
                }
            }
            UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: id, content: content, trigger: nil))
        } else {
            // UserNotifications refused (non-Apple signature) -> deliver via
            // osascript. The banner shows the sender app name as the title
            // (the icon remains the system script icon; see COMPATIBILITY.md).
            deliverViaOsascript(title: headerTitle, subtitle: headerSubtitle, body: body, sound: sound)
        }
    }

    private func deliverViaOsascript(title: String, subtitle: String, body: String, sound: Bool) {
        func escape(_ s: String) -> String {
            s.replacingOccurrences(of: "\\", with: "\\\\")
             .replacingOccurrences(of: "\"", with: "\\\"")
        }
        var script = "display notification \"\(escape(body))\" with title \"\(escape(title))\""
        if !subtitle.isEmpty { script += " subtitle \"\(escape(subtitle))\"" }
        if sound { script += " sound name \"default\"" }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", script]
        do { try process.run() } catch {
            NSLog("MacSync: osascript delivery failed: \(error)")
        }
    }
}
