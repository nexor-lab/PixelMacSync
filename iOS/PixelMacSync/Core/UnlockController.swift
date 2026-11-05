//
//  UnlockController.swift
//  PixelMacSync iOS
//
//  Pairing + challenge/response using the Secure Enclave key. The Mac side is
//  unchanged: it verifies a DER ECDSA P-256 signature over the same transcript.
//

import Foundation
import Combine
import CryptoKit
import UIKit
import Security

final class UnlockController: ObservableObject {
    static let shared = UnlockController()

    @Published var pairingActive = false
    @Published var pairingMacName = ""
    @Published var lastMessage = ""
    @Published var pendingUnlockMacName = ""

    private struct PendingUnlock {
        let macId: String
        let macName: String
        let sessionIdB64: String
        let transcriptReq: Data
        let expiresAt: Int64
    }
    private var pendingUnlock: PendingUnlock?
    private var pairing: (macId: String, macName: String, macPubB64: String, macSessionPubB64: String)?
    private var pendingPairingId: String?
    private var sessionAuthenticated = false
    private var sessionMacId: String?
    private var sessionNonce: Data?

    var hasPendingUnlock: Bool { pendingUnlock != nil }

    // MARK: - Outbound actions

    func requestPairing() {
        guard let macId = BLEPeripheralManager.shared.connectedMacId else {
            lastMessage = "请先连接 Mac"
            return
        }
        BLEPeripheralManager.shared.send(
            UnlockProtocol.pairRequest(phoneId: TrustedMacStore.shared.deviceId, macId: macId))
        lastMessage = "已请求绑定，请在 Mac 上确认配对"
    }

    /// Start (or restart) session authentication for the connected Mac.
    func startSession(macId: String, name: String) {
        sessionMacId = macId
        sessionNonce = nil
        sessionAuthenticated = false
        guard let mac = TrustedMacStore.shared.get(macId), mac.sessionPublicKeyB64 != nil else {
            // No session key yet: let the Mac start the (re)pairing flow.
            BLEPeripheralManager.shared.send(UnlockProtocol.sessionUnpaired(phoneName: UIDevice.current.name))
            return
        }
        var nonce = Data(count: 16)
        _ = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        sessionNonce = nonce
        BLEPeripheralManager.shared.send(
            UnlockProtocol.sessionChallenge(nonceB64: UnlockProtocol.b64(nonce)))
    }

    func sessionReset() {
        sessionAuthenticated = false
        sessionNonce = nil
        sessionMacId = nil
    }

    func confirmPairing() async {
        guard let p = pairing else { return }
        let ok = await SecureKey.shared.authenticateBiometrics(reason: "验证以完成与 Mac 的绑定")
        guard ok else {
            lastMessage = "面容/指纹验证失败"
            return
        }
        let pairingId = Self.randomB64(12)
        pendingPairingId = pairingId
        BLEPeripheralManager.shared.send(UnlockProtocol.pairConfirm(pairingId: pairingId, macId: p.macId))
        lastMessage = "已确认，等待 Mac 完成绑定"
    }

    func approveUnlock() async {
        guard let p = pendingUnlock else { return }
        if Self.nowMs() > p.expiresAt {
            pendingUnlock = nil
            lastMessage = "解锁请求已过期"
            return
        }
        let phoneId = TrustedMacStore.shared.deviceId
        let resp = UnlockProtocol.transcriptResponse(transcriptRequest: p.transcriptReq, phoneId: phoneId)
        guard let sig = SecureKey.shared.sign(resp) else {
            lastMessage = "签名失败（密钥不可用？）"
            return
        }
        BLEPeripheralManager.shared.send(
            UnlockProtocol.authResponse(sessionIdB64: p.sessionIdB64, phoneId: phoneId,
                                        signatureB64: UnlockProtocol.b64(sig)))
        TrustedMacStore.shared.markAuthorized(p.macId)
        pendingUnlock = nil
        DispatchQueue.main.async { self.pendingUnlockMacName = "" }
        lastMessage = "已发送解锁授权"
    }

    func triggerUnlock(macId: String) {
        let phoneId = TrustedMacStore.shared.deviceId
        let payload = ["UNLOCK_TRIGGER", "1", phoneId, macId].joined(separator: UnlockProtocol.us)
        BLEPeripheralManager.shared.send(payload)
        lastMessage = "已请求 Mac 启动解锁"
    }

    /// Face ID gate for unbinding (secondary confirmation happens in the UI).
    func unbindWithAuth(_ macId: String) async {
        let ok = await SecureKey.shared.authenticateBiometrics(reason: "验证以解绑该 Mac")
        guard ok else {
            lastMessage = "面容验证失败，未解绑"
            return
        }
        unbind(macId)
    }

    func unbind(_ macId: String) {
        TrustedMacStore.shared.remove(macId)
        BLEPeripheralManager.shared.send(UnlockProtocol.unbind(phoneId: TrustedMacStore.shared.deviceId))
        lastMessage = "已解绑"
    }

    /// Called when the app becomes active (e.g. after tapping the notification).
    func onAppActive() {
        if pendingUnlock != nil {
            Task { await approveUnlock() }
        }
    }

    // MARK: - Inbound (Mac -> phone)

