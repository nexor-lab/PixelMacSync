import Foundation
import CoreBluetooth
import Combine
import UserNotifications

class BLEManager: NSObject, ObservableObject, CBCentralManagerDelegate {
    var centralManager: CBCentralManager!
    var pixelPeripheral: CBPeripheral?
    
    let serviceUUID = CBUUID(string: "E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
    let telemetryUUID = CBUUID(string: "33333333-73F5-4BC4-A12F-17D1AD07A961")
    let notificationsUUID = CBUUID(string: "22222222-73F5-4BC4-A12F-17D1AD07A961")
    
    @Published var isSwitchedOn = false
    @Published var connectionStatus = "Disconnesso"
    @Published var batteryLevel: String = "--%"
    @Published var isCharging: Bool = false
    @Published var networkType: String = "5G"
    @Published var signalStrength: Int = 3 // Da 0 a 4
    @Published var isWifi: Bool = false
    @Published var isHotspotActive: Bool = false
    @Published var isPlaying: Bool = false
    @Published var songTitle: String = "Nessun media in riproduzione"
    @Published var songArtist: String = "---"

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            print("MacSync: Permessi notifiche macOS concessi: \(granted)")
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            isSwitchedOn = true
            connectionStatus = "Scansione..."
            
            // Un micro-ritardo assicura che l'antenna sia 100% pronta prima di scansionare
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.centralManager.scanForPeripherals(withServices: [self.serviceUUID], options: nil)
            }
        } else {
            // Se spegniamo il Bluetooth, puliamo tutto! Altrimenti al riavvio si incanta.
            isSwitchedOn = false
            connectionStatus = "Bluetooth OFF"
            pixelPeripheral = nil
            
            batteryLevel = "--%"
            isCharging = false
            networkType = "---"
            signalStrength = 0
            isWifi = false
            isHotspotActive = false
            songTitle = "Nessun media in riproduzione"
            songArtist = "---"
            isPlaying = false
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        centralManager.stopScan()
        self.pixelPeripheral = peripheral
        self.pixelPeripheral?.delegate = self
        centralManager.connect(peripheral, options: nil)
    }
    
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectionStatus = "Connesso al Pixel"
        peripheral.discoverServices([serviceUUID])
    }
    
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        print("MacSync: Dispositivo disconnesso. Ripristino stato e riavvio scansione.")
        
        DispatchQueue.main.async {
            self.connectionStatus = "Ricerca..."
            self.pixelPeripheral = nil
            
            // Azzeriamo esplicitamente TUTTI i dati visivi per evitare i "dati fantasma"
            self.batteryLevel = "--%"
            self.isCharging = false
            self.networkType = "---"
            self.signalStrength = 0
            self.isWifi = false
            self.isHotspotActive = false
            self.songTitle = "Nessun media in riproduzione"
            self.songArtist = "---"
            self.isPlaying = false
            
            // Fermiamo eventuali scansioni incastrate e facciamo ripartire la ricerca pulita
            self.centralManager.stopScan()
            self.centralManager.scanForPeripherals(withServices: [self.serviceUUID], options: nil)
        }
    }
    
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        print("MacSync: Connessione fallita.")
        DispatchQueue.main.async {
            self.pixelPeripheral = nil
            self.centralManager.scanForPeripherals(withServices: [self.serviceUUID], options: nil)
        }
    }
}

// MARK: - Gestione Telemetria e Notifiche
extension BLEManager: CBPeripheralDelegate {
    
    // NUOVO METODO: Intercetta quando l'app su Android viene killata (il servizio sparisce)
    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        for service in invalidatedServices {
            if service.uuid == serviceUUID {
                print("MacSync: Il servizio Android è sparito (App chiusa?). Forzo la disconnessione.")
                centralManager.cancelPeripheralConnection(peripheral)
                break
            }
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == serviceUUID {
            peripheral.discoverCharacteristics([telemetryUUID, notificationsUUID], for: service)
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
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        
        // Gestione Telemetria
        if characteristic.uuid == telemetryUUID, let data = characteristic.value,
           let payload = String(data: data, encoding: .utf8) {
            
            let parts = payload.components(separatedBy: "\u{001F}")
            
            DispatchQueue.main.async {
                if parts.count >= 5 {
                    self.batteryLevel = "\(parts[0])%"
                    self.isCharging = (parts[1] == "true")
                    self.networkType = parts[2]
                    self.signalStrength = Int(parts[3]) ?? 0
                    self.isWifi = (parts[4] == "true")
                }
            }
        }
        
        // Gestione Notifiche in arrivo
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
                            print("MacSync: Creata cartella icone in: \(iconsFolderURL.path)")
                        }
                        
                        let iconFileURL = iconsFolderURL.appendingPathComponent("\(bundleId).png")
                        
                        if fileManager.fileExists(atPath: iconFileURL.path) {
                            do {
                                let attachment = try UNNotificationAttachment(identifier: bundleId, url: iconFileURL, options: nil)
                                content.attachments = [attachment]
                                print("MacSync: Icona custom caricata correttamente da Immagini per \(bundleId)")
                            } catch {
                                print("MacSync: Errore nella creazione dell'allegato per \(bundleId): \(error)")
                            }
                        } else {
                            print("MacSync: Icona non trovata in Immagini/MacSyncIcons per \(bundleId).")
                        }
                    }
                    
                    let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
                    UNUserNotificationCenter.current().add(request)
                    
                    print("MacSync: Notifica nativa lanciata -> \(title): \(body)")
                }
            }
        }
    }
}
