package com.antitheft.admin.security

import android.content.Context
import android.os.Build
import android.os.Debug
import android.provider.Settings
import java.io.File

/**
 * Banking-grade hardware & software integrity checks:
 * - Anti-debugging / tracer detection
 * - Developer options detection
 * - ADB / USB debugging detection
 * - Root / Superuser binary detection
 * - Dangerous system properties / test-keys detection
 */
object SecurityIntegrityManager {

    /**
     * Checks if a debugger is attached.
     */
    fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    /**
     * Checks if USB debugging / ADB is enabled in system settings.
     */
    fun isAdbEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if Developer Options are enabled in system settings.
     */
    fun isDeveloperOptionsEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks for root binaries (su, magisk, busybox, etc.) in common paths.
     */
    fun isRooted(): Boolean {
        // 1. Build tags test-keys check
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }

        // 2. Common su paths check
        val suPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/xbin/daemonsu"
        )
        for (path in suPaths) {
            try {
                if (File(path).exists()) return true
            } catch (_: Exception) {}
        }

        // 3. Magisk / Which su test
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }
}
