package it.luigi.macsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** One phone number belonging to a contact. */
data class PhoneContact(val id: String, val name: String, val number: String)

/**
 * Reads the device contacts and remembers which ones the user chose to sync to
 * the Mac (Multi-... contacts). Only **selected** contacts are ever sent, and the
 * whole feature is opt-in (see ADR-013 / ADR-025).
 */
object ContactsRepository {

    private const val PREFS = "MacSync_Prefs"
    private const val KEY_SELECTED = "selected_contacts"
    private const val KEY_REMOTE_DIAL = "remote_dial_enabled"
    private const val KEY_AUTO_SYNC = "contact_auto_sync"
    private const val KEY_DIRECT_DIAL = "direct_dial_enabled"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** All contacts (first number per contact), sorted by name. Empty without permission. */
    fun load(context: Context): List<PhoneContact> {
        if (!hasPermission(context)) return emptyList()
        val out = LinkedHashMap<String, PhoneContact>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val id = c.getString(idIdx) ?: continue
                    if (out.containsKey(id)) continue
                    val name = c.getString(nameIdx) ?: ""
                    val number = (c.getString(numIdx) ?: "").replace(Regex("[\\s\\-()]"), "")
                    if (number.isBlank()) continue
                    out[id] = PhoneContact(id, name, number)
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    fun selectedIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED, emptySet()) ?: emptySet()

    fun setSelectedIds(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED, ids).apply()
    }

    /** The selected contacts, with fresh data from the device. */
    fun selectedContacts(context: Context): List<PhoneContact> {
        val ids = selectedIds(context)
        if (ids.isEmpty()) return emptyList()
        return load(context).filter { it.id in ids }
    }

    fun remoteDialEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_REMOTE_DIAL, false)
    fun setRemoteDialEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMOTE_DIAL, value).apply()
    }

    fun autoSync(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_SYNC, false)
    fun setAutoSync(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SYNC, value).apply()
    }

    /**
     * When enabled, a Mac dial places the call **directly** via the privileged
     * shell (`ACTION_CALL`, root/Shizuku) instead of opening the dialer. Default
     * off (opening the dialer is the safe simulation).
     */
    fun directDial(context: Context): Boolean = prefs(context).getBoolean(KEY_DIRECT_DIAL, false)
    fun setDirectDial(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DIRECT_DIAL, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
