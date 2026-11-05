package it.luigi.macsync.unlock

import android.content.Context
import android.content.Intent
import android.util.Log
import it.luigi.macsync.ble.GattServerManager
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-side pairing. No 6-digit code:
 *   Mac  -> PAIR_BEGIN   (mac_id, mac_name, mac_pub)     [after Mac login-password auth]
 *   us   -> PAIR_REPLY   (phone_id, phone_pub)
 *   user -> fingerprint  (BiometricPrompt in PairingActivity)
 *   us   -> PAIR_CONFIRM (pairing_id, mac_id)
 *   Mac  -> PAIR_DONE    (pairing_id)                     [Mac stores trust]
 *   us   : store trust locally.
 */
object PairingController {

    private const val TAG = "MacSyncPair"
    const val ACTION_TRUST_CHANGED = "it.luigi.macsync.TRUST_CHANGED"
    private const val PREFS = "MacSync_Pair"
    private const val KEY_PENDING = "pending_v1"
    private const val KEY_PENDING_BEGIN = "pending_begin_v1"

    data class Pending(
        val macId: String,
        val macName: String,
        val macPubB64: String,
        val macSessionPubB64: String,
        val keyAlias: String,
        val sessionKeyAlias: String,
        val pairingId: String,
    )

    @Volatile
    private var pending: Pending? = null

    private val _pendingFlow = MutableStateFlow<Pending?>(null)
    val pendingFlow: StateFlow<Pending?> = _pendingFlow

    private fun updatePending(value: Pending?) {
        pending = value
        _pendingFlow.value = value
    }

