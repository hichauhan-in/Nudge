package com.example.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLanguage {
    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty()
    } else context.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE).getString("app_language", "").orEmpty()

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val language = selected(context)
        if (language.isEmpty()) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(configuration)
    }

    fun select(activity: Activity, language: String) {
        require(language in setOf("", "en", "hi"))
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
        } else {
            activity.getSharedPreferences("focus_time_prefs", Context.MODE_PRIVATE).edit().putString("app_language", language).apply()
            activity.recreate()
        }
    }
}