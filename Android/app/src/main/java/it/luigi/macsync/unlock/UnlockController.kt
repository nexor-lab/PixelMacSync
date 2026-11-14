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
    /** Tolerate small wall-clock differences between phone and Mac. */
    private const val CLOCK_SKEW_MS = 60_000L

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
        val macLabel = macId.take(8)
        Log.d(TAG, "event=unlock_request_received mac=$macLabel version=$version")
        if (version != UnlockProtocol.VERSION) return false
        val mac = TrustedMacStore.get(context, macId)
        if (mac == null || !mac.trusted) {
            Log.w(TAG, "event=unlock_request_rejected reason=untrusted_mac mac=$macLabel")
            return false
        }
        if (System.currentTimeMillis() > expiresAt + CLOCK_SKEW_MS) {
            Log.w(TAG, "event=unlock_request_rejected reason=expired mac=$macLabel")
            return false
        }
        val sessionId = try { UnlockProtocol.unb64(sessionIdB64) } catch (_: Exception) { return false }
        val challenge = try { UnlockProtocol.unb64(challengeB64) } catch (_: Exception) { return false }
        if (challenge.size != 32) return false

        val transcriptReq = UnlockProtocol.transcriptRequest(version, sessionId, macId, challenge, expiresAt)

        // Authenticate the requesting Mac: a rogue BLE device cannot produce this.
        if (!verifyMacSignature(mac.publicKeyB64, transcriptReq, macSigB64)) {
            Log.w(TAG, "event=unlock_request_rejected reason=invalid_mac_signature mac=$macLabel")
            return false
        }

        // Never revoke trust from this pre-notification path. Keystore calls can
        // fail transiently while the phone is locked; UnlockActivity performs
        // the real key check and asks for a re-pair only after the user acts.
        if (!AndroidKeyStore.isUnlockKeyPresent(mac.keyAlias)) {
            Log.w(TAG, "event=unlock_key_unreadable mac=$macLabel; deferring check")
        }
        val authBound = AndroidKeyStore.requiresUserAuthentication(mac.keyAlias)
        Log.d(
            TAG,
            "event=unlock_key_state mac=$macLabel present=" +
                "${AndroidKeyStore.isUnlockKeyPresent(mac.keyAlias)} authBound=$authBound",
        )

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
        Log.d(TAG, "event=unlock_prompt_posted mac=$macLabel")
        return true
    }

    /** Called by UnlockActivity after a successful biometric signature. */
    fun onSigned(context: Context, sessionIdB64: String, signatureB64: String): Boolean {
        val p = pending ?: return false
        val sessionId = try { UnlockProtocol.unb64(sessionIdB64) } catch (_: Exception) { return false }
        if (!sessionId.contentEquals(p.sessionId)) return false
        if (System.currentTimeMillis() > p.expiresAt + CLOCK_SKEW_MS) {
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
            Log.d(TAG, "event=unlock_auth_response_sent mac=${p.macId.take(8)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "event=unlock_auth_response_failed error=${e.message}")
            false
        }
    }

    /** The unlock key is missing/invalidated: drop trust and ask for a fresh
     *  pairing (the Mac will send PAIR_BEGIN; the new key is provisioned then). */
    fun requireRepair(context: Context, macId: String) {
        Log.w(TAG, "event=require_repair mac=${macId.take(8)}")
        pending = null
        TrustedMacStore.setTrusted(context, macId, false)
        UnlockNotification.dismiss(context)
        GattServerManager.getInstance(context).restartSessionAuth()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context,
                it.luigi.macsync.R.string.unlock_key_invalid_repair,
                android.widget.Toast.LENGTH_LONG,
            ).show()
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
