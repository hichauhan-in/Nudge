# Nudge 9.0.0: Play Store Resubmission

Prepared 7 September 2026 for application ID `in.hichauhan.nudge`, version name `9.0.0`, version code `9`.

This is a release checklist, not a promise of Google approval. The previously rejected artifact, current Play Console declarations, public policy URL, signing credentials, and AI Studio's final generated artifact are not accessible from this workspace.

## Accessibility Rejection Fix

- The normal first-use flow shows a separate AccessibilityService disclosure before Android settings or event processing.
- A matching permission introduction precedes that disclosure. **View Permission Details** only navigates to the detailed page and does not save consent or open Android settings. **Not Now** and Back continue without automatic monitoring. Show both pages in the reviewer video.
- The disclosure names foreground package/window access, background operation, local storage, feature purposes, and optional user-initiated exports.
- Two distinct buttons are present on the detailed disclosure: **Agree And Enable** and **Decline**. No toggle grants consent.
- Only **Agree And Enable** saves acceptance and opens Android settings. Android service approval is a separate step.
- Decline and Back leave monitoring off. Home, scrolling, elapsed time, and returning to the app do not grant acceptance.
- Both buttons remain outside the scrolling explanation and are covered by compact/large-font tests.
- Disclosure version 2 invalidates older acceptance. The old, unused one-button permission screen was removed.
- Withdrawal stops timers/monitoring and disables the accessibility service. Backup restore never imports consent.
- `isAccessibilityTool=false`; no screen-content retrieval, interactive-window retrieval, or gesture capability is requested.

## Actions Required in Play Console

1. **Upload the right artifact.** Build and upload a signed 9.0.0 AAB using the existing Play upload identity. Version code 9 must be unused; increase it if needed. A locally unsigned bundle is not an upload-ready artifact. Do not use a debug signing certificate for the update.
2. **Update the Accessibility declaration.** Declare app functionality: deterministic, user-configured app reminders, timers, schedules, individual/shared daily budgets, and cooldowns. Do not identify Nudge as a disability-support accessibility tool, parental supervision app, or autonomous agent. Explain that package/window events are processed in the background after consent; history stays local except explicit exports.
3. **Submit a new reviewer video.** Use the exact signed build. Show all steps listed below, not just Android's service description. An old video demonstrating a one-button screen is not sufficient.
4. **Update the foreground-service declaration.** Retain `specialUse`: user-started mindful timers continue across app switches, with grouped countdown notifications and reset actions. Explain why delaying/interruption would miss a user-selected deadline. Show starting a timer, its notification, reset, and service stopping when no timers remain. Do not declare camera/location/media/health/data-sync types that are not used.
5. **Publish the updated privacy policy.** Deploy `index.html` to the public URL already configured in Play Console. It must be accessible without login, not a PDF, and match the developer/app identity. Verify it actually serves the new text after publication.
6. **Recheck Data safety against the final artifact.** Local-only processing differs from Google's off-device collection definition. Evaluate optional user-directed CSV/backup exports and the chosen document provider, Google Play Review SDK practices, and external payment/browser flows. Do not select answers merely because the app lacks Internet permission. No ads, account, app-operated telemetry, or upload server are implemented. The source handles installed-app identifiers and local app activity, not messages, credentials, location, or browsing contents.
7. **Recheck the store listing and audience.** Describe voluntary digital self-management, not guaranteed blocking, medical treatment, addiction cure, full system-wide screen-time measurement, or tamper-proof parental control. Hindi covers consent/core controls; some detailed copy is still English. Keep target audience, ads declaration, content rating, and listing aligned with the actual release.
8. **Verify optional support destinations.** UPI/Ko-fi support must remain voluntary and grant no digital content, membership, badge, feature, or service. Review current Payments policy and destination configuration; a label of "donation" alone does not establish an exemption. If the destination sells digital benefits, use a compliant billing/program flow or remove that destination before release.

## Reviewer Video Sequence

