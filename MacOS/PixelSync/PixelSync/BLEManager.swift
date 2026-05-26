import Foundation
import CoreBluetooth
import Combine
import UserNotifications
import AppKit

class BLEManager: NSObject, ObservableObject, CBCentralManagerDelegate, UNUserNotificationCenterDelegate {
    var centralManager: CBCentralManager!
    var pixelPeripheral: CBPeripheral?
    
    let serviceUUID = CBUUID(string: "E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
    let telemetryUUID = CBUUID(string: "33333333-73F5-4BC4-A12F-17D1AD07A961")
    let notificationsUUID = CBUUID(string: "22222222-73F5-4BC4-A12F-17D1AD07A961")
    let commandUUID = CBUUID(string: "44444444-73F5-4BC4-A12F-17D1AD07A961")
    
    var commandCharacteristic: CBCharacteristic?
    
    var connectionAttempts = 0
    
    // --- DIZIONARIO DINAMICO DELLE APP ---
    var appMap: [String: String] = [:]
    
    @Published var isSwitchedOn = false
    @Published var connectionStatus = "Disconnesso"
    @Published var batteryLevel: String = "--%"
    @Published var isCharging: Bool = false
    @Published var networkType: String = "5G"
    @Published var signalStrength: Int = 3
    @Published var isWifi: Bool = false
    @Published var isHotspotActive: Bool = false

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

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            print("MacSync: Permessi notifiche macOS concessi: \(granted)")
        }
        
        UNUserNotificationCenter.current().delegate = self
        
