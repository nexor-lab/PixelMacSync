//
//  SignatureVerifier.swift
//  PixelSync
//
//  ECDSA P-256 + SHA-256 verification of phone signatures, and the Mac's own
//  signing key used to authenticate unlock requests to the phone.
//  Algorithm is public/standard (spec §4/§5); no custom crypto.
//

import Foundation
import CryptoKit

enum SignatureVerifier {

    /// Verify a DER ECDSA signature (Android Keystore, SHA256withECDSA) against
    /// an X.509 SubjectPublicKeyInfo DER public key.
    static func verify(publicKeyDER: Data, data: Data, signatureDER: Data) -> Bool {
        do {
            let pub = try P256.Signing.PublicKey(derRepresentation: publicKeyDER)
            let sig = try P256.Signing.ECDSASignature(derRepresentation: signatureDER)
            return pub.isValidSignature(sig, for: data)
        } catch {
            return false
        }
    }
}

/// The Mac's long-term P-256 signing key. Kept in a `0600` file under
/// Application Support (an Intel Mac has no Secure Enclave); never leaves the Mac.
final class MacSigningKey {
    private let url: URL
    private var cached: P256.Signing.PrivateKey?

    init(folder: URL) {
        url = folder.appendingPathComponent("unlock_mac_key.bin")
    }

    var publicKeyDER: Data? {
        (try? privateKey())?.publicKey.derRepresentation
    }

    var publicKeyB64: String? {
        publicKeyDER.map { $0.base64EncodedString() }
    }

    func sign(_ data: Data) -> Data? {
        guard let key = try? privateKey() else { return nil }
        return try? key.signature(for: data).derRepresentation
    }

    private func privateKey() throws -> P256.Signing.PrivateKey {
        if let c = cached { return c }
        if let data = try? Data(contentsOf: url), let k = try? P256.Signing.PrivateKey(rawRepresentation: data) {
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
