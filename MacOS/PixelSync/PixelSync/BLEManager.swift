import Foundation
import CoreBluetooth
import Combine

class BLEManager: NSObject, ObservableObject, CBCentralManagerDelegate {
    var centralManager: CBCentralManager!
    var pixelPeripheral: CBPeripheral?
    
    let serviceUUID = CBUUID(string: "E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
    let telemetryUUID = CBUUID(string: "33333333-73F5-4BC4-A12F-17D1AD07A961")
    
    @Published var isSwitchedOn = false
    @Published var connectionStatus = "Disconnesso"
    @Published var batteryLevel: String = "--%"

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
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

// MARK: - Gestione Telemetria
extension BLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == serviceUUID {
            peripheral.discoverCharacteristics([telemetryUUID], for: service)
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for characteristic in characteristics where characteristic.uuid == telemetryUUID {
            // Lettura immediata e iscrizione per i cambiamenti futuri
            peripheral.readValue(for: characteristic)
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == telemetryUUID, let data = characteristic.value,
           let value = String(data: data, encoding: .utf8) {
            DispatchQueue.main.async {
                self.batteryLevel = "\(value)%"
            }
        }
    }
}