        // Carichiamo la mappa delle app dal file esterno JSON
        loadAppMap()
        
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(macDidSleep), name: NSWorkspace.willSleepNotification, object: nil)
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(macDidWake), name: NSWorkspace.didWakeNotification, object: nil)
    }

    // --- CARICAMENTO E SALVATAGGIO CONFIGURAZIONE ---
    func loadAppMap() {
        guard let url = configURL else { return }
        
        if FileManager.default.fileExists(atPath: url.path) {
            if let data = try? Data(contentsOf: url),
               let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
                self.appMap = decoded
                print("MacSync: Mappa app caricata correttamente da JSON: \(self.appMap)")
                return
            }
        }
        
        // Se il file non esiste ancora, creiamo un default iniziale con le tue tre app
        self.appMap = [
            "com.instagram.android": "Instagram",
            "com.discord": "Discord",
            "org.telegram.messenger": "Telegram"
        ]
        saveAppMap()
    }

    func saveAppMap() {
        guard let url = configURL else { return }
        if let data = try? JSONEncoder().encode(appMap) {
            try? data.write(to: url)
            print("MacSync: Configurazione JSON aggiornata in Documenti/MacSync/")
        }
    }

    @objc func macDidSleep() {
        print("MacSync: Coperchio chiuso o stop display. Sgancio il Bluetooth preventivamente.")
        if let peripheral = pixelPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        centralManager.stopScan()
    }

    // 🚀 NUOVO RISVEGLIO AGGRESSIVO
    @objc func macDidWake() {
        print("MacSync: Sistema sveglio. Riavvio motore Bluetooth pulito.")
        
        DispatchQueue.main.async {
            self.connectionStatus = "Risveglio..."
            self.batteryLevel = "--%"
            self.isCharging = false
            self.networkType = "---"
            self.signalStrength = 0
            self.isWifi = false
            self.isHotspotActive = false
        }

        // Diamo 4 secondi al Mac per riattivare i driver hardware
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) {
            // Usiamo il reset pesante invece di quello gentile
            self.forceRestartBluetooth()
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            isSwitchedOn = true
            connectionStatus = "Scansione..."
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.startScanningOrReconnect()
            }
        } else {
            isSwitchedOn = false
            connectionStatus = "Bluetooth OFF"
            
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
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        guard self.pixelPeripheral == nil else { return }
        
        centralManager.stopScan()
        self.pixelPeripheral = peripheral
        self.pixelPeripheral?.delegate = self
        connectionStatus = "Connessione..."
        
        connectionAttempts += 1
        if connectionAttempts > 3 {
            print("MacSync: Loop di connessione rilevato! Eseguo Hard Reset interno...")
            forceRestartBluetooth()
            return
        }
        
        centralManager.connect(peripheral, options: nil)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) { [weak self] in
            guard let self = self else { return }
            if self.pixelPeripheral?.identifier == peripheral.identifier && peripheral.state != .connected {
                print("MacSync: Timeout connessione in scansione! Il Pixel non risponde.")
                self.centralManager.cancelPeripheralConnection(peripheral)
                self.connectionStatus = "Ricerca..."
                
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
        connectionStatus = "Connesso al Pixel"
        peripheral.discoverServices([serviceUUID])
    }
    
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        print("MacSync: Dispositivo disconnesso. Ripristino stato e riavvio scansione.")
        
        DispatchQueue.main.async {
            self.connectionStatus = "Ricerca..."
            self.batteryLevel = "--%"
            self.isCharging = false
            self.networkType = "---"
            self.signalStrength = 0
            self.isWifi = false
            self.isHotspotActive = false
            
            self.centralManager.stopScan()
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                if self.centralManager.state == .poweredOn {
                    self.startScanningOrReconnect()
                }
            }
        }
    }
    
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        print("MacSync: Connessione fallita dal sistema.")
        DispatchQueue.main.async {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                if self.centralManager.state == .poweredOn {
                    self.startScanningOrReconnect()
                }
            }
        }
    }
    
    func setRemoteHotspot(enable: Bool) {
        guard let peripheral = pixelPeripheral, let characteristic = commandCharacteristic else {
            print("MacSync: Impossibile inviare il comando. Dispositivo o canale non pronto.")
            return
        }
        
        let commandString = enable ? "HOTSPOT_ON" : "HOTSPOT_OFF"
        if let data = commandString.data(using: .utf8) {
            peripheral.writeValue(data, for: characteristic, type: .withResponse)
            print("MacSync: Inviato comando -> \(commandString)")
            
            DispatchQueue.main.async {
                self.isHotspotActive = enable
            }
        }
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
            connectionStatus = "Connessione (Cache)..."
            
            connectionAttempts += 1
            if connectionAttempts > 3 {
                forceRestartBluetooth()
                return
            }
            
            centralManager.connect(peripheral, options: nil)
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) { [weak self] in
                guard let self = self else { return }
                if self.pixelPeripheral?.identifier == peripheral.identifier && peripheral.state != .connected {
                    self.centralManager.cancelPeripheralConnection(peripheral)
                    self.connectionStatus = "Ricerca..."
                    
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                        self.forceRestartBluetooth()
                    }
                }
            }
            
        } else {
            connectionStatus = "Ricerca..."
            centralManager.stopScan()
            centralManager.scanForPeripherals(withServices: [serviceUUID], options: nil)
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 20.0) { [weak self] in
                guard let self = self else { return }
                if self.connectionStatus == "Ricerca..." && self.pixelPeripheral == nil {
                    self.forceRestartBluetooth()
                }
            }
        }
    }

    func forceRestartBluetooth() {
        if let peripheral = pixelPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
            peripheral.delegate = nil
        }
        centralManager.stopScan()
        
        self.pixelPeripheral = nil
        self.commandCharacteristic = nil
        self.connectionAttempts = 0
        
        DispatchQueue.main.async {
            self.connectionStatus = "Riavvio in corso..."
            self.batteryLevel = "--%"
            self.isCharging = false
            self.networkType = "---"
            self.signalStrength = 0
            self.isWifi = false
            self.isHotspotActive = false
        }
        
        centralManager.delegate = nil
        centralManager = CBCentralManager(delegate: self, queue: nil)
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
            }
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        
        if characteristic.uuid == telemetryUUID, let data = characteristic.value,
           let payload = String(data: data, encoding: .utf8) {
            
            let parts = payload.components(separatedBy: "\u{001F}")
            
            DispatchQueue.main.async {
                if parts.count >= 6 {
                    self.batteryLevel = "\(parts[0])%"
                    self.isCharging = (parts[1] == "true")
                    self.networkType = parts[2]
                    self.signalStrength = Int(parts[3]) ?? 0
                    self.isWifi = (parts[4] == "true")
                    self.isHotspotActive = (parts[5] == "true")
                }
            }
        }
        
        if characteristic.uuid == notificationsUUID {
            if let data = characteristic.value, let payload = String(data: data, encoding: .utf8) {
                let parts = payload.components(separatedBy: "\u{001F}")
                guard !parts.isEmpty else { return }
                
                let action = parts[0]
                
                if action == "POST" && parts.count >= 5 {
                    let notifId = parts[1]
                    let bundleId = parts[2]
                    let title = parts[3]
                    let body = parts[4]
                    
                    let content = UNMutableNotificationContent()
                    content.title = title
                    content.body = body
                    content.sound = UNNotificationSound.default
                    
                    content.userInfo = [
                        "androidPackage": bundleId,
                        "notifId": notifId
                    ]
                    
                    let fileManager = FileManager.default
                    if let picturesURL = fileManager.urls(for: .picturesDirectory, in: .userDomainMask).first {
                        let iconsFolderURL = picturesURL.appendingPathComponent("MacSyncIcons", isDirectory: true)
                        let iconFileURL = iconsFolderURL.appendingPathComponent("\(bundleId).png")
                        if fileManager.fileExists(atPath: iconFileURL.path) {
                            do {
                                let attachment = try UNNotificationAttachment(identifier: bundleId, url: iconFileURL, options: nil)
                                content.attachments = [attachment]
                            } catch { }
                        }
                    }
                    
                    let request = UNNotificationRequest(identifier: notifId, content: content, trigger: nil)
                    UNUserNotificationCenter.current().add(request)
                    
                } else if action == "REMOVE" && parts.count >= 2 {
                    let notifId = parts[1]
                    UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [notifId])
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
            
            print("MacSync: Click rilevato per pacchetto: \(androidPackage)")
            
            // --- 1. APERTURA APP MAC ---
            if let macAppName = appMap[androidPackage] {
                // Esecuzione immediata
                launchMacApp(named: macAppName)
            } else {
                // NUOVA APP RILEVATA: Chiediamo all'utente cosa aprire tramite selettore nativo
                print("MacSync: Pacchetto sconosciuto. Mostro il selettore di applicazioni...")
                promptUserToSelectApp(for: androidPackage)
            }
            
            // --- 2. 🔫 INVIA IL COMANDO DI REVERSE DISMISS AD ANDROID ---
            let killCommand = "KILL\u{001F}\(notifId)"
            if let data = killCommand.data(using: .utf8),
               let peripheral = self.pixelPeripheral,
               let characteristic = self.commandCharacteristic {
                
                peripheral.writeValue(data, for: characteristic, type: .withResponse)
                print("MacSync: Inviato comando di Reverse Dismiss -> \(killCommand)")
            }
        }
        
        completionHandler()
    }
    
    // Funzione ausiliaria per l'apertura delle applicazioni nativa e PWA
    private func launchMacApp(named name: String) {
        print("MacSync: Avvio applicazione -> \(name)")
        let task = Process()
        task.launchPath = "/usr/bin/open"
        task.arguments = ["-a", name]
        try? task.run()
    }
    
    // INTERFACCIA DI SELEZIONE DINAMICA (Apre /Applications e filtra i file .app)
    private func promptUserToSelectApp(for androidPackage: String) {
        DispatchQueue.main.async {
            // Forza l'applicazione in primo piano per mostrare la finestra di dialogo sopra tutto
            NSApp.activate(ignoringOtherApps: true)
            
            let openPanel = NSOpenPanel()
            openPanel.title = "Seleziona l'app Mac da associare a \(androidPackage)"
            openPanel.prompt = "Associa applicazione"
            openPanel.showsResizeIndicator = true
            openPanel.showsHiddenFiles = false
            openPanel.canChooseDirectories = false
            openPanel.canCreateDirectories = false
            openPanel.allowsMultipleSelection = false
            openPanel.allowedFileTypes = ["app"] // Riconosce sia app native che PWA (.app wrapper)
            openPanel.directoryURL = URL(fileURLWithPath: "/Applications")
            
            if openPanel.runModal() == .OK {
                if let url = openPanel.url {
                    // Estrae il nome dell'applicazione escludendo l'estensione .app
                    let appName = url.deletingPathExtension().lastPathComponent
                    
                    // Memorizza l'associazione nel dizionario e aggiorna il file JSON
                    self.appMap[androidPackage] = appName
                    self.saveAppMap()
                    
                    print("MacSync: Nuova associazione memorizzata: \(androidPackage) -> \(appName)")
                    
                    // Avvia subito l'app appena scelta per completare l'azione del click
                    self.launchMacApp(named: appName)
                }
            }
        }
    }
    
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
