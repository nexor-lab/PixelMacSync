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

    func markAuthorized(_ id: String) {
        guard let idx = devices.firstIndex(where: { $0.id == id }) else { return }
        devices[idx].lastAuthAt = Date()
        save()
    }
}
