//
//  MacTelemetry.swift
//  PixelSync
//
//  Created by Luigi Quitadamo on 20/06/2026.
//

import Foundation
import IOKit.ps

struct MacTelemetry {
    static func getBatteryInfo() -> (level: Int, isCharging: Bool) {
        // Valori di default (es. se sei su un Mac Mini o iMac desktop)
        var batteryLevel = 100
        var isCharging = false
        
        // Chiamata sicura a IOKit
        let snapshot = IOPSCopyPowerSourcesInfo().takeRetainedValue()
        let sources = IOPSCopyPowerSourcesList(snapshot).takeRetainedValue() as Array
        
        for ps in sources {
            if let info = IOPSGetPowerSourceDescription(snapshot, ps)?.takeUnretainedValue() as? [String: Any] {
                // Leggiamo la percentuale
                if let capacity = info[kIOPSCurrentCapacityKey] as? Int {
                    batteryLevel = capacity
                }
                // Leggiamo lo stato di carica
                if let charging = info[kIOPSIsChargingKey] as? Bool {
                    isCharging = charging
                }
                break // Esce al primo power source trovato (la batteria principale)
            }
        }
        
        return (batteryLevel, isCharging)
    }
}
