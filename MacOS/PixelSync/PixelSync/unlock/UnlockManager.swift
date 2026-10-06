//
//  UnlockManager.swift
//  PixelSync
//
//  Mac-side orchestration of phone biometric unlock.
//
//  Pairing model (no 6-digit code):
//   - Mac authorizes adding a device with the **macOS login password** via the
//     system Authorization dialog (never stored, never seen by the app).
//   - Android authorizes with its **fingerprint**.
//  Unlock: Mac sends a one-time challenge, the phone signs it, the Mac verifies
//  and hands a password-free grant to the Authorization Plugin.
//

import Foundation
import Security
import IOKit.pwr_mgt

final class UnlockManager {

    let deviceStore: TrustedDeviceStore
    private let signingKey: MacSigningKey
    private let challengeManager = ChallengeManager()
    private let bridge = AuthorizationBridge.shared

    private let macId: String
    private let macName: String

    /// Sends a command on the existing BLE Command characteristic.
    var sendCommand: ((String) -> Void)?

    // UI callbacks (dispatched on the main queue).
    var onPairingStateChanged: ((Bool) -> Void)?   // active
    var onPaired: ((TrustedPhone) -> Void)?
    var onUnlockGranted: (() -> Void)?
    var onUnlockFailed: ((String) -> Void)?
    var onTrustChanged: (() -> Void)?

    private struct PairingState {
        var phoneId: String?
        var phonePubB64: String?
        var phoneConfirmed = false
    }
    private var pairing: PairingState?

    init(macId: String, macName: String, supportDir: URL) {
        self.macId = macId
        self.macName = macName
        try? FileManager.default.createDirectory(at: supportDir, withIntermediateDirectories: true)
        self.deviceStore = TrustedDeviceStore(folder: supportDir)
        self.signingKey = MacSigningKey(folder: supportDir)
    }

    var trustedDevices: [TrustedPhone] { deviceStore.devices }
    var hasTrustedDevice: Bool { deviceStore.devices.contains { $0.enabled } }
    var macPublicKeyB64: String? { signingKey.publicKeyB64 }
    var isPairing: Bool { pairing != nil }

    // MARK: - Phone-side policy (auto-lock / remote wake)
    private let kAway = "pixel.unlock.awayAutoLock"
    private let kRemoteWake = "pixel.unlock.remoteWake"

    var awayAutoLock: Bool {
        get { (UserDefaults.standard.object(forKey: kAway) as? Bool) ?? true }
        set { UserDefaults.standard.set(newValue, forKey: kAway) }
    }
    var remoteWake: Bool {
        get { (UserDefaults.standard.object(forKey: kRemoteWake) as? Bool) ?? true }
        set { UserDefaults.standard.set(newValue, forKey: kRemoteWake) }
    }
    private var awayLockWork: DispatchWorkItem?

