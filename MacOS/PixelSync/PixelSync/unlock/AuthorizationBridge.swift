//
//  AuthorizationBridge.swift
//  PixelSync
//
//  Minimal, password-free handshake between the menu-bar app and the
//  SecurityAgent Authorization Plugin. The app performs BLE + signature
//  verification; when it succeeds it leaves a short-lived, single-use grant
//  that the plugin consumes to return ALLOW (no password ever involved).
//
//  No Mac password, no private key, no biometric data is ever written here —
//  only a session id + expiry.
//

import Foundation
import Security

final class AuthorizationBridge {

    static let shared = AuthorizationBridge()

    /// User-writable, plugin-readable handshake directory.
    let dir: URL

    private init() {
        dir = URL(fileURLWithPath: "/Users/Shared/PixelSync", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        // Never inherit a stale grant from a previous run.
        clearGrant()
    }

    private var grantURL: URL { dir.appendingPathComponent("unlock_grant") }

    /// Called after a phone signature verified. Grants a single unlock window.
    ///
    /// v2 includes the phone's signature over the unlock transcript. The PAM
    /// module verifies it against a **root-owned** public key, so a user-level
    /// process cannot forge a grant by simply writing this file.
    func grant(sessionIdB64: String, macId: String, expiresAtMs: Int64,
               phoneId: String, transcriptB64: String, signatureB64: String) {
        let user = NSUserName()
        let body = [
            "v2",
            sessionIdB64,
            macId,
            String(expiresAtMs),
            user,
            phoneId,
            transcriptB64,
            signatureB64,
        ].joined(separator: "\n") + "\n"
        try? body.data(using: .utf8)?.write(to: grantURL, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: grantURL.path)
    }

    func clearGrant() {
        try? FileManager.default.removeItem(at: grantURL)
    }

    /// True when the root-owned PAM trust file matches this phone's current
    /// unlock public key. A mismatch means phone unlock will fall back to the
    /// password until `sudo MacOS/pam/install.sh` is run again.
    func isProvisioned(phoneId: String, publicKeyB64: String) -> Bool {
        let path = "/usr/local/etc/pixelsync/trusted_phones/\(phoneId).pub"
        guard let stored = try? String(contentsOfFile: path, encoding: .utf8) else { return false }
        return stored.trimmingCharacters(in: .whitespacesAndNewlines)
            == publicKeyB64.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Updates the root-owned PAM trust keys from the current trust store.
    /// Runs one admin-authorized shell step; only the key files are touched.
    func syncPamTrust(phones: [TrustedPhone],
                      completion: @escaping (Bool, String) -> Void) {
        let entries: [(String, String)] = phones.filter { $0.enabled }.compactMap { phone in
            let id = phone.id
            guard id.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil else { return nil }
            let key = phone.publicKeyB64.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !key.isEmpty, !key.contains("'") else { return nil }
            return (id, key)
        }
        guard !entries.isEmpty else {
            completion(false, L10n.t("No trusted phone key", "没有可同步的手机公钥"))
            return
        }

        var shell = "set -e; "
        shell += "D=/usr/local/etc/pixelsync/trusted_phones; "
        shell += "mkdir -p \"$D\"; "
        shell += "chown root:wheel /usr/local/etc/pixelsync \"$D\"; "
        shell += "chmod 755 /usr/local/etc/pixelsync \"$D\"; "
        shell += "rm -f \"$D\"/*.pub; "
        for (id, key) in entries {
            shell += "printf '%s\\n' '\(key)' > \"$D/\(id).pub\"; "
            shell += "chown root:wheel \"$D/\(id).pub\"; "
            shell += "chmod 644 \"$D/\(id).pub\"; "
        }
        let escaped = shell
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        let appleScript = "do shell script \"\(escaped)\" with administrator privileges"

        DispatchQueue.global(qos: .userInitiated).async {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            process.arguments = ["-e", appleScript]
            let out = Pipe()
            let err = Pipe()
            process.standardOutput = out
            process.standardError = err
            do {
                try process.run()
                process.waitUntilExit()
            } catch {
                DispatchQueue.main.async { completion(false, error.localizedDescription) }
                return
            }
            if process.terminationStatus == 0 {
                DispatchQueue.main.async { completion(true, L10n.t("PAM key synced", "PAM 公钥已同步")) }
            } else {
                let data = err.fileHandleForReading.readDataToEndOfFile()
                let message = String(data: data, encoding: .utf8)?
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? "osascript failed"
                DispatchQueue.main.async { completion(false, message) }
            }
        }
    }

    /// True if a currently-installed Authorization Plugin could unlock this Mac.
    /// (The plugin is installed to /Library/Security/SecurityAgentPlugins by an
    /// admin step; until then the app must not promise passwordless unlock.)
    var pluginInstalled: Bool {
        let module = "/usr/local/lib/pam/pam_pixelsync.so"
        guard FileManager.default.fileExists(atPath: module) else { return false }
        guard let conf = try? String(contentsOfFile: "/etc/pam.d/screensaver", encoding: .utf8) else {
            return false
        }
        return conf.contains("pam_pixelsync.so")
    }

    /// Ask macOS to authenticate the user with their login password via the
    /// standard system dialog. The password is **never** returned to or stored
    /// by the app; we only learn whether authorization succeeded.
    static func requireLoginPassword() -> Bool {
        var auth: AuthorizationRef?
        guard AuthorizationCreate(nil, nil, [], &auth) == errAuthorizationSuccess,
              let auth = auth else { return false }
        defer { AuthorizationFree(auth, [.destroyRights]) }
        var item = AuthorizationItem(name: "system.privilege.admin", valueLength: 0, value: nil, flags: 0)
        var rights = AuthorizationRights(count: 1, items: &item)
        let flags: AuthorizationFlags = [.interactionAllowed, .extendRights, .preAuthorize]
        return AuthorizationCopyRights(auth, &rights, nil, flags, nil) == errAuthorizationSuccess
    }
}
