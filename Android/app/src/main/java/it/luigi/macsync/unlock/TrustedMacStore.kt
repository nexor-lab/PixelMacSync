package it.luigi.macsync.unlock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** A Mac the phone has completed pairing with. */
data class TrustedMac(
    val macId: String,
    val name: String,
    val publicKeyB64: String,
    val pairingId: String,
    val keyAlias: String,
    var trusted: Boolean = true,
    var allowManualLock: Boolean = true,
    var allowLidWake: Boolean = true,
    var lastAuthAt: Long = 0L,
)

/**
 * Phone-side trust state. Persists only public material + the phone's own
 * random device_id; the signing private key lives in the AndroidKeyStore and is
 * never written here.
 */
object TrustedMacStore {

    private const val PREFS = "MacSync_Unlock"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MACS = "trusted_macs"
    private const val KEY_PENDING = "pending_pairing"

    // --- phone identity ---------------------------------------------------

    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val rnd = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = rnd.joinToString("") { "%02x".format(it) }
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    // --- trusted macs -----------------------------------------------------

    fun list(context: Context): List<TrustedMac> {
        val raw = prefs(context).getString(KEY_MACS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TrustedMac(
                    macId = o.getString("macId"),
                    name = o.optString("name", o.getString("macId")),
                    publicKeyB64 = o.optString("publicKey", ""),
                    pairingId = o.optString("pairingId", ""),
                    keyAlias = o.optString("keyAlias", ""),
                    trusted = o.optBoolean("trusted", false),
                    allowManualLock = o.optBoolean("allowManualLock", true),
                    allowLidWake = o.optBoolean("allowLidWake", true),
                    lastAuthAt = o.optLong("lastAuthAt", 0L),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, macId: String): TrustedMac? =
        list(context).firstOrNull { it.macId == macId }

    fun isTrusted(context: Context, macId: String): Boolean =
        get(context, macId)?.trusted == true

    fun upsert(context: Context, mac: TrustedMac) {
        val all = list(context).filterNot { it.macId == mac.macId }.toMutableList()
        all.add(mac)
        save(context, all)
    }

    fun setTrusted(context: Context, macId: String, trusted: Boolean) {
        val all = list(context).map { if (it.macId == macId) it.copy(trusted = trusted) else it }
        save(context, all)
    }

    fun markAuthorized(context: Context, macId: String) {
        val all = list(context).map {
            if (it.macId == macId) it.copy(lastAuthAt = System.currentTimeMillis()) else it
        }
        save(context, all)
    }

    fun remove(context: Context, macId: String) {
        val mac = get(context, macId)
        if (mac != null) AndroidKeyStore.deleteKey(mac.keyAlias)
        save(context, list(context).filterNot { it.macId == macId })
    }

    private fun save(context: Context, list: List<TrustedMac>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("macId", m.macId)
                put("name", m.name)
                put("publicKey", m.publicKeyB64)
                put("pairingId", m.pairingId)
                put("keyAlias", m.keyAlias)
                put("trusted", m.trusted)
                put("allowManualLock", m.allowManualLock)
                put("allowLidWake", m.allowLidWake)
                put("lastAuthAt", m.lastAuthAt)
            })
        }
        prefs(context).edit().putString(KEY_MACS, arr.toString()).apply()
    }

    // --- pending pairing (in-memory would be better; kept short-lived) ----

    fun setPending(context: Context, macId: String, macPubB64: String, nonceMacB64: String) {
        prefs(context).edit()
            .putString(KEY_PENDING, JSONObject().apply {
                put("macId", macId); put("macPub", macPubB64); put("nonceMac", nonceMacB64)
            }.toString())
            .apply()
    }

    fun pending(context: Context): JSONObject? {
        val raw = prefs(context).getString(KEY_PENDING, null) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
