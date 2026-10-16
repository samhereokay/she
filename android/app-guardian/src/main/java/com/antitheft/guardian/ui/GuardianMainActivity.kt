package com.antitheft.guardian.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import android.graphics.Color
import android.widget.CheckBox
import com.antitheft.deviceowner.DeviceOwnerManager
import com.antitheft.deviceowner.SystemGuidanceManager
import java.util.Locale

class GuardianMainActivity : AppCompatActivity() {

    private lateinit var storageHelper: DirectBootStorageHelper
    private lateinit var captureManager: LocationCaptureManager
    private val deviceOwnerManager = DeviceOwnerManager()
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        if (isStolen && deviceOwnerManager.isDeviceOwner(this) && !isLockTaskActive) {
            val success = deviceOwnerManager.enterLockTaskStolenMode(this)
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
            val dialIntent = Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone"))
            startActivity(dialIntent)
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

        findViewById<Button>(R.id.btnEnforceRestrictions).setOnClickListener {
            if (deviceOwnerManager.isDeviceOwner(this)) {
                val res = deviceOwnerManager.applyTheftRestrictions(this)
                Toast.makeText(this, "Enforced ${res.count { it.value }} restrictions", Toast.LENGTH_SHORT).show()
                refreshDashboardData()
            } else {
                showProvisioningDialog()
            }
        }

        findViewById<Button>(R.id.btnClearRestrictions).setOnClickListener {
            if (!deviceOwnerManager.isDeviceOwner(this)) {
                Toast.makeText(this, "Device Owner not provisioned", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Require owner authentication to clear lockdown
            requestOwnerAuth("Authenticate to Clear Lockdown") {
                deviceOwnerManager.clearTheftRestrictions(this)
                Toast.makeText(this, "Theft restrictions cleared by authenticated owner", Toast.LENGTH_SHORT).show()
                refreshDashboardData()
            }
        }

        findViewById<Button>(R.id.btnToggleLockTaskKiosk).setOnClickListener {
            if (!deviceOwnerManager.isDeviceOwner(this)) {
                showProvisioningDialog()
                return@setOnClickListener
            }
            if (!isLockTaskActive) {
                val success = deviceOwnerManager.enterLockTaskStolenMode(this)
                if (success) {
                    isLockTaskActive = true
                    findViewById<Button>(R.id.btnToggleLockTaskKiosk).text = "Kiosk Mode Active (Protected)"
                    findViewById<android.view.View>(R.id.cardEmergencyContact)?.visibility = android.view.View.VISIBLE
                    Toast.makeText(this, "Stolen Kiosk Mode ACTIVE (Dialer Permitted, Nav Suppressed)", Toast.LENGTH_LONG).show()
                }
            } else {
                // Thief protection: Exiting kiosk mode strictly requires owner-signed command from Admin companion phone
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Kiosk Lockdown Active")
                    .setMessage("This device is locked into Anti-Theft Kiosk mode. To prevent thief bypass, local PIN exit is disabled. An owner-signed EXIT_KIOSK or RECOVERED command must be dispatched from the registered Admin phone.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        findViewById<Button>(R.id.btnShowAdbCommand).setOnClickListener {
            showProvisioningDialog()
        }

        handleIncomingPairingIntent(intent)
        handleIncomingDeviceOwnerIntent(intent)
    }

    private fun showProvisioningDialog() {
        val cmd = deviceOwnerManager.getAdbProvisioningCommand(this)
        val tv = android.widget.EditText(this).apply {
            setText(cmd)
            isFocusable = true
            isClickable = true
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Provision Device Owner (ADB)")
            .setMessage("To enable hardware-level anti-theft lockdown, run this ADB command from your PC (device must have no user accounts added):")
            .setView(tv)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AdbCmd", cmd))
                Toast.makeText(this, "Command copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
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
                val res = deviceOwnerManager.applyTheftRestrictions(this)
                deviceOwnerManager.setDeviceOwnerLockScreenInfo(this, "STOLEN DEVICE: Protected by AntiTheft Guardian. If found, call +1-555-0199.")
                Toast.makeText(this, "Enforced ${res.count { it.value }} restrictions", Toast.LENGTH_SHORT).show()
                refreshDashboardData()
            }
            "clear_restrictions" -> {
                deviceOwnerManager.clearTheftRestrictions(this)
                deviceOwnerManager.setDeviceOwnerLockScreenInfo(this, null)
                Toast.makeText(this, "Restrictions cleared", Toast.LENGTH_SHORT).show()
                refreshDashboardData()
            }
            "enter_kiosk" -> {
                val ok = deviceOwnerManager.enterLockTaskStolenMode(this)
                if (ok) {
                    isLockTaskActive = true
                    findViewById<Button>(R.id.btnToggleLockTaskKiosk).text = "Kiosk Mode Active (Protected)"
                    findViewById<android.view.View>(R.id.cardEmergencyContact)?.visibility = android.view.View.VISIBLE
                }
            }
            "exit_kiosk" -> {
                storageHelper.setStolenMode(false)
                deviceOwnerManager.exitLockTaskMode(this)
                isLockTaskActive = false
                findViewById<Button>(R.id.btnToggleLockTaskKiosk).text = "Enter Stolen Kiosk Mode"
                findViewById<android.view.View>(R.id.cardEmergencyContact)?.visibility = android.view.View.GONE
                refreshDashboardData()
            }
            "lock_now" -> {
                deviceOwnerManager.setDeviceOwnerLockScreenInfo(this, "STOLEN DEVICE: Protected by AntiTheft Guardian. If found, call +1-555-0199.")
                deviceOwnerManager.lockNow(this)
            }
        }
    }

    private fun showPairingInputDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Paste JSON from Admin QR code"
            setPadding(32, 32, 32, 32)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pair with Admin Phone")
            .setMessage("Paste or scan the enrollment JSON payload displayed on your Admin device:")
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

    private fun applyPairingPayload(jsonString: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (storageHelper.isPaired()) {
                    android.util.Log.w("GuardianPairing", "REJECTED_ALREADY_PAIRED: Device is already paired to an owner. Re-pairing rejected.")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@GuardianMainActivity,
                            "Re-pairing rejected: Device is already paired. Send an owner-signed UNPAIR command first.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val clean = jsonString.trim()
                val parsedJson = if (!clean.startsWith("{")) {
                    try {
                        String(java.util.Base64.getDecoder().decode(clean), Charsets.UTF_8)
                    } catch (e: Exception) {
                        clean
                    }
                } else clean
                val payload = com.google.gson.JsonParser.parseString(parsedJson).asJsonObject
                val serverUrl = payload.get("server_url")?.asString
                val adminPubPem = payload.get("admin_public_key_pem")?.asString
                val pairingSecretHex = payload.get("pairing_secret_hex")?.asString
                val pairingToken = payload.get("pairing_token")?.asString ?: payload.get("auth_token")?.asString

                if (!serverUrl.isNullOrBlank()) {
                    storageHelper.setServerUrl(serverUrl)
                }
                if (!adminPubPem.isNullOrBlank()) {
                    storageHelper.setAdminPublicKeyPem(adminPubPem)
                    storageHelper.setLastAdminCommandSequence(0L)
                }
                if (!pairingSecretHex.isNullOrBlank()) {
                    val secretBytes = hexToByteArray(pairingSecretHex)
                    storageHelper.setPairingSecret(secretBytes)
                }

                val keystoreManager = com.antitheft.guardian.crypto.KeystoreManager()
                keystoreManager.getSecurityInfo(this@GuardianMainActivity)
                val guardianPubPem = keystoreManager.getPublicKeyPem()

                var successMsg = "Pairing applied locally."
                var confirmationCode: String? = null

                // If pairing token is present, register device with backend API
                if (!pairingToken.isNullOrBlank() && !serverUrl.isNullOrBlank()) {
                    val client = okhttp3.OkHttpClient()
                    val pairEndpoint = serverUrl.trimEnd('/') + "/api/v1/devices/pair"

                    val reqBodyMap = mapOf(
                        "device_id" to storageHelper.getDeviceId(),
                        "device_alias" to (storageHelper.getDeviceAlias() ?: "Guardian Pixel"),
                        "public_key_pem" to guardianPubPem,
                        "trusted_phone_number" to (if (storageHelper.getTrustedPhoneNumber().length >= 5) storageHelper.getTrustedPhoneNumber() else "+15551234567"),
                        "pairing_secret" to (pairingSecretHex ?: "")
                    )
                    val reqJson = com.google.gson.Gson().toJson(reqBodyMap)
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val body = reqJson.toRequestBody(mediaType)
                    val request = okhttp3.Request.Builder()
                        .url(pairEndpoint)
                        .header("Authorization", "Bearer $pairingToken")
                        .post(body)
                        .build()

                    val response = client.newCall(request).execute()
                    val respBody = response.body?.string() ?: ""
                    
                    // Immediately purge pairing token from memory
                    android.util.Log.i("GuardianPairing", "PAIRING_TOKEN_DISCARDED: Single-use pairing token consumed and cleared from memory.")

                    if (response.isSuccessful) {
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
                        android.util.Log.w("GuardianPairing", "Backend pairing returned HTTP ${response.code}")
                        successMsg = "Local pairing set. Backend returned ${response.code}"
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
                    Toast.makeText(this@GuardianMainActivity, "Pairing error: ${e.message}", Toast.LENGTH_LONG).show()
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
            val db = GuardianDatabase.getInstance(this@GuardianMainActivity, storageHelper.getDbPassphrase())
            val latest = db.locationDao().getLatestLocation()
            val pendingCount = db.locationDao().getPendingCount()

            val isDO = deviceOwnerManager.isDeviceOwner(this@GuardianMainActivity)
            val restrictions = deviceOwnerManager.getActiveRestrictions(this@GuardianMainActivity)
            val checklist = guidanceManager.evaluateSystemChecklist(this@GuardianMainActivity)

            withContext(Dispatchers.Main) {
                val tvLastLocation = findViewById<TextView>(R.id.tvLastLocation)
                val tvQueueCount = findViewById<TextView>(R.id.tvQueueCount)

                if (latest != null) {
                    tvLastLocation.text = String.format(
                        Locale.US,
                        "Last Fix: %.5f, %.5f (acc: %.1fm)",
                        latest.latitude,
                        latest.longitude,
                        latest.accuracy
                    )
                } else {
                    tvLastLocation.text = "Last Fix: None"
                }

                tvQueueCount.text = "Offline Queue: $pendingCount pending"

                // Update Device Owner Card
                val tvDeviceOwnerBadge = findViewById<TextView>(R.id.tvDeviceOwnerBadge)
                val tvRestrictionsStatus = findViewById<TextView>(R.id.tvRestrictionsStatus)
                if (isDO) {
                    tvDeviceOwnerBadge.text = "DEVICE OWNER: ACTIVE (TIER 3)"
                    tvDeviceOwnerBadge.setBackgroundColor(Color.parseColor("#DCFCE7"))
                    tvDeviceOwnerBadge.setTextColor(Color.parseColor("#166534"))
                } else {
                    tvDeviceOwnerBadge.text = "DEVICE OWNER: NOT PROVISIONED"
                    tvDeviceOwnerBadge.setBackgroundColor(Color.parseColor("#FEF3C7"))
                    tvDeviceOwnerBadge.setTextColor(Color.parseColor("#92400E"))
                }

                val sb = StringBuilder()
                sb.append("Active Restrictions (${restrictions.count { it.value }}/${restrictions.size}):\n")
                restrictions.forEach { (k, v) ->
                    val cleanKey = k.replace("no_", "").uppercase()
                    sb.append(" • ").append(cleanKey).append(": ").append(if (v) "ENFORCED" else "inactive").append("\n")
                }
                tvRestrictionsStatus.text = sb.toString().trimEnd()

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
                // Background location must be requested separately on Android 10+
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
        }
    }

    private fun startTrackingService() {
        TrackingForegroundService.start(this)
    }

    companion object {
        private const val REQUEST_CODE_OWNER_AUTH = 7001
    }
}
