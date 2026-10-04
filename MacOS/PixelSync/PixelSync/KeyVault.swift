import Foundation
import CryptoKit

/// Provides the AES-GCM key used to encrypt the contacts file.
///
/// Two backends, chosen at runtime by capability (not by build):
/// - [LocalKeyVault] — a random 256-bit key in a local `0600` file. The
///   **guaranteed baseline** for the 2015 Intel Mac (and any machine without a
///   Secure Enclave).
/// - [SecureEnclaveKeyVault] — an advanced path for Apple Silicon / T2: a P256 key
///   held in the **Secure Enclave** derives the AES key via ECDH + HKDF-SHA256.
///   The raw key never leaves the SE.
protocol ContactsKeyVault {
    func key() throws -> SymmetricKey
}

/// Baseline: random AES-256 key stored in a local `0600` file (not the Keychain).
final class LocalKeyVault: ContactsKeyVault {
    private let url: URL
    private var cached: SymmetricKey?

    init(folder: URL) { url = folder.appendingPathComponent("contacts.key") }

    func key() throws -> SymmetricKey {
        if let c = cached { return c }
        if let data = try? Data(contentsOf: url), data.count == 32 {
            let k = SymmetricKey(data: data); cached = k; return k
        }
        let k = SymmetricKey(size: .bits256)
        let data = k.withUnsafeBytes { Data($0) }
        try data.write(to: url, options: [.atomic])
        Self.protect(url)
        cached = k
        return k
    }

    static func protect(_ url: URL) {
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        var u = url
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? u.setResourceValues(rv)
    }
}

/// Advanced: a Secure Enclave P256 key derives the AES key (ECDH + HKDF-SHA256).
/// The SE key blob is stored (`0600`); using it requires the Secure Enclave. Any
/// failure makes the caller fall back to [LocalKeyVault], so the baseline always
/// works.
final class SecureEnclaveKeyVault: ContactsKeyVault {
    private let url: URL
    private var cached: SymmetricKey?

    init(folder: URL) { url = folder.appendingPathComponent("contacts.sekey") }

    func key() throws -> SymmetricKey {
        if let c = cached { return c }
        let priv: SecureEnclave.P256.KeyAgreement.PrivateKey
        if let data = try? Data(contentsOf: url),
           let existing = try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: data) {
            priv = existing
        } else {
            priv = try SecureEnclave.P256.KeyAgreement.PrivateKey()
            try priv.dataRepresentation.write(to: url, options: [.atomic])
            LocalKeyVault.protect(url)
        }
        let shared = try priv.sharedSecretFromKeyAgreement(with: priv.publicKey)
        let key = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data("PixelSync-contacts-v1".utf8),
            sharedInfo: Data(),
            outputByteCount: 32)
        cached = key
        return key
    }
}
