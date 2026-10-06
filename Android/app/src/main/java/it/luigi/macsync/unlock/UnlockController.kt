package it.luigi.macsync.unlock

import android.content.Context
import android.util.Log
import it.luigi.macsync.ble.GattServerManager
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Android-side unlock orchestration.
 *
 * Holds the single in-flight unlock request **in memory only** (spec: never a
 * file, never a full challenge history), verifies the Mac's request signature
 * against the paired public key, and turns a successful biometric signature
 * into an `AUTH_RESPONSE` on the existing BLE Notifications channel.
 */
object UnlockController {

    private const val TAG = "MacSyncUnlock"

    data class Pending(
        val macId: String,
        val macName: String,
        val sessionId: ByteArray,
        val challenge: ByteArray,
        val expiresAt: Long,
        val transcriptRequest: ByteArray,
        val keyAlias: String,
    )

    @Volatile
    private var pending: Pending? = null

    fun currentPending(): Pending? = pending

    /** Send the phone-side unlock policy to the Mac (auto-lock / remote wake). */
    fun sendPolicy(context: Context) {
        val payload = listOf(
            "SET_POLICY", UnlockProtocol.VERSION.toString(),
            if (UnlockSettings.awayAutoLock(context)) "1" else "0",
            if (UnlockSettings.remoteWake(context)) "1" else "0",
        ).joinToString(UnlockProtocol.SEP)
        try {
            GattServerManager.getInstance(context).sendNotificationToMac(payload)
        } catch (e: Exception) {
            Log.e(TAG, "sendPolicy failed: ${e.message}")
        }
        // Refresh the UI (show/hide the binding controls) immediately.
        context.sendBroadcast(
            android.content.Intent(PairingController.ACTION_TRUST_CHANGED)
                .setPackage(context.applicationContext.packageName)
        )
    }

    /**
     * Manual trigger from the app: ask the Mac to (re)start the unlock flow, so
     * the user can retry when they missed the notification.
     */
    fun requestUnlock(context: Context, macId: String) {
        val phoneId = TrustedMacStore.deviceId(context)
        val payload = listOf(
            "UNLOCK_TRIGGER", UnlockProtocol.VERSION.toString(), phoneId, macId,
        ).joinToString(UnlockProtocol.SEP)
        try {
            GattServerManager.getInstance(context).sendNotificationToMac(payload)
        } catch (e: Exception) {
            Log.e(TAG, "requestUnlock failed: ${e.message}")
        }
    }

    /**
     * Handle an inbound `UNLOCK_REQUEST` from a GATT write. Returns true when the
     * request is authentic and a notification was posted.
     */
    fun onUnlockRequest(
        context: Context,
        version: Int,
        sessionIdB64: String,
        macId: String,
        challengeB64: String,
        expiresAt: Long,
        macSigB64: String,
    ): Boolean {
        if (version != UnlockProtocol.VERSION) return false
        val mac = TrustedMacStore.get(context, macId)
        if (mac == null || !mac.trusted) {
            Log.w(TAG, "unlock request from untrusted mac")
            return false
        }
        if (System.currentTimeMillis() > expiresAt) {
            Log.w(TAG, "unlock request expired")
            return false
        }
        val sessionId = try { UnlockProtocol.unb64(sessionIdB64) } catch (_: Exception) { return false }
        val challenge = try { UnlockProtocol.unb64(challengeB64) } catch (_: Exception) { return false }
        if (challenge.size != 32) return false

        val transcriptReq = UnlockProtocol.transcriptRequest(version, sessionId, macId, challenge, expiresAt)

        // Authenticate the requesting Mac: a rogue BLE device cannot produce this.
        if (!verifyMacSignature(mac.publicKeyB64, transcriptReq, macSigB64)) {
            Log.w(TAG, "mac request signature invalid")
            return false
        }

        pending = Pending(
            macId = macId,
            macName = mac.name,
            sessionId = sessionId,
            challenge = challenge,
            expiresAt = expiresAt,
            transcriptRequest = transcriptReq,
            keyAlias = mac.keyAlias,
        )
        UnlockNotification.post(context, mac.name)
        return true
    }

    /** Called by UnlockActivity after a successful biometric signature. */
    fun onSigned(context: Context, sessionIdB64: String, signatureB64: String): Boolean {
        val p = pending ?: return false
        val sessionId = try { UnlockProtocol.unb64(sessionIdB64) } catch (_: Exception) { return false }
        if (!sessionId.contentEquals(p.sessionId)) return false
        if (System.currentTimeMillis() > p.expiresAt) {
            pending = null
            return false
        }
        val phoneId = TrustedMacStore.deviceId(context)
        val payload = listOf(
            "AUTH_RESPONSE",
            UnlockProtocol.VERSION.toString(),
            UnlockProtocol.b64(p.sessionId),
            phoneId,
            signatureB64,
        ).joinToString(UnlockProtocol.SEP)
        pending = null
        TrustedMacStore.markAuthorized(context, p.macId)
        return try {
            GattServerManager.getInstance(context).sendNotificationToMac(payload)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to send AUTH_RESPONSE: ${e.message}")
            false
        }
    }

    /** User dismissed / auth failed / BLE dropped: discard the session. */
    fun cancel() {
        pending = null
    }

    private fun verifyMacSignature(publicKeyB64: String, data: ByteArray, sigB64: String): Boolean {
        return try {
            val der = Base64.getDecoder().decode(publicKeyB64)
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pub)
            sig.update(data)
            sig.verify(Base64.getDecoder().decode(sigB64))
        } catch (e: Exception) {
            Log.w(TAG, "mac signature verify error: ${e.message}")
            false
        }
    }
}
