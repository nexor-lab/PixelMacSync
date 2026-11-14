//
//  MacSessionKey.swift
//  PixelSync
//
//  Non-biometric P-256 key used only to prove this Mac's identity during the
//  BLE session handshake (basic pairing). It is SEPARATE from the unlock request
//  signing key. Never leaves the Mac.
//

import Foundation
import CryptoKit

final class MacSessionKey {
    private let url: URL
    private var cached: P256.Signing.PrivateKey?

    init(folder: URL) { url = folder.appendingPathComponent("session_key.bin") }

    var publicKeyDER: Data? { (try? key())?.publicKey.derRepresentation }
    var publicKeyB64: String? { publicKeyDER.map { $0.base64EncodedString() } }

    func sign(_ data: Data) -> Data? {
        guard let k = try? key() else { return nil }
        return try? k.signature(for: data).derRepresentation
    }

    private func key() throws -> P256.Signing.PrivateKey {
        if let c = cached { return c }
        if let data = try? Data(contentsOf: url),
           let k = try? P256.Signing.PrivateKey(rawRepresentation: data) {
            cached = k
            return k
        }
        let k = P256.Signing.PrivateKey()
        try k.rawRepresentation.write(to: url, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        cached = k
        return k
    }
}
