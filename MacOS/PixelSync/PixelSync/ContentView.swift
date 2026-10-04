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

    /// Call header text: ringing / calling (outgoing) / in call.
    private var callTitle: String {
        if bleManager.callPhase == .ringing { return L10n.callIncoming }
        return bleManager.callIsOutgoing ? L10n.callCalling : L10n.callActive
    }
    
    var body: some View {
      ZStack {
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

            // --- CALL CONTROL (only while a call is ringing/active) ---
            if bleManager.callPhase != .none {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Image(systemName: "phone.fill")
                            .foregroundColor(.accentColor)
                        Text(callTitle)
                            .font(.subheadline)
                        Spacer()
                        if bleManager.callPhase == .ringing {
                            Button(L10n.callAnswer) { bleManager.callAnswer() }
                            Button(L10n.callReject) { bleManager.callEnd() }
                        } else {
                            Button(bleManager.callMuted ? L10n.callUnmute : L10n.callMute) {
                                bleManager.callMuteToggle()
                            }
                            Button(L10n.callHangUp) { bleManager.callEnd() }
                        }
                    }
                    if !bleManager.callNumber.isEmpty {
                        Text(bleManager.callNumber)
                            .font(.title3).fontWeight(.semibold)
                            .lineLimit(1)
                    }
                }
                .transition(.move(edge: .top).combined(with: .opacity))
                Divider()
            }

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

            Divider()

            // --- SEZIONE 4: DIAL (remote dialing + synced contacts) ---
            DialView(bleManager: bleManager)

        }
        .padding(16)
        .frame(width: 320)
        .animation(.easeInOut(duration: 0.25), value: bleManager.callPhase)
        .animation(.easeInOut(duration: 0.25), value: bleManager.isConnected)
        .animation(.easeInOut(duration: 0.20), value: bleManager.hotspotState)

        if !L10n.buildWatermark.isEmpty {
            Text(L10n.buildWatermark)
                .font(.system(size: 26, weight: .black))
                .foregroundColor(.red.opacity(0.16))
                .multilineTextAlignment(.center)
                .rotationEffect(.degrees(-30))
                .allowsHitTesting(false)
        }
      }
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

// MARK: - Remote dialing

struct DialView: View {
    @ObservedObject var bleManager: BLEManager
    @State private var number = ""
    @State private var countryCode = "+86"

    /// A short list of common country codes (the user can pick one).
    private static let countryCodes: [(String, String)] = [
        ("+86", "CN"), ("+852", "HK"), ("+853", "MO"), ("+886", "TW"),
        ("+1", "US"), ("+81", "JP"), ("+82", "KR"), ("+65", "SG"),
        ("+60", "MY"), ("+44", "UK"), ("+61", "AU"), ("+49", "DE"),
        ("+33", "FR"), ("+7", "RU"), ("+91", "IN")
    ]

