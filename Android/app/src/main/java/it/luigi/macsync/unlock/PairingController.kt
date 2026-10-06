package it.luigi.macsync.unlock

import android.content.Context
import android.content.Intent
import android.util.Log
import it.luigi.macsync.ble.GattServerManager
import java.security.SecureRandom

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

    data class Pending(
        val macId: String,
        val macName: String,
        val macPubB64: String,
        val keyAlias: String,
        val pairingId: String,
    )

    @Volatile
    private var pending: Pending? = null

    fun current(): Pending? = pending

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

        val alias = keyAliasFor(macId)
        if (!AndroidKeyStore.keyExists(alias)) AndroidKeyStore.createKey(alias)
        val phonePub = AndroidKeyStore.publicKeyDer(alias) ?: return false
        val phoneId = TrustedMacStore.deviceId(context)

        send(context, listOf(
            "PAIR_REPLY", UnlockProtocol.VERSION.toString(),
            phoneId, UnlockProtocol.b64(phonePub),
        ))

        val pairingId = UnlockProtocol.b64(ByteArray(12).also { SecureRandom().nextBytes(it) })
        pending = Pending(macId, macName, macPubB64, alias, pairingId)
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
                trusted = true,
            ),
        )
        pending = null
        PairingNotification.dismiss(context)
        notifyTrustChanged(context)
        return true
    }

    fun cancel(context: Context) {
        val p = pending
        if (p != null) {
            AndroidKeyStore.deleteKey(p.keyAlias)
            send(context, listOf("PAIR_CANCEL", UnlockProtocol.VERSION.toString(), p.macId))
        }
        pending = null
        PairingNotification.dismiss(context)
        notifyTrustChanged(context)
    }

    /** User removes the binding: delete the Keystore key + local trust, tell the Mac. */
    fun revoke(context: Context, macId: String) {
        TrustedMacStore.remove(context, macId)
        val phoneId = TrustedMacStore.deviceId(context)
        send(context, listOf("UNBIND", UnlockProtocol.VERSION.toString(), phoneId))
        notifyTrustChanged(context)
    }

    private fun notifyTrustChanged(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_TRUST_CHANGED).setPackage(context.applicationContext.packageName)
        )
    }

    private fun keyAliasFor(macId: String): String =
        "px_unlock_" + macId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun send(context: Context, fields: List<String>) {
        try {
            GattServerManager.getInstance(context)
                .sendNotificationToMac(fields.joinToString(UnlockProtocol.SEP))
        } catch (e: Exception) {
            Log.e(TAG, "send failed: ${e.message}")
        }
    }
}
