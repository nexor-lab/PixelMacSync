package it.luigi.macsync.unlock

import android.content.Context

/**
 * Remote-unlock security policy (phone-side settings).
 *  - awayAutoLock: lock the Mac when the phone moves away.
 *  - remoteWake:   allow phone-triggered wake/unlock; when off the binding UI is hidden.
 */
object UnlockSettings {

    private const val PREFS = "MacSync_Unlock"
    private const val KEY_AWAY_LOCK = "away_auto_lock"
    private const val KEY_REMOTE_WAKE = "remote_wake"

    fun awayAutoLock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AWAY_LOCK, true)

    fun setAwayAutoLock(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AWAY_LOCK, value).apply()
    }

    fun remoteWake(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMOTE_WAKE, true)

    fun setRemoteWake(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMOTE_WAKE, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
