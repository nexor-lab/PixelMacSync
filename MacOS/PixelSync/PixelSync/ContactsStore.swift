import Foundation
import CryptoKit

/// Stores synced contacts **encrypted at rest** with a local AES-GCM key.
///
/// The 256-bit key is kept in a `0600` file next to the data — **never** in the
/// Keychain, so it can't sync to iCloud and can't interfere with the user's saved
/// passwords. The plaintext exists only in memory, to search and to dial; nothing
/// is written unencrypted and no contact data is logged. (ADR-025)
final class ContactsStore {

    static let shared = ContactsStore()

    private var vault: ContactsKeyVault?
    private(set) var contacts: [MacContact] = []

    private var folderURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let folder = dir.appendingPathComponent("PixelSync", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
    private var fileURL: URL { folderURL.appendingPathComponent("contacts.enc") }

    init() { load() }

    // MARK: - Key vault
    // Baseline (2015 Intel): a local `0600` key file. Advanced (Apple Silicon /
    // T2): a Secure Enclave-backed key. Chosen by capability; Intel always works.

    private func loadKey() -> SymmetricKey? {
        if let v = vault { return try? v.key() }
        if Platform.secureEnclaveAvailable {
            let se = SecureEnclaveKeyVault(folder: folderURL)
            if let k = try? se.key() {
                NSLog("MacSync: contatti: chiave su Secure Enclave")
                vault = se
                return k
            }
            NSLog("MacSync: contatti: Secure Enclave non disponibile; uso chiave locale")
        }
        let local = LocalKeyVault(folder: folderURL)
        vault = local
        return try? local.key()
    }

    // MARK: - Persistence

    func load() {
        guard let encrypted = try? Data(contentsOf: fileURL), !encrypted.isEmpty else { return }
        guard let key = loadKey() else { return }
        do {
            let box = try AES.GCM.SealedBox(combined: encrypted)
            let plain = try AES.GCM.open(box, using: key)
            contacts = (try? JSONDecoder().decode([MacContact].self, from: plain)) ?? []
            if !contacts.isEmpty { NSLog("MacSync: contatti caricati: \(contacts.count)") }
        } catch {
            NSLog("MacSync: contatti: decifratura fallita: \(error)")
        }
    }

    private func persist() {
        guard let plain = try? JSONEncoder().encode(contacts) else { return }
        guard let key = loadKey() else { return }
        do {
            let sealed = try AES.GCM.seal(plain, using: key)
            if let combined = sealed.combined {
                try combined.write(to: fileURL, options: .atomic)
            }
        } catch {
            NSLog("MacSync: contatti: cifratura fallita: \(error)")
        }
    }

    func replaceAll(_ list: [MacContact]) {
        contacts = list
        persist()
    }

    func remove(id: String) {
        contacts.removeAll { $0.id == id }
        persist()
    }

    /// Prefix (number) / substring (name) search for the dialer suggestions.
    func search(_ query: String, limit: Int = 6) -> [MacContact] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return [] }
        let lower = q.lowercased()
        return contacts.filter {
            $0.number.contains(q) || $0.name.lowercased().contains(lower)
        }.prefix(limit).map { $0 }
    }
}
