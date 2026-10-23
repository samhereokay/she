package com.antitheft.common.update

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Robust in-app OTA Update Manager for SHE applications.
 *
 * Checks for updates from the backend `/releases/check` endpoint.
 * Downloads the signed APK over HTTPS, validates SHA-256 integrity,
 * and launches package installer intent to override and install automatically.
 */
object AppUpdateManager {

    data class UpdateInfo(
        val isUpdateAvailable: Boolean,
        val currentVersionCode: Long,
        val latestVersionCode: Long,
        val latestVersionName: String,
        val downloadUrl: String,
        val expectedSha256: String,
        val sizeBytes: Long
    )

    /**
     * Queries the server for update availability for the calling application.
     */
    suspend fun checkForUpdates(
        context: Context,
        serverUrl: String,
        appType: String // "guardian" or "admin"
    ): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val base = serverUrl.trimEnd('/')
            val checkUrl = URL("$base/releases/check")
            val conn = (checkUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "SHE-${appType}-App")
            }

            if (conn.responseCode != 200) {
                return@withContext Result.failure(
                    Exception("Server returned HTTP ${conn.responseCode}")
                )
            }

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)

            val appObj = if (root.has(appType)) {
                root.getJSONObject(appType)
            } else {
                return@withContext Result.failure(Exception("Unknown app type $appType in manifest"))
            }

            val remoteVersionCode = appObj.optLong("version_code", 0L)
            val remoteVersionName = appObj.optString("version_name", "")
            val downloadUrl = appObj.optString("download_url", "")
            val sha256 = appObj.optString("sha256", "")
            val sizeBytes = appObj.optLong("size_bytes", 0L)

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }

            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            val isAvailable = remoteVersionCode > currentVersionCode && downloadUrl.isNotBlank()

            Result.success(
                UpdateInfo(
                    isUpdateAvailable = isAvailable,
                    currentVersionCode = currentVersionCode,
                    latestVersionCode = remoteVersionCode,
                    latestVersionName = remoteVersionName,
                    downloadUrl = downloadUrl,
                    expectedSha256 = sha256,
                    sizeBytes = sizeBytes
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK file into cache/updates, validates its SHA-256 hash, and prompts install.
     */
    suspend fun downloadAndInstall(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val targetFile = File(updateDir, "update_${updateInfo.latestVersionCode}.apk")
            if (targetFile.exists()) targetFile.delete()

            val url = URL(updateInfo.downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 60000
                setRequestProperty("User-Agent", "SHE-AppUpdate")
            }

            val totalBytes = if (conn.contentLengthLong > 0) conn.contentLengthLong else updateInfo.sizeBytes
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            withContext(Dispatchers.Main) { onProgress(progress) }
                        }
                    }
                    output.flush()
                }
            }

            // Verify integrity if SHA-256 is present
            if (updateInfo.expectedSha256.isNotBlank()) {
                val computedSha256 = calculateSha256(targetFile)
                if (!computedSha256.equals(updateInfo.expectedSha256, ignoreCase = true)) {
                    targetFile.delete()
                    return@withContext Result.failure(
                        SecurityException("APK SHA-256 checksum mismatch! Expected: ${updateInfo.expectedSha256}, Got: $computedSha256")
                    )
                }
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Triggers the Android package installer to override and install the downloaded APK.
     */
    fun launchInstallIntent(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permissionIntent)
                    Toast.makeText(
                        context,
                        "Please allow 'Install unknown apps' permission to complete the update override.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }

            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * High-level dialog flow: Checks for update, prompts user, downloads with progress, and triggers install.
     */
    fun checkAndPromptUpdate(
        context: Context,
        serverUrl: String,
        appType: String,
        coroutineScope: CoroutineScope,
        manualTrigger: Boolean = false
    ) {
        coroutineScope.launch {
            if (manualTrigger) {
                Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()
            }
            val result = checkForUpdates(context, serverUrl, appType)
            result.onSuccess { info ->
                if (info.isUpdateAvailable) {
                    AlertDialog.Builder(context)
                        .setTitle("Update Available (v${info.latestVersionName})")
                        .setMessage("A new version of SHE (${info.latestVersionName}, Build ${info.latestVersionCode}) is available.\n\nWould you like to download and install this update now?")
                        .setPositiveButton("Update Now") { _, _ ->
                            performDownloadAndInstall(context, info, coroutineScope)
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else if (manualTrigger) {
                    Toast.makeText(context, "You are already using the latest version (Build ${info.currentVersionCode}).", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                if (manualTrigger) {
                    Toast.makeText(context, "Failed to check updates: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun performDownloadAndInstall(
        context: Context,
        info: UpdateInfo,
        coroutineScope: CoroutineScope
    ) {
        val progressDialog = AlertDialog.Builder(context)
            .setTitle("Downloading Update")
            .setMessage("Downloading v${info.latestVersionName} (0%)...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        coroutineScope.launch {
            val downloadRes = downloadAndInstall(context, info) { percent ->
                progressDialog.setMessage("Downloading v${info.latestVersionName} ($percent%)...")
            }
            progressDialog.dismiss()

            downloadRes.onSuccess { apkFile ->
                Toast.makeText(context, "Download complete! Opening installer...", Toast.LENGTH_SHORT).show()
                launchInstallIntent(context, apkFile)
            }.onFailure { e ->
                AlertDialog.Builder(context)
                    .setTitle("Update Failed")
                    .setMessage("Failed to download update: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(65536)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
