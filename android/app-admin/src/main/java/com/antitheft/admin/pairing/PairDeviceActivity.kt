package com.antitheft.admin.pairing

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antitheft.admin.R
import com.antitheft.admin.auth.AdminSessionManager
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch
import java.security.SecureRandom

class PairDeviceActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager
    private var currentPairingSecretHex: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screen capture / recording in production release builds
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
        val btnDone = findViewById<Button>(R.id.btnDone)

        // Generate 256-bit symmetric key for SMS HMAC channel
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        currentPairingSecretHex = randomBytes.joinToString("") { "%02X".format(it) }

        tvPairingSecret.text = "Secret: ${currentPairingSecretHex.take(16)}..."

        val btnApprovePairing = findViewById<Button>(R.id.btnApprovePairing)
        val etConfirmCode = findViewById<android.widget.EditText>(R.id.etConfirmCode)
        val etAdminPhone = findViewById<android.widget.EditText>(R.id.etAdminPhone)
        val defaultAdminPhone = sessionManager.getAdminPhoneNumber()
        etAdminPhone.setText(defaultAdminPhone)

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

        setupPairingFlow()
    }

    private fun promptFor2Fa() {
        val input = android.widget.EditText(this).apply {
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
                    setupPairingFlow(code)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun setupPairingFlow(totpCode: String? = null) {
        val adminKeystore = com.antitheft.admin.crypto.AdminKeystoreManager()
        val adminPubPem = adminKeystore.getPublicKeyPem()
        val pairingSecretHex = currentPairingSecretHex

        val etAdminPhone = findViewById<android.widget.EditText>(R.id.etAdminPhone)
        val ivQrCode = findViewById<ImageView>(R.id.ivQrCode)

        lifecycleScope.launch {
            val api = com.antitheft.admin.network.ApiClient.create(sessionManager)

            if (!totpCode.isNullOrBlank()) {
                try {
                    val tokenResp = api.validateTotp(com.antitheft.admin.network.TotpValidateRequest(totpCode))
                    sessionManager.saveToken(tokenResp.access_token)
                    android.util.Log.i("AdminPairing", "2FA session upgraded successfully.")
                } catch (e: Exception) {
                    android.util.Log.e("AdminPairing", "Failed validating 2FA code", e)
                    Toast.makeText(this@PairDeviceActivity, "2FA verification failed: ${e.message}", Toast.LENGTH_LONG).show()
                    promptFor2Fa()
                    return@launch
                }
            }

            var trustedId = sessionManager.getTrustedDeviceId()
            if (trustedId.isNullOrBlank()) {
                try {
                    val regResp = api.registerTrustedDevice(
                        com.antitheft.admin.network.RegisterTrustedDeviceRequest(
                            device_name = "Admin Controller Phone",
                            public_key_pem = adminPubPem
                        )
                    )
                    trustedId = regResp.id
                    sessionManager.saveTrustedDeviceId(trustedId)
                } catch (e: Exception) {
                    android.util.Log.w("AdminPairing", "Failed registering trusted device online: ${e.message}")
                    if (e.message?.contains("403") == true && totpCode == null) {
                        promptFor2Fa()
                        return@launch
                    }
                    trustedId = "offline-admin-device"
                }
            }

            // Issue dedicated single-use, 5-minute pairing token from backend
            var pairingToken = ""
            try {
                val tokenResp = api.createPairingToken(
                    com.antitheft.admin.network.PairingTokenRequest(trusted_device_id = trustedId)
                )
                pairingToken = tokenResp.pairing_token
            } catch (e: Exception) {
                android.util.Log.e("AdminPairing", "Failed creating pairing token: ${e.message}")
                if (e.message?.contains("403") == true && totpCode == null) {
                    promptFor2Fa()
                    return@launch
                }
                Toast.makeText(this@PairDeviceActivity, "Token error: ${e.message}", Toast.LENGTH_LONG).show()
            }

            val defaultPhone = sessionManager.getAdminPhoneNumber()
            val rawPhone = etAdminPhone.text.toString().trim().ifBlank { defaultPhone }
            val adminPhone = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeToE164(rawPhone)
            sessionManager.setAdminPhoneNumber(adminPhone)

            val pairingPayload = mapOf(
                "server_url" to sessionManager.getServerUrl(),
                "trusted_device_id" to trustedId,
                "admin_public_key_pem" to adminPubPem,
                "pairing_secret_hex" to pairingSecretHex,
                "pairing_token" to pairingToken,
                "trusted_phone_number" to adminPhone,
                "pairing_timestamp" to (System.currentTimeMillis() / 1000)
            )

            val jsonString = Gson().toJson(pairingPayload)
            android.util.Log.i("AdminPairing", "GENERATED_PAIRING_PAYLOAD phone:$adminPhone")

            try {
                val barcodeEncoder = BarcodeEncoder()
                val bitmap: Bitmap = barcodeEncoder.encodeBitmap(
                    jsonString,
                    BarcodeFormat.QR_CODE,
                    600,
                    600
                )
                ivQrCode.setImageBitmap(bitmap)
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
