package com.antitheft.admin.pairing

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.antitheft.admin.auth.AdminSessionManager
import com.antitheft.admin.ui.theme.SheTheme
import com.antitheft.common.protocol.PhoneNumberNormalizer
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch
import java.security.SecureRandom

class PairDeviceActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager
    private var currentPairingSecretHex: String = ""
    private var qrBitmapState by mutableStateOf<Bitmap?>(null)
    private var pairingSecretState by mutableStateOf("")
    private var isLoadingState by mutableStateOf(false)
    private var showSameDeviceState by mutableStateOf(false)
    private var currentPayloadJson: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.antitheft.admin.BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        sessionManager = AdminSessionManager(this)
        val savedPhone = sessionManager.getAdminPhoneNumber()

        setContent {
            SheTheme {
                PairDeviceScreen(
                    initialAdminPhone = savedPhone,
                    initialServerUrl = sessionManager.getServerUrl(),
                    qrBitmap = qrBitmapState,
                    pairingSecretHex = pairingSecretState,
                    onGenerateQr = { phone, serverUrl ->
                        if (phone.isBlank() || phone.length < 5) {
                            Toast.makeText(this, "Please enter your valid phone number first", Toast.LENGTH_SHORT).show()
                            return@PairDeviceScreen
                        }
                        val normalizedPhone = PhoneNumberNormalizer.normalizeToE164(phone)
                        sessionManager.setAdminPhoneNumber(normalizedPhone)

                        val randomBytes = ByteArray(32)
                        SecureRandom().nextBytes(randomBytes)
                        currentPairingSecretHex = randomBytes.joinToString("") { "%02X".format(it) }

                        setupPairingFlow(adminPhone = normalizedPhone, serverUrl = serverUrl)
                    },
                    onConfirmCode = { code ->
                        if (code.isBlank()) {
                            Toast.makeText(this, "Please enter the code shown on Guardian", Toast.LENGTH_SHORT).show()
                            return@PairDeviceScreen
                        }
                        confirmDevicePairing(code)
                    },
                    onPairSameDevice = {
                        if (currentPayloadJson.isNotBlank()) {
                            try {
                                val intent = Intent().apply {
                                    component = ComponentName("com.antitheft.guardian", "com.antitheft.guardian.ui.GuardianMainActivity")
                                    putExtra("pairing_payload", currentPayloadJson)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                startActivity(intent)
                                Toast.makeText(this, "Launching Guardian with pairing payload...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this, "Could not launch Guardian: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    showSameDeviceButton = showSameDeviceState,
                    onBack = { finish() },
                    isLoading = isLoadingState
                )
            }
        }

        if (savedPhone.isNotBlank() && savedPhone.length >= 5) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            currentPairingSecretHex = randomBytes.joinToString("") { "%02X".format(it) }
            setupPairingFlow(adminPhone = savedPhone, serverUrl = sessionManager.getServerUrl())
        }
    }

    private fun promptFor2Fa(adminPhone: String, serverUrl: String) {
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
                    setupPairingFlow(totpCode = code, adminPhone = adminPhone, serverUrl = serverUrl)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun setupPairingFlow(totpCode: String? = null, adminPhone: String, serverUrl: String) {
        if (totpCode.isNullOrBlank()) {
            promptFor2Fa(adminPhone, serverUrl)
            return
        }

        val adminKeystore = com.antitheft.admin.crypto.AdminKeystoreManager()
        val adminPubPem = adminKeystore.getPublicKeyPem()
        val pairingSecretHex = currentPairingSecretHex

        isLoadingState = true

        lifecycleScope.launch {
            try {
                val api = com.antitheft.admin.network.ApiClient.create(sessionManager)

                try {
                    val tokenResp = api.validateTotp(com.antitheft.admin.network.TotpValidateRequest(totpCode))
                    sessionManager.saveToken(tokenResp.access_token)
                    android.util.Log.i("AdminPairing", "2FA session upgraded successfully.")
                } catch (e: Exception) {
                    isLoadingState = false
                    Toast.makeText(this@PairDeviceActivity, "2FA verification failed: ${e.message}", Toast.LENGTH_LONG).show()
                    promptFor2Fa(adminPhone, serverUrl)
                    return@launch
                }

                val api2fa = com.antitheft.admin.network.ApiClient.create(sessionManager)

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
                        isLoadingState = false
                        Toast.makeText(this@PairDeviceActivity, "Trusted device registration failed: ${e.message}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }

                val tokenResp = api2fa.createPairingToken(
                    com.antitheft.admin.network.PairingTokenRequest(trusted_device_id = trustedId)
                )
                val pairingToken = tokenResp.pairing_token
                if (pairingToken.isBlank()) {
                    throw IllegalStateException("Server returned empty pairing token")
                }

                val effectiveServerUrl = if (serverUrl.isNotBlank()) {
                    serverUrl
                } else if (sessionManager.getServerUrl().contains("10.0.2.2") && !sessionManager.isEmulator()) {
                    "http://127.0.0.1:8000"
                } else {
                    sessionManager.getServerUrl()
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
                currentPayloadJson = jsonString

                val barcodeEncoder = BarcodeEncoder()
                val bitmap: Bitmap = barcodeEncoder.encodeBitmap(
                    jsonString,
                    BarcodeFormat.QR_CODE,
                    600,
                    600
                )
                qrBitmapState = bitmap
                pairingSecretState = pairingSecretHex
                showSameDeviceState = true
                isLoadingState = false

                Toast.makeText(this@PairDeviceActivity, "Fresh QR Code ready! Scan with Guardian.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                isLoadingState = false
                Toast.makeText(this@PairDeviceActivity, "Failed generating QR: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDevicePairing(code: String) {
        isLoadingState = true
        lifecycleScope.launch {
            try {
                val api = com.antitheft.admin.network.ApiClient.create(sessionManager)
                val pending = api.listPendingConfirmations()
                if (pending.isEmpty()) {
                    isLoadingState = false
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
                isLoadingState = false
                Toast.makeText(this@PairDeviceActivity, "Enrollment confirmed! Device is now ACTIVE.", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                isLoadingState = false
                Toast.makeText(this@PairDeviceActivity, "Confirmation failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
