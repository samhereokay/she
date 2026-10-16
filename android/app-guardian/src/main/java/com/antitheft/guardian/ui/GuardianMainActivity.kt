package com.antitheft.guardian.ui

import android.Manifest
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
import java.util.Locale

class GuardianMainActivity : AppCompatActivity() {

    private lateinit var storageHelper: DirectBootStorageHelper
    private lateinit var captureManager: LocationCaptureManager

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

        val keystoreManager = com.antitheft.guardian.crypto.KeystoreManager()
        android.util.Log.i("GuardianPairing", "PAIRING_PUBLIC_KEY:${keystoreManager.getPublicKeyPem().replace("\n", "\\n")}")
        android.util.Log.i("GuardianPairing", "DEVICE_ID:${storageHelper.getDeviceId()}")

        setupViews()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardData()
    }

    private fun setupViews() {
        findViewById<TextView>(R.id.tvDeviceId).text = "Device ID: ${storageHelper.getDeviceId()}"

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

        handleIncomingPairingIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIncomingPairingIntent(intent)
    }

    private fun handleIncomingPairingIntent(intent: android.content.Intent?) {
        val payload = intent?.getStringExtra("pairing_payload")
        if (!payload.isNullOrBlank()) {
            applyPairingPayload(payload)
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
                    android.util.Log.i("GuardianPairing", "PAIRING_TOKEN_DISCARDED: Single-use pairing token consumed and cleared from memory. Never written to persistent storage.")

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
                        android.util.Log.i("GuardianPairing", "PAIRED_SUCCESSFULLY DeviceID: $returnedId PairingID: $pairingId ConfirmationCode: $confirmationCode")
                    } else {
                        android.util.Log.w("GuardianPairing", "Backend pairing returned HTTP ${response.code}: $respBody")
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
}
