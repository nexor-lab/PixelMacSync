import SwiftUI
import AppKit

struct ContentView: View {
    @ObservedObject var bleManager: BLEManager
    
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
                if bleManager.isSwitchedOn && bleManager.isConnected {
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
                        .help(bleManager.isSwitchedOn ? L10n.antennaRestartTooltip : L10n.bluetoothOffTooltip)
                }
                
                Text(bleManager.isConnected ? bleManager.deviceName : bleManager.connectionStatus)
                    .font(.headline)
                    .fixedSize(horizontal: true, vertical: false)
                
                Spacer()
                
                if bleManager.isConnected {

                    // Segnale di Rete
                    HStack(spacing: 4) {
                        Text(bleManager.networkType).font(.system(size: 10, weight: .bold)).foregroundColor(.secondary).lineLimit(1).minimumScaleFactor(0.6)
                        
                        if bleManager.isWifi {
                            Image(systemName: "wifi")
                                .foregroundColor(.primary)
                        }
                        
                        if #available(macOS 13.0, *) {
                            Image(systemName: "cellularbars", variableValue: Double(bleManager.signalStrength) / 4.0)
                                .foregroundColor(.primary)
                        } else {
                            Image(systemName: "cellularbars")
                                .foregroundColor(.primary)
                        }
                    }
                    
                    // Batteria
                    HStack(spacing: 4) {
                        Text(bleManager.batteryLevel)
                            .font(.caption)
                            .monospacedDigit().fixedSize(horizontal: true, vertical: false)
                        
                        Image(systemName: batteryIconName)
                            .symbolRenderingMode(.hierarchical)
                            .foregroundColor(bleManager.isCharging ? .green : (levelColor(for: bleManager.batteryLevel)))
                    }
                    .padding(.leading, 4)
                }
            }
            
            Divider()
            
            // --- SEZIONE 2: HOTSPOT REMOTO (controllo reale via BLE + Root) ---
            HStack(spacing: 8) {
                Image(systemName: "personalhotspot")
                    .foregroundColor(bleManager.hotspotState == .on ? .accentColor : .secondary)
                Text(L10n.remoteHotspot)
                    .font(.subheadline)
                Spacer()
                if bleManager.isConnected {
                    Text(bleManager.hotspotStateText)
                        .font(.caption)
                        .foregroundColor(bleManager.hotspotState == .error ? .red : .secondary)
                }
                Button(bleManager.hotspotState == .on ? L10n.hotspotTurnOff : L10n.hotspotTurnOn) {
                    bleManager.setRemoteHotspot(enable: bleManager.hotspotState != .on)
                }
                .disabled(!bleManager.isConnected || bleManager.hotspotBusy)
            }

            Divider()

            // --- SEZIONE 3: MUSICA (controllo MediaSession via BLE) ---
            MusicControlView(bleManager: bleManager)

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

// MARK: - Music control panel

struct MusicControlView: View {
    @ObservedObject var bleManager: BLEManager

    @State private var isDragging = false
    @State private var dragSeconds: Double = 0
    @State private var isDraggingVolume = false
    @State private var dragVolume: Double = 0

    private var durationSeconds: Double {
        max(1, Double(bleManager.music.durationMs) / 1000.0)
    }

    /// Locally extrapolated playback position (Android sends it on change only).
    private var liveSeconds: Double {
        let base = Double(bleManager.music.positionMs) / 1000.0
        guard bleManager.music.isPlaying else { return base }
        let elapsed = Date().timeIntervalSince(bleManager.music.updatedAt)
        return min(durationSeconds, base + elapsed)
    }

    private static func timeString(_ seconds: Double) -> String {
        let total = max(0, Int(seconds.rounded()))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "music.note")
                    .foregroundColor(bleManager.music.isPlaying ? .accentColor : .secondary)
                Text(L10n.music)
                    .font(.subheadline)
                Spacer()
                if !bleManager.isConnected {
                    Text(L10n.musicUnavailable)
                        .font(.caption)
                        .foregroundColor(.secondary)
                } else if bleManager.music.stopped || !bleManager.music.hasTrack {
                    Text(L10n.musicNoTrack)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            if bleManager.isConnected && bleManager.music.hasTrack {
                HStack(spacing: 10) {
                    coverImage
                    VStack(alignment: .leading, spacing: 2) {
                        Text(bleManager.music.title.isEmpty ? L10n.musicNoTrack : bleManager.music.title)
                            .font(.subheadline).fontWeight(.semibold)
                            .lineLimit(1)
                        Text(bleManager.music.artist.isEmpty ? L10n.musicUnknownArtist : bleManager.music.artist)
                            .font(.caption).foregroundColor(.secondary)
                            .lineLimit(1)
                        if !bleManager.music.album.isEmpty {
                            Text(bleManager.music.album)
                                .font(.caption2).foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                    }
                    Spacer()
                }

                TimelineView(.periodic(from: .now, by: 1.0)) { _ in
                    let shown = isDragging ? dragSeconds : liveSeconds
                    VStack(spacing: 2) {
                        Slider(
                            value: Binding(
                                get: { isDragging ? dragSeconds : liveSeconds },
                                set: { dragSeconds = $0 }
                            ),
                            in: 0...durationSeconds,
                            onEditingChanged: { editing in
                                if editing {
                                    isDragging = true
                                    dragSeconds = liveSeconds
                                } else {
                                    isDragging = false
                                    bleManager.musicSeek(toMs: Int(dragSeconds * 1000))
                                }
                            }
                        )
                        HStack {
                            Text(MusicControlView.timeString(shown))
                            Spacer()
                            Text(MusicControlView.timeString(durationSeconds))
                        }
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .monospacedDigit()
                    }
                }

                HStack {
                    Spacer()
                    Button(action: { bleManager.musicPrevious() }) {
                        Image(systemName: "backward.fill")
                    }
                    Button(action: { bleManager.musicToggle() }) {
                        Image(systemName: bleManager.music.isPlaying ? "pause.fill" : "play.fill")
                            .frame(width: 22)
                    }
                    Button(action: { bleManager.musicNext() }) {
                        Image(systemName: "forward.fill")
                    }
                    Spacer()
                }
                .buttonStyle(.borderless)
            }

            if bleManager.isConnected {
                HStack(spacing: 8) {
                    Image(systemName: "speaker.fill")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Slider(
                        value: Binding(
                            get: { isDraggingVolume ? dragVolume : Double(bleManager.music.volumePercent) },
                            set: { dragVolume = $0 }
                        ),
                        in: 0...100,
                        onEditingChanged: { editing in
                            if editing {
                                isDraggingVolume = true
                                dragVolume = Double(bleManager.music.volumePercent)
                            } else {
                                isDraggingVolume = false
                                bleManager.setMusicVolume(percent: Int(dragVolume.rounded()))
                            }
                        }
                    )
                    Image(systemName: "speaker.wave.3.fill")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text("\(isDraggingVolume ? Int(dragVolume.rounded()) : bleManager.music.volumePercent)%")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .monospacedDigit()
                        .frame(width: 34, alignment: .trailing)
                }
            }
        }
    }

    @ViewBuilder
    private var coverImage: some View {
        if let path = bleManager.music.coverPath, let image = NSImage(contentsOfFile: path) {
            Image(nsImage: image)
                .resizable()
                .aspectRatio(contentMode: .fill)
                .frame(width: 46, height: 46)
                .cornerRadius(6)
                .clipped()
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color.secondary.opacity(0.15))
                Image(systemName: "music.note")
                    .foregroundColor(.secondary)
            }
            .frame(width: 46, height: 46)
        }
    }
}
