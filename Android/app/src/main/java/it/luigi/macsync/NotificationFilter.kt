package it.luigi.macsync

import android.content.Context

/**
 * Gestisce la lista delle app le cui notifiche vengono inoltrate al Mac.
 *
 * Al primo avvio viene creato un set predefinito con le app "importanti" che
 * risultano installate sul telefono. L'utente può poi modificarle dalla UI.
 */
object NotificationFilter {

    const val PREFS = "MacSync_Prefs"
    const val KEY_ENABLED_APPS = "enabled_apps"

    // App considerate importanti di default (se installate).
    private val DEFAULT_PACKAGES = listOf(
        "com.tencent.mm",                      // WeChat
        "com.tencent.mobileqq",                // QQ
        "com.android.mms",                     // SMS (AOSP)
        "com.google.android.apps.messaging",   // Google Messages
        "org.telegram.messenger",              // Telegram
        "org.thunderdog.challegram",           // Telegram X
        "com.whatsapp",                        // WhatsApp
        "com.whatsapp.w4b",                    // WhatsApp Business
        "com.google.android.gm"                // Gmail
    )

    @Synchronized
    fun ensureInitialized(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ENABLED_APPS)) return

        val installed = DEFAULT_PACKAGES.filter { pkg ->
            try {
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) {
                false
            }
        }.toSet()

        prefs.edit().putStringSet(KEY_ENABLED_APPS, installed).apply()
    }

    fun enabledApps(context: Context): Set<String> {
        ensureInitialized(context)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED_APPS, emptySet()) ?: emptySet()
    }
}
