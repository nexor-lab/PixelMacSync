import SwiftUI

struct ContentView: View {
    @StateObject private var bleManager = BLEManager()
    
    // Funzione di supporto corretta per l'icona della batteria
    var batteryIconName: String {
        if bleManager.isCharging {
            return "battery.100.bolt"
        }
       
        let level = Int(bleManager.batteryLevel.replacingOccurrences(of: "%", with: "")) ?? 50
        if level <= 12 { return "battery.0" }
        if level <= 37 { return "battery.25" }
        if level <= 62 { return "battery.50" }
        if level <= 87 { return "battery.75" }
        return "battery.100"
    }
    
    var body: some View {
        VStack(spacing: 16) {
            
            // --- SEZIONE 1: STATO CONNESSIONE E DISPOSITIVO ---
            HStack {
                if bleManager.isSwitchedOn && bleManager.connectionStatus.contains("Connesso") {
                    Image(systemName: "iphone")
                        .foregroundColor(.primary)
                } else {
                    Image(systemName: bleManager.isSwitchedOn ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                        .foregroundColor(bleManager.isSwitchedOn ? .accentColor : .red)
                }
                
                Text(bleManager.connectionStatus.contains("Connesso") ? "Pixel 7 Pro" : bleManager.connectionStatus)
                    .font(.headline)
                
                Spacer()
                
                if bleManager.connectionStatus.contains("Connesso") {

                    // Segnale di Rete
                    HStack(spacing: 4) {
                        Text(bleManager.networkType)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .fixedSize(horizontal: true, vertical: false)
                        
                        if bleManager.isWifi {
                            Image(systemName: "wifi")
                                .foregroundColor(.primary)
                        }
                        
                        Image(systemName: "cellularbars", variableValue: Double(bleManager.signalStrength) / 4.0)
                            .foregroundColor(.primary)
                    }
                    
                    // Batteria
                    HStack(spacing: 4) {
                        Text(bleManager.batteryLevel)
                            .font(.caption)
                            .monospacedDigit()
                        
                        Image(systemName: batteryIconName)
                            .symbolRenderingMode(.hierarchical)
                            .foregroundColor(bleManager.isCharging ? .green : (levelColor(for: bleManager.batteryLevel)))
                    }
                    .padding(.leading, 4)
                }
            }
            
            Divider()
            
            // --- SEZIONE 2: HOTSPOT ---
            Toggle(isOn: $bleManager.isHotspotActive) {
                HStack {
                    Image(systemName: "personalhotspot")
                        .foregroundColor(bleManager.isHotspotActive ? .accentColor : .secondary)
                    Text("Hotspot Remoto")
                        .font(.subheadline)
                }
            }
            .toggleStyle(.switch)
            .onChange(of: bleManager.isHotspotActive) { oldValue, newValue in
                bleManager.setRemoteHotspot(enable: newValue)
            }
            
        }
        .padding(16)
        .frame(width: 320)
    }
    
    func levelColor(for levelStr: String) -> Color {
        let level = Int(levelStr.replacingOccurrences(of: "%", with: "")) ?? 50
        if level <= 20 { return .red }
        return .primary
    }
}
