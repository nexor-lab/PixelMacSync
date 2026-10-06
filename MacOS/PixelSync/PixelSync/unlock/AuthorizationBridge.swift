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
    func grant(sessionIdB64: String, macId: String, expiresAtMs: Int64) {
        let user = NSUserName()
        let body = "\(sessionIdB64)\n\(macId)\n\(expiresAtMs)\n\(user)\n"
        try? body.data(using: .utf8)?.write(to: grantURL, options: [.atomic])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: grantURL.path)
    }

    func clearGrant() {
        try? FileManager.default.removeItem(at: grantURL)
    }

    /// True if a currently-installed Authorization Plugin could unlock this Mac.
    /// (The plugin is installed to /Library/Security/SecurityAgentPlugins by an
    /// admin step; until then the app must not promise passwordless unlock.)
    var pluginInstalled: Bool {
        FileManager.default.fileExists(
            atPath: "/Library/Security/SecurityAgentPlugins/PixelSyncAuthPlugin.bundle")
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