    private var suggestions: [MacContact] {
        let q = number.trimmingCharacters(in: .whitespaces)
        return q.isEmpty ? [] : bleManager.contactSuggestions(q)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "phone.arrow.up.right")
                    .foregroundColor(.accentColor)
                Text(L10n.dial).font(.subheadline)
                Spacer()
                if bleManager.contactCount > 0 {
                    Text(L10n.contactsSynced(bleManager.contactCount))
                        .font(.caption2).foregroundColor(.secondary)
                }
            }

            HStack(spacing: 6) {
                // Country-code selector (split from the number field).
                Menu {
                    ForEach(Self.countryCodes, id: \.0) { cc in
                        Button {
                            countryCode = cc.0
                        } label: {
                            Text("\(cc.0)  \(cc.1)")
                        }
                    }
                } label: {
                    HStack(spacing: 3) {
                        Text(countryCode).font(.caption).monospacedDigit()
                        Image(systemName: "chevron.down").font(.system(size: 9))
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(Color.secondary.opacity(0.12))
                    .cornerRadius(6)
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()

                TextField(L10n.dialPlaceholder, text: $number)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { call() }
                    // Free text so a name (including CJK) can be typed for
                    // suggestions; only the digits are dialed. Capped in length.
                    .onChange(of: number) { newValue in
                        let limited = String(newValue.prefix(24))
                        if limited != newValue { number = limited }
                    }

                Button(L10n.dialAction) { call() }
                    .disabled(!bleManager.isConnected || !bleManager.remoteDialEnabled
                              || !number.contains(where: { $0.isNumber }))
            }

            if !bleManager.remoteDialEnabled {
                Text(L10n.dialDisabledHint)
                    .font(.caption2).foregroundColor(.orange)
            }

            if !suggestions.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(suggestions, id: \.id) { c in
                        Button {
                            number = c.number
                        } label: {
                            HStack {
                                Text(c.name.isEmpty ? c.number : c.name).lineLimit(1)
                                Spacer()
                                Text(c.number).foregroundColor(.secondary)
                            }
                            .font(.caption)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(6)
                .background(Color.secondary.opacity(0.08))
                .cornerRadius(6)
            }

            HStack(spacing: 8) {
                Text(L10n.callMethodLabel).font(.caption).foregroundColor(.secondary)
                methodChip(L10n.callMethodPhone, selected: bleManager.callMethod == .phone, enabled: true) {
                    bleManager.setCallMethod(.phone)
                }
                methodChip(L10n.callMethodMac, selected: bleManager.callMethod == .macBluetooth, enabled: true) {
                    bleManager.setCallMethod(.macBluetooth)
                }
            }

            if bleManager.callMethod == .macBluetooth {
                Text(L10n.callMethodMacWarning)
                    .font(.caption2).foregroundColor(.orange)
                    .fixedSize(horizontal: false, vertical: true)
                handsFreeControls()
            } else if !bleManager.isConnected {
                Text(L10n.musicUnavailable).font(.caption2).foregroundColor(.secondary)
            }
        }
        .animation(.easeInOut(duration: 0.15), value: suggestions.count)
    }

    /// Paired Bluetooth-Classic phones + HFP connection status ("Mac 本机").
    private func handsFreeControls() -> some View {
        let paired = bleManager.pairedPhones
        return VStack(alignment: .leading, spacing: 4) {
            if paired.isEmpty {
                Text(L10n.handsFreeHint).font(.caption2).foregroundColor(.orange)
            } else {
                Text(L10n.handsFreeSelectDevice).font(.caption2).foregroundColor(.secondary)
                HStack(spacing: 6) {
                    Menu {
                        ForEach(paired, id: \.self) { name in
                            Button(name) { bleManager.connectHandsFree(phoneName: name) }
                        }
                    } label: {
                        HStack(spacing: 3) {
                            Text(bleManager.handsFree.targetName.isEmpty
                                 ? L10n.handsFreeConnect
                                 : bleManager.handsFree.targetName)
                                .font(.caption).lineLimit(1)
                            Image(systemName: "chevron.down").font(.system(size: 9))
                        }
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Color.secondary.opacity(0.12)).cornerRadius(6)
                    }
                    .menuStyle(.borderlessButton)
                    .menuIndicator(.hidden)
                    .fixedSize()
                }
                Text(bleManager.handsFree.status)
                    .font(.caption2).foregroundColor(.secondary).lineLimit(1)
            }
        }
    }

    private func call() {
        let hadPlus = number.trimmingCharacters(in: .whitespaces).hasPrefix("+")
        var digits = number.filter { $0.isNumber }
        guard !digits.isEmpty else { return }
        digits = String(digits.prefix(15))
        // A leading "+" means the user typed a full international number.
        // The default +86 (China) is dialed as a local number (no international
        // prefix); any other selected country code is prepended.
        let full: String
        if hadPlus {
            full = "+" + digits
        } else if countryCode == "+86" {
            full = digits
        } else {
            full = countryCode + digits
        }
        bleManager.dial(full)
    }

    /// A small selectable chip used for the call-method selector.
    @ViewBuilder
    private func methodChip(_ title: String, selected: Bool, enabled: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(selected ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.10))
                .cornerRadius(8)
        }
        .buttonStyle(.plain)
        .foregroundColor(enabled ? .primary : Color.secondary.opacity(0.45))
        .disabled(!enabled)
        .help(enabled ? "" : L10n.callMethodMacUnavailable)
    }
}
