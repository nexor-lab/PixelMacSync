package it.luigi.macsync

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * In-app language selection backed by the platform per-app locale API.
 *
 * An empty locale list means "follow the system". The system persists the
 * choice and applies it to every activity/service of this app.
 */
object AppLanguage {

    const val SYSTEM = ""
    const val ZH_CN = "zh-CN"
    const val EN = "en"

    fun current(context: Context): String {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return SYSTEM
        val locales = manager.applicationLocales
        if (locales.isEmpty) return SYSTEM
        return locales.toLanguageTags()
    }

    fun set(context: Context, tag: String) {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales =
            if (tag.isBlank()) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(tag)
    }
}
