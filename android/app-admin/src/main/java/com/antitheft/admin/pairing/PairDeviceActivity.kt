package com.antitheft.admin.pairing

import android.content.Intent
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.antitheft.admin.R
import com.antitheft.admin.auth.AdminSessionManager
import com.antitheft.common.protocol.PhoneNumberNormalizer
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch
import java.security.SecureRandom

class PairDeviceActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager
    private var currentPairingSecretHex: String = ""
    private var lastGeneratedPhone: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.antitheft.admin.BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContentView(R.layout.activity_pair_device)

        sessionManager = AdminSessionManager(this)

        val ivQrCode = findViewById<ImageView>(R.id.ivQrCode)
        val tvPairingSecret = findViewById<TextView>(R.id.tvPairingSecret)
        val tvQrInstructions = findViewById<TextView>(R.id.tvQrInstructions)
        val etAdminPhone = findViewById<EditText>(R.id.etAdminPhone)
        val btnGenerateQr = findViewById<Button>(R.id.btnGenerateQr)
        val etConfirmCode = findViewById<EditText>(R.id.etConfirmCode)
        val btnApprovePairing = findViewById<Button>(R.id.btnApprovePairing)
        val btnDone = findViewById<Button>(R.id.btnDone)

        // Preload saved admin phone if user previously entered it
        val savedPhone = sessionManager.getAdminPhoneNumber()
        if (savedPhone.isNotBlank()) {
            etAdminPhone.setText(savedPhone)
        }

        val etServerUrl = findViewById<EditText>(R.id.etServerUrl)
        val currentServer = sessionManager.getServerUrl()
        etServerUrl.setText(currentServer)

        // Detect input changes to invalidate outdated QR codes
        val invalidateListener: (android.text.Editable?) -> Unit = {
            if (lastGeneratedPhone.isNotBlank()) {
                ivQrCode.setImageBitmap(null)
                tvPairingSecret.text = "Pairing Secret: Outdated"
                tvQrInstructions.text = "⚠️ Input changed. Tap 'Generate Pairing QR Code' to update QR."
                tvQrInstructions.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            }
        }
        etAdminPhone.addTextChangedListener { invalidateListener(it) }
        etServerUrl.addTextChangedListener { invalidateListener(it) }

        btnGenerateQr.setOnClickListener {
            val rawPhone = etAdminPhone.text.toString().trim()
            if (rawPhone.isBlank() || rawPhone.length < 5) {
                Toast.makeText(this, "Please enter your valid phone number first", Toast.LENGTH_SHORT).show()
                etAdminPhone.requestFocus()
                return@setOnClickListener
            }

            val normalizedPhone = PhoneNumberNormalizer.normalizeToE164(rawPhone)
            sessionManager.setAdminPhoneNumber(normalizedPhone)
            etAdminPhone.setText(normalizedPhone)

            // Generate fresh 256-bit symmetric key for SMS HMAC channel
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            currentPairingSecretHex = randomBytes.joinToString("") { "%02X".format(it) }

            setupPairingFlow(adminPhone = normalizedPhone)
        }

        btnApprovePairing.setOnClickListener {
            val code = etConfirmCode.text.toString().trim()
            android.util.Log.i("AdminPairing", "btnApprovePairing clicked with code: '$code'")
            if (code.isBlank()) {
                Toast.makeText(this, "Please enter the code shown on Guardian", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                try {
                    val api = com.antitheft.admin.network.ApiClient.create(sessionManager)
                    val pending = api.listPendingConfirmations()
                    if (pending.isEmpty()) {
                        Toast.makeText(this@PairDeviceActivity, "No pending Guardian devices found to approve", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val target = pending.first()
                    val confirmResp = api.confirmPairing(
                        target.id,
                        com.antitheft.admin.network.ConfirmPairingRequest(confirmation_code = code)
                    )
                    sessionManager.saveDevicePairingId(target.id, confirmResp.pairing_id)
                    sessionManager.saveDevicePairingSecret(target.id, currentPairingSecretHex)
                    if (com.antitheft.admin.BuildConfig.DEBUG) {
                        try {
                            val debugFile = java.io.File(filesDir, "pairing_payload.json")
                            if (debugFile.exists()) debugFile.delete()
                        } catch (_: Exception) {}
                    }
                    android.util.Log.i("AdminPairing", "CONFIRMED_PAIRING: Device ${target.id} active with pairing_id: ${confirmResp.pairing_id}")
                    Toast.makeText(this@PairDeviceActivity, "Enrollment confirmed! Device is now ACTIVE.", Toast.LENGTH_LONG).show()
                    finish()
                } catch (e: Exception) {
                    android.util.Log.e("AdminPairing", "Error confirming pairing", e)
                    Toast.makeText(this@PairDeviceActivity, "Confirmation failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnDone.setOnClickListener {
            if (com.antitheft.admin.BuildConfig.DEBUG) {
                try {
                    val debugFile = java.io.File(filesDir, "pairing_payload.json")
                    if (debugFile.exists()) debugFile.delete()
                } catch (_: Exception) {}
            }
            finish()
        }

        // If phone was already saved, generate QR on initial open
        if (savedPhone.isNotBlank() && savedPhone.length >= 5) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            currentPairingSecretHex = randomBytes.joinToString("") { "%02X".format(it) }
            setupPairingFlow(adminPhone = savedPhone)
        }
    }

    private fun promptFor2Fa(adminPhone: String) {
        val input = EditText(this).apply {
            hint = "6-digit TOTP or recovery code"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(32, 32, 32, 32)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Two-Factor Authentication Required")
            .setMessage("Enrolling a new device requires owner 2FA verification. Enter your TOTP code or emergency recovery code:")
            .setView(input)
            .setPositiveButton("Verify & Generate QR") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotBlank()) {
                    setupPairingFlow(totpCode = code, adminPhone = adminPhone)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun setupPairingFlow(totpCode: String? = null, adminPhone: String) {
        if (totpCode.isNullOrBlank()) {
            promptFor2Fa(adminPhone)
            return
        }

        val adminKeystore = com.antitheft.admin.crypto.AdminKeystoreManager()
        val adminPubPem = adminKeystore.getPublicKeyPem()
        val pairingSecretHex = currentPairingSecretHex

        val ivQrCode = findViewById<ImageView>(R.id.ivQrCode)
        val tvPairingSecret = findViewById<TextView>(R.id.tvPairingSecret)
        val tvQrInstructions = findViewById<TextView>(R.id.tvQrInstructions)
        val btnGenerateQr = findViewById<Button>(R.id.btnGenerateQr)
        val btnPairSameDevice = findViewById<Button>(R.id.btnPairSameDevice)

        btnGenerateQr.isEnabled = false
        btnGenerateQr.text = "Generating QR Code..."

        lifecycleScope.launch {
            try {
                val api = com.antitheft.admin.network.ApiClient.create(sessionManager)

                // 1. Upgrade session to 2FA using TOTP code
                try {
                    val tokenResp = api.validateTotp(com.antitheft.admin.network.TotpValidateRequest(totpCode))
                    sessionManager.saveToken(tokenResp.access_token)
                    android.util.Log.i("AdminPairing", "2FA session upgraded successfully.")
                } catch (e: Exception) {
                    android.util.Log.e("AdminPairing", "Failed validating 2FA code", e)
                    Toast.makeText(this@PairDeviceActivity, "2FA verification failed: ${e.message}", Toast.LENGTH_LONG).show()
                    btnGenerateQr.isEnabled = true
                    btnGenerateQr.text = "Generate Pairing QR Code"
                    promptFor2Fa(adminPhone)
                    return@launch
                }

                // 2. Re-create api client with upgraded 2FA token
                val api2fa = com.antitheft.admin.network.ApiClient.create(sessionManager)

                // 3. Register trusted device if not registered yet
                var trustedId = sessionManager.getTrustedDeviceId()
                if (trustedId.isNullOrBlank() || trustedId == "offline-admin-device") {
                    try {
                        val regResp = api2fa.registerTrustedDevice(
                            com.antitheft.admin.network.RegisterTrustedDeviceRequest(
                                device_name = "Admin Controller Phone",
                                public_key_pem = adminPubPem
                            )
                        )
                        trustedId = regResp.id
                        sessionManager.saveTrustedDeviceId(trustedId)
                    } catch (e: Exception) {
                        android.util.Log.e("AdminPairing", "Failed registering trusted device online", e)
                        Toast.makeText(this@PairDeviceActivity, "Trusted device registration failed: ${e.message}", Toast.LENGTH_LONG).show()
                        btnGenerateQr.isEnabled = true
                        btnGenerateQr.text = "Generate Pairing QR Code"
                        return@launch
                    }
                }

                // 4. Issue dedicated single-use, 5-minute pairing token from backend
                val tokenResp = api2fa.createPairingToken(
                    com.antitheft.admin.network.PairingTokenRequest(trusted_device_id = trustedId)
                )
                val pairingToken = tokenResp.pairing_token
                if (pairingToken.isBlank()) {
                    throw IllegalStateException("Server returned empty pairing token")
                }

                // 5. Determine effective server URL (customizable for Wi-Fi vs USB)
                val inputUrl = findViewById<EditText>(R.id.etServerUrl).text.toString().trim()
                val configuredUrl = sessionManager.getServerUrl()
                val effectiveServerUrl = if (inputUrl.isNotBlank()) {
                    inputUrl
                } else if (configuredUrl.contains("10.0.2.2") && !sessionManager.isEmulator()) {
                    "http://127.0.0.1:8000"
                } else {
                    configuredUrl
                }

                val pairingPayload = mapOf(
                    "server_url" to effectiveServerUrl,
                    "trusted_device_id" to trustedId,
                    "admin_public_key_pem" to adminPubPem,
                    "pairing_secret_hex" to pairingSecretHex,
                    "pairing_token" to pairingToken,
                    "trusted_phone_number" to adminPhone,
                    "pairing_timestamp" to (System.currentTimeMillis() / 1000)
                )

                val jsonString = Gson().toJson(pairingPayload)
                android.util.Log.i("AdminPairing", "GENERATED_PAIRING_PAYLOAD phone:$adminPhone url:$effectiveServerUrl")

                val barcodeEncoder = BarcodeEncoder()
                val bitmap: Bitmap = barcodeEncoder.encodeBitmap(
                    jsonString,
                    BarcodeFormat.QR_CODE,
                    600,
                    600
                )
                ivQrCode.setImageBitmap(bitmap)
                lastGeneratedPhone = adminPhone
                tvPairingSecret.text = "Pairing Secret: ${pairingSecretHex.take(16)}..."
                tvQrInstructions.text = "✓ Fresh QR Code ready for $adminPhone! (Valid 15m)\nScan from Guardian, or tap below to pair on this handset."
                tvQrInstructions.setTextColor(android.graphics.Color.parseColor("#10B981"))

                // Enable Same-Device 1-Tap Pairing button
                btnPairSameDevice.visibility = View.VISIBLE
                btnPairSameDevice.setOnClickListener {
                    try {
                        val intent = Intent().apply {
                            component = ComponentName("com.antitheft.guardian", "com.antitheft.guardian.ui.GuardianMainActivity")
                            putExtra("pairing_payload", jsonString)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                        Toast.makeText(this@PairDeviceActivity, "Launching Guardian with pairing payload...", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@PairDeviceActivity, "Could not launch Guardian: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }

                if (com.antitheft.admin.BuildConfig.DEBUG) {
                    try {
                        val debugFile = java.io.File(filesDir, "pairing_payload.json")
                        debugFile.writeText(jsonString)
                    } catch (e: Exception) {
                        android.util.Log.w("AdminPairing", "Could not write debug payload: ${e.message}")
                    }
                    ivQrCode.setOnClickListener {
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PairingPayload", jsonString))
                        Toast.makeText(this@PairDeviceActivity, "Debug: Pairing payload copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@PairDeviceActivity, "Failed generating QR: ${e.message}", Toast.LENGTH_SHORT).show()
                tvQrInstructions.text = "Error generating QR: ${e.message}"
                tvQrInstructions.setTextColor(android.graphics.Color.parseColor("#EF4444"))
                btnPairSameDevice.visibility = View.GONE
            } finally {
                btnGenerateQr.isEnabled = true
                btnGenerateQr.text = "Generate Pairing QR Code"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (com.antitheft.admin.BuildConfig.DEBUG) {
            try {
                val debugFile = java.io.File(filesDir, "pairing_payload.json")
                if (debugFile.exists()) debugFile.delete()
            } catch (_: Exception) {}
        }
    }
}
