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

        btnLogin.setOnClickListener {
            val serverUrl = etServerUrl.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            performLogin(username, password, serverUrl)
        }

        btnRegister.setOnClickListener {
            val serverUrl = etServerUrl.text.toString().trim()
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Enter username and password to register", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performRegister(username, password, serverUrl)
        }

        handleIncomingLoginIntent(intent)
    }

    private fun performRegister(username: String, password: String, serverUrl: String) {
        sessionManager.setServerUrl(serverUrl)
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val email = if (username.contains("@")) username else "$username@example.com"
                api.register(com.antitheft.admin.network.RegisterRequest(username, email, password))
                android.util.Log.i("AdminLogin", "REGISTER_SUCCESSFUL user:$username")
                Toast.makeText(this@AdminLoginActivity, "Owner registered successfully! Signing in...", Toast.LENGTH_SHORT).show()
                performLogin(username, password, serverUrl)
            } catch (e: Exception) {
                android.util.Log.e("AdminLogin", "REGISTER_FAILED", e)
                Toast.makeText(this@AdminLoginActivity, "Registration failed: ${e.message}", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@AdminLoginActivity, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openDashboard() {
        startActivity(Intent(this, AdminDashboardActivity::class.java))
        finish()
    }
}
