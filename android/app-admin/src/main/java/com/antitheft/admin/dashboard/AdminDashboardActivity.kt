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
import com.antitheft.admin.security.BaseSecureActivity

import com.antitheft.admin.network.EmailConfirmRequest
import com.antitheft.admin.network.PhoneConfirmRequest
import com.antitheft.admin.network.PhoneStartRequest
import com.antitheft.admin.network.UserProfileDto
import com.antitheft.admin.network.UserSessionDto
import org.json.JSONObject

class AdminDashboardActivity : BaseSecureActivity() {

    private val adminKeystore = AdminKeystoreManager()
    private var devicesList by mutableStateOf<List<DeviceDto>>(emptyList())
    private var userProfile by mutableStateOf<UserProfileDto?>(null)
    private var isLoading by mutableStateOf(false)
    private var isUnlockedByBiometric by mutableStateOf(false)

    // Verification Dialog State
    private var showEmailVerifyDialog by mutableStateOf(false)
    private var isEmailVerifying by mutableStateOf(false)
    private var emailVerifyMessage by mutableStateOf<String?>(null)

    private var showPhoneVerifyDialog by mutableStateOf(false)
    private var isPhoneVerifying by mutableStateOf(false)
    private var phoneVerifyMessage by mutableStateOf<String?>(null)

    private var activeCommandDispatch by mutableStateOf<CommandDispatchDialogState?>(null)
    private var showGuideDialog by mutableStateOf(false)

    // Session Management State
    private var showSessionsDialog by mutableStateOf(false)
    private var sessionsList by mutableStateOf<List<UserSessionDto>>(emptyList())
    private var isSessionsLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (sessionManager.isBiometricEnabled() && !sessionManager.isEmulator()) {
            requireBiometricAuth(
                title = "SHE Security Center",
                subtitle = "Confirm fingerprint or screen lock to access command center",
                onSuccess = {
                    isUnlockedByBiometric = true
                    refreshDashboardData()
                },
                onFailedOrCancelled = {
                    finish()
                }
            )
        } else {
            isUnlockedByBiometric = true
            refreshDashboardData()
        }

        setContent {
            SheTheme {
                if (isUnlockedByBiometric) {
                    AdminDashboardScreen(
                        userProfile = userProfile,
                        devices = devicesList,
                        isLoading = isLoading,
                        onRefresh = { refreshDashboardData() },
                        onOpenVault = {
                            if (userProfile?.email_verified_at == null) {
                                emailVerifyMessage = "Email verification required before accessing the vault."
                                showEmailVerifyDialog = true
                                Toast.makeText(this, "Email verification required before accessing vault", Toast.LENGTH_SHORT).show()
                            } else {
                                startActivity(Intent(this, AdminVaultActivity::class.java))
                            }
                        },
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
                            if (userProfile?.email_verified_at == null) {
                                emailVerifyMessage = "Email verification required before pairing devices."
                                showEmailVerifyDialog = true
                                Toast.makeText(this, "Email verification required before pairing devices", Toast.LENGTH_SHORT).show()
                            } else {
                                startActivity(Intent(this, PairDeviceActivity::class.java))
                            }
                        },
                        onSendCommand = { device, cmd ->
                            if (userProfile?.email_verified_at == null) {
                                emailVerifyMessage = "Email verification required before dispatching commands."
                                showEmailVerifyDialog = true
                                Toast.makeText(this, "Email verification required before dispatching commands", Toast.LENGTH_SHORT).show()
                            } else {
                                activeCommandDispatch = CommandDispatchDialogState(device, cmd)
                            }
                        },
                        onShowGuide = {
                            showGuideDialog = true
                        },
                        onStartEmailVerification = {
                            emailVerifyMessage = null
                            showEmailVerifyDialog = true
                        },
                        onStartPhoneVerification = {
                            phoneVerifyMessage = null
                            showPhoneVerifyDialog = true
                        },
                        showEmailVerifyDialog = showEmailVerifyDialog,
                        onDismissEmailVerifyDialog = { showEmailVerifyDialog = false },
                        onSendEmailCode = { sendEmailVerificationCode() },
                        onConfirmEmailCode = { code -> confirmEmailVerificationCode(code) },
                        isEmailVerifying = isEmailVerifying,
                        emailVerifyMessage = emailVerifyMessage,
                        showPhoneVerifyDialog = showPhoneVerifyDialog,
                        onDismissPhoneVerifyDialog = { showPhoneVerifyDialog = false },
                        onSendPhoneCode = { phone -> sendPhoneVerificationCode(phone) },
                        onConfirmPhoneCode = { code -> confirmPhoneVerificationCode(code) },
                        isPhoneVerifying = isPhoneVerifying,
                        phoneVerifyMessage = phoneVerifyMessage,
                        commandDispatchState = activeCommandDispatch,
                        onDismissCommandDispatch = { activeCommandDispatch = null },
                        onConfirmCommandDispatch = { totpCode, friendPhone ->
                            val dispatchState = activeCommandDispatch
                            activeCommandDispatch = null
                            if (dispatchState != null) {
                                dispatchCommandWithTotp(
                                    targetDeviceId = dispatchState.device.id,
                                    commandType = dispatchState.commandType,
                                    totpCode = totpCode,
                                    friendPhone = friendPhone
                                )
                            }
                        },
                        showGuideDialog = showGuideDialog,
                        onDismissGuideDialog = { showGuideDialog = false },
                        showSessionsDialog = showSessionsDialog,
                        onOpenSessions = {
                            showSessionsDialog = true
                            loadSessions()
                        },
                        onDismissSessionsDialog = { showSessionsDialog = false },
                        sessionsList = sessionsList,
                        isSessionsLoading = isSessionsLoading,
                        onRevokeOtherSessions = { revokeOtherSessions() },
                        onRevokeSession = { sessionId, force -> revokeSession(sessionId, force) }
                    )
                }
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
        if (isUnlockedByBiometric) {
            refreshDashboardData()
        }
    }

