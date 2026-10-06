//
//  UnlockProtocol.swift
//  PixelSync
//
//  Wire + signing transcript for the phone biometric unlock. Must match
//  Android's unlock/UnlockProtocol.kt byte-for-byte. See .opencode/UNLOCK_DESIGN.md.
//

import Foundation
import CryptoKit

enum UnlockProtocol {

    static let version = 1
    static let us = "\u{1F}"
    static let ttlMs: Int64 = 10_000

    // MARK: - base64

    static func b64(_ data: Data) -> String { data.base64EncodedString() }
    static func unb64(_ s: String) -> Data? { Data(base64Encoded: s) }

    // MARK: - length-prefixed transcript

    private static func lp(_ data: Data) -> Data {
        var out = Data()
        let n = UInt16(clamping: data.count)
        out.append(UInt8((n >> 8) & 0xFF))
        out.append(UInt8(n & 0xFF))
        out.append(data)
        return out
    }

    private static func lpU64(_ v: UInt64) -> Data {
        var out = Data()
        var be = v.bigEndian
        withUnsafeBytes(of: &be) { out.append(contentsOf: $0) }
        return out
    }

    private static func lpString(_ s: String) -> Data {
        lp(Data(s.utf8))
    }

    /// transcript_req = LP(version) ‖ LP(session_id) ‖ LP(mac_id)
    ///                  ‖ LP(challenge) ‖ LP_u64(expires_at)
    static func transcriptRequest(
        version: Int,
        sessionId: Data,
        macId: String,
        challenge: Data,
        expiresAt: Int64
    ) -> Data {
        var out = Data()
        out.append(lp(Data([UInt8(version)])))
        out.append(lp(sessionId))
        out.append(lpString(macId))
        out.append(lp(challenge))
        out.append(lpU64(UInt64(bitPattern: expiresAt)))
        return out
    }

    /// transcript_resp = transcript_req ‖ LP(phone_id)
    static func transcriptResponse(transcriptRequest req: Data, phoneId: String) -> Data {
        var out = req
        out.append(lpString(phoneId))
        return out
    }

    // MARK: - pairing SAS (must match Android)

    static func pairingSas(pkMac: Data, pkPhone: Data, nonceMac: Data, noncePhone: Data) -> String {
        let first: Data
        let second: Data
        if compare(pkMac, pkPhone) <= 0 { first = pkMac; second = pkPhone } else { first = pkPhone; second = pkMac }
        var input = Data()
        input.append(first); input.append(second); input.append(nonceMac); input.append(noncePhone)
        let digest = SHA256.hash(data: input)
        let d = Array(digest)
        let n = (UInt32(d[0]) << 24) | (UInt32(d[1]) << 16) | (UInt32(d[2]) << 8) | UInt32(d[3])
        return String(format: "%06d", n % 1_000_000)
    }

    private static func compare(_ a: Data, _ b: Data) -> Int {
        let n = min(a.count, b.count)
        for i in 0..<n {
            let x = Int(a[a.startIndex + i])
            let y = Int(b[b.startIndex + i])
            if x != y { return x - y }
        }
        return a.count - b.count
    }

    // MARK: - packet parsing (Android -> Mac)

    enum Packet {
        case pairRequest(version: Int, phoneId: String, macId: String)
        case unlockTrigger(version: Int, phoneId: String, macId: String)
        case setPolicy(version: Int, awayAutoLock: Bool, remoteWake: Bool)
        case pairReply(version: Int, phoneId: String, phonePubB64: String)
        case pairConfirm(version: Int, pairingId: String, macId: String)
        case pairCancel(version: Int, macId: String)
        case unbind(version: Int, phoneId: String)
        case authResponse(version: Int, sessionIdB64: String, phoneId: String, signatureB64: String)
        case trustRevoked(macId: String)
        case unknown(String)
    }

    static func parse(_ fields: [String]) -> Packet? {
        guard let head = fields.first else { return nil }
        func int(_ i: Int) -> Int? { i < fields.count ? Int(fields[i]) : nil }
        switch head {
        case "PAIR_REQUEST":
            guard fields.count >= 4, let v = int(1) else { return nil }
            return .pairRequest(version: v, phoneId: fields[2], macId: fields[3])
        case "UNLOCK_TRIGGER":
            guard fields.count >= 4, let v = int(1) else { return nil }
            return .unlockTrigger(version: v, phoneId: fields[2], macId: fields[3])
        case "SET_POLICY":
            guard fields.count >= 4, let v = int(1) else { return nil }
            return .setPolicy(version: v, awayAutoLock: fields[2] == "1", remoteWake: fields[3] == "1")
        case "PAIR_REPLY":
            guard fields.count >= 4, let v = int(1) else { return nil }
            return .pairReply(version: v, phoneId: fields[2], phonePubB64: fields[3])
        case "PAIR_CONFIRM":
            guard fields.count >= 4, let v = int(1) else { return nil }
            return .pairConfirm(version: v, pairingId: fields[2], macId: fields[3])
        case "PAIR_CANCEL":
            guard fields.count >= 3, let v = int(1) else { return nil }
            return .pairCancel(version: v, macId: fields[2])
        case "UNBIND":
            guard fields.count >= 3, let v = int(1) else { return nil }
            return .unbind(version: v, phoneId: fields[2])
        case "AUTH_RESPONSE":
            guard fields.count >= 5, let v = int(1) else { return nil }
            return .authResponse(version: v, sessionIdB64: fields[2], phoneId: fields[3], signatureB64: fields[4])
        case "TRUST_REVOKED":
            guard fields.count >= 2 else { return nil }
            return .trustRevoked(macId: fields[1])
        default:
            return .unknown(head)
        }
    }

    // MARK: - packet building (Mac -> Android)

    static func pairBegin(macId: String, macName: String, macPubB64: String) -> String {
        ["PAIR_BEGIN", "\(version)", macId, macName, macPubB64].joined(separator: us)
    }

    static func pairDone(pairingId: String) -> String {
        ["PAIR_DONE", "\(version)", pairingId].joined(separator: us)
    }

    static func pairCancel(reason: String) -> String {
        ["PAIR_CANCEL", "\(version)", reason].joined(separator: us)
    }

    static func unlockRequest(sessionIdB64: String, challengeB64: String, expiresAt: Int64, macSigB64: String) -> String {
        ["UNLOCK_REQUEST", "\(version)", sessionIdB64, challengeB64, "\(expiresAt)", macSigB64].joined(separator: us)
    }

    static func trustRevoked(macId: String) -> String {
        ["TRUST_REVOKED", macId].joined(separator: us)
    }
}
