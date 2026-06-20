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
                    // ICONA ANTENNA CLICCABILE
                    Image(systemName: bleManager.isSwitchedOn ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                        .foregroundColor(bleManager.isSwitchedOn ? .accentColor : .red)
                        .onTapGesture {
                            if bleManager.isSwitchedOn {
                                bleManager.forceRestartBluetooth()
                            }
                        }
                        .help(bleManager.isSwitchedOn ? "Clicca per forzare il riavvio della ricerca Bluetooth" : "Bluetooth spento")
                }
                
                Text(bleManager.connectionStatus.contains("Connesso") ? bleManager.deviceName : bleManager.connectionStatus)
                    .font(.headline)
                    .fixedSize(horizontal: true, vertical: false)
                
                Spacer()
                
                if bleManager.connectionStatus.contains("Connesso") {

                    // Segnale di Rete
                    HStack(spacing: 4) {
                        MarqueeText(text: bleManager.networkType)
                        
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
struct MarqueeText: View {
    let text: String
    let maxWidth: CGFloat = 90 // Limite di sicurezza prima di intaccare Batteria e Nome
    
    @State private var offset: CGFloat = 0
    @State private var textWidth: CGFloat = 0
    
    var body: some View {
        let isTooLong = textWidth > maxWidth
        
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .foregroundColor(.secondary)
            .lineLimit(1)
            // Forziamo il testo a prendere tutto lo spazio che gli serve per essere misurato
            .fixedSize(horizontal: true, vertical: false)
            .background(GeometryReader { geo -> Color in
                // Leggiamo la larghezza reale del font
                if textWidth != geo.size.width {
                    DispatchQueue.main.async {
                        textWidth = geo.size.width
                        resetAnimation()
                    }
                }
                return Color.clear
            })
            // Se è troppo lungo applichiamo l'offset, altrimenti sta fermo a 0
            .offset(x: isTooLong ? offset : 0)
            // Maschera di ritaglio: se è corto è largo esattamente quanto il testo, se è lungo si ferma a 90
            .frame(width: isTooLong ? maxWidth : textWidth, alignment: .leading)
            .clipped()
            .onChange(of: text) { _, _ in
                resetAnimation()
            }
    }
    
    private func resetAnimation() {
        offset = 0
        // L'animazione parte solo ed esclusivamente se la larghezza supera la soglia
        if textWidth > maxWidth {
            // Effetto "Ping-Pong": scorre a sinistra, fa una pausa e torna a destra
            withAnimation(.linear(duration: Double(textWidth) * 0.03).delay(1.5).repeatForever(autoreverses: true)) {
                offset = maxWidth - textWidth
            }
        }
    }
}
