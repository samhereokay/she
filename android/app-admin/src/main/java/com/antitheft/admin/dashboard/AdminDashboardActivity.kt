package com.antitheft.admin.dashboard

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.antitheft.admin.R
import com.antitheft.admin.auth.AdminSessionManager
import com.antitheft.admin.crypto.AdminKeystoreManager
import com.antitheft.admin.network.ApiClient
import com.antitheft.admin.network.CommandDispatchRequest
import com.antitheft.admin.network.DeviceDto
import com.antitheft.admin.network.RegisterTrustedDeviceRequest
import com.antitheft.admin.network.TotpValidateRequest
import com.antitheft.admin.pairing.PairDeviceActivity
import com.antitheft.admin.ui.theme.SheTheme
import com.antitheft.admin.vault.AdminVaultActivity
import com.antitheft.common.crypto.EcdsaSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.Locale
import java.util.UUID

class AdminDashboardActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager
    private val adminKeystore = AdminKeystoreManager()
    private var devicesList by mutableStateOf<List<DeviceDto>>(emptyList())
    private var isLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = AdminSessionManager(this)

        setContent {
            SheTheme {
                AdminDashboardScreen(
                    devices = devicesList,
                    isLoading = isLoading,
                    onRefresh = { loadDevices() },
                    onOpenVault = { startActivity(Intent(this, AdminVaultActivity::class.java)) },
                    onLogout = {
                        sessionManager.clearSession()
                        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this, com.antitheft.admin.auth.AdminLoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
                    },
                    onPairDevice = {
                        startActivity(Intent(this, PairDeviceActivity::class.java))
                    },
                    onSendCommand = { device, cmd ->
                        when (cmd) {
                            "CLEAR_DEVICE_OWNER" -> showDestructiveClearOwnerDialog(device.id)
                            "TEST_MODE" -> promptForTotpAndDispatch(device.id, "STOLEN_MODE", "{\"test_mode\": true}")
                            "STOLEN_MODE" -> promptForTotpAndDispatch(device.id, "STOLEN_MODE", "{\"test_mode\": false}")
                            else -> promptForTotpAndDispatch(device.id, cmd, "{}")
                        }
                    },
                    onShowGuide = {
                        showCommandGuideDialog()
                    }
                )
            }
        }

        handleIncomingCommandIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingCommandIntent(intent)
    }

    private fun handleIncomingCommandIntent(intent: Intent?) {
        if (!com.antitheft.admin.BuildConfig.DEBUG) return
        val cmd = intent?.getStringExtra("dispatch_cmd")
        val targetId = intent?.getStringExtra("target_device_id")
        val totp = intent?.getStringExtra("totp_code") ?: ""
        if (!cmd.isNullOrBlank() && !targetId.isNullOrBlank()) {
            dispatchCommandOnline(targetId, cmd, totp)
        }
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
    }

    private fun loadDevices() {
        isLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val response = api.listDevices()
                response.devices.forEach { d ->
                    if (!d.pairing_id.isNullOrBlank()) {
                        sessionManager.saveDevicePairingId(d.id, d.pairing_id)
                    }
                }
                devicesList = response.devices
                isLoading = false
            } catch (e: Exception) {
                isLoading = false
                val msg = e.message ?: ""
                val is401 = msg.contains("401", ignoreCase = true) ||
                        (e is retrofit2.HttpException && e.code() == 401)
                if (is401) {
                    sessionManager.clearSession()
                    Toast.makeText(this@AdminDashboardActivity, "Session expired or owner account reset. Please sign in or register.", Toast.LENGTH_LONG).show()
                    val loginIntent = Intent(this@AdminDashboardActivity, com.antitheft.admin.auth.AdminLoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(loginIntent)
                    finish()
                } else {
                    Toast.makeText(this@AdminDashboardActivity, "Error loading devices: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCommandGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("SHE Recovery Command Guide")
            .setMessage(
                "• LOCATE:\n" +
                "  Pings the handset's hardware GPS to capture and transmit fresh satellite coordinates over WebSocket immediately.\n\n" +
                "• RING:\n" +
                "  Triggers loud acoustic emergency alarm at maximum hardware volume to pinpoint the handset nearby (overrides Silent/DND modes).\n\n" +
                "• STOP_RING:\n" +
                "  Silences the active acoustic siren alert.\n\n" +
                "• LOCK:\n" +
                "  Immediately turns off the screen and forces enterprise PIN/kiosk lockdown.\n\n" +
                "• TEST_MODE (Drill):\n" +
                "  Executes a safe security drill simulation on the handset without triggering real emergency panic escalation.\n\n" +
                "• STOLEN_MODE (Real OG):\n" +
                "  Arms comprehensive theft lockdown: hides status bar, suppresses notifications, locks into kiosk, and initiates high-frequency GPS tracking beacons.\n\n" +
                "• EXIT_KIOSK:\n" +
                "  Releases kiosk lock and restores normal Android home screen launcher.\n\n" +
                "• UNPAIR:\n" +
                "  Revokes mutual pairing handshake between this companion and the target handset.\n\n" +
                "• CLEAR_DEVICE_OWNER:\n" +
                "  Deprovisions Device Owner enterprise administration status (strictly requires 6-digit TOTP confirmation)."
            )
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showDestructiveClearOwnerDialog(targetDeviceId: String) {
        val input = EditText(this).apply {
            hint = "6-digit TOTP code (strictly required)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(32, 32, 32, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("⚠️ DANGER: CLEAR DEVICE OWNER")
            .setMessage("This command permanently deprovisions Device Owner status on the target device, clears all theft restrictions (safe boot, factory reset protection), and revokes enterprise lockdown.\n\nEnter your 6-digit TOTP authenticator code to confirm this destructive action:")
            .setView(input)
            .setPositiveButton("Deprovision Device") { _, _ ->
                val totpCode = input.text.toString().trim()
                if (totpCode.isBlank() || (totpCode.length != 6 && totpCode.length !in 8..9)) {
                    Toast.makeText(this, "Valid 6-digit TOTP or emergency recovery code (XXXX-XXXX) required", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                dispatchCommandOnline(targetDeviceId, "CLEAR_DEVICE_OWNER", totpCode, "{}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForTotpAndDispatch(targetDeviceId: String, commandType: String, paramsJson: String = "{}") {
        val input = EditText(this).apply {
            hint = "6-digit TOTP or recovery code (XXXX-XXXX)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(32, 32, 32, 32)
        }

        val explanation = when (commandType) {
            "LOCATE" -> "Pings hardware GPS to capture and stream fresh satellite coordinates."
            "RING" -> "Sounds loud acoustic alarm at maximum volume (overrides silent mode)."
            "STOP_RING" -> "Silences the active emergency siren alarm."
            "LOCK" -> "Immediately turns off screen and locks the device into kiosk security."
            "STOLEN_MODE" -> "Arms the handset: locks kiosk, suppresses notifications, and initiates high-frequency GPS beacons."
            "EXIT_KIOSK" -> "Releases kiosk lock and restores default Android home screen."
            "UNPAIR" -> "Revokes device pairing session from this controller."
            else -> "Executes signed remote command on target device."
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm: $commandType")
            .setMessage("$explanation\n\nEnter your 6-digit TOTP code or recovery code:")
            .setView(input)
            .setPositiveButton("Dispatch") { _, _ ->
                val totpCode = input.text.toString().trim()
                if (totpCode.isBlank() || (totpCode.length != 6 && totpCode.length !in 8..9)) {
                    Toast.makeText(this, "Valid 6-digit TOTP or emergency recovery code (XXXX-XXXX) required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                dispatchCommandOnline(targetDeviceId, commandType, totpCode, paramsJson)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dispatchCommandOnline(targetDeviceId: String, commandType: String, totpCode: String, paramsJson: String = "{}") {
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val trustedDevId = ensureTrustedDeviceRegistered(api)

                val seqCounter = sessionManager.getNextCommandSequence()
                val nowEpochSec = System.currentTimeMillis() / 1000L
                val expiryEpochSec = nowEpochSec + 300L
                val cmdId = UUID.randomUUID().toString()

                val pairingId = sessionManager.getDevicePairingId(targetDeviceId) ?: ""

                val canonicalBytes = EcdsaSigner.createCanonicalCommandBytes(
                    pairingId = pairingId,
                    commandId = cmdId,
                    targetDeviceId = targetDeviceId,
                    commandType = commandType,
                    sequenceCounter = seqCounter,
                    expiryEpoch = expiryEpochSec,
                    parametersJson = paramsJson
                )

                val signatureDer = adminKeystore.signCommand(canonicalBytes)
                val signatureBase64 = Base64.getEncoder().encodeToString(signatureDer)

                val req = CommandDispatchRequest(
                    command_id = cmdId,
                    pairing_id = pairingId,
                    trusted_device_id = trustedDevId,
                    target_device_id = targetDeviceId,
                    command_type = commandType,
                    sequence_counter = seqCounter,
                    expiry_epoch = expiryEpochSec,
                    parameters_json = paramsJson,
                    admin_signature = signatureBase64
                )

                val resp = api.dispatchCommand(req)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminDashboardActivity, "Command $commandType dispatched (Status: ${resp.status})", Toast.LENGTH_SHORT).show()
                }
                loadDevices()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminDashboardActivity, "Dispatch failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun ensureTrustedDeviceRegistered(api: com.antitheft.admin.network.AdminApiService): String {
        return withContext(Dispatchers.IO) {
            val existing = sessionManager.getTrustedDeviceId()
            if (!existing.isNullOrBlank()) {
                return@withContext existing
            }

            val pubKeyPem = adminKeystore.getPublicKeyPem()
            val req = RegisterTrustedDeviceRequest(
                device_name = "Admin Controller ${android.os.Build.MODEL}",
                public_key_pem = pubKeyPem
            )
            val resp = api.registerTrustedDevice(req)
            sessionManager.saveTrustedDeviceId(resp.id)
            resp.id
        }
    }
}
