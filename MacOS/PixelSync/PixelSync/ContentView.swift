import SwiftUI
import AppKit

private enum PopoverSection: String, CaseIterable {
    case devices
    case media
    case settings

    var title: String {
        switch self {
        case .devices: return L10n.t("Device", "设备")
        case .media: return L10n.t("Media", "媒体")
        case .settings: return L10n.t("Settings", "设置")
        }
    }
}

struct ContentView: View {
    @ObservedObject var bleManager: BLEManager
    @State private var selectedSection: PopoverSection = .devices
    
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
            VStack(spacing: 0) {
                header
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
                    .padding(.bottom, 12)

                if bleManager.connectionState == .needsUnpair {
                    Text(L10n.unpairHint)
                        .font(.caption2)
                        .foregroundColor(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                }

                if bleManager.callPhase != .none {
                    callPanel
                        .padding(.horizontal, 16)
                        .padding(.bottom, 10)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }

                if bleManager.pamTrustNeedsSync {
                    pamWarning
                        .padding(.horizontal, 16)
                        .padding(.bottom, 10)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }

                Divider()

                Picker("", selection: $selectedSection) {
                    ForEach(PopoverSection.allCases, id: \.self) { section in
                        Text(section.title).tag(section)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

                ScrollView {
                    selectedContent
                        .id(selectedSection)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 16)
                        .transition(.opacity)
                }
                .frame(maxHeight: .infinity)
            }
            .frame(width: 340, height: 340)
            .animation(.easeInOut(duration: 0.22), value: selectedSection)
            .animation(.easeInOut(duration: 0.20), value: bleManager.callPhase)
            .animation(.easeInOut(duration: 0.20), value: bleManager.isConnected)
            .animation(.easeInOut(duration: 0.20), value: bleManager.hotspotState)

        }
    }

    private var statusColor: Color {
        if bleManager.isConnected { return .green }
        if bleManager.isSwitchedOn { return .orange }
        return .red
    }

    private var header: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle()
                    .fill(statusColor.opacity(0.14))
                    .frame(width: 38, height: 38)
                Image(systemName: bleManager.isConnected ? "iphone" : "antenna.radiowaves.left.and.right")
                    .foregroundColor(statusColor)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(
                    bleManager.isConnected
                        ? (bleManager.selectedPhoneName ?? bleManager.deviceName)
                        : L10n.appName
                )
                    .font(.headline)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Circle().fill(statusColor).frame(width: 6, height: 6)
                    Text(bleManager.isConnected ? L10n.state(.connected) : bleManager.connectionStatus)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 6)

            if bleManager.isConnected {
                HStack(spacing: 8) {
                    HStack(spacing: 3) {
                        Text(bleManager.networkType)
                            .font(.system(size: 9, weight: .bold))
                            .foregroundColor(.secondary)
                        Image(systemName: bleManager.isWifi ? "wifi" : "cellularbars")
                            .font(.caption)
                    }
                    HStack(spacing: 3) {
                        Text(bleManager.batteryLevel)
                            .font(.caption2)
                            .monospacedDigit()
                        Image(systemName: batteryIconName)
                            .symbolRenderingMode(.hierarchical)
                            .foregroundColor(
                                bleManager.isCharging
                                    ? .green
                                    : levelColor(for: bleManager.batteryLevel)
                            )
                    }
                }
            } else {
                Button {
                    bleManager.reconnectNow(reason: "header")
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.borderless)
                .disabled(!bleManager.isSwitchedOn)
                .help(L10n.reconnectTooltip)
            }
        }
    }

    private var callPanel: some View {
        VStack(alignment: .leading, spacing: 5) {
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
                    .font(.title3)
                    .fontWeight(.semibold)
                    .lineLimit(1)
            }
        }
        .padding(12)
        .background(Color.accentColor.opacity(0.08))
        .cornerRadius(12)
    }

    private var pamWarning: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.shield.fill")
                .foregroundColor(.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.t("PAM key needs sync", "PAM 公钥需要同步"))
                    .font(.caption)
                    .fontWeight(.semibold)
                Text(L10n.t("Phone unlock is blocked until the key is synced.",
                            "同步公钥前，手机解锁会被阻止。"))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            Spacer()
            Button(L10n.t("Sync", "同步")) {
                bleManager.syncPamTrust { _, _ in }
            }
            .font(.caption)
            .buttonStyle(.bordered)
        }
        .padding(10)
        .background(Color.orange.opacity(0.09))
        .cornerRadius(10)
    }

    @ViewBuilder
    private var selectedContent: some View {
        switch selectedSection {
        case .devices:
            VStack(spacing: 14) {
                if !bleManager.isConnected {
                    NearbyPhonesCard(bleManager: bleManager)
                }
                if bleManager.isIPhone {
                    UnavailableOnIOS(title: L10n.remoteHotspot, icon: "personalhotspot")
                    UnavailableOnIOS(title: L10n.t("Dialing", "拨号"), icon: "phone")
                } else {
                    HotspotControlView(bleManager: bleManager)
                    Divider()
                    CollapsibleDial(bleManager: bleManager)
                }
                UnlockQuickCard(bleManager: bleManager) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        selectedSection = .settings
                    }
                }
            }
        case .media:
            if bleManager.isIPhone {
                UnavailableOnIOS(title: L10n.t("Music", "音乐"), icon: "music.note")
            } else {
                MusicControlView(bleManager: bleManager)
            }
        case .settings:
            UnlockPanel(bleManager: bleManager)
        }
    }
    
    func levelColor(for levelStr: String) -> Color {
        let level = Int(levelStr.replacingOccurrences(of: "%", with: "")) ?? 50
        if level <= 20 { return .red }
        return .primary
    }
}

