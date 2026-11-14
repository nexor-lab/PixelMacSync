//
//  ChallengeManager.swift
//  PixelSync
//
//  One-time unlock sessions: 32-byte random challenge, ~10 s TTL, single use,
//  memory only (spec §6/§7). Nothing is written to disk.
//

import Foundation
import Security

struct UnlockSession {
    let sessionId: Data
    let challenge: Data
    let expiresAt: Int64          // epoch milliseconds
    let transcriptRequest: Data
    var used: Bool = false
}

final class ChallengeManager {

    private var sessions: [String: UnlockSession] = [:]

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func create(macId: String) -> UnlockSession {
        purge()
        let sessionId = Self.random(16)
        let challenge = Self.random(32)
        let expiresAt = Self.nowMs() + UnlockProtocol.ttlMs
        let req = UnlockProtocol.transcriptRequest(
            version: UnlockProtocol.version,
            sessionId: sessionId,
            macId: macId,
            challenge: challenge,
            expiresAt: expiresAt
        )
        let session = UnlockSession(
            sessionId: sessionId,
            challenge: challenge,
            expiresAt: expiresAt,
            transcriptRequest: req
        )
        sessions[sessionId.base64EncodedString()] = session
        return session
    }

    /// Returns the session exactly once; nil if unknown, expired, or replayed.
    func consume(sessionIdB64: String) -> UnlockSession? {
        purge()
        guard var s = sessions[sessionIdB64], !s.used, Self.nowMs() <= s.expiresAt else { return nil }
        s.used = true
        sessions[sessionIdB64] = s
        return s
    }

    func discard(sessionIdB64: String) { sessions.removeValue(forKey: sessionIdB64) }

    func purge() {
        let now = Self.nowMs()
        sessions = sessions.filter { $0.value.expiresAt >= now }
    }

    private static func random(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let rc = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        if rc != errSecSuccess {
            // Fall back to the system RNG (never returns a constant).
            bytes = (0..<count).map { _ in UInt8.random(in: 0...255) }
        }
        return Data(bytes)
    }
}
