//
//  UnlockProtocol.swift
//  PixelMacSync iOS
//
//  Wire + signing transcript shared with Android (UnlockProtocol.kt) and macOS
//  (UnlockProtocol.swift). Keep byte-for-byte identical.
//

import Foundation

enum UnlockProtocol {
    static let version = 1
    static let us = "\u{1F}"

    static func b64(_ data: Data) -> String { data.base64EncodedString() }
    static func unb64(_ s: String) -> Data? { Data(base64Encoded: s) }

    // MARK: - length-prefixed transcript

    static func lp(_ data: Data) -> Data {
        let n = min(data.count, 0xFFFF)
        var out = Data()
        out.append(UInt8((n >> 8) & 0xFF))
        out.append(UInt8(n & 0xFF))
        out.append(data.prefix(n))
        return out
    }

    static func lpString(_ s: String) -> Data { lp(Data(s.utf8)) }

    static func lpU64(_ v: Int64) -> Data {
        var be = UInt64(bitPattern: v).bigEndian
        return withUnsafeBytes(of: &be) { Data($0) }
    }

    /// transcript_req = LP(version) ‖ LP(session_id) ‖ LP(mac_id)
    ///                  ‖ LP(challenge) ‖ LP_u64(expires_at)
    static func transcriptRequest(version: Int, sessionId: Data, macId: String,
                                  challenge: Data, expiresAt: Int64) -> Data {
        var out = Data()
        out.append(lp(Data([UInt8(version)])))
        out.append(lp(sessionId))
        out.append(lpString(macId))
        out.append(lp(challenge))
        out.append(lpU64(expiresAt))
        return out
    }

    /// transcript_resp = transcript_req ‖ LP(phone_id)
    static func transcriptResponse(transcriptRequest req: Data, phoneId: String) -> Data {
        var out = req
        out.append(lpString(phoneId))
        return out
    }

    // MARK: - outbound builders (iOS -> Mac, on Notifications)

    static func pairRequest(phoneId: String, macId: String) -> String {
        ["PAIR_REQUEST", "\(version)", phoneId, macId].joined(separator: us)
    }
    static func pairReply(phoneId: String, phonePubB64: String) -> String {
        ["PAIR_REPLY", "\(version)", phoneId, phonePubB64].joined(separator: us)
    }
    static func pairConfirm(pairingId: String, macId: String) -> String {
        ["PAIR_CONFIRM", "\(version)", pairingId, macId].joined(separator: us)
    }
    static func authResponse(sessionIdB64: String, phoneId: String, signatureB64: String) -> String {
        ["AUTH_RESPONSE", "\(version)", sessionIdB64, phoneId, signatureB64].joined(separator: us)
    }
    static func setPolicy(awayAutoLock: Bool, remoteWake: Bool) -> String {
        ["SET_POLICY", "\(version)", awayAutoLock ? "1" : "0", remoteWake ? "1" : "0"]
            .joined(separator: us)
    }
    static func unbind(phoneId: String) -> String {
        ["UNBIND", "\(version)", phoneId].joined(separator: us)
    }
}
