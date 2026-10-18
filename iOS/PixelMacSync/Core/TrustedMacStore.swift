//
//  TrustedMacStore.swift
//  PixelMacSync iOS
//
//  Stores the phone's random device_id and the Macs it has paired with (public
//  material only; the phone's private key stays in the Secure Enclave).
//

import Foundation
import Security

struct TrustedMac: Codable, Equatable {
    var macId: String
    var name: String
    var publicKeyB64: String   // Mac's P-256 public key, DER base64
    var pairingId: String
    var createdAt: Date
    var lastAuthAt: Date?
}

final class TrustedMacStore {
    static let shared = TrustedMacStore()

    private let prefs = UserDefaults.standard
    private let kDeviceId = "px.ios.deviceId"
    private let kMacs = "px.ios.trustedMacs"

    var deviceId: String {
        if let v = prefs.string(forKey: kDeviceId) { return v }
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        let id = bytes.map { String(format: "%02x", $0) }.joined()
        prefs.set(id, forKey: kDeviceId)
        return id
    }

    func all() -> [TrustedMac] {
        guard let data = prefs.data(forKey: kMacs),
              let list = try? JSONDecoder().decode([TrustedMac].self, from: data) else { return [] }
        return list
    }

    func get(_ macId: String) -> TrustedMac? { all().first { $0.macId == macId } }
    func isTrusted(_ macId: String) -> Bool { get(macId) != nil }

    func upsert(_ mac: TrustedMac) {
        var list = all().filter { $0.macId != mac.macId }
        list.append(mac)
        save(list)
    }

    func remove(_ macId: String) {
        save(all().filter { $0.macId != macId })
    }

    func markAuthorized(_ macId: String) {
        var list = all()
        if let i = list.firstIndex(where: { $0.macId == macId }) {
            list[i].lastAuthAt = Date()
            save(list)
        }
    }

    private func save(_ list: [TrustedMac]) {
        if let data = try? JSONEncoder().encode(list) {
            prefs.set(data, forKey: kMacs)
        }
    }
}
