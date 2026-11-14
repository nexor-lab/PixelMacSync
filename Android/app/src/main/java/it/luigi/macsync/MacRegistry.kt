package it.luigi.macsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A Mac that has connected at least once. */
data class SavedMac(val id: String, val name: String, val lastSeen: Long)

/**
 * Persisted list of known Macs + which one is the **Active Mac** (Multi-Mac, ADR-012).
 *
 * Phase 1: several Macs may be saved, but exactly one is active. Disconnect
 * semantics are tracked with [setUserDisconnected]: a Mac the user explicitly
 * disconnected must not be auto-accepted again until the user selects it.
 *
 * Only macOS builds that send `HELLO US <id> [US <name>]` are distinguishable.
 * Legacy clients (no `HELLO`) are stored under the single id `legacy`.
 */
object MacRegistry {

    const val LEGACY_ID = "legacy"

    private const val PREFS = "MacSync_Prefs"
    private const val KEY_MACS = "saved_macs"
    private const val KEY_ACTIVE = "active_mac_id"
    private const val KEY_USER_DISCONNECTED = "user_disconnected_mac_id"

    fun saved(context: Context): List<SavedMac> {
        val raw = prefs(context).getString(KEY_MACS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                SavedMac(id, o.optString("name", id).ifBlank { id }, o.optLong("lastSeen", 0L))
            }.sortedByDescending { it.lastSeen }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(context: Context, id: String, name: String) {
        val list = saved(context).filterNot { it.id == id }.toMutableList()
        list.add(SavedMac(id, name.ifBlank { id }, System.currentTimeMillis()))
        saveList(context, list)
    }

    fun remove(context: Context, id: String) {
        saveList(context, saved(context).filterNot { it.id == id })
        if (activeId(context) == id) setActive(context, null)
        if (userDisconnectedId(context) == id) setUserDisconnected(context, null)
    }

    fun activeId(context: Context): String? = prefs(context).getString(KEY_ACTIVE, null)

    fun setActive(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
    }

    fun userDisconnectedId(context: Context): String? =
        prefs(context).getString(KEY_USER_DISCONNECTED, null)

    fun setUserDisconnected(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id == null) remove(KEY_USER_DISCONNECTED) else putString(KEY_USER_DISCONNECTED, id)
        }.apply()
    }

    private fun saveList(context: Context, list: List<SavedMac>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("lastSeen", it.lastSeen))
        }
        prefs(context).edit().putString(KEY_MACS, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
