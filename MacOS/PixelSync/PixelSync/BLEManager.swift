import Foundation
import CoreBluetooth
import Combine
import UserNotifications // NOVITÀ: Importiamo il framework per le notifiche native di macOS

class BLEManager: NSObject, ObservableObject, CBCentralManagerDelegate {
    var centralManager: CBCentralManager!
    var pixelPeripheral: CBPeripheral?
    
    let serviceUUID = CBUUID(string: "E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
    let telemetryUUID = CBUUID(string: "33333333-73F5-4BC4-A12F-17D1AD07A961")
    let notificationsUUID = CBUUID(string: "22222222-73F5-4BC4-A12F-17D1AD07A961") // NOVITÀ: UUID Notifiche
    
    @Published var isSwitchedOn = false
    @Published var connectionStatus = "Disconnesso"
    @Published var batteryLevel: String = "--%"

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
        
        // NOVITÀ: Chiediamo i permessi a macOS per mostrare i banner delle notifiche
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
            // NOVITÀ: Diciamo al Mac di cercare ENTRAMBE le caratteristiche
            peripheral.discoverCharacteristics([telemetryUUID, notificationsUUID], for: service)
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for characteristic in characteristics {
            if characteristic.uuid == telemetryUUID {
                // Lettura immediata e iscrizione per i cambiamenti futuri
                peripheral.readValue(for: characteristic)
                peripheral.setNotifyValue(true, for: characteristic)
            }
            
            // NOVITÀ: Ci iscriviamo al canale delle notifiche
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
                        
                        // NOVITÀ: Usiamo lo stesso carattere Unicode per tagliare la stringa
                        let parts = payload.components(separatedBy: "\u{001F}")
                        
                        if parts.count >= 3 {
                            let bundleId = parts[0] // Ci servirà poi per il mapping dell'icona O(1)!
                            let title = parts[1]
                            let body = parts[2]
                            
                            // Creiamo la notifica nativa per macOS
                            let content = UNMutableNotificationContent()
                            content.title = title
                            content.body = body
                            content.sound = UNNotificationSound.default
                            
                            // Mostriamo il banner
                            let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
                            UNUserNotificationCenter.current().add(request)
                            
                            print("MacSync: Notifica nativa lanciata -> \(title): \(body)")
                        }
                    }
                }
    }
}
