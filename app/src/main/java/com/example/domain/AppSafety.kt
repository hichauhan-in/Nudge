package com.example.domain

object AppSafety {
    private val protectedPackages = setOf(
        "android", "com.android.settings", "com.android.systemui",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.emergency", "com.android.server.telecom"
    )

    fun isProtected(packageName: String, ownPackageName: String): Boolean =
        packageName == ownPackageName || packageName in protectedPackages
}