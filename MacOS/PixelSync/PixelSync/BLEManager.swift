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
            centralManager.scanForPeripherals(withServices: [serviceUUID], options: nil)
        } else {
            isSwitchedOn = false
            connectionStatus = "Bluetooth OFF"
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
        connectionStatus = "Ricerca..."
        self.pixelPeripheral = nil
        self.batteryLevel = "--%"
        centralManager.scanForPeripherals(withServices: [serviceUUID], options: nil)
    }
}

// MARK: - Gestione Telemetria e Notifiche
extension BLEManager: CBPeripheralDelegate {
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
        // Gestione Batteria
        if characteristic.uuid == telemetryUUID, let data = characteristic.value,
           let value = String(data: data, encoding: .utf8) {
            DispatchQueue.main.async {
                self.batteryLevel = "\(value)%"
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
                    
                    // --- INIZIO GESTIONE ICONE ---
                    
                    // Dizionario per mappare il Bundle ID al nome del file .png (senza estensione)
                    // Attenzione al case-sensitive: i valori a destra devono essere IDENTICI al nome file in Xcode
                    let appIconMap: [String: String] = [
                        "com.whatsapp": "WhatsApp", // es: WhatsApp.png
                        "org.telegram": "Telegram", // es: Telegram.png
                        "com.google.android.apps.messaging": "Messages",
                        "com.instagram": "Instagram",
                        "com.google.android.apps.tasks": "Tasks" // Il file che hai appena esportato!
                    ]
                    
                    // Cerchiamo una corrispondenza
                    var matchedImageName: String? = nil
                    for (key, imageName) in appIconMap {
                        if bundleId.lowercased().contains(key.lowercased()) {
                            matchedImageName = imageName
                            break
                        }
                    }
                    
                    // Se troviamo l'immagine, l'alleghiamo al banner
                    if let imageName = matchedImageName,
                       let imageURL = Bundle.main.url(forResource: imageName, withExtension: "png") {
                        do {
                            let attachment = try UNNotificationAttachment(identifier: imageName, url: imageURL, options: nil)
                            content.attachments = [attachment]
                        } catch {
                            print("MacSync: Errore nel caricare l'icona per \(imageName): \(error)")
                        }
                    }
                    // --- FINE GESTIONE ICONE ---
                    
                    // Mostriamo il banner
                    let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
                    UNUserNotificationCenter.current().add(request)
                    
                    print("MacSync: Notifica nativa lanciata -> \(title): \(body)")
                }
            }
        }
    }
}
