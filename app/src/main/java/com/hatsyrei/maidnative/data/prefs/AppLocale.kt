package com.hatsyrei.maidnative.data.prefs

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * The app's own UI language, or "" to follow the system. Android 13+ keeps it
 * (and offers it in system Settings); older versions keep it here and apply it
 * through [wrap].
 */
object AppLocale {
    /** Must match res/xml/locales_config.xml. */
    val supported = listOf("en", "zh-CN")

    // SharedPreferences, not DataStore: attachBaseContext needs it synchronously.
    private const val PREFS = "app-locale"
    private const val KEY = "tag"

    /** One of [supported], or "" for the system language. */
    fun get(context: Context): String {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.toLanguageTag()
        } else {
            prefs(context).getString(KEY, null)
        }
        if (tag.isNullOrEmpty()) return ""
        // The system's picker may store a fuller tag than ours, e.g. zh-Hans-CN for zh-CN.
        val language = Locale.forLanguageTag(tag).language
        return supported.firstOrNull { Locale.forLanguageTag(it).language == language } ?: tag
    }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit { putString(KEY, tag) }
            activity.recreate()
        }
    }

    /** [base] in the chosen language; from Android 13 the system already did this. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString(KEY, null)
        if (tag.isNullOrEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
