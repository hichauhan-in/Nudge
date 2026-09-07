package com.example.domain

enum class ForegroundKind { APP, INPUT_METHOD, SYSTEM_OVERLAY }

object ForegroundClassifier {
    private val systemOverlays = setOf(
        "android", "com.android.systemui", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller", "com.android.clipboardui",
        "com.samsung.android.clipboarduiservice", "com.samsung.android.app.smartcapture",
        "com.miui.notification", "com.coloros.notificationmanager", "com.oneplus.systemui.support"
    )

    fun classify(packageName: String, className: String, inputMethods: Set<String>): ForegroundKind = when {
        packageName in inputMethods && !className.endsWith("Activity", ignoreCase = true) -> ForegroundKind.INPUT_METHOD
        packageName in systemOverlays -> ForegroundKind.SYSTEM_OVERLAY
        else -> ForegroundKind.APP
    }
}