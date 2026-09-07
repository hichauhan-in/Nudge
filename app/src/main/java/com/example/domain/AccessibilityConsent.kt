package com.example.domain

import android.content.Context

object AccessibilityConsent {
    const val VERSION = 1
    const val PREFS_NAME = "focus_time_prefs"
    const val VERSION_KEY = "accessibility_disclosure_version"
    const val ACCEPTED_KEY = "accessibility_disclosure_accepted"

    fun hasDecision(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(VERSION_KEY, 0) == VERSION

    fun isAccepted(context: Context): Boolean = hasDecision(context) &&
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ACCEPTED_KEY, false)

    fun accept(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(VERSION_KEY, VERSION)
            .putBoolean(ACCEPTED_KEY, true)
            .commit()

    fun decline(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(VERSION_KEY, VERSION)
            .putBoolean(ACCEPTED_KEY, false)
            .apply()
    }
}