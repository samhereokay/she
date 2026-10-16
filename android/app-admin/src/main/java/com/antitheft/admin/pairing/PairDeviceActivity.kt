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

        lifecycleScope.launch {
            var trustedId = sessionManager.getTrustedDeviceId()
            if (trustedId.isNullOrBlank()) {
                try {
                    val api = com.antitheft.admin.network.ApiClient.create(sessionManager)
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

            val pairingPayload = mapOf(
                "server_url" to sessionManager.getServerUrl(),
                "trusted_device_id" to trustedId,
                "admin_public_key_pem" to adminPubPem,
                "pairing_secret_hex" to pairingSecretHex,
                "auth_token" to (sessionManager.getToken() ?: ""),
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

        btnDone.setOnClickListener {
            finish()
        }
    }
}
