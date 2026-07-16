package com.example

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Thin wrapper around the Google Play In-App Review API.
 *
 * Google Play itself decides whether to actually show the rating sheet — it is quota-limited and
 * is never shown to users who have already reviewed — so we simply request it at natural, spaced
 * out moments and let Play handle the rest. No API keys or extra configuration are required.
 *
 * Note: the review sheet only appears for builds installed through Google Play (production or an
 * internal/closed testing track under the same package name). It will NOT appear for local or
 * emulator debug installs — that is expected behavior, not a bug.
 */
object AppReviewManager {
    private const val PREFS = "focus_time_prefs"
    private const val KEY_ELIGIBLE_OPENS = "review_eligible_opens"
    private const val FIRST_PROMPT_AT = 3      // first offer after the 3rd post-onboarding open
    private const val REPROMPT_INTERVAL = 10   // then re-offer every 10 opens

    /**
     * Counts a genuine app open and, at spaced-out thresholds, asks Play to surface the in-app
     * review flow. Safe to call on every launch; it self-throttles and never affects the UX.
     */
    fun maybeRequestReview(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val opens = prefs.getInt(KEY_ELIGIBLE_OPENS, 0) + 1
        prefs.edit().putInt(KEY_ELIGIBLE_OPENS, opens).apply()

        if (opens < FIRST_PROMPT_AT) return
        if ((opens - FIRST_PROMPT_AT) % REPROMPT_INTERVAL != 0) return

        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (task.isSuccessful && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    manager.launchReviewFlow(activity, task.result)
                } catch (e: Exception) {
                    // A review request must never affect the app experience.
                }
            }
        }
    }
}
