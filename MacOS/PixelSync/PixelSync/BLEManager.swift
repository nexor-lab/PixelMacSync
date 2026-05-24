import Foundation
import CoreBluetooth
import Combine
import UserNotifications
import AppKit

class BLEManager: NSObject, ObservableObject, CBCentralManagerDelegate {
    var centralManager: CBCentralManager!
    var pixelPeripheral: CBPeripheral?
    
    let serviceUUID = CBUUID(string: "E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
    let telemetryUUID = CBUUID(string: "33333333-73F5-4BC4-A12F-17D1AD07A961")
    let notificationsUUID = CBUUID(string: "22222222-73F5-4BC4-A12F-17D1AD07A961")
    let commandUUID = CBUUID(string: "44444444-73F5-4BC4-A12F-17D1AD07A961")
    
    var commandCharacteristic: CBCharacteristic?
    
    // Contatore per l'anti-loop di connessione
    var connectionAttempts = 0
    
    @Published var isSwitchedOn = false
    @Published var connectionStatus = "Disconnesso"
    @Published var batteryLevel: String = "--%"
    @Published var isCharging: Bool = false
    @Published var networkType: String = "5G"
    @Published var signalStrength: Int = 3
    @Published var isWifi: Bool = false
    @Published var isHotspotActive: Bool = false

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            print("MacSync: Permessi notifiche macOS concessi: \(granted)")
        }
        
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(macDidSleep), name: NSWorkspace.willSleepNotification, object: nil)
        NSWorkspace.shared.notificationCenter.addObserver(self, selector: #selector(macDidWake), name: NSWorkspace.didWakeNotification, object: nil)
    }

    @objc func macDidSleep() {
        print("MacSync: Coperchio chiuso o stop display. Sgancio il Bluetooth preventivamente.")
        if let peripheral = pixelPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        centralManager.stopScan()
    }

    @objc func macDidWake() {
        print("MacSync: Sistema sveglio. Riavvio motore Bluetooth pulito.")
        
        DispatchQueue.main.async {
            self.connectionStatus = "Ricerca..."
            self.batteryLevel = "--%"
            self.isCharging = false
            self.networkType = "---"
            self.signalStrength = 0
            self.isWifi = false
            self.isHotspotActive = false
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            if self.centralManager.state == .poweredOn {
                self.startScanningOrReconnect()
            }
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
    
    // MARK: - Funzione di Scansione / Riconnessione
    func startScanningOrReconnect() {
        if let peripheral = pixelPeripheral {
            if peripheral.state == .connected {
                print("MacSync: Già connesso stabilmente.")
                return
            }
            if peripheral.state == .connecting {
                print("MacSync: Attendo...")
                return
            }
            centralManager.cancelPeripheralConnection(peripheral)
            peripheral.delegate = nil
        }
        
        self.pixelPeripheral = nil
        self.commandCharacteristic = nil
        
        let systemConnected = centralManager.retrieveConnectedPeripherals(withServices: [serviceUUID])
        
        if let peripheral = systemConnected.first {
            print("MacSync: Trovato in cache. Provo a riagganciarmi...")
            self.pixelPeripheral = peripheral
            self.pixelPeripheral?.delegate = self
            connectionStatus = "Connessione (Cache)..."
            
            connectionAttempts += 1
            if connectionAttempts > 3 {
                print("MacSync: Cache macOS corrotta rilevata (Loop)! Eseguo Hard Reset...")
                forceRestartBluetooth()
                return
            }
            
            centralManager.connect(peripheral, options: nil)
            
            // Timeout di emergenza anche per la cache (il bug di solito colpisce qui)
            DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) { [weak self] in
                guard let self = self else { return }
                if self.pixelPeripheral?.identifier == peripheral.identifier && peripheral.state != .connected {
                    print("MacSync: Timeout su connessione Cache!")
                    self.centralManager.cancelPeripheralConnection(peripheral)
                    self.connectionStatus = "Ricerca..."
                    
                    // CORRETTO: Esegue l'Hard Reset invece di startScanningOrReconnect()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                        self.forceRestartBluetooth()
                    }
                }
            }
            
        } else {
            print("MacSync: Nessuna cache. Avvio scansione aerea...")
            connectionStatus = "Ricerca..."
            centralManager.stopScan()
            centralManager.scanForPeripherals(withServices: [serviceUUID], options: nil)
            
            // WATCHDOG AUTOMATICO (20 Secondi)
            DispatchQueue.main.asyncAfter(deadline: .now() + 20.0) { [weak self] in
                guard let self = self else { return }
                if self.connectionStatus == "Ricerca..." && self.pixelPeripheral == nil {
                    print("MacSync: Watchdog 20s scattato, eseguo un auto-retry con Hard Reset...")
                    // CORRETTO: Chiama la forza bruta automatica
                    self.forceRestartBluetooth()
                }
            }
        }
    }

    // MARK: - HARD RESET (Nuova Versione Distruttiva)
    func forceRestartBluetooth() {
        print("MacSync: Eseguo HARD RESET dell'intero motore Bluetooth...")
        
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
        
        // 🧨 Distruzione e ricreazione istantanea del CBCentralManager
        centralManager.delegate = nil
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }
}

// MARK: - Gestione Telemetria e Notifiche
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
                print("MacSync: Canale Notifiche pronto e in ascolto")
                peripheral.setNotifyValue(true, for: characteristic)
            }
            
            if characteristic.uuid == commandUUID {
                print("MacSync: Canale Comandi armato e pronto al fuoco!")
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
                if parts.count >= 3 {
                    let bundleId = parts[0]
                    let title = parts[1]
                    let body = parts[2]
                    
                    let content = UNMutableNotificationContent()
                    content.title = title
                    content.body = body
                    content.sound = UNNotificationSound.default
                    
                    let fileManager = FileManager.default
                    if let picturesURL = fileManager.urls(for: .picturesDirectory, in: .userDomainMask).first {
                        let iconsFolderURL = picturesURL.appendingPathComponent("MacSyncIcons", isDirectory: true)
                        if !fileManager.fileExists(atPath: iconsFolderURL.path) {
                            try? fileManager.createDirectory(at: iconsFolderURL, withIntermediateDirectories: true, attributes: nil)
                        }
                        let iconFileURL = iconsFolderURL.appendingPathComponent("\(bundleId).png")
                        if fileManager.fileExists(atPath: iconFileURL.path) {
                            do {
                                let attachment = try UNNotificationAttachment(identifier: bundleId, url: iconFileURL, options: nil)
                                content.attachments = [attachment]
                            } catch {
                                print("MacSync: Errore allegato: \(error)")
                            }
                        }
                    }
                    let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
                    UNUserNotificationCenter.current().add(request)
                }
            }
        }
    }
}
