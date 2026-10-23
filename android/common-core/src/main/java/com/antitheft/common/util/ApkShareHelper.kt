package com.antitheft.common.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

object ApkShareHelper {

    const val RELEASES_WEB_URL = "https://she-92h6.onrender.com/releases"

    fun showShareOptions(
        context: Context,
        appName: String,
        apkFileName: String
    ) {
        val options = arrayOf(
            "Send APK File (Bluetooth, Quick Share, Chat)",
            "Share Web Download Link",
            "Copy Download Link to Clipboard"
        )

        AlertDialog.Builder(context)
            .setTitle("Share $appName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareApkBinary(context, appName, apkFileName)
                    1 -> shareDownloadLink(context, appName)
                    2 -> copyDownloadLink(context)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun shareApkBinary(context: Context, appName: String, apkFileName: String) {
        try {
            val sourceDir = context.applicationInfo.sourceDir
            val sourceFile = File(sourceDir)
            if (!sourceFile.exists()) {
                Toast.makeText(context, "APK binary not found on storage", Toast.LENGTH_SHORT).show()
                return
            }

            val shareDir = File(context.cacheDir, "shared_apks")
            if (!shareDir.exists()) shareDir.mkdirs()

            val targetApk = File(shareDir, apkFileName)
            sourceFile.copyTo(targetApk, overwrite = true)

            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, targetApk)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, apkUri)
                putExtra(Intent.EXTRA_SUBJECT, "$appName - Anti-Theft APK")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "$appName (SHE Sentinel Anti-Theft Defense).\nOfficial release: $RELEASES_WEB_URL"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share $appName via Bluetooth, Quick Share...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("ApkShareHelper", "Failed sharing APK binary", e)
            Toast.makeText(context, "Sharing APK failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun shareDownloadLink(context: Context, appName: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "SHE Anti-Theft APK Download")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Download $appName for Android Handset Defense.\nDirect portal: $RELEASES_WEB_URL\n(Zero commercial cloud telemetry, 100% self-hosted & open-source)."
                )
            }
            val chooser = Intent.createChooser(shareIntent, "Share Download Link")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to launch share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyDownloadLink(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("SHE Download Link", RELEASES_WEB_URL))
            Toast.makeText(context, "Download link copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }
}