    /** Persist/restore the pending pairing so a process kill does not leave a
     *  notification that opens an empty activity. */
    fun restore(context: Context) {
        if (pending != null) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null) ?: return
        try {
            val o = JSONObject(raw)
            val p = Pending(
                macId = o.getString("macId"),
                macName = o.getString("macName"),
                macPubB64 = o.getString("macPubB64"),
                macSessionPubB64 = o.getString("macSessionPubB64"),
                keyAlias = o.getString("keyAlias"),
                sessionKeyAlias = o.getString("sessionKeyAlias"),
                pairingId = o.getString("pairingId"),
            )
            updatePending(p)
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_PENDING).apply()
        }
    }

    private fun persist(context: Context, value: Pending?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (value == null) {
            editor.remove(KEY_PENDING)
        } else {
            val o = JSONObject().apply {
                put("macId", value.macId)
                put("macName", value.macName)
                put("macPubB64", value.macPubB64)
                put("macSessionPubB64", value.macSessionPubB64)
                put("keyAlias", value.keyAlias)
                put("sessionKeyAlias", value.sessionKeyAlias)
                put("pairingId", value.pairingId)
            }
            editor.putString(KEY_PENDING, o.toString())
        }
        editor.apply()
    }

    fun current(): Pending? = pending

    private fun savePendingBegin(context: Context, parts: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_BEGIN, JSONArray(parts).toString()).apply()
    }

    private fun clearPendingBegin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING_BEGIN).apply()
    }

    /** Called after the device is unlocked / the app resumes. Android refuses to
     *  create biometric-gated keys while locked, so the original PAIR_BEGIN is
     *  replayed here instead of leaving the Mac/phone stuck at "verifying". */
    @Synchronized
    fun retryPendingBegin(context: Context): Boolean {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_BEGIN, null) ?: return false
        return try {
            val arr = JSONArray(raw)
            val parts = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) parts.add(arr.getString(i))
            onBegin(context, parts)
        } catch (e: Exception) {
            Log.w(TAG, "retryPendingBegin failed: ${e.message}")
            false
        }
    }

    /** Phone-initiated pairing from the "connected devices" list. */
    fun requestPairing(context: Context, macId: String) {
        val phoneId = TrustedMacStore.deviceId(context)
        send(context, listOf(
            "PAIR_REQUEST", UnlockProtocol.VERSION.toString(), phoneId, macId,
        ))
    }

    /** parts = PAIR_BEGIN, 1, mac_id, mac_name, mac_pub_b64 */
    fun onBegin(context: Context, parts: List<String>): Boolean {
        val version = parts.getOrNull(1)?.toIntOrNull() ?: return false
        if (version != UnlockProtocol.VERSION) return false
        val macId = parts.getOrNull(2) ?: return false
        val macName = parts.getOrNull(3)?.ifBlank { macId } ?: macId
        val macPubB64 = parts.getOrNull(4) ?: return false
        val macSessionPubB64 = parts.getOrNull(5) ?: macPubB64

        val alias = keyAliasFor(macId)
        val sessionAlias = sessionAliasFor(macId)
        val phonePub: ByteArray?
        val phoneSessionPub: ByteArray?
        try {
            // Recreate the unlock key when it is missing *or invalidated* (the
            // latter happens after a fingerprint enrollment change).
            if (!AndroidKeyStore.isUnlockKeyUsable(alias)) {
                AndroidKeyStore.deleteKey(alias)
                AndroidKeyStore.createKey(alias)
            }
            phonePub = AndroidKeyStore.publicKeyDer(alias)
            if (!AndroidKeyStore.sessionKeyExists(sessionAlias)) {
                AndroidKeyStore.createSessionKey(sessionAlias)
            }
            phoneSessionPub = AndroidKeyStore.publicKeyDer(sessionAlias)
        } catch (e: Exception) {
            // Android refuses to generate biometric-gated keys while the device
            // is locked. Remember the request and retry after USER_PRESENT.
            Log.w(TAG, "pairing key generation failed (device locked?): ${e.message}")
            savePendingBegin(context, parts)
            PairingNotification.postUnlockRequired(context, macName)
            return false
        }
        if (phonePub == null || phoneSessionPub == null) {
            Log.w(TAG, "pairing public key unavailable")
            savePendingBegin(context, parts)
            PairingNotification.postUnlockRequired(context, macName)
            return false
        }

        val phoneId = TrustedMacStore.deviceId(context)
        // Split to stay under the 180-byte BLE notification cap: the two P-256
        // public keys do not fit in a single packet.
        send(context, listOf(
            "PAIR_REPLY", UnlockProtocol.VERSION.toString(),
            phoneId, UnlockProtocol.b64(phonePub),
        ))
        send(context, listOf(
            "PAIR_SESSION_PUB", UnlockProtocol.VERSION.toString(),
            phoneId, UnlockProtocol.b64(phoneSessionPub),
        ))

        val pairingId = UnlockProtocol.b64(ByteArray(12).also { SecureRandom().nextBytes(it) })
        updatePending(Pending(macId, macName, macPubB64, macSessionPubB64, alias, sessionAlias, pairingId))
        persist(context, pending)
        clearPendingBegin(context)
        PairingNotification.dismissUnlockRequired(context)
        PairingNotification.post(context, macName)
        return true
    }

    /** After a successful fingerprint: tell the Mac to finalize. */
    fun confirm(context: Context): Boolean {
        val p = pending ?: return false
        send(context, listOf(
            "PAIR_CONFIRM", UnlockProtocol.VERSION.toString(), p.pairingId, p.macId,
        ))
        return true
    }

    /** Mac finished authorizing: store trust locally. */
    fun onDone(context: Context, pairingId: String): Boolean {
        val p = pending ?: return false
        if (pairingId.isNotEmpty() && pairingId != p.pairingId) return false
        TrustedMacStore.upsert(
            context,
            TrustedMac(
                macId = p.macId,
                name = p.macName,
                publicKeyB64 = p.macPubB64,
                pairingId = p.pairingId,
                keyAlias = p.keyAlias,
                sessionKeyAlias = p.sessionKeyAlias,
                sessionPublicKeyB64 = p.macSessionPubB64,
                trusted = true,
            ),
        )
        updatePending(null)
        persist(context, null)
        PairingNotification.dismiss(context)
        notifyTrustChanged(context)
        GattServerManager.getInstance(context).restartSessionAuth()
        return true
    }

    fun cancel(context: Context) {
        val p = pending
        if (p != null) {
            AndroidKeyStore.deleteKey(p.keyAlias)
            send(context, listOf("PAIR_CANCEL", UnlockProtocol.VERSION.toString(), p.macId))
        }
        updatePending(null)
        persist(context, null)
        PairingNotification.dismiss(context)
        notifyTrustChanged(context)
    }

    /** User removes the binding: delete the Keystore key + local trust, tell the Mac. */
    fun revoke(context: Context, macId: String) {
        TrustedMacStore.remove(context, macId)
        val phoneId = TrustedMacStore.deviceId(context)
        send(context, listOf("UNBIND", UnlockProtocol.VERSION.toString(), phoneId))
        GattServerManager.getInstance(context).dropSessionForUnbind()
        notifyTrustChanged(context)
    }

    private fun notifyTrustChanged(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_TRUST_CHANGED).setPackage(context.applicationContext.packageName)
        )
    }

    private fun sessionAliasFor(macId: String): String =
        "px_session_" + macId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun keyAliasFor(macId: String): String =
        "px_unlock_" + macId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun send(context: Context, fields: List<String>) {
        try {
            val packet = fields.joinToString(UnlockProtocol.SEP)
            val sent = GattServerManager.getInstance(context).sendLinkPacket(packet)
            if (!sent) Log.w(TAG, "link not ready, dropped: ${fields.firstOrNull()}")
        } catch (e: Exception) {
            Log.e(TAG, "send failed: ${e.message}")
        }
    }
}