    /// BLE link lost: lock the screen after a grace period if still gone.
    func handleLinkLost() {
        guard awayAutoLock, hasTrustedDevice else { return }
        awayLockWork?.cancel()
        let work = DispatchWorkItem { ScreenControl.lockScreen() }
        awayLockWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: work)
        NSLog("PixelSync: link lost — will auto-lock in 20 s if not reconnected")
    }

    /// BLE link restored: cancel a pending away-lock; if locked, wake + start unlock.
    func handleLinkRestored() {
        awayLockWork?.cancel()
        awayLockWork = nil
        guard remoteWake, hasTrustedDevice else { return }
        if LockStateMonitor.isScreenLocked() {
            ScreenControl.wakeDisplay()
            triggerUnlock(reason: "reconnect")
        }
    }

    // MARK: - Pairing

    /// `fromPhone` = true when triggered by the Android PAIR_REQUEST (so we can
    /// tell the phone if the Mac password prompt is cancelled).
    func beginPairing(fromPhone: Bool = false) {
        guard let pub = signingKey.publicKeyB64 else {
            NSLog("PixelSync: unlock signing key unavailable")
            return
        }
        // Mac-side authorization: the macOS login password (system dialog).
        guard AuthorizationBridge.requireLoginPassword() else {
            NSLog("PixelSync: pairing not authorized on the Mac")
            if fromPhone {
                sendCommand?(UnlockProtocol.pairCancel(reason: "mac_auth_cancelled"))
            }
            return
        }
        pairing = PairingState()
        DispatchQueue.main.async { self.onPairingStateChanged?(true) }
        sendCommand?(UnlockProtocol.pairBegin(
            macId: macId, macName: macName, macPubB64: pub))
    }

    func cancelPairing() {
        pairing = nil
        DispatchQueue.main.async { self.onPairingStateChanged?(false) }
    }

    private func finalizePairingIfReady() {
        guard let state = pairing, state.phoneConfirmed,
              let phoneId = state.phoneId,
              let pub = state.phonePubB64,
              let pairingId = pairingId else { return }
        let device = TrustedPhone(
            id: phoneId, name: phoneId, publicKeyB64: pub, pairingId: pairingId,
            enabled: true, allowManualLock: true, allowLidWake: true, createdAt: Date())
        deviceStore.upsert(device)
        pairing = nil
        sendCommand?(UnlockProtocol.pairDone(pairingId: pairingId))
        DispatchQueue.main.async {
            self.onPairingStateChanged?(false)
            self.onPaired?(device)
            self.onTrustChanged?()
        }
        NSLog("PixelSync: paired phone \(phoneId)")
    }

    private var pairingId: String?

    // MARK: - Inbound packets

    func handlePacket(_ fields: [String]) -> Bool {
        guard let packet = UnlockProtocol.parse(fields) else { return false }
        switch packet {
        case .pairRequest(let version, _, let requestedMacId):
            guard version == UnlockProtocol.version, requestedMacId == macId else { return true }
            beginPairing(fromPhone: true)

        case .unlockTrigger(let version, let phoneId, let requestedMacId):
            guard version == UnlockProtocol.version, requestedMacId == macId,
                  deviceStore.isTrusted(phoneId) else { return true }
            triggerUnlock(reason: "phone")

        case .setPolicy(let version, let away, let wake):
            guard version == UnlockProtocol.version else { return true }
            awayAutoLock = away
            remoteWake = wake
            NSLog("PixelSync: policy awayAutoLock=\(away) remoteWake=\(wake)")

        case .pairReply(let version, let phoneId, let phonePubB64):
            guard version == UnlockProtocol.version, var state = pairing else { return true }
            state.phoneId = phoneId
            state.phonePubB64 = phonePubB64
            pairing = state

        case .pairConfirm(let version, let pairingId, let confirmedMacId):
            guard version == UnlockProtocol.version, confirmedMacId == macId else { return true }
            self.pairingId = pairingId
            if var state = pairing { state.phoneConfirmed = true; pairing = state }
            finalizePairingIfReady()

        case .pairCancel:
            cancelPairing()

        case .unbind(let version, let phoneId):
            guard version == UnlockProtocol.version else { return true }
            deviceStore.remove(phoneId)
            DispatchQueue.main.async { self.onTrustChanged?() }
            NSLog("PixelSync: phone \(phoneId) unbound by user")

        case .authResponse(let version, let sessionIdB64, let phoneId, let signatureB64):
            guard version == UnlockProtocol.version,
                  let session = challengeManager.consume(sessionIdB64: sessionIdB64) else {
                DispatchQueue.main.async { self.onUnlockFailed?("session_invalid") }
                return true
            }
            guard let device = deviceStore.get(phoneId), device.enabled else {
                DispatchQueue.main.async { self.onUnlockFailed?("device_revoked") }
                return true
            }
            guard let pkDER = UnlockProtocol.unb64(device.publicKeyB64),
                  let sigDER = UnlockProtocol.unb64(signatureB64) else {
                DispatchQueue.main.async { self.onUnlockFailed?("signature_invalid") }
                return true
            }
            let transcriptResp = UnlockProtocol.transcriptResponse(
                transcriptRequest: session.transcriptRequest, phoneId: phoneId)
            if SignatureVerifier.verify(publicKeyDER: pkDER, data: transcriptResp, signatureDER: sigDER) {
                deviceStore.markAuthorized(phoneId)
                // Unlock window (not the 10 s BLE session): give the user time to
                // submit on the lock screen. Single-use, local only.
                let unlockWindowMs = ChallengeManager.nowMs() + 120_000
                bridge.grant(sessionIdB64: sessionIdB64, macId: macId, expiresAtMs: unlockWindowMs)
                DispatchQueue.main.async { self.onUnlockGranted?() }
                NSLog("PixelSync: phone signature verified -> unlock granted")
            } else {
                DispatchQueue.main.async { self.onUnlockFailed?("signature_invalid") }
                NSLog("PixelSync: phone signature INVALID")
            }

        case .trustRevoked:
            DispatchQueue.main.async { self.onTrustChanged?() }

        case .unknown:
            return false
        }
        return true
    }

    // MARK: - Unlock trigger

    /// Start the unlock flow when the screen locks. Silent no-op when no phone
    /// is paired (so we never spam a misleading Mac notification).
    func triggerUnlock(reason: String) {
        guard let device = deviceStore.devices.first(where: { $0.enabled }) else {
            NSLog("PixelSync: unlock skipped (\(reason)) — no trusted phone")
            return
        }
        let session = challengeManager.create(macId: macId)
        guard let sig = signingKey.sign(session.transcriptRequest) else { return }
        let cmd = UnlockProtocol.unlockRequest(
            sessionIdB64: UnlockProtocol.b64(session.sessionId),
            challengeB64: UnlockProtocol.b64(session.challenge),
            expiresAt: session.expiresAt,
            macSigB64: UnlockProtocol.b64(sig))
        NSLog("PixelSync: unlock trigger (\(reason)) for \(device.id)")
        holdDisplayAwake()
        sendCommand?(cmd)
    }

    // Keep the display on while the lock-screen plugin waits for the grant, so
    // the wait does not blank the screen and require a wake click.
    private var displayAssertion: IOPMAssertionID = 0

    func holdDisplayAwake() {
        if displayAssertion == 0 {
            var id: IOPMAssertionID = 0
            let reason = "PixelSync phone unlock" as CFString
            let rc = IOPMAssertionCreateWithName(
                kIOPMAssertionTypePreventUserIdleDisplaySleep as CFString,
                IOPMAssertionLevel(kIOPMAssertionLevelOn),
                reason, &id)
            if rc == kIOReturnSuccess { displayAssertion = id }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 40) { [weak self] in
            self?.releaseDisplayAssertion()
        }
    }

    private func releaseDisplayAssertion() {
        if displayAssertion != 0 {
            IOPMAssertionRelease(displayAssertion)
            displayAssertion = 0
        }
    }

    // MARK: - Revocation

    func revoke(_ phoneId: String) {
        deviceStore.remove(phoneId)
        sendCommand?(UnlockProtocol.trustRevoked(macId: macId))
        onTrustChanged?()
    }

    func setEnabled(_ phoneId: String, _ enabled: Bool) {
        deviceStore.setEnabled(phoneId, enabled)
        onTrustChanged?()
    }
}
