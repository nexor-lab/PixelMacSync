//
//  BLEManager.swift
//  PixelSync
//
//  Created by Luigi Quitadamo on 14/05/2026.
//

import Foundation
import CoreBluetooth
import Combine

class BLEManager: NSObject, ObservableObject, CBCentralManagerDelegate {
    var centralManager: CBCentralManager!
    var pixelPeripheral: CBPeripheral? // Aggiunto per memorizzare il dispositivo
    
    // Inserisci qui lo stesso UUID che abbiamo messo su Android
    let serviceUUID = CBUUID(string: "1234")

    @Published var isSwitchedOn = false
    @Published var connectionStatus = "Disconnesso" // Aggiunto per l'interfaccia utente

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    // Controllo stato Bluetooth del Mac
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            isSwitchedOn = true
            connectionStatus = "Scansione in corso..."
            // Avviamo la scansione cercando solo il tuo Pixel
            centralManager.scanForPeripherals(withServices: [serviceUUID], options: nil)
            print("MacSync: Scansione avviata...")
        } else {
            isSwitchedOn = false
            connectionStatus = "Bluetooth non disponibile"
            print("Bluetooth non disponibile")
        }
    }

    // 1. Trovato il dispositivo
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        print("MacSync: Trovato Pixel! Segnale: \(RSSI)dB")
        
        // Fermiamo la scansione per risparmiare risorse e batteria
        centralManager.stopScan()
        
        // Salviamo il riferimento alla periferica
        self.pixelPeripheral = peripheral
        
        // Diciamo che questa classe (tramite l'extension) gestirà le comunicazioni
        self.pixelPeripheral?.delegate = self
        
        // Proviamo a connetterci
        centralManager.connect(peripheral, options: nil)
    }
    
    // 2. Connessione riuscita
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectionStatus = "Connesso al Pixel"
        print("MacSync: Connessione stabilita con successo!")
        
        // Diciamo al Pixel: "Mostrami i tuoi servizi (quelli con questo UUID)"
        peripheral.discoverServices([serviceUUID])
    }
    
    // 3. Gestione disconnessione (es. il Pixel si allontana o spegne il Bluetooth)
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectionStatus = "Disconnesso - Ricerca in corso..."
        print("MacSync: Disconnesso dal Pixel. Riavvio scansione...")
        
        // Puliamo il riferimento e ripartiamo a cercare
        self.pixelPeripheral = nil
        centralManager.scanForPeripherals(withServices: [serviceUUID], options: nil)
    }
}

// MARK: - Gestione della Periferica (Pixel)
extension BLEManager: CBPeripheralDelegate {
    
    // 4. Il Mac ha trovato i servizi del Pixel
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error {
            print("Errore nella scoperta dei servizi: \(error.localizedDescription)")
            return
        }
        
        guard let services = peripheral.services else { return }
        
        for service in services {
            print("MacSync: Trovato servizio con UUID \(service.uuid)")
            // Ora chiediamo di scoprire le "Caratteristiche" (i canali dati) di questo servizio
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }
    
    // 5. Il Mac ha trovato le caratteristiche all'interno del servizio
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error = error {
            print("Errore nella scoperta delle caratteristiche: \(error.localizedDescription)")
            return
        }
        
        guard let characteristics = service.characteristics else { return }
        
        for characteristic in characteristics {
            print("MacSync: Trovata caratteristica con UUID \(characteristic.uuid)")
            
            // Qui potremo decidere cosa fare in base all'UUID della caratteristica:
            // - Leggere un dato: peripheral.readValue(for: characteristic)
            // - Iscriversi alle notifiche: peripheral.setNotifyValue(true, for: characteristic)
        }
    }
}
