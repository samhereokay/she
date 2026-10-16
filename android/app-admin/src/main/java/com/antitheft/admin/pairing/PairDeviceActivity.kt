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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screen capture / recording of sensitive pairing secret and enrollment payload
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_pair_device)

        sessionManager = AdminSessionManager(this)

        val ivQrCode = findViewById<ImageView>(R.id.ivQrCode)
        val tvPairingSecret = findViewById<TextView>(R.id.tvPairingSecret)
        val btnDone = findViewById<Button>(R.id.btnDone)

        // Generate 256-bit symmetric key for SMS HMAC channel
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val pairingSecretHex = randomBytes.joinToString("") { "%02X".format(it) }

        tvPairingSecret.text = "Secret: ${pairingSecretHex.take(16)}..."

        val adminKeystore = com.antitheft.admin.crypto.AdminKeystoreManager()
        val adminPubPem = adminKeystore.getPublicKeyPem()

        val etConfirmCode = findViewById<android.widget.EditText>(R.id.etConfirmCode)
        val btnApprovePairing = findViewById<Button>(R.id.btnApprovePairing)

        lifecycleScope.launch {
            val api = com.antitheft.admin.network.ApiClient.create(sessionManager)
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
                android.util.Log.e("AdminPairing", "Failed creating pairing token", e)
                Toast.makeText(this@PairDeviceActivity, "Token error: ${e.message}", Toast.LENGTH_LONG).show()
            }

            val pairingPayload = mapOf(
                "server_url" to sessionManager.getServerUrl(),
                "trusted_device_id" to trustedId,
                "admin_public_key_pem" to adminPubPem,
                "pairing_secret_hex" to pairingSecretHex,
                "pairing_token" to pairingToken,
                "pairing_timestamp" to (System.currentTimeMillis() / 1000)
            )

            val jsonString = Gson().toJson(pairingPayload)
            android.util.Log.i("AdminPairing", "PAIRING_PAYLOAD:$jsonString")

            try {
                val barcodeEncoder = BarcodeEncoder()
                val bitmap: Bitmap = barcodeEncoder.encodeBitmap(
                    jsonString,
                    BarcodeFormat.QR_CODE,
                    600,
                    600
                )
                ivQrCode.setImageBitmap(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this@PairDeviceActivity, "Failed generating QR: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
            finish()
        }
    }
}