    func handleInbound(_ parts: [String]) {
        guard let head = parts.first else { return }
        switch head {
        case "PAIR_BEGIN":
            guard parts.count >= 5, let v = Int(parts[1]), v == UnlockProtocol.version else { return }
            let macId = parts[2], macName = parts[3], macPub = parts[4]
            let macSessionPub = parts.count >= 6 ? parts[5] : ""
            _ = SecureKey.shared.key()
            guard let phonePub = SecureKey.shared.publicKeyB64(),
                  let phoneSessionPub = SecureKey.shared.sessionPublicKeyB64() else { return }
            pairing = (macId, macName, macPub, macSessionPub)
            let phoneId = TrustedMacStore.shared.deviceId
            BLEPeripheralManager.shared.send(
                UnlockProtocol.pairReply(phoneId: phoneId, phonePubB64: phonePub))
            BLEPeripheralManager.shared.send(
                UnlockProtocol.pairSessionPub(phoneId: phoneId, sessionPubB64: phoneSessionPub))
            DispatchQueue.main.async {
                self.pairingMacName = macName
                self.pairingActive = true
            }

        case "PAIR_DONE":
            guard parts.count >= 3 else { return }
            let pairingId = parts[2]
            var pairedMac: (String, String)?
            if let p = pairing, pendingPairingId == nil || pendingPairingId == pairingId {
                TrustedMacStore.shared.upsert(TrustedMac(
                    macId: p.macId, name: p.macName, publicKeyB64: p.macPubB64,
                    sessionPublicKeyB64: p.macSessionPubB64.isEmpty ? nil : p.macSessionPubB64,
                    pairingId: pairingId, createdAt: Date()))
                pairedMac = (p.macId, p.macName)
            }
            pairing = nil
            pendingPairingId = nil
            DispatchQueue.main.async {
                self.pairingActive = false
                self.lastMessage = "绑定成功"
            }
            if let (macId, name) = pairedMac { startSession(macId: macId, name: name) }

        case "PAIR_CANCEL":
            pairing = nil
            pendingPairingId = nil
            DispatchQueue.main.async { self.pairingActive = false }

        case "SESSION_MAC_PROOF":
            guard parts.count >= 4, let v = Int(parts[1]), v == UnlockProtocol.version,
                  let macId = sessionMacId, let nonce = sessionNonce,
                  let mac = TrustedMacStore.shared.get(macId),
                  let macSessionPub = mac.sessionPublicKeyB64,
                  let nonceMac = UnlockProtocol.unb64(parts[3]),
                  Self.verify(macPubB64: macSessionPub,
                              data: UnlockProtocol.sessionMacTranscript(nonce),
                              sigB64: parts[2]),
                  let sig = SecureKey.shared.signSession(
                              UnlockProtocol.sessionPhoneTranscript(nonceMac)) else {
                postMessage("会话验证失败")
                return
            }
            BLEPeripheralManager.shared.send(
                UnlockProtocol.sessionPhoneProof(
                    phoneId: TrustedMacStore.shared.deviceId,
                    signatureB64: UnlockProtocol.b64(sig)))

        case "SESSION_OK":
            guard parts.count >= 2, let v = Int(parts[1]), v == UnlockProtocol.version,
                  let macId = sessionMacId else { return }
            sessionAuthenticated = true
            BLEPeripheralManager.shared.sessionEstablished(macId: macId)

        case "SESSION_REJECTED":
            sessionAuthenticated = false
            postMessage("会话验证失败，请在 Mac 上重新配对")

        case "UNLOCK_REQUEST":
            handleUnlockRequest(parts)

        case "TRUST_REVOKED":
            if parts.count >= 2 { TrustedMacStore.shared.remove(parts[1]) }

        default:
            break
        }
    }

    private func handleUnlockRequest(_ parts: [String]) {
        guard sessionAuthenticated else { return }
        guard parts.count >= 6, let v = Int(parts[1]), v == UnlockProtocol.version else { return }
        let sessionIdB64 = parts[2], challengeB64 = parts[3], sigB64 = parts[5]
        guard let expiresAt = Int64(parts[4]),
              let challenge = UnlockProtocol.unb64(challengeB64), challenge.count == 32,
              let sessionId = UnlockProtocol.unb64(sessionIdB64) else { return }

        let macId = BLEPeripheralManager.shared.connectedMacId ?? ""
        guard let mac = TrustedMacStore.shared.get(macId) else { return }
        guard Self.nowMs() <= expiresAt else { return }

        let req = UnlockProtocol.transcriptRequest(
            version: v, sessionId: sessionId, macId: macId, challenge: challenge, expiresAt: expiresAt)
        guard Self.verify(macPubB64: mac.publicKeyB64, data: req, sigB64: sigB64) else { return }

        pendingUnlock = PendingUnlock(
            macId: macId, macName: mac.name, sessionIdB64: sessionIdB64,
            transcriptReq: req, expiresAt: expiresAt)
        DispatchQueue.main.async { self.pendingUnlockMacName = mac.name }
        NotificationManager.shared.postUnlock(macName: mac.name)
    }

    // MARK: - Helpers

    private static func verify(macPubB64: String, data: Data, sigB64: String) -> Bool {
        guard let der = UnlockProtocol.unb64(macPubB64),
              let sig = UnlockProtocol.unb64(sigB64) else { return false }
        do {
            let pub = try P256.Signing.PublicKey(derRepresentation: der)
            let ecdsa = try P256.Signing.ECDSASignature(derRepresentation: sig)
            return pub.isValidSignature(ecdsa, for: data)
        } catch {
            return false
        }
    }

    private func postMessage(_ text: String) {
        DispatchQueue.main.async { self.lastMessage = text }
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private static func randomB64(_ count: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes).base64EncodedString()
    }
}
