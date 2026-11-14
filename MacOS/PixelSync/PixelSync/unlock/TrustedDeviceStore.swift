//
//  TrustedDeviceStore.swift
//  PixelSync
//
//  Mac-side list of paired phones. Stores only public key material + pairing
//  metadata (never a phone secret, never a password).
//

import Foundation

struct TrustedPhone: Codable, Equatable {
    var id: String            // phone device_id
    var name: String
    var publicKeyB64: String  // X.509 DER, base64
    var sessionKeyB64: String? // phone session key (X.509 DER, base64)
    var pairingId: String
    var enabled: Bool
    var allowManualLock: Bool
    var allowLidWake: Bool
    var createdAt: Date
    var lastAuthAt: Date?
}

final class TrustedDeviceStore {
    private let url: URL
    private(set) var devices: [TrustedPhone] = []

    init(folder: URL) {
        url = folder.appendingPathComponent("trusted_devices.json")
        load()
    }

    func load() {
        guard let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([TrustedPhone].self, from: data) else {
            devices = []
            return
        }
        devices = list
    }

    private func save() {
        do {
            let data = try JSONEncoder().encode(devices)
            try data.write(to: url, options: [.atomic])
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        } catch {
            NSLog("PixelSync: TrustedDeviceStore save failed: \(error)")
        }
    }

    func upsert(_ device: TrustedPhone) {
        if let idx = devices.firstIndex(where: { $0.id == device.id }) {
            devices[idx] = device
        } else {
            devices.append(device)
        }
        save()
    }

    func get(_ id: String) -> TrustedPhone? { devices.first { $0.id == id } }

    func isTrusted(_ id: String) -> Bool { get(id)?.enabled == true }

    func remove(_ id: String) {
        devices.removeAll { $0.id == id }
        save()
    }

    func setEnabled(_ id: String, _ enabled: Bool) {
        guard let idx = devices.firstIndex(where: { $0.id == id }) else { return }
        devices[idx].enabled = enabled
        save()
    }

    /// Remove stale trust records that have the same display name as the
    /// device that just completed session authentication. Reinstalling the
    /// Android app can rotate its phone id and otherwise leave duplicate
    /// "ghost" entries behind on the Mac.
    @discardableResult
    func removeDuplicateNames(keeping deviceId: String) -> Int {
        guard let current = devices.first(where: { $0.id == deviceId }) else { return 0 }
        let name = current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return 0 }

        let before = devices.count
        devices.removeAll {
            $0.id != deviceId &&
                $0.name.trimmingCharacters(in: .whitespacesAndNewlines)
                    .caseInsensitiveCompare(name) == .orderedSame
        }
        let removed = before - devices.count
        if removed > 0 { save() }
        return removed
    }

    func markAuthorized(_ id: String) {
        guard let idx = devices.firstIndex(where: { $0.id == id }) else { return }
        devices[idx].lastAuthAt = Date()
        save()
    }
}
