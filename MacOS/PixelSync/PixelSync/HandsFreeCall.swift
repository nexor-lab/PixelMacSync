import Foundation
import IOBluetooth
import Combine

/// Uses the Mac as a Bluetooth **Hands-Free unit** (`IOBluetoothHandsFreeDevice`),
/// so a phone call's audio can be routed to the Mac's own speaker/microphone
/// ("Mac 本机"). The phone must be paired over **Bluetooth Classic** (HFP).
///
/// Built on Apple's public (if undocumented) IOBluetooth HandsFree classes:
/// `connect()` establishes the SLC, `acceptCall()/endCall()/dialNumber()` control
/// the call and `transferAudioToComputer()` moves SCO audio to the Mac. The SCO
/// audio itself is handled by the framework (VoiceProcessingIO).
final class HandsFreeCall: NSObject, ObservableObject, IOBluetoothHandsFreeDeviceDelegate {

    @Published var connected = false
    @Published var scoOpen = false
    @Published var callActive = false
    @Published var status = ""

    private var hf: IOBluetoothHandsFreeDevice?
    private(set) var targetName: String

    /// Names of the currently paired Bluetooth-Classic devices (for the picker).
    static func pairedPhoneNames() -> [String] {
        guard let devs = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] else { return [] }
        return devs.map { $0.name ?? $0.addressString ?? "?" }
    }

    init(targetName: String) {
        self.targetName = targetName
        super.init()
        // Do not connect here: the HFP unit is only started while the "Mac 本机"
        // call method is selected (see BLEManager).
    }

    func configure(name: String) {
        guard name != targetName else { return }
        targetName = name
        UserDefaults.standard.set(name, forKey: "hfPhoneName")
        stop()
        if !name.isEmpty { start() }
    }

    /// Auto-selects a paired Classic device whose name matches the phone's
    /// Bluetooth name (reported over BLE). No-op if a target is already chosen.
    func autoDetect(fromBLE name: String) {
        guard targetName.isEmpty, !name.isEmpty else { return }
        let paired = Self.pairedPhoneNames()
        if let match = paired.first(where: {
            $0.localizedCaseInsensitiveContains(name) || name.localizedCaseInsensitiveContains($0)
        }) {
            NSLog("MacSync: HFP auto-select '\(match)' (phone BT name '\(name)')")
            configure(name: match)
        }
    }

    private func findPhone() -> IOBluetoothDevice? {
        guard !targetName.isEmpty,
              let devs = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] else { return nil }
        return devs.first { ($0.name ?? "").localizedCaseInsensitiveContains(targetName) }
    }

    func start() {
        guard hf == nil else { return }
        guard let phone = findPhone() else {
            status = "Phone not paired over Bluetooth (HFP). Pair it in System Settings first."
            NSLog("MacSync: HFP: no paired phone matching '\(targetName)'")
            return
        }
        let device = IOBluetoothHandsFreeDevice(device: phone, delegate: self)
        hf = device
        status = "Connecting to \(phone.name ?? "phone")…"
        device?.connect()
        NSLog("MacSync: HFP connecting to \(phone.name ?? "?")")
    }

    func stop() {
        hf?.disconnect()
        hf = nil
        connected = false
        scoOpen = false
        callActive = false
    }

    // MARK: - Call control (used when Call via = "Mac 本机")

    func answer() { hf?.acceptCall(); routeToComputer() }
    func end() { hf?.endCall() }
    func dial(_ number: String) { hf?.dialNumber(number); routeToComputer() }
    func setMuted(_ muted: Bool) { hf?.isInputMuted = muted }
    func routeToComputer() { hf?.transferAudioToComputer() }
    func routeToPhone() { hf?.transferAudioToPhone() }

    // MARK: - IOBluetoothHandsFreeDelegate

    func handsFree(_ device: IOBluetoothHandsFree!, connected status: NSNumber!) {
        let ok = (status?.intValue ?? -1) == 0
        DispatchQueue.main.async { self.connected = ok; self.status = ok ? "Connected" : "Connection failed" }
        NSLog("MacSync: HFP connected status=\(status?.intValue ?? -999)")
    }

    func handsFree(_ device: IOBluetoothHandsFree!, disconnected status: NSNumber!) {
        DispatchQueue.main.async { self.connected = false; self.scoOpen = false; self.status = "Disconnected" }
    }

    func handsFree(_ device: IOBluetoothHandsFree!, scoConnectionOpened status: NSNumber!) {
        DispatchQueue.main.async { self.scoOpen = true }
        // Route the call audio through the computer as soon as SCO is up.
        (device as? IOBluetoothHandsFreeDevice)?.transferAudioToComputer()
        NSLog("MacSync: HFP SCO opened status=\(status?.intValue ?? -999)")
    }

    func handsFree(_ device: IOBluetoothHandsFree!, scoConnectionClosed status: NSNumber!) {
        DispatchQueue.main.async { self.scoOpen = false }
    }

    // MARK: - IOBluetoothHandsFreeDeviceDelegate

    func handsFree(_ device: IOBluetoothHandsFreeDevice!, isCallActive status: NSNumber!) {
        let active = (status?.intValue ?? 0) == 1
        DispatchQueue.main.async { self.callActive = active }
        // Keep the call audio on the computer while the call is active (avoids
        // the phone playing it on its own speaker as well).
        if active { device.transferAudioToComputer() }
    }

    func handsFree(_ device: IOBluetoothHandsFreeDevice!, incomingCallFrom number: String!) {
        NSLog("MacSync: HFP incoming call")
    }
}
