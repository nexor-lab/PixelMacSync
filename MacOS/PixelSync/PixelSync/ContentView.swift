import SwiftUI

struct ContentView: View {
    @StateObject private var bleManager = BLEManager()
    
    // Funzione di supporto corretta per l'icona della batteria
    var batteryIconName: String {
        // Se è in carica, Apple richiede l'uso dell'unica icona col fulmine disponibile
        if bleManager.isCharging {
            return "battery.100.bolt"
        }
        
        // Se non è in carica, calcola lo scaglione corretto
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
                // Sostituiti i loghi Bluetooth con le antenne di sistema
                if bleManager.isSwitchedOn && bleManager.connectionStatus.contains("Connesso") {
                    // MODIFICA QUI: Icona moderna!
                    Image(systemName: "iphone")
                        .foregroundColor(.primary)
                } else {
                    Image(systemName: bleManager.isSwitchedOn ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                        .foregroundColor(bleManager.isSwitchedOn ? .blue : .red)
                }
                
                Text(bleManager.connectionStatus.contains("Connesso") ? "Pixel 7 Pro" : bleManager.connectionStatus)
                    .font(.headline)
                
                Spacer()
                
                if bleManager.connectionStatus.contains("Connesso") {

                    // Segnale di Rete
                    HStack(spacing: 4) {
                        Text(bleManager.networkType) // Qui arriverà "5G", "4G" o "Tuo_WiFi"
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.secondary)
                        
                        // Icona Wi-Fi (visibile solo se connesso al Wi-Fi)
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
            
            // --- SEZIONE 2: MEDIA CONTROL ---
            HStack {
                VStack(alignment: .leading) {
                    Text(bleManager.songTitle)
                        .font(.subheadline)
                        .bold()
                        .lineLimit(1)
                    Text(bleManager.songArtist)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
                
                Spacer()
                
                Button(action: { /* Indietro */ }) {
                    Image(systemName: "backward.fill")
                }.buttonStyle(.plain)
                
                Button(action: { bleManager.isPlaying.toggle() }) {
                    Image(systemName: bleManager.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title2)
                }.buttonStyle(.plain)
                
                Button(action: { /* Avanti */ }) {
                    Image(systemName: "forward.fill")
                }.buttonStyle(.plain)
            }
            
            Divider()
            
            // --- SEZIONE 3: HOTSPOT ---
            Toggle(isOn: $bleManager.isHotspotActive) {
                HStack {
                    Image(systemName: "personalhotspot")
                        .foregroundColor(bleManager.isHotspotActive ? .blue : .secondary)
                    Text("Hotspot Remoto")
                        .font(.subheadline)
                }
            }
            .toggleStyle(.switch)
            .tint(.blue)
            
        }
        .padding(16)
        .frame(width: 320)
    }
    
    // Helper per colorare di rosso se la batteria scende sotto il 20%
    func levelColor(for levelStr: String) -> Color {
        let level = Int(levelStr.replacingOccurrences(of: "%", with: "")) ?? 50
        if level <= 20 { return .red }
        return .primary
    }
}
