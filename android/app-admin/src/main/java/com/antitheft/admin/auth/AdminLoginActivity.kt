package com.antitheft.admin.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antitheft.admin.R
import com.antitheft.admin.dashboard.AdminDashboardActivity
import com.antitheft.admin.network.ApiClient
import com.antitheft.admin.network.LoginRequest
import kotlinx.coroutines.launch

class AdminLoginActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = AdminSessionManager(this)

        if (sessionManager.isLoggedIn()) {
            openDashboard()
            return
        }

        setContentView(R.layout.activity_login)

        val etServerUrl = findViewById<EditText>(R.id.etServerUrl)
        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)

        etServerUrl.setText(sessionManager.getServerUrl())

        val btnRegister = findViewById<Button>(R.id.btnRegister)

        val tvErrorMessage = findViewById<android.widget.TextView>(R.id.tvErrorMessage)

        btnLogin.setOnClickListener {
            val serverUrl = etServerUrl.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            tvErrorMessage.visibility = android.view.View.GONE

            performLogin(username, password, serverUrl, tvErrorMessage)
        }

        btnRegister.setOnClickListener {
            val serverUrl = etServerUrl.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            tvErrorMessage.visibility = android.view.View.GONE
            if (username.isBlank() || password.isBlank()) {
                val msg = "Enter username and password to register"
                tvErrorMessage.text = msg
                tvErrorMessage.visibility = android.view.View.VISIBLE
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performRegister(username, password, serverUrl, tvErrorMessage)
        }

        handleIncomingLoginIntent(intent, tvErrorMessage)
    }

    private fun formatErrorMessage(e: Throwable, serverUrl: String): String {
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

    private fun performRegister(username: String, password: String, serverUrl: String, tvError: android.widget.TextView) {
        sessionManager.setServerUrl(serverUrl)
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val email = if (username.contains("@")) username else "$username@example.com"
                api.register(com.antitheft.admin.network.RegisterRequest(username, email, password))
                android.util.Log.i("AdminLogin", "REGISTER_SUCCESSFUL user:$username")
                Toast.makeText(this@AdminLoginActivity, "Owner registered successfully! Signing in...", Toast.LENGTH_SHORT).show()
                performLogin(username, password, serverUrl, tvError)
            } catch (e: Exception) {
                android.util.Log.e("AdminLogin", "REGISTER_FAILED", e)
                val formatted = formatErrorMessage(e, serverUrl)
                tvError.text = formatted
                tvError.visibility = android.view.View.VISIBLE
                Toast.makeText(this@AdminLoginActivity, formatted, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tvErrorMessage = findViewById<android.widget.TextView>(R.id.tvErrorMessage)
        handleIncomingLoginIntent(intent, tvErrorMessage)
    }

    private fun handleIncomingLoginIntent(intent: Intent?, tvError: android.widget.TextView) {
        if (!com.antitheft.admin.BuildConfig.DEBUG) return
        val u = intent?.getStringExtra("username")
        val p = intent?.getStringExtra("password")
        val url = intent?.getStringExtra("server_url") ?: sessionManager.getServerUrl()
        if (!u.isNullOrBlank() && !p.isNullOrBlank()) {
            performLogin(u, p, url, tvError)
        }
    }

    private fun performLogin(username: String, password: String, serverUrl: String, tvError: android.widget.TextView) {
        sessionManager.setServerUrl(serverUrl)
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val tokenResp = api.login(LoginRequest(username, password))
                sessionManager.saveToken(tokenResp.access_token)
                android.util.Log.i("AdminLogin", "LOGIN_SUCCESSFUL user:$username")
                Toast.makeText(this@AdminLoginActivity, "Authenticated successfully", Toast.LENGTH_SHORT).show()
                openDashboard()
            } catch (e: Exception) {
                android.util.Log.e("AdminLogin", "LOGIN_FAILED", e)
                val formatted = formatErrorMessage(e, serverUrl)
                tvError.text = formatted
                tvError.visibility = android.view.View.VISIBLE
                Toast.makeText(this@AdminLoginActivity, formatted, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openDashboard() {
        startActivity(Intent(this, AdminDashboardActivity::class.java))
        finish()
    }
}