private struct HotspotControlView: View {
    @ObservedObject var bleManager: BLEManager

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "personalhotspot")
                .foregroundColor(bleManager.hotspotState == .on ? .accentColor : .secondary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.remoteHotspot)
                    .font(.subheadline)
                if bleManager.isConnected {
                    Text(bleManager.hotspotStateText)
                        .font(.caption2)
                        .foregroundColor(bleManager.hotspotState == .error ? .red : .secondary)
                }
            }
            Spacer()
            Button(bleManager.hotspotState == .on ? L10n.hotspotTurnOff : L10n.hotspotTurnOn) {
                bleManager.setRemoteHotspot(enable: bleManager.hotspotState != .on)
            }
            .disabled(!bleManager.isConnected || bleManager.hotspotBusy)
        }
        .padding(12)
        .background(Color.secondary.opacity(0.07))
        .cornerRadius(12)
    }
}

private struct UnlockQuickCard: View {
    @ObservedObject var bleManager: BLEManager
    let onManage: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "faceid")
                .foregroundColor(bleManager.pamTrustNeedsSync ? .orange : .accentColor)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.trustedDevices)
                    .font(.subheadline)
                if bleManager.trustedPhones.isEmpty {
                    Text(L10n.noTrustedDevice)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                } else if bleManager.pamTrustNeedsSync {
                    Text(L10n.t("PAM key needs sync", "PAM 公钥需要同步"))
                        .font(.caption2)
                        .foregroundColor(.orange)
                } else {
                    Text(L10n.t("Ready for phone unlock", "手机解锁已就绪"))
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            Spacer()
            if bleManager.trustedPhones.isEmpty {
                Button(L10n.addDevice) {
                    bleManager.unlockManager.beginPairing()
                }
                .disabled(!bleManager.isLinkConnected)
            } else if bleManager.pamTrustNeedsSync {
                Button(L10n.t("Sync", "同步")) {
                    bleManager.syncPamTrust { _, _ in }
                }
                .buttonStyle(.borderedProminent)
            } else {
                Button(L10n.t("Manage", "管理"), action: onManage)
                    .buttonStyle(.bordered)
            }
        }
        .padding(12)
        .background(Color.secondary.opacity(0.07))
        .cornerRadius(12)
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
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "music.note")
                    .foregroundColor(bleManager.music.isPlaying ? .accentColor : .secondary)
                Text(L10n.music).font(.subheadline)
                Spacer()
                if !bleManager.isConnected {
                    Text(L10n.musicUnavailable).font(.caption).foregroundColor(.secondary)
                } else if bleManager.music.stopped || !bleManager.music.hasTrack {
                    Text(L10n.musicNoTrack).font(.caption).foregroundColor(.secondary)
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
        .padding(12)
        .background(Color.secondary.opacity(0.07))
        .cornerRadius(12)
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

// MARK: - Phone biometric unlock settings

struct UnlockPanel: View {
    @ObservedObject var bleManager: BLEManager
    @State private var awayDelay: Double = 20
    @State private var pamSyncing = false
    @State private var pamSyncMessage: String?

    init(bleManager: BLEManager) {
        self.bleManager = bleManager
        _awayDelay = State(initialValue: bleManager.unlockManager.awayLockDelay)
    }


    @ViewBuilder
    private func trustedRow(_ device: TrustedPhone) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Image(systemName: "iphone").font(.caption).foregroundColor(.secondary)
                Text(device.name).font(.caption).lineLimit(1).truncationMode(.middle)
                Spacer()
                Text(device.enabled ? L10n.deviceAuthorized : L10n.deviceRevoked)
                    .font(.caption2)
                    .foregroundColor(device.enabled ? .green : .secondary)
                Button(L10n.revoke) { bleManager.unlockManager.revoke(device.id) }
                    .font(.caption2)
            }
            Text(trustedDeviceDetail(device))
                .font(.caption2)
                .foregroundColor(.secondary)
                .lineLimit(1)
        }
    }

    private func trustedDeviceDetail(_ device: TrustedPhone) -> String {
        let suffix = String(device.id.suffix(6))
        let lastUsed: String
        if let date = device.lastAuthAt {
            lastUsed = L10n.t(
                "Last unlock \(Self.trustedDateFormatter.string(from: date))",
                "最近解锁 \(Self.trustedDateFormatter.string(from: date))"
            )
        } else {
            lastUsed = L10n.t("Never unlocked", "尚未用于解锁")
        }
        return "\(lastUsed) · ID …\(suffix)"
    }

    private static let trustedDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy/MM/dd HH:mm"
        return formatter
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "faceid")
                    .foregroundColor(bleManager.trustedPhones.isEmpty ? .secondary : .accentColor)
                Text(L10n.trustedDevices).font(.subheadline)
                Spacer()
                Button(L10n.addDevice) { bleManager.unlockManager.beginPairing() }
                    .disabled(!bleManager.isLinkConnected)
            }

            if bleManager.trustedPhones.isEmpty {
                Text(L10n.noTrustedDevice)
                    .font(.caption).foregroundColor(.secondary)
            } else if bleManager.trustedPhones.count == 1 {
                trustedRow(bleManager.trustedPhones[0])
            } else {
                if duplicateTrustedNames {
                    Label(
                        L10n.t(
                            "Duplicate names: use the last-unlock time and ID suffix to revoke old records.",
                            "存在同名设备：请根据最近解锁时间和标识后缀清理旧记录。"
                        ),
                        systemImage: "exclamationmark.triangle"
                    )
                    .font(.caption2)
                    .foregroundColor(.orange)
                }
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(sortedTrustedPhones, id: \.id) { trustedRow($0) }
                }
            }

            HStack(spacing: 8) {
                Image(systemName: "lock.rotation")
                    .font(.caption).foregroundColor(.secondary)
                Text(L10n.awayLockTitle).font(.caption)
                Spacer()
                Picker("", selection: $awayDelay) {
                    Text(L10n.off).tag(0.0)
                    Text(L10n.seconds(5)).tag(5.0)
                    Text(L10n.seconds(10)).tag(10.0)
                    Text(L10n.seconds(20)).tag(20.0)
                    Text(L10n.seconds(30)).tag(30.0)
                    Text(L10n.seconds(60)).tag(60.0)
                }
                .labelsHidden()
                .pickerStyle(.menu)
                .frame(width: 90)
                .onChange(of: awayDelay) { v in
                    bleManager.unlockManager.setAwayLockDelay(v)
                }
            }
            .padding(.top, 4)

            if bleManager.unlockPairingActive {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text(L10n.pairWaitingPhone).font(.caption2).foregroundColor(.secondary)
                }
                .padding(.vertical, 4)
            }

            if !bleManager.trustedPhones.isEmpty {
                if bleManager.pamTrustNeedsSync {
                    Button(pamSyncing
                           ? L10n.t("Syncing…", "同步中…")
                           : L10n.t("Sync PAM key now", "立即同步 PAM 公钥")) {
                        pamSyncing = true
                        pamSyncMessage = nil
                        bleManager.syncPamTrust { ok, message in
                            pamSyncing = false
                            pamSyncMessage = ok ? message
                                : L10n.t("Sync failed: ", "同步失败：") + message
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(pamSyncing)
                }

                if let message = pamSyncMessage {
                    Text(message)
                        .font(.caption2)
                        .foregroundColor(message.hasPrefix(L10n.t("Sync failed", "同步失败")) ? .red : .secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if !bleManager.trustedPhones.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L10n.t("Advanced", "高级"))
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Button(L10n.testUnlock) {
                        bleManager.unlockManager.triggerUnlock(reason: "manual")
                    }
                    .font(.caption)
                    .buttonStyle(.link)
                    .frame(maxWidth: .infinity, alignment: .leading)

                    if !bleManager.pamTrustNeedsSync {
                        Button(pamSyncing
                               ? L10n.t("Syncing…", "同步中…")
                               : L10n.t("Sync PAM key", "同步 PAM 公钥")) {
                            pamSyncing = true
                            pamSyncMessage = nil
                            bleManager.syncPamTrust { ok, message in
                                pamSyncing = false
                                pamSyncMessage = ok ? message
                                    : L10n.t("Sync failed: ", "同步失败：") + message
                            }
                        }
                        .font(.caption)
                        .buttonStyle(.link)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .disabled(pamSyncing)
                    }

                    if !bleManager.unlockPluginInstalled {
                        Text(L10n.t("Unlock helper not installed (password still works).",
                                    "解锁组件未安装（密码仍可用）。"))
                            .font(.caption2)
                            .foregroundColor(.orange)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.top, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var sortedTrustedPhones: [TrustedPhone] {
        bleManager.trustedPhones.sorted {
            ($0.lastAuthAt ?? .distantPast) > ($1.lastAuthAt ?? .distantPast)
        }
    }

    private var duplicateTrustedNames: Bool {
        Dictionary(grouping: bleManager.trustedPhones, by: \.name)
            .values
            .contains { $0.count > 1 }
    }
}

// MARK: - iOS unsupported section (collapsed, not hidden)

struct UnavailableOnIOS: View {
    let title: String
    let icon: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: icon).foregroundColor(.secondary)
                Text(title).font(.subheadline)
                Spacer()
                Text(L10n.t("Not on iOS", "iOS 不支持"))
                    .font(.caption2).foregroundColor(.orange)
            }
            Text(L10n.t("This feature is not available when the connected phone is an iPhone (iOS system limitation).",
                        "连接 iPhone 时该功能不可用（iOS 系统限制）。"))
                .font(.caption2)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}


struct CollapsibleDial: View {
    @ObservedObject var bleManager: BLEManager

    var body: some View {
        DialView(bleManager: bleManager)
    }
}


struct NearbyPhonesCard: View {
    @ObservedObject var bleManager: BLEManager

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "dot.radiowaves.left.and.right").foregroundColor(.accentColor)
                Text(L10n.t("Nearby phones", "附近的手机")).font(.subheadline)
                Spacer()
                Button(L10n.t("Refresh", "刷新")) { bleManager.rescan() }
                    .font(.caption2).buttonStyle(.link)
            }
            if bleManager.discoveredPhones.isEmpty {
                Text(L10n.t("Scanning…", "正在扫描…")).font(.caption).foregroundColor(.secondary)
            } else {
                ForEach(bleManager.discoveredPhones) { phone in
                    HStack(spacing: 6) {
                        Image(systemName: "iphone").font(.caption).foregroundColor(.secondary)
                        Text(phone.name).font(.caption).lineLimit(1).truncationMode(.middle)
                        Text("\(phone.rssi) dBm").font(.caption2).foregroundColor(.secondary)
                        Spacer()
                        if bleManager.selectedPhoneId == phone.id && bleManager.connectionState != .connected {
                            ProgressView().controlSize(.small)
                        } else {
                            Button(L10n.t("Connect", "连接")) { bleManager.selectPhone(phone) }
                                .font(.caption2)
                        }
                    }
                }
            }
            if bleManager.needingRepair && bleManager.isLinkConnected {
                Button(L10n.t("Pair again", "重新配对")) { bleManager.unlockManager.beginPairing(force: true) }
                    .font(.caption2).buttonStyle(.link)
            }
        }
    }
}