    private fun refreshDashboardData() {
        isLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                try {
                    val profile = api.getProfile()
                    userProfile = profile
                    if (profile.email_verified_at.isNullOrBlank()) {
                        showEmailVerifyDialog = true
                    }
                } catch (pe: Exception) {
                    android.util.Log.w("AdminDashboard", "Failed to fetch profile: ${pe.message}")
                }
                loadDevicesInternal(api)
            } catch (e: Exception) {
                handleApiError(e)
            } finally {
                isLoading = false
            }
        }
    }

    private suspend fun loadDevicesInternal(api: com.antitheft.admin.network.AdminApiService) {
        val response = api.listDevices()
        response.devices.forEach { d ->
            if (!d.pairing_id.isNullOrBlank()) {
                sessionManager.saveDevicePairingId(d.id, d.pairing_id)
            }
        }
        devicesList = response.devices
    }

    private fun handleApiError(e: Throwable) {
        val msg = e.message ?: ""
        var isEmailNotVerified = false

        if (e is retrofit2.HttpException && e.code() == 403) {
            val errBody = try { e.response()?.errorBody()?.string() ?: "" } catch (_: Exception) { "" }
            if (errBody.contains("email_not_verified", ignoreCase = true) || msg.contains("email_not_verified", ignoreCase = true)) {
                isEmailNotVerified = true
            }
        } else if (msg.contains("email_not_verified", ignoreCase = true)) {
            isEmailNotVerified = true
        }

        if (isEmailNotVerified) {
            showEmailVerifyDialog = true
            Toast.makeText(this@AdminDashboardActivity, "Ownership verification required: Please verify your email", Toast.LENGTH_LONG).show()
            return
        }

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

    private fun sendEmailVerificationCode() {
        isEmailVerifying = true
        emailVerifyMessage = null
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                api.sendEmailVerification()
                withContext(Dispatchers.Main) {
                    emailVerifyMessage = "Verification OTP dispatched to ${userProfile?.email ?: "your email"}. Check your inbox."
                    Toast.makeText(this@AdminDashboardActivity, "Verification OTP code sent", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errMsg = if (e is retrofit2.HttpException) {
                        try {
                            val body = e.response()?.errorBody()?.string() ?: ""
                            if (body.contains("{")) JSONObject(body).optString("detail", e.message()) else body
                        } catch (_: Exception) { e.message() }
                    } else e.message ?: "Failed to dispatch email OTP"
                    emailVerifyMessage = "Error: $errMsg"
                }
            } finally {
                isEmailVerifying = false
            }
        }
    }

    private fun confirmEmailVerificationCode(code: String) {
        if (code.length != 6) {
            emailVerifyMessage = "Please enter the full 6-digit code"
            return
        }
        isEmailVerifying = true
        emailVerifyMessage = null
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val resp = api.confirmEmailVerification(EmailConfirmRequest(code.trim()))
                withContext(Dispatchers.Main) {
                    userProfile = userProfile?.copy(email_verified_at = resp.email_verified_at)
                    showEmailVerifyDialog = false
                    Toast.makeText(this@AdminDashboardActivity, "Email verified successfully! Access granted.", Toast.LENGTH_LONG).show()
                    refreshDashboardData()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errMsg = if (e is retrofit2.HttpException) {
                        try {
                            val body = e.response()?.errorBody()?.string() ?: ""
                            if (body.contains("{")) JSONObject(body).optString("detail", e.message()) else body
                        } catch (_: Exception) { e.message() }
                    } else e.message ?: "Verification failed"
                    emailVerifyMessage = "Error: $errMsg"
                }
            } finally {
                isEmailVerifying = false
            }
        }
    }

    private fun sendPhoneVerificationCode(phone: String) {
        val clean = phone.trim()
        if (clean.isBlank()) {
            phoneVerifyMessage = "Please enter an international phone number (e.g. +1... or +91...)"
            return
        }
        val normalized = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeToE164(clean)
        val targetPhone = if (normalized.isNotBlank()) normalized else clean

        isPhoneVerifying = true
        phoneVerifyMessage = null
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                api.startPhoneVerification(PhoneStartRequest(targetPhone))
                withContext(Dispatchers.Main) {
                    phoneVerifyMessage = "SMS challenge dispatched via active Guardian handset to $targetPhone."
                    Toast.makeText(this@AdminDashboardActivity, "SMS challenge dispatched", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errMsg = if (e is retrofit2.HttpException) {
                        try {
                            val body = e.response()?.errorBody()?.string() ?: ""
                            if (body.contains("{")) JSONObject(body).optString("detail", e.message()) else body
                        } catch (_: Exception) { e.message() }
                    } else e.message ?: "Failed to dispatch SMS"
                    phoneVerifyMessage = "Error: $errMsg"
                }
            } finally {
                isPhoneVerifying = false
            }
        }
    }

    private fun confirmPhoneVerificationCode(code: String) {
        if (code.length != 6) {
            phoneVerifyMessage = "Please enter the 6-digit SMS code"
            return
        }
        isPhoneVerifying = true
        phoneVerifyMessage = null
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val resp = api.confirmPhoneVerification(PhoneConfirmRequest(code.trim()))
                withContext(Dispatchers.Main) {
                    userProfile = userProfile?.copy(
                        phone_verified_at = resp.phone_verified_at,
                        fallback_phone_e164 = resp.fallback_phone_e164
                    )
                    sessionManager.setAdminPhoneNumber(resp.fallback_phone_e164)
                    showPhoneVerifyDialog = false
                    Toast.makeText(this@AdminDashboardActivity, "Phone verified! Offline SMS beacons armed.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errMsg = if (e is retrofit2.HttpException) {
                        try {
                            val body = e.response()?.errorBody()?.string() ?: ""
                            if (body.contains("{")) JSONObject(body).optString("detail", e.message()) else body
                        } catch (_: Exception) { e.message() }
                    } else e.message ?: "Verification failed"
                    phoneVerifyMessage = "Error: $errMsg"
                }
            } finally {
                isPhoneVerifying = false
            }
        }
    }

    private fun dispatchCommandWithTotp(
        targetDeviceId: String,
        commandType: String,
        totpCode: String,
        friendPhone: String
    ) {
        val paramsJson = when (commandType) {
            "TEST_MODE" -> {
                val json = JSONObject().apply {
                    put("test_mode", true)
                    if (friendPhone.isNotBlank()) {
                        put("test_recipient_phone", friendPhone.trim())
                    }
                }
                json.toString()
            }
            "STOLEN_MODE" -> "{\"test_mode\": false}"
            else -> "{}"
        }
        val actualCmd = if (commandType == "TEST_MODE") "STOLEN_MODE" else commandType
        dispatchCommandOnline(targetDeviceId, actualCmd, totpCode, paramsJson)
    }

    private fun dispatchCommandOnline(targetDeviceId: String, commandType: String, totpCode: String = "", paramsJson: String = "{}") {
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                if (totpCode.isNotBlank()) {
                    try {
                        val upgraded = api.validateTotp(com.antitheft.admin.network.TotpValidateRequest(totpCode.trim()))
                        sessionManager.saveToken(upgraded.access_token)
                    } catch (te: Exception) {
                        android.util.Log.w("AdminDashboard", "TOTP pre-validation note: ${te.message}")
                    }
                }
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
                refreshDashboardData()
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

    private fun loadSessions() {
        isSessionsLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val list = api.getSessions()
                sessionsList = list
            } catch (e: Exception) {
                android.util.Log.e("AdminDashboard", "Failed to load sessions", e)
                Toast.makeText(this@AdminDashboardActivity, "Failed to load sessions: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isSessionsLoading = false
            }
        }
    }

    private fun revokeOtherSessions() {
        isSessionsLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val res = api.revokeOtherSessions()
                Toast.makeText(this@AdminDashboardActivity, res.message, Toast.LENGTH_LONG).show()
                loadSessions()
            } catch (e: Exception) {
                android.util.Log.e("AdminDashboard", "Failed to revoke other sessions", e)
                Toast.makeText(this@AdminDashboardActivity, "Failed to revoke other sessions: ${e.message}", Toast.LENGTH_SHORT).show()
                isSessionsLoading = false
            }
        }
    }

    private fun revokeSession(sessionId: String, force: Boolean = false) {
        isSessionsLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val res = api.revokeSession(sessionId, force)
                Toast.makeText(this@AdminDashboardActivity, res.message, Toast.LENGTH_SHORT).show()
                loadSessions()
            } catch (e: Exception) {
                android.util.Log.e("AdminDashboard", "Failed to revoke session", e)
                Toast.makeText(this@AdminDashboardActivity, "Failed to revoke session: ${e.message}", Toast.LENGTH_SHORT).show()
                isSessionsLoading = false
            }
        }
    }
}
