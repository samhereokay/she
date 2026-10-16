package com.antitheft.admin.pairing

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antitheft.admin.R
import com.antitheft.admin.auth.AdminSessionManager
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.security.SecureRandom

class PairDeviceActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val pairingPayload = mapOf(
            "server_url" to sessionManager.getServerUrl(),
            "pairing_secret_hex" to pairingSecretHex,
            "pairing_timestamp" to (System.currentTimeMillis() / 1000)
        )

        val jsonString = Gson().toJson(pairingPayload)

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
            Toast.makeText(this, "Failed generating QR: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        btnDone.setOnClickListener {
            finish()
        }
    }
}
