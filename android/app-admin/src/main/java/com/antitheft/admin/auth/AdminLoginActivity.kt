package com.antitheft.admin.auth

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.antitheft.admin.dashboard.AdminDashboardActivity
import com.antitheft.admin.network.ApiClient
import com.antitheft.admin.network.LoginRequest
import com.antitheft.admin.network.RegisterRequest
import com.antitheft.admin.network.TotpValidateRequest
import com.antitheft.admin.network.TotpVerifyRequest
import com.antitheft.admin.ui.theme.SheTheme
import kotlinx.coroutines.launch

class AdminLoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager
    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    // 2FA & Recovery State
    private var totpSetupState by mutableStateOf<TotpSetupState?>(null)
    private var recoveryKitState by mutableStateOf<AccountRecoveryKitState?>(null)
    private var show2faLoginDialog by mutableStateOf(false)
    private var registeredUser: com.antitheft.admin.network.UserResponseDto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = AdminSessionManager(this)

        if (sessionManager.isLoggedIn()) {
            openDashboard()
            return
        }

        setContent {
            SheTheme {
                AdminLoginScreen(
                    initialServerUrl = sessionManager.getServerUrl(),
                    onLoginClick = { u, p, url ->
                        errorMessage = null
                        if (u.isBlank() || p.isBlank()) {
                            errorMessage = "Please enter username and password"
                        } else {
                            performLogin(u, p, url)
                        }
                    },
                    onRegisterClick = { u, email, phone, p, url ->
                        errorMessage = null
                        when {
                            u.isBlank() -> errorMessage = "Please enter an account username"
                            email.isBlank() -> errorMessage = "Please enter your recovery email"
                            phone.isBlank() -> errorMessage = "Please enter your owner mobile number"
                            p.isBlank() -> errorMessage = "Please enter a master password"
                            else -> performRegister(u, email, phone, p, url)
                        }
                    },
                    totpSetupState = totpSetupState,
                    onVerifyTotpSetup = { code ->
                        verifyTotpSetup(code)
                    },
                    recoveryKitState = recoveryKitState,
                    onDownloadRecoveryKit = { state ->
                        downloadRecoveryKit(state)
                    },
                    onDismissRecoveryKit = {
                        recoveryKitState = null
                        openDashboard()
                    },
                    show2faLoginDialog = show2faLoginDialog,
                    onValidate2faLogin = { code ->
                        validate2faLogin(code)
                    },
                    onDismiss2faLogin = {
                        show2faLoginDialog = false
                    },
                    isLoading = isLoading,
                    errorMessage = errorMessage
                )
            }
        }

        handleIncomingLoginIntent(intent)
    }

    private fun formatErrorMessage(e: Throwable, serverUrl: String): String {
        val serverDetail = if (e is retrofit2.HttpException) {
            try {
                val body = e.response()?.errorBody()?.string() ?: ""
                if (body.contains("{")) {
                    val json = org.json.JSONObject(body)
                    json.optString("detail", "")
                } else body
            } catch (_: Exception) { "" }
        } else ""

        if (serverDetail.isNotBlank()) {
            return serverDetail
        }

        val msg = e.message ?: ""
        val causeMsg = e.cause?.message ?: ""
        val full = "$msg $causeMsg"
        return when {
            full.contains("CLEARTEXT", ignoreCase = true) ->
                "Network Security Policy Error: Cleartext HTTP is blocked in release builds. Use HTTPS (e.g. https://...)."
            e is javax.net.ssl.SSLHandshakeException ||
            e is java.security.cert.CertificateException ||
            e is javax.net.ssl.SSLPeerUnverifiedException ||
            full.contains("CertPathValidatorException", ignoreCase = true) ||
            full.contains("SSL", ignoreCase = true) ->
                "TLS/SSL Certificate Error: The certificate for $serverUrl is untrusted or self-signed. Use a valid HTTPS tunnel with a public CA."
            e is java.net.ConnectException || full.contains("Failed to connect", ignoreCase = true) || full.contains("Connection refused", ignoreCase = true) ->
                "Connection Refused: Cannot reach $serverUrl. Check server host, port, and network reachability."
            e is java.net.UnknownHostException ->
                "DNS Error: Unable to resolve hostname for $serverUrl."
            e is java.net.SocketTimeoutException ->
                "Timeout Error: Connection to $serverUrl timed out."
            full.contains("401", ignoreCase = true) || full.contains("Unauthorized", ignoreCase = true) ->
                "Authentication Failed (401): Invalid username or password."
            full.contains("403", ignoreCase = true) || full.contains("Forbidden", ignoreCase = true) ->
                "Registration or Authorization Failed (403): Verify registration token or user permissions."
            full.contains("422", ignoreCase = true) ->
                "Validation Error (422): Request payload invalid or malformed."
            else ->
                "Network / API Error: ${e.localizedMessage ?: e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun performRegister(
        username: String,
        email: String,
        phone: String,
        password: String,
        serverUrl: String
    ) {
        sessionManager.setServerUrl(serverUrl)
        isLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val androidId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "device"
                val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}) [ID: ${androidId.take(8)}]"

                val userResp = api.register(
                    RegisterRequest(
                        username = username.trim(),
                        email = email.trim(),
                        password = password,
                        registration_token = null,
                        phone_number = phone.trim(),
                        device_info = deviceInfo
                    )
                )
                registeredUser = userResp
                android.util.Log.i("AdminLogin", "REGISTER_SUCCESSFUL user:${userResp.username} id:${userResp.id}")
                sessionManager.setAdminPhoneNumber(phone.trim())

                // Obtain 1FA session to initialize TOTP
                val tokenResp = api.login(LoginRequest(username.trim(), password))
                sessionManager.saveToken(tokenResp.access_token)

                // Request TOTP secret for setup
                val totpSetup = api.setupTotp()
                totpSetupState = TotpSetupState(
                    secret = totpSetup.secret,
                    otpauthUri = totpSetup.otpauth_uri
                )
                isLoading = false
            } catch (e: Exception) {
                isLoading = false
                android.util.Log.e("AdminLogin", "REGISTER_FAILED", e)
                val formatted = formatErrorMessage(e, serverUrl)
                errorMessage = formatted
                Toast.makeText(this@AdminLoginActivity, formatted, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun verifyTotpSetup(code: String) {
        isLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val tokenResp = api.verifyTotp(TotpVerifyRequest(code.trim()))
                sessionManager.saveToken(tokenResp.access_token)
                totpSetupState = null
                isLoading = false

                val user = registeredUser
                val androidId = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "device"
                val fallbackDevice = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}) [ID: ${androidId.take(8)}]"

                recoveryKitState = AccountRecoveryKitState(
                    accountId = user?.id ?: "ACCT-${System.currentTimeMillis()}",
                    username = user?.username ?: "owner",
                    email = user?.email ?: "owner@device",
                    phoneNumber = user?.phone_number?.ifBlank { null } ?: sessionManager.getAdminPhoneNumber().ifBlank { "Verified" },
                    deviceInfo = user?.device_info ?: fallbackDevice,
                    recoveryKeys = tokenResp.recovery_codes ?: emptyList()
                )
            } catch (e: Exception) {
                isLoading = false
                android.util.Log.e("AdminLogin", "TOTP_VERIFY_FAILED", e)
                val formatted = formatErrorMessage(e, sessionManager.getServerUrl())
                errorMessage = formatted
                Toast.makeText(this@AdminLoginActivity, formatted, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadRecoveryKit(state: AccountRecoveryKitState) {
        try {
            val recoveryDir = java.io.File(cacheDir, "recovery_kits")
            if (!recoveryDir.exists()) recoveryDir.mkdirs()

            val fileName = "she-recovery-kit-${state.username}.txt"
            val kitFile = java.io.File(recoveryDir, fileName)

            val keysBlock = if (state.recoveryKeys.isNotEmpty()) {
                state.recoveryKeys.chunked(2).mapIndexed { idx, pair ->
                    val left = "[${idx * 2 + 1}]  ${pair[0]}"
                    val right = if (pair.size > 1) "[${idx * 2 + 2}]  ${pair[1]}" else ""
                    String.format("%-25s %-25s", left, right)
                }.joinToString("\n")
            } else {
                "No recovery keys generated."
            }

            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date())
            val content = """
                ============================================================
                      SHE ANTI-THEFT SENTINEL • ACCOUNT RECOVERY KIT
                ============================================================
                Account ID:     ${state.accountId}
                Owner Username: ${state.username}
                Owner Email:    ${state.email}
                Owner Mobile:   ${state.phoneNumber}
                Registered HW:  ${state.deviceInfo}
                Date Created:   $timestamp
                ============================================================
                CRITICAL INSTRUCTIONS:
                If your phone is lost, stolen, or destroyed, your Google Authenticator
                is also on that phone. Use ANY of the 8 single-use emergency recovery
                keys below along with your Master Password to sign in to the
                Emergency Web Console from any browser or borrowed device:

                Emergency Web Console: https://she.up.railway.app/emergency
                ============================================================
                SINGLE-USE EMERGENCY RECOVERY KEYS:
                $keysBlock
                ============================================================
                Store this document securely in a password manager, encrypted USB,
                or printed paper copy. Each recovery key can only be used once.
                ============================================================
            """.trimIndent()

            kitFile.writeText(content)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                kitFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SHE Anti-Theft Account Recovery Kit - ${state.username}")
                putExtra(Intent.EXTRA_TEXT, "Account Recovery Kit for SHE Anti-Theft ($fileName)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Save or Share Recovery Kit (.txt)").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("AdminLogin", "Failed to export recovery kit", e)
            Toast.makeText(this, "Failed to export recovery kit: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingLoginIntent(intent)
    }

    private fun handleIncomingLoginIntent(intent: Intent?) {
        if (!com.antitheft.admin.BuildConfig.DEBUG) return
        val u = intent?.getStringExtra("username")
        val p = intent?.getStringExtra("password")
        val url = intent?.getStringExtra("server_url") ?: sessionManager.getServerUrl()
        if (!u.isNullOrBlank() && !p.isNullOrBlank()) {
            performLogin(u, p, url)
        }
    }

    private fun performLogin(username: String, password: String, serverUrl: String) {
        sessionManager.setServerUrl(serverUrl)
        isLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val tokenResp = api.login(LoginRequest(username.trim(), password))
                sessionManager.saveToken(tokenResp.access_token)
                isLoading = false

                if (tokenResp.totp_required) {
                    show2faLoginDialog = true
                } else {
                    android.util.Log.i("AdminLogin", "LOGIN_SUCCESSFUL user:$username")
                    Toast.makeText(this@AdminLoginActivity, "Authenticated successfully", Toast.LENGTH_SHORT).show()
                    openDashboard()
                }
            } catch (e: Exception) {
                isLoading = false
                android.util.Log.e("AdminLogin", "LOGIN_FAILED", e)
                val formatted = formatErrorMessage(e, serverUrl)
                errorMessage = formatted
                Toast.makeText(this@AdminLoginActivity, formatted, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun validate2faLogin(code: String) {
        isLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val finalToken = api.validateTotp(TotpValidateRequest(code.trim()))
                sessionManager.saveToken(finalToken.access_token)
                isLoading = false
                show2faLoginDialog = false
                Toast.makeText(this@AdminLoginActivity, "2FA Authenticated successfully", Toast.LENGTH_SHORT).show()
                openDashboard()
            } catch (e: Exception) {
                isLoading = false
                android.util.Log.e("AdminLogin", "2FA_LOGIN_FAILED", e)
                val formatted = formatErrorMessage(e, sessionManager.getServerUrl())
                errorMessage = formatted
                Toast.makeText(this@AdminLoginActivity, formatted, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openDashboard() {
        startActivity(Intent(this, AdminDashboardActivity::class.java))
        finish()
    }
}
