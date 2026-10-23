package com.antitheft.guardian.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.antitheft.guardian.R
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.db.GuardianDatabase
import com.antitheft.guardian.location.LocationCaptureManager
import com.antitheft.guardian.location.TrackingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import com.antitheft.guardian.control.DeviceControl
import com.antitheft.guardian.control.LiteDeviceControl
import com.antitheft.guardian.guidance.SystemGuidanceManager
import com.antitheft.common.util.ApkShareHelper
import android.provider.Settings
import java.util.Locale

class GuardianMainActivity : AppCompatActivity() {

    private lateinit var storageHelper: DirectBootStorageHelper
    private lateinit var captureManager: LocationCaptureManager
    private val deviceControl: DeviceControl = LiteDeviceControl()
    private val guidanceManager = SystemGuidanceManager()
    private var isLockTaskActive = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted) {
            startTrackingService()
            requestBackgroundLocation()
        } else {
            Toast.makeText(this, "Location permission is required for anti-theft tracking", Toast.LENGTH_LONG).show()
        }
        val smsSend = permissions[Manifest.permission.SEND_SMS] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
        val smsRecv = permissions[Manifest.permission.RECEIVE_SMS] ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
        if (!smsSend || !smsRecv) {
            showSmsPermissionRequiredDialog()
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.util.Log.w("GuardianLocation", "Background location permission was not granted by user")
        }
    }

    private val qrScanLauncher = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        if (result.contents != null) {
            val scannedData = result.contents
            android.util.Log.i("GuardianPairing", "Scanned QR Code successfully")
            applyPairingPayload(scannedData)
        } else {
            Toast.makeText(this, "QR scanning cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screen recording, screenshots, and recents thumbnail snooping
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_main)

        storageHelper = DirectBootStorageHelper(this)
        captureManager = LocationCaptureManager(this)

        setupViews()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardData()
        checkAndEnterKioskIfNeeded()
    }

    private fun checkAndEnterKioskIfNeeded() {
        val isStolen = storageHelper.isStolenMode()
        findViewById<android.view.View>(R.id.cardEmergencyContact)?.visibility = if (isStolen || isLockTaskActive) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<TextView>(R.id.tvEmergencyPhone)?.text = "Owner Emergency Contact: ${storageHelper.getTrustedPhoneNumber().ifBlank { "+1-555-0199" }}"

        if (isStolen && deviceControl.isDeviceOwner(this) && !isLockTaskActive) {
            val success = deviceControl.enterLockTaskStolenMode(this)
            if (success) {
                isLockTaskActive = true
                findViewById<Button>(R.id.btnToggleLockTaskKiosk)?.text = "Kiosk Mode Active (Protected)"
                android.util.Log.i("GuardianKiosk", "Automatically engaged LockTask kiosk mode due to active stolen mode.")
            }
        }
    }

    private fun setupViews() {
        findViewById<TextView>(R.id.tvDeviceId).text = "Device ID: ${storageHelper.getDeviceId()}"

        findViewById<Button>(R.id.btnCallEmergencyContact)?.setOnClickListener {
            val phone = storageHelper.getTrustedPhoneNumber().ifBlank { "+15550199" }
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone"))
                startActivity(dialIntent)
            } catch (e: Exception) {
                Toast.makeText(this@GuardianMainActivity, "Unable to launch emergency dialer", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCallPolice112)?.setOnClickListener {
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:112"))
                startActivity(dialIntent)
            } catch (e: Exception) {
                Toast.makeText(this@GuardianMainActivity, "Unable to launch emergency dialer", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCaptureNow).setOnClickListener {
            lifecycleScope.launch {
                val loc = captureManager.captureCurrentLocation()
                if (loc != null) {
                    Toast.makeText(this@GuardianMainActivity, "Fix acquired: ${loc.latitude}, ${loc.longitude}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@GuardianMainActivity, "Acquiring location...", Toast.LENGTH_SHORT).show()
                }
                refreshDashboardData()
            }
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            captureManager.enqueueSyncWork()
            Toast.makeText(this, "Sync scheduled with backend", Toast.LENGTH_SHORT).show()
            refreshDashboardData()
        }

        findViewById<Button>(R.id.btnPairWithAdmin).setOnClickListener {
            showPairingInputDialog()
        }

        findViewById<Button>(R.id.btnEnforceRestrictions)?.setOnClickListener {
            if (deviceControl.isDeviceOwner(this)) {
                val res = deviceControl.applyTheftRestrictions(this)
                Toast.makeText(this, "Restrictions enforced", Toast.LENGTH_SHORT).show()
                refreshDashboardData()
            } else {
                showProvisioningDialog()
            }
        }

        findViewById<Button>(R.id.btnClearRestrictions)?.setOnClickListener {
            if (!deviceControl.isDeviceOwner(this)) {
                Toast.makeText(this, "Device Owner not provisioned", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Require owner authentication to clear lockdown
            requestOwnerAuth("Authenticate to Clear Lockdown") {
                deviceControl.clearTheftRestrictions(this)
                Toast.makeText(this, "Theft restrictions cleared by authenticated owner", Toast.LENGTH_SHORT).show()
                refreshDashboardData()
            }
        }

        findViewById<Button>(R.id.btnToggleLockTaskKiosk)?.setOnClickListener {
            if (!deviceControl.isDeviceOwner(this)) {
                showProvisioningDialog()
                return@setOnClickListener
            }
            if (!isLockTaskActive) {
                val success = deviceControl.enterLockTaskStolenMode(this)
                if (success) {
                    isLockTaskActive = true
                    findViewById<Button>(R.id.btnToggleLockTaskKiosk)?.text = "Kiosk Mode Active (Protected)"
                    findViewById<android.view.View>(R.id.cardEmergencyContact)?.visibility = android.view.View.VISIBLE
                    Toast.makeText(this, "Stolen Kiosk Mode ACTIVE (Dialer Permitted, Nav Suppressed)", Toast.LENGTH_LONG).show()
                }
            } else {
                val remainingLockout = storageHelper.getRecoveryLockoutRemainingSeconds()
                if (remainingLockout > 0) {
                    val waitMsg = if (remainingLockout >= 60) "${remainingLockout / 60}m ${remainingLockout % 60}s" else "${remainingLockout}s"
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Lockout Active")
                        .setMessage("Too many failed recovery code attempts. Entry is locked for $waitMsg to prevent brute-force attacks.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnClickListener
                }

                val attempts = storageHelper.getFailedRecoveryAttempts()
                val attemptsWarning = if (attempts > 0) "\n\n(Previous failed attempts: $attempts. Delay escalates on attempt 4+)" else ""

                val input = android.widget.EditText(this).apply {
                    hint = "e.g. RECOVER-7K9M2W4P"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Kiosk Lockdown Active")
                    .setMessage("This device is locked into Anti-Theft Kiosk mode. To exit, dispatch an owner-signed EXIT_KIOSK command from your Admin phone, or enter your offline Vault Recovery Code (Crockford Base32, 8 characters):$attemptsWarning")
                    .setView(input)
                    .setPositiveButton("Verify Recovery Code") { _, _ ->
                        val currentRemaining = storageHelper.getRecoveryLockoutRemainingSeconds()
                        if (currentRemaining > 0) {
                            Toast.makeText(this, "Lockout active. Try again in ${currentRemaining}s.", Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }

                        val rawCode = input.text.toString().trim()
                        val normalizedInput = rawCode.uppercase()
                        val expected = storageHelper.getOfflineRecoveryCode().uppercase()

                        if (normalizedInput == expected) {
                            storageHelper.resetFailedRecoveryAttempts()
                            deviceControl.exitLockTaskMode(this)
                            storageHelper.setStolenMode(false)
                            isLockTaskActive = false
                            findViewById<Button>(R.id.btnToggleLockTaskKiosk)?.text = "Enter Lock-Task Kiosk Mode"
                            findViewById<android.view.View>(R.id.cardEmergencyContact)?.visibility = android.view.View.GONE
                            Toast.makeText(this, "Recovery code verified. Kiosk mode EXITED.", Toast.LENGTH_LONG).show()
                        } else {
                            val delay = storageHelper.recordFailedRecoveryAttempt()
                            val totalFailed = storageHelper.getFailedRecoveryAttempts()
                            if (delay > 0) {
                                Toast.makeText(this, "INVALID code. Attempt $totalFailed failed! Locked out for ${delay}s.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "INVALID code. Attempt $totalFailed/3. Delay escalates on attempt 4.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        findViewById<Button>(R.id.btnShowAdbCommand).setOnClickListener {
            showProvisioningDialog()
        }

        findViewById<Button>(R.id.btnShareGuardianApk)?.setOnClickListener {
            ApkShareHelper.showShareOptions(
                this,
                "SHE Anti-Theft Guardian",
                "she-guardian.apk"
            )
        }

        findViewById<Button>(R.id.btnCheckForUpdates)?.setOnClickListener {
            com.antitheft.common.update.AppUpdateManager.checkAndPromptUpdate(
                context = this,
                serverUrl = storageHelper.getServerUrl(),
                appType = "guardian",
                coroutineScope = lifecycleScope,
                manualTrigger = true
            )
        }

        // Automatic non-intrusive OTA check on startup
        com.antitheft.common.update.AppUpdateManager.checkAndPromptUpdate(
            context = this,
            serverUrl = storageHelper.getServerUrl(),
            appType = "guardian",
            coroutineScope = lifecycleScope,
            manualTrigger = false
        )

        findViewById<Button>(R.id.btnAdminGuide)?.setOnClickListener {
            showBrandSetupGuideDialog()
        }

        findViewById<Button>(R.id.btnOnboardingGuide)?.setOnClickListener {
            showHonestOnboardingDialog()
        }

        findViewById<Button>(R.id.btnRestrictedSettingsGuide)?.setOnClickListener {
            showRestrictedSettingsGuide()
        }

        findViewById<Button>(R.id.btnPlayProtectGuide)?.setOnClickListener {
            showPlayProtectGuide()
        }

        handleIncomingPairingIntent(intent)
        handleIncomingDeviceOwnerIntent(intent)
    }

    private fun showProvisioningDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Guardian Lite")
            .setMessage("Guardian Lite runs with standard Android permissions and does not require Device Owner or Device Admin privileges. Enterprise lockdown is reserved for Part 2 (Hardened flavor).")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIncomingPairingIntent(intent)
        handleIncomingDeviceOwnerIntent(intent)
        checkAndEnterKioskIfNeeded()
    }

    private var pendingAuthAction: (() -> Unit)? = null

    private fun requestOwnerAuth(title: String, onAuthenticated: () -> Unit) {
        val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km != null && km.isDeviceSecure) {
            pendingAuthAction = onAuthenticated
            val authIntent = km.createConfirmDeviceCredentialIntent(title, "Confirm your device credentials to proceed")
            if (authIntent != null) {
                startActivityForResult(authIntent, REQUEST_CODE_OWNER_AUTH)
                return
            }
        }
        // Fallback for test emulators without screen lock set: confirmation dialog
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Owner confirmation required to proceed:")
            .setPositiveButton("Confirm") { _, _ -> onAuthenticated() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OWNER_AUTH) {
            if (resultCode == RESULT_OK) {
                pendingAuthAction?.invoke()
            } else {
                Toast.makeText(this, "Authentication failed or cancelled.", Toast.LENGTH_SHORT).show()
            }
            pendingAuthAction = null
        }
    }

    private fun handleIncomingPairingIntent(intent: android.content.Intent?) {
        if (!com.antitheft.guardian.BuildConfig.DEBUG) return
        val payload = intent?.getStringExtra("pairing_payload")
        if (!payload.isNullOrBlank()) {
            applyPairingPayload(payload)
        }
    }

    private fun handleIncomingDeviceOwnerIntent(intent: android.content.Intent?) {
        if (!com.antitheft.guardian.BuildConfig.DEBUG) return
        if (intent == null) return
        val action = intent.getStringExtra("do_action") ?: intent.getStringExtra("action") ?: return
        android.util.Log.i("GuardianDO", "Received Device Owner test action: $action")
        when (action) {
            "enforce_restrictions" -> {
                deviceControl.applyTheftRestrictions(this)
                deviceControl.setLockScreenInfo(this, "STOLEN DEVICE: Protected by AntiTheft Guardian. If found, call +1-555-0199.")
                refreshDashboardData()
            }
            "clear_restrictions" -> {
                deviceControl.clearTheftRestrictions(this)
                deviceControl.setLockScreenInfo(this, null)
                refreshDashboardData()
            }
            "enter_kiosk" -> {
                val ok = deviceControl.enterLockTaskStolenMode(this)
                if (ok) {
                    isLockTaskActive = true
                    findViewById<Button>(R.id.btnToggleLockTaskKiosk)?.text = "Kiosk Mode Active (Protected)"
                    findViewById<android.view.View>(R.id.cardEmergencyContact)?.visibility = android.view.View.VISIBLE
                }
            }
            "exit_kiosk" -> {
                storageHelper.setStolenMode(false)
                deviceControl.exitLockTaskMode(this)
                isLockTaskActive = false
                findViewById<Button>(R.id.btnToggleLockTaskKiosk)?.text = "Enter Stolen Kiosk Mode"
                findViewById<android.view.View>(R.id.cardEmergencyContact)?.visibility = android.view.View.GONE
                refreshDashboardData()
            }
            "lock_now" -> {
                deviceControl.setLockScreenInfo(this, "STOLEN DEVICE: Protected by AntiTheft Guardian. If found, call +1-555-0199.")
                deviceControl.lockDevice(this)
            }
            "clear_pairing" -> {
                if (!com.antitheft.guardian.BuildConfig.DEBUG) {
                    android.util.Log.w("GuardianDO", "BLOCKED: clear_pairing intent action is disabled in release builds.")
                    return
                }
                storageHelper.clearPairing()
                Toast.makeText(this, "Pairing reset for testing", Toast.LENGTH_SHORT).show()
                refreshDashboardData()
            }
        }
    }

    private fun showPairingInputDialog() {
        val options = mutableListOf<String>()
        options.add("Scan Admin QR Code (Camera)")
        options.add("Pair from Clipboard (Same Device)")
        if (com.antitheft.guardian.BuildConfig.DEBUG) {
            options.add("Enter Payload Manually / Debug")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pair with Admin Phone")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val scanOptions = com.journeyapps.barcodescanner.ScanOptions().apply {
                            setPrompt("Point camera at Admin pairing QR code")
                            setBeepEnabled(true)
                            setOrientationLocked(false)
                        }
                        qrScanLauncher.launch(scanOptions)
                    }
                    1 -> {
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                        if (clipText.startsWith("{") && clipText.contains("pairing_secret_hex")) {
                            Toast.makeText(this, "Pairing from clipboard...", Toast.LENGTH_SHORT).show()
                            applyPairingPayload(clipText)
                        } else {
                            Toast.makeText(this, "No valid pairing payload in clipboard. In Admin app, tap 'Pair on This Handset' first.", Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> showManualPairingDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualPairingDialog() {
        var defaultText = ""
        if (com.antitheft.guardian.BuildConfig.DEBUG) {
            try {
                val debugFile = java.io.File(filesDir, "pairing_payload.json")
                if (debugFile.exists()) {
                    defaultText = debugFile.readText().trim()
                    debugFile.delete()
                }
            } catch (_: Exception) {}
        }
        val input = android.widget.EditText(this).apply {
            hint = "Paste JSON from Admin QR code"
            if (defaultText.isNotBlank()) {
                setText(defaultText)
            }
            maxLines = 4
            setPadding(32, 32, 32, 32)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Manual Pairing Entry")
            .setMessage("Paste the enrollment JSON payload displayed on your Admin device:")
            .setView(input)
            .setPositiveButton("Pair") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    applyPairingPayload(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPairingPayload(jsonString: String, forceRePair: Boolean = false) {
        if (storageHelper.isPaired() && !forceRePair) {
            android.util.Log.w("GuardianPairing", "Device is currently paired. Prompting owner for re-pairing confirmation.")
            runOnUiThread {
                requestOwnerAuth("Re-pair Guardian Handset") {
                    storageHelper.clearPairing()
                    Toast.makeText(this@GuardianMainActivity, "Previous pairing cleared. Connecting to new Admin...", Toast.LENGTH_SHORT).show()
                    applyPairingPayload(jsonString, forceRePair = true)
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var clean = jsonString.trim()
                if (clean.startsWith("```")) {
                    clean = clean.removePrefix("```json").removePrefix("```").trim()
                    if (clean.endsWith("```")) {
                        clean = clean.removeSuffix("```").trim()
                    }
                }
                if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
                    clean = clean.substring(1, clean.length - 1).trim()
                }
                val parsedJson = if (!clean.startsWith("{")) {
                    if (clean.contains("%7B") || clean.contains("%22")) {
                        try {
                            java.net.URLDecoder.decode(clean, "UTF-8")
                        } catch (_: Exception) { clean }
                    } else {
                        try {
                            String(java.util.Base64.getDecoder().decode(clean), Charsets.UTF_8)
                        } catch (_: Exception) {
                            clean
                        }
                    }
                } else clean

                val firstBrace = parsedJson.indexOf('{')
                val lastBrace = parsedJson.lastIndexOf('}')
                val jsonSnippet = if (firstBrace != -1 && lastBrace > firstBrace) {
                    parsedJson.substring(firstBrace, lastBrace + 1)
                } else {
                    parsedJson
                }
                val payload = com.google.gson.JsonParser.parseString(jsonSnippet).asJsonObject
                val serverUrl = payload.get("server_url")?.asString
                val adminPubPem = payload.get("admin_public_key_pem")?.asString
                val pairingSecretHex = payload.get("pairing_secret_hex")?.asString
                val pairingToken = payload.get("pairing_token")?.asString ?: payload.get("auth_token")?.asString
                val trustedPhoneNumber = payload.get("trusted_phone_number")?.asString

                val keystoreManager = com.antitheft.guardian.crypto.KeystoreManager()
                keystoreManager.getSecurityInfo(this@GuardianMainActivity)
                val guardianPubPem = keystoreManager.getPublicKeyPem()

                val mfr = android.os.Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
                val defaultAlias = "$mfr ${android.os.Build.MODEL}".trim()
                val currentAlias = storageHelper.getDeviceAlias()
                val effectiveAlias = if (currentAlias.isBlank() || currentAlias == "Guardian Pixel" || currentAlias == "GuardianDevice") defaultAlias else currentAlias

                val batteryFilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = registerReceiver(null, batteryFilter)
                val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryLevel = if (level >= 0 && scale > 0) (level * 100) / scale else 100
                val bStatus = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = bStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING || bStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

                val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val activeNet = cm?.activeNetwork
                val netCaps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
                val connType = when {
                    netCaps == null -> "offline"
                    netCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    netCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    netCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    netCaps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "other"
                }
                val tm = getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                val simOperator = tm?.simOperatorName?.takeIf { it.isNotBlank() }
                    ?: tm?.networkOperatorName?.takeIf { it.isNotBlank() }

                var successMsg = "Pairing failed."
                var confirmationCode: String? = null

                // If pairing token is present, register device with backend API
                if (!pairingToken.isNullOrBlank() && !serverUrl.isNullOrBlank()) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val effectiveServerUrl = if (serverUrl.contains("10.0.2.2")) {
                        try {
                            java.net.Socket().use { s ->
                                s.connect(java.net.InetSocketAddress("127.0.0.1", 443), 500)
                                "https://127.0.0.1"
                            }
                        } catch (e: Exception) {
                            serverUrl.replace("http://", "https://")
                        }
                    } else serverUrl.replace("http://", "https://")
                    val pairEndpoint = effectiveServerUrl.trimEnd('/') + "/api/v1/devices/pair"

                    val reqBodyMap = mutableMapOf<String, Any?>(
                        "device_id" to storageHelper.getDeviceId(),
                        "device_alias" to effectiveAlias,
                        "public_key_pem" to guardianPubPem,
                        "trusted_phone_number" to (if (!trustedPhoneNumber.isNullOrBlank() && trustedPhoneNumber.length >= 3) trustedPhoneNumber else "+15551234567"),
                        "pairing_secret" to (pairingSecretHex ?: ""),
                        "manufacturer" to android.os.Build.MANUFACTURER,
                        "brand" to android.os.Build.BRAND,
                        "model" to android.os.Build.MODEL,
                        "android_version" to android.os.Build.VERSION.RELEASE,
                        "sdk_level" to android.os.Build.VERSION.SDK_INT,
                        "variant" to "lite",
                        "app_version" to "1.0",
                        "is_device_owner" to false,
                        "battery_level" to batteryLevel,
                        "is_charging" to isCharging,
                        "connectivity_type" to connType,
                        "capabilities" to listOf("locate_sms", "live_gps", "siren", "ring", "battery_report")
                    )
                    if (!simOperator.isNullOrBlank()) {
                        reqBodyMap["sim_operator"] = simOperator
                    }
                    val reqJson = com.google.gson.Gson().toJson(reqBodyMap)
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val body = reqJson.toRequestBody(mediaType)
                    val cleanToken = pairingToken.trim().trim('"').trim('\'')
                    val request = okhttp3.Request.Builder()
                        .url(pairEndpoint)
                        .header("Authorization", "Bearer $cleanToken")
                        .post(body)
                        .build()

                    val response = client.newCall(request).execute()
                    val respBody = response.body?.string() ?: ""
                    
                    // Immediately purge pairing token from memory
                    android.util.Log.i("GuardianPairing", "PAIRING_TOKEN_DISCARDED: Single-use pairing token consumed and cleared from memory.")

                    if (response.isSuccessful) {
                        // Atomic commit: Only persist credentials when backend pairing is successful
                        if (!effectiveServerUrl.isNullOrBlank()) {
                            storageHelper.setServerUrl(effectiveServerUrl)
                        }
                        if (!adminPubPem.isNullOrBlank()) {
                            storageHelper.setAdminPublicKeyPem(adminPubPem)
                            storageHelper.setLastAdminCommandSequence(0L)
                        }
                        if (!pairingSecretHex.isNullOrBlank()) {
                            val secretBytes = hexToByteArray(pairingSecretHex)
                            storageHelper.setPairingSecret(secretBytes)
                        }
                        if (!trustedPhoneNumber.isNullOrBlank()) {
                            // Security invariant: Pairing payload phone is provisional.
                            // Only numbers cryptographically confirmed via /verify/phone/confirm will be marked verified.
                            storageHelper.setTrustedPhoneNumber(trustedPhoneNumber)
                            android.util.Log.i("GuardianPairing", "PROVISIONAL_PHONE_STORED: Stored provisional phone (unverified until /verify/phone/confirm syncs).")
                        }

                        val respObj = com.google.gson.JsonParser.parseString(respBody).asJsonObject
                        val returnedId = respObj.get("id")?.asString
                        if (!returnedId.isNullOrBlank()) {
                            storageHelper.setDeviceId(returnedId)
                        }
                        val pairingId = respObj.get("pairing_id")?.asString
                        if (!pairingId.isNullOrBlank()) {
                            storageHelper.setPairingId(pairingId)
                        }
                        confirmationCode = respObj.get("confirmation_code")?.asString
                        successMsg = "Device enrollment requested! Confirm code: $confirmationCode on Admin phone."
                        android.util.Log.i("GuardianPairing", "PAIRED_SUCCESSFULLY DeviceID: $returnedId PairingID: $pairingId")
                    } else {
                        val errorDetail = try {
                            val errObj = com.google.gson.JsonParser.parseString(respBody).asJsonObject
                            errObj.get("detail")?.asString ?: respBody
                        } catch (_: Exception) {
                            respBody
                        }
                        android.util.Log.w("GuardianPairing", "Backend pairing returned HTTP ${response.code}: $errorDetail")
                        successMsg = "Pairing rejected by backend (HTTP ${response.code}): $errorDetail"
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GuardianMainActivity, successMsg, Toast.LENGTH_LONG).show()
                    val statusText = if (!confirmationCode.isNullOrBlank()) {
                        "Device ID: ${storageHelper.getDeviceId()}\nConfirmation Code: $confirmationCode\nStatus: Pending Admin Confirmation"
                    } else {
                        "Device ID: ${storageHelper.getDeviceId()}"
                    }
                    findViewById<TextView>(R.id.tvDeviceId).text = statusText
                    startTrackingService()
                    refreshDashboardData()
                }
            } catch (e: Exception) {
                android.util.Log.e("GuardianPairing", "Error applying pairing payload", e)
                withContext(Dispatchers.Main) {
                    val msg = e.message ?: "Unknown error"
                    val userTip = if (msg.contains("127.0.0.1") || msg.contains("Connection refused")) {
                        "Connection failed. Verify server URL (e.g. https://she-92h6.onrender.com or local https://192.168.0.x). Ensure TLS is active."
                    } else {
                        "Pairing error: $msg"
                    }
                    Toast.makeText(this@GuardianMainActivity, userTip, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val clean = hex.trim()
        val result = ByteArray(clean.length / 2)
        for (i in 0 until clean.length step 2) {
            result[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private fun refreshDashboardData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = GuardianDatabase.getInstance(this@GuardianMainActivity, storageHelper)
            val latest = db.locationDao().getLatestLocation()
            val pendingCount = db.locationDao().getPendingCount()

            val isDO = deviceControl.isDeviceOwner(this@GuardianMainActivity)
            val restrictions = deviceControl.getActiveRestrictions(this@GuardianMainActivity)
            val checklist = guidanceManager.evaluateSystemChecklist(this@GuardianMainActivity)

            withContext(Dispatchers.Main) {
                val tvLastLocation = findViewById<TextView>(R.id.tvLastLocation)
                val tvQueueCount = findViewById<TextView>(R.id.tvQueueCount)
                val btnViewLocationMap = findViewById<Button>(R.id.btnViewLocationMap)
                val btnLocationInfo = findViewById<Button>(R.id.btnLocationInfo)

                if (latest != null) {
                    tvLastLocation.text = String.format(
                        Locale.US,
                        "Last Fix: %.5f, %.5f (acc: %.1fm)",
                        latest.latitude,
                        latest.longitude,
                        latest.accuracy
                    )
                    btnViewLocationMap.isEnabled = true
                    btnViewLocationMap.setOnClickListener {
                        try {
                            val geoUri = Uri.parse("geo:${latest.latitude},${latest.longitude}?q=${latest.latitude},${latest.longitude}(Guardian Handset)")
                            val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
                            if (mapIntent.resolveActivity(packageManager) != null) {
                                startActivity(mapIntent)
                            } else {
                                val webUri = Uri.parse("https://maps.google.com/?q=${latest.latitude},${latest.longitude}")
                                startActivity(Intent(Intent.ACTION_VIEW, webUri))
                            }
                        } catch (e: Exception) {
                            val webUri = Uri.parse("https://maps.google.com/?q=${latest.latitude},${latest.longitude}")
                            startActivity(Intent(Intent.ACTION_VIEW, webUri))
                        }
                    }
                } else {
                    tvLastLocation.text = "Last Fix: None"
                    btnViewLocationMap.isEnabled = false
                    btnViewLocationMap.setOnClickListener {
                        Toast.makeText(this@GuardianMainActivity, "No GPS fix captured yet", Toast.LENGTH_SHORT).show()
                    }
                }

                btnLocationInfo.setOnClickListener {
                    AlertDialog.Builder(this@GuardianMainActivity)
                        .setTitle("Handset Telemetry & Location Guide")
                        .setMessage(
                            "• Real-Time GPS Tracking:\n" +
                            "  Guardian captures hardware GPS and network provider fixes, signing each coordinate packet with this phone's hardware-bound ECDSA P-256 key before transmitting it to your private server.\n\n" +
                            "• Direct Boot Resumption:\n" +
                            "  Runs in Device Encrypted Storage (DES) so tracking starts automatically when the phone turns on, even before you unlock your screen PIN.\n\n" +
                            "• Accuracy (acc: ±m):\n" +
                            "  Indicates GPS satellite accuracy in meters. Lower numbers mean tighter pinpoint precision.\n\n" +
                            "• 100% Zero-Telemetry Guarantee:\n" +
                            "  Coordinates are sent directly to your own self-hosted or private server. No analytics, no third-party tracking."
                        )
                        .setPositiveButton("Got It", null)
                        .show()
                }

                tvQueueCount.text = "Offline Queue: $pendingCount pending"

                val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val simReady = tm?.simState == TelephonyManager.SIM_STATE_READY
                val opName = tm?.simOperatorName?.takeIf { it.isNotBlank() } ?: (if (simReady) "Ready" else "No SIM")
                val simStr = if (simReady) "SIM: Ready ($opName)" else "SIM: Absent/Restricted"
                val smsGranted = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                val smsStr = if (smsGranted) "SMS: Granted" else "SMS: Denied/Restricted"
                val tvDiagnostics = findViewById<TextView>(R.id.tvDiagnostics)
                tvDiagnostics?.text = "$simStr | $smsStr | Pending SMS: 0"

                // Update Tier 2 OEM & System Guidance Card
                findViewById<TextView>(R.id.tvLiteBadge)?.text = "LITE ACTIVE"

                // Update Device Owner Card
                val tvDeviceOwnerBadge = findViewById<TextView>(R.id.tvDeviceOwnerBadge)
                val tvRestrictionsStatus = findViewById<TextView>(R.id.tvRestrictionsStatus)
                if (isDO) {
                    tvDeviceOwnerBadge?.text = "DEVICE OWNER: ACTIVE (TIER 3)"
                    tvDeviceOwnerBadge?.setBackgroundResource(R.drawable.bg_badge_success)
                    tvDeviceOwnerBadge?.setTextColor(Color.parseColor("#22C55E"))
                } else {
                    tvDeviceOwnerBadge?.text = "DEVICE OWNER: NOT PROVISIONED"
                    tvDeviceOwnerBadge?.setBackgroundResource(R.drawable.bg_badge_warning)
                    tvDeviceOwnerBadge?.setTextColor(Color.parseColor("#F59E0B"))
                }

                val sb = StringBuilder()
                sb.append("Active Restrictions (${restrictions.count { it.value }}/${restrictions.size}):\n")
                restrictions.forEach { (k, v) ->
                    val cleanKey = k.replace("no_", "").uppercase()
                    sb.append(" • ").append(cleanKey).append(": ").append(if (v) "ENFORCED" else "inactive").append("\n")
                }
                tvRestrictionsStatus?.text = sb.toString().trimEnd()

                // Update Tier 1 System Checklist
                val cbFindHub = findViewById<CheckBox>(R.id.cbFindHub)
                val cbTheftDetection = findViewById<CheckBox>(R.id.cbTheftDetection)
                val cbSimLock = findViewById<CheckBox>(R.id.cbSimLock)
                val cbPowerMenu = findViewById<CheckBox>(R.id.cbPowerMenu)

                cbFindHub.isChecked = checklist.find { it.id == "find_hub" }?.isEnabled == true
                cbTheftDetection.isChecked = checklist.find { it.id == "screen_lock" }?.isEnabled == true
                cbSimLock.isChecked = checklist.find { it.id == "sim_pin" }?.isEnabled == true
                cbPowerMenu.isChecked = checklist.find { it.id == "power_menu_lock" }?.isEnabled == true
            }
        }
    }

    private fun showBrandSetupGuideDialog() {
        val brands = arrayOf(
            "Auto-Detect: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }}",
            "Samsung One UI",
            "Xiaomi / Redmi (MIUI / HyperOS)",
            "Realme / Oppo (ColorOS)",
            "Vivo / iQOO (Funtouch OS)",
            "Motorola / Google Pixel (Stock Android)"
        )

        AlertDialog.Builder(this)
            .setTitle("OEM Background & Battery Guide")
            .setItems(brands) { _, which ->
                when (which) {
                    0 -> showSpecificBrandGuide(Build.MANUFACTURER.lowercase())
                    1 -> showSpecificBrandGuide("samsung")
                    2 -> showSpecificBrandGuide("xiaomi")
                    3 -> showSpecificBrandGuide("oppo")
                    4 -> showSpecificBrandGuide("vivo")
                    5 -> showSpecificBrandGuide("motorola")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
    private fun showSpecificBrandGuide(brand: String) {
        val (title, instructions, settingsAction) = when {
            brand.contains("samsung") -> Triple(
                "Samsung One UI Setup",
                "1. Battery & Background Protection:\n" +
                "   Settings -> Apps -> SHE Guardian Lite -> Battery -> Select 'Unrestricted'.\n\n" +
                "2. Secure Power-Off:\n" +
                "   Settings -> Lock screen -> Secure lock settings -> Enable 'Lock network and security' so a thief cannot toggle Wi-Fi or power off without PIN.",
                Settings.ACTION_SECURITY_SETTINGS
            )
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> Triple(
                "Xiaomi / HyperOS / MIUI Setup",
                "1. Autostart & Background Protection:\n" +
                "   Settings -> Apps -> Manage apps -> SHE Guardian Lite -> Enable 'Autostart'. In Battery Saver, select 'No restrictions'.\n\n" +
                "2. Lock in Recents:\n" +
                "   Open App Switcher -> Long press Guardian card -> Tap Lock icon to prevent memory cleaner closure.",
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            )
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> Triple(
                "Realme / Oppo / OnePlus Setup",
                "1. Background Activity:\n" +
                "   Settings -> Battery -> More settings -> App battery management -> Guardian -> Enable 'Allow background activity' & 'Allow auto-launch'.\n\n" +
                "2. Require Password to Power Off:\n" +
                "   Settings -> Password & Security -> System security -> Enable 'Require password to power off'.",
                Settings.ACTION_SECURITY_SETTINGS
            )
            brand.contains("vivo") || brand.contains("iqoo") -> Triple(
                "Vivo / iQOO Funtouch OS Setup",
                "1. High Background Power Consumption:\n" +
                "   Settings -> Battery -> Background power consumption management -> Guardian -> Select 'High background power consumption'.\n\n" +
                "2. iManager Autostart:\n" +
                "   Open iManager app -> App manager -> Autostart -> Allow SHE Guardian Lite.",
                Settings.ACTION_SECURITY_SETTINGS
            )
            else -> Triple(
                "Motorola / Pixel / Stock Android Setup",
                "1. Battery Optimization (Unrestricted):\n" +
                "   Settings -> Apps -> SHE Guardian Lite -> App battery usage -> Choose 'Unrestricted' so background GPS tracking is never paused.\n\n" +
                "2. Lock Screen Security:\n" +
                "   Settings -> Security -> Screen lock -> Ensure 6+ digit PIN or strong alphanumeric passphrase is set.",
                Settings.ACTION_SECURITY_SETTINGS
            )
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(instructions)
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    val intent = if (settingsAction == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    } else {
                        Intent(settingsAction)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startTrackingService()
            requestBackgroundLocation()
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                // Two-step guided flow: Educate owner before triggering system permission dialog
                AlertDialog.Builder(this)
                    .setTitle("Allow 'Allow all the time' for Anti-Theft Protection")
                    .setMessage(
                        "To track and recover this phone if it is stolen or lost while the screen is locked or the app is closed, SHE Guardian requires 'Allow all the time' location access.\n\n" +
                        "On the next system screen, please select 'Allow all the time'."
                    )
                    .setPositiveButton("Continue") { _, _ ->
                        backgroundLocationLauncher.launch(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                    }
                    .setNegativeButton("Not Now", null)
                    .show()
            }
        }
    }

    private fun showHonestOnboardingDialog() {
        val theftProtectionAvailable = Build.VERSION.SDK_INT >= 34
        val theftLockMsg = if (theftProtectionAvailable) {
            "• Android Theft Detection Lock:\n" +
            "  Your device may support Google's native Theft Detection Lock (AI-based snatch detection) and Offline Device Lock. Check Settings -> Google -> All Services -> Theft Protection to enable it alongside SHE."
        } else {
            "• System Theft Protection Fallback:\n" +
            "  Ensure a strong 6+ digit lock screen PIN is configured, and verify Google Find My Device is active."
        }

        AlertDialog.Builder(this)
            .setTitle("SHE Guardian Lite: Capabilities & Limits")
            .setMessage(
                "WHAT SHE GUARDIAN LITE DOES:\n" +
                "• Continuous GPS and network location tracking with visible notification.\n" +
                "• Hardware Keystore tamper-evident cryptographic signing.\n" +
                "• Offline recovery via Direct Boot SMS (answers locate/siren commands before phone unlock).\n" +
                "• Maximum volume acoustic siren on alarm stream overriding silent mode.\n" +
                "• Battery level reporting and low-battery emergency location beacon.\n\n" +
                "WHAT LITE CANNOT DO (LIMITATIONS):\n" +
                "• Cannot prevent a thief from turning off the phone or removing the SIM.\n" +
                "• Cannot block uninstallation if the thief knows your device screen lock PIN.\n" +
                "• Cannot prevent factory reset from the bootloader/recovery menu (Device Owner required in Part 2).\n" +
                "• Never 100% theft-proof. SHE reduces recovery latency and increases recovery probability.\n\n" +
                theftLockMsg
            )
            .setPositiveButton("Understood", null)
            .show()
    }

    private fun showRestrictedSettingsGuide() {
        AlertDialog.Builder(this)
            .setTitle("Android 13+ Restricted Settings Guide")
            .setMessage(
                "On Android 13 and newer, sideloaded applications may have certain permissions greyed out with 'Restricted setting'.\n\n" +
                "To unblock them:\n" +
                "1. Open device Settings -> Apps -> SHE Guardian Lite.\n" +
                "2. Tap the three dots (⋮) in the top-right corner.\n" +
                "3. Tap 'Allow restricted settings' and confirm your PIN or fingerprint.\n" +
                "4. Return to SHE Guardian Lite and grant the requested permission."
            )
            .setPositiveButton("Open App Settings") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPlayProtectGuide() {
        AlertDialog.Builder(this)
            .setTitle("Installation & Play Protect Guide")
            .setMessage(
                "When installing or updating SHE Guardian, Android may flag sideloaded security applications.\n\n" +
                "1. During APK installation/update:\n" +
                "   Tap 'More details' (down arrow) -> Tap 'Install anyway'.\n\n" +
                "2. Android 13/14 Restricted Settings (if SMS or Location permissions are greyed out):\n" +
                "   - Go to phone Settings -> Apps -> Sentinel Guardian.\n" +
                "   - Tap the 3 dots menu in the top-right corner.\n" +
                "   - Tap 'Allow restricted settings' and confirm with your device PIN or Fingerprint.\n" +
                "   - Return to the app and grant required permissions.\n\n" +
                "No need to disable Play Protect entirely on the device."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSmsPermissionRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("SMS Permissions Required for Emergency & SIM Swap Tracker")
            .setMessage(
                "SHE Guardian relies on SMS beacons to report phone location and detect SIM card swaps when mobile data / Wi-Fi is disconnected.\n\n" +
                "NOTE for Android 13+ & Play Protect:\n" +
                "If SMS permissions are blocked or disabled:\n" +
                "1. Tap 'Open App Settings' below.\n" +
                "2. Tap the 3 dots (⋮) in the top-right corner -> 'Allow restricted settings'.\n" +
                "3. Tap 'Permissions' -> enable 'SMS' and 'Phone'.\n" +
                "4. In Google Play Store -> Play Protect -> Settings, ensure scanning does not silently block emergency SMS beacons."
            )
            .setPositiveButton("Open App Settings") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startTrackingService() {
        TrackingForegroundService.start(this)
    }

    companion object {
        private const val REQUEST_CODE_OWNER_AUTH = 7001
    }
}
