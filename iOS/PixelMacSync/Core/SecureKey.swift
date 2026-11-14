//
//  SecureKey.swift
//  PixelMacSync iOS
//
//  P-256 signing key. On a real device it lives in the Secure Enclave and is
//  usable only after Face ID / Touch ID (biometryCurrentSet). On the simulator
//  (no Secure Enclave) it falls back to a software key so the app still builds.
//

import Foundation
import Security
import LocalAuthentication
import CryptoKit

final class SecureKey {
    static let shared = SecureKey()

    private let tag = "it.luigi.pixelsync.ios.unlockkey".data(using: .utf8)!
    private let sessionTag = "it.luigi.pixelsync.ios.sessionkey".data(using: .utf8)!
    private var cached: SecKey?
    private var cachedSession: SecKey?

    var isSecureEnclave: Bool { SecureEnclave.isAvailable }

    // 26-byte SubjectPublicKeyInfo header for id-ecPublicKey / prime256v1.
    private let spkiHeader: [UInt8] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
    ]

    func key() -> SecKey? {
        if let c = cached { return c }
        if let existing = load() { cached = existing; return existing }
        let created = create()
        cached = created
        return created
    }

    func delete() {
        let q: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
        ]
        SecItemDelete(q as CFDictionary)
        cached = nil
    }

    /// DER SubjectPublicKeyInfo (what the Mac expects).
    func publicKeyDER() -> Data? {
        guard let key = key() else { return nil }
        return der(for: key)
    }

    func publicKeyB64() -> String? {
        publicKeyDER().map { $0.base64EncodedString() }
    }

    // MARK: - Session key (non-biometric; usable while backgrounded)

    func sessionKey() -> SecKey? {
        if let c = cachedSession { return c }
        if let existing = loadSession() { cachedSession = existing; return existing }
        let created = createSession()
        cachedSession = created
        return created
    }

    func sessionPublicKeyDER() -> Data? {
        guard let key = sessionKey() else { return nil }
        return der(for: key)
    }

    func sessionPublicKeyB64() -> String? {
        sessionPublicKeyDER().map { $0.base64EncodedString() }
    }

    func signSession(_ data: Data) -> Data? {
        guard let key = sessionKey() else { return nil }
        var err: Unmanaged<CFError>?
        let algo: SecKeyAlgorithm = .ecdsaSignatureMessageX962SHA256
        guard SecKeyIsAlgorithmSupported(key, .sign, algo) else { return nil }
        return SecKeyCreateSignature(key, algo, data as CFData, &err) as Data?
    }

    private func der(for key: SecKey) -> Data? {
        var err: Unmanaged<CFError>?
        guard let pub = SecKeyCopyPublicKey(key),
              let raw = SecKeyCopyExternalRepresentation(pub, &err) as Data? else { return nil }
        var out = Data(spkiHeader)
        out.append(raw)
        return out
    }

    private func loadSession() -> SecKey? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: sessionTag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let value = item, CFGetTypeID(value) == SecKeyGetTypeID() else { return nil }
        return (value as! SecKey)
    }

    private func createSession() -> SecKey? {
        if SecureEnclave.isAvailable {
            var acErr: Unmanaged<CFError>?
            if let access = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                [.privateKeyUsage], &acErr) {
                let attrs: [String: Any] = [
                    kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
                    kSecAttrKeySizeInBits as String: 256,
                    kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
                    kSecPrivateKeyAttrs as String: [
                        kSecAttrIsPermanent as String: true,
                        kSecAttrApplicationTag as String: sessionTag,
                        kSecAttrAccessControl as String: access,
                    ],
                ]
                var err: Unmanaged<CFError>?
                if let k = SecKeyCreateRandomKey(attrs as CFDictionary, &err) { return k }
            }
        }
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: sessionTag,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ],
        ]
        var err: Unmanaged<CFError>?
        return SecKeyCreateRandomKey(attrs as CFDictionary, &err)
    }

    /// DER ECDSA signature over `data` (SHA-256). Triggers Face ID on device.
    func sign(_ data: Data) -> Data? {
        guard let key = key() else { return nil }
        var err: Unmanaged<CFError>?
        let algo: SecKeyAlgorithm = .ecdsaSignatureMessageX962SHA256
        guard SecKeyIsAlgorithmSupported(key, .sign, algo) else { return nil }
        return SecKeyCreateSignature(key, algo, data as CFData, &err) as Data?
    }

    /// Face ID gate (used for pairing confirmation).
    func authenticateBiometrics(reason: String) async -> Bool {
        let ctx = LAContext()
        ctx.localizedReason = reason
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err) else {
            return false
        }
        return await withCheckedContinuation { cont in
            ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { ok, _ in
                cont.resume(returning: ok)
            }
        }
    }

    // MARK: - Private

    private func load() -> SecKey? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let value = item, CFGetTypeID(value) == SecKeyGetTypeID() else { return nil }
        return (value as! SecKey)
    }

    private func create() -> SecKey? {
        if SecureEnclave.isAvailable {
            if let k = createSecureEnclave() { return k }
        }
        return createSoftware()
    }

    private func createSecureEnclave() -> SecKey? {
        var acErr: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            [.privateKeyUsage, .biometryCurrentSet], &acErr) else { return nil }

        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecAttrAccessControl as String: access,
            ],
        ]
        var err: Unmanaged<CFError>?
        return SecKeyCreateRandomKey(attrs as CFDictionary, &err)
    }

    private func createSoftware() -> SecKey? {
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            ],
        ]
        var err: Unmanaged<CFError>?
        return SecKeyCreateRandomKey(attrs as CFDictionary, &err)
    }
}