1. Fresh install, launch Nudge, and tap Continue. Show the Accessibility Permission introduction and its two choices. Tap View Permission Details, then show the complete detailed disclosure and both consent buttons. Slowly scroll through all disclosure paragraphs. The introduction alone is not the required disclosure.
2. Tap Decline. Show that onboarding/home remains usable and automatic monitoring is off.
3. After declining, reopen through Configure > Enable Guard System Service > Review Accessibility Access, or the Home accessibility warning. The introduction appears first; tap View Permission Details for the disclosure. Press Back, reopen, press Home, and return: no consent is recorded. Once access is enabled, Configure > Guard System Service opens Monitoring Status and disable/pause controls, not another consent request.
4. Tap Agree And Enable on the detailed disclosure, then separately enable Nudge in Android Accessibility settings.
5. Select a launchable app, open it, show the prompt, start a short timer, and show the countdown notification. Demonstrate close, extend, and deliberate bypass.
6. Show a small daily/shared budget or a configured schedule using deterministic user choices. Show that Android Settings and uninstall remain accessible.
7. Withdraw consent in Configure > Privacy & Data and reopen the selected app: no prompt. Demonstrate that re-enabling requires the disclosure again. Configure > Guard System Service > Monitoring status also offers confirmed disable, plus Android app settings and full-data reset under Recovery.

Keep the video accessible to reviewers. Screen previews generated by Robolectric are not substitutes for this device recording. If exports are shown, use dummy history and never record a real backup passphrase.

## Manifest and Permissions

| Declaration | Action |
| --- | --- |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Retain; submit the matching special-use declaration/video. |
| `POST_NOTIFICATIONS` | Retain; notification visibility remains independent from accessibility consent. |
| Accessibility service with `BIND_ACCESSIBILITY_SERVICE` | Retain as the service binding restriction, not a general runtime permission request. |
| Quick Settings tile with `BIND_QUICK_SETTINGS_TILE` | New protected service binding; no new runtime consent permission. |
| `MAIN/LAUNCHER` and `MAIN/HOME` queries | Retain scoped app visibility. |
| AndroidX app-private dynamic receiver permission | Generated signature-level protection; not a user data permission. |
| Internet, `QUERY_ALL_PACKAGES`, usage access, storage, exact alarms, `USE_FULL_SCREEN_INTENT`, device admin | Not used. Do not add them in AI Studio to work around a publishing warning. |

Manual export/restore uses the Android document picker; no broad storage permission is necessary. Timed resume uses an inexact alarm and can be delayed by Android power management. Automatic app data backup/transfer remains disabled.

## Required Validation Before Production

Run `scripts/verify.ps1` with JDK 17 and the Android SDK, or the Gradle tasks in README. Build the signed release again in the publishing environment and inspect its merged manifest/dependencies; AI Studio must not reintroduce the old disclosure, server-AI SDKs, broad permissions, or an old version code.

Test the signed Play-delivered build on Android 10 and current Android versions, with at least one restrictive OEM, large font sizes, and a 16 KB-page device/emulator. Exercise consent/refusal/withdrawal, fresh install and upgrade, prompt loops, two simultaneous timers, notification reset, lock/unlock, process recreation, force-stop/recovery, midnight and time-zone changes, schedules, shared budgets, cooldown, timed pause, tile, language switch, and export/restore with wrong password/corrupt input/cancelled picker. Check portrait/landscape, long app names, and TalkBack.

Use Play internal testing and its pre-launch report before production. A host test or a successful bundle build cannot validate OEM event delivery, Android's background-launch restrictions, Play Review availability, payment destinations, or store review approval. No physical device/emulator was connected during this workspace session; those checks remain required.

## References

- [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Prominent disclosure guidance](https://support.google.com/googleplay/android-developer/answer/11150561)
- [AccessibilityService declaration and video](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Foreground service declaration](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738)
- [16 KB page-size compatibility](https://developer.android.com/guide/practices/page-sizes)