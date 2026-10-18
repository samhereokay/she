package com.antitheft.admin.auth

import android.content.Intent
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
import com.antitheft.admin.ui.theme.SheTheme
import kotlinx.coroutines.launch

class AdminLoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager
    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

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
                    onRegisterClick = { u, p, url ->
                        errorMessage = null
                        if (u.isBlank() || p.isBlank()) {
                            errorMessage = "Please enter username and password to register"
                        } else {
                            performRegister(u, p, url)
                        }
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
                "Registration Closed (403): An owner account already exists."
            full.contains("422", ignoreCase = true) ->
                "Validation Error (422): Request payload invalid or malformed."
            else ->
                "Network / API Error: ${e.localizedMessage ?: e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun performRegister(username: String, password: String, serverUrl: String) {
        sessionManager.setServerUrl(serverUrl)
        isLoading = true
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val email = if (username.contains("@")) username else "$username@example.com"
                api.register(com.antitheft.admin.network.RegisterRequest(username, email, password))
                android.util.Log.i("AdminLogin", "REGISTER_SUCCESSFUL user:$username")
                Toast.makeText(this@AdminLoginActivity, "Owner registered successfully! Signing in...", Toast.LENGTH_SHORT).show()
                performLogin(username, password, serverUrl)
            } catch (e: Exception) {
                isLoading = false
                android.util.Log.e("AdminLogin", "REGISTER_FAILED", e)
                val formatted = formatErrorMessage(e, serverUrl)
                errorMessage = formatted
                Toast.makeText(this@AdminLoginActivity, formatted, Toast.LENGTH_LONG).show()
            }
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
                val tokenResp = api.login(LoginRequest(username, password))
                sessionManager.saveToken(tokenResp.access_token)
                isLoading = false
                android.util.Log.i("AdminLogin", "LOGIN_SUCCESSFUL user:$username")
                Toast.makeText(this@AdminLoginActivity, "Authenticated successfully", Toast.LENGTH_SHORT).show()
                openDashboard()
            } catch (e: Exception) {
                isLoading = false
                android.util.Log.e("AdminLogin", "LOGIN_FAILED", e)
                val formatted = formatErrorMessage(e, serverUrl)
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
