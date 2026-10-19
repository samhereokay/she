package com.antitheft.admin.security

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.antitheft.admin.BuildConfig
import com.antitheft.admin.auth.AdminSessionManager

/**
 * Base Activity providing banking-grade security protections:
 * 1. Screen recording & screenshot prevention via WindowManager.LayoutParams.FLAG_SECURE
 * 2. Anti-debugging / tracer check
 * 3. Developer Options & USB Debugging enforcement
 * 4. Device integrity / Root check
 * 5. App-level Biometric authentication gate (Fingerprint / Face / Device PIN)
 */
abstract class BaseSecureActivity : AppCompatActivity() {

    protected val sessionManager by lazy { AdminSessionManager(this) }
    private var isBiometricAuthenticated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Block Screen Recording & Screenshots (Banking-grade protection)
        // FLAG_SECURE prevents screen recording, screen casting, screenshots, and recents thumbnail snooping
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    override fun onResume() {
        super.onResume()

        // In production release builds, perform rigorous environment integrity checks
        if (!BuildConfig.DEBUG && !sessionManager.isEmulator()) {
            if (SecurityIntegrityManager.isDebuggerAttached()) {
                showSecurityViolationDialog(
                    title = "Security Alert: Debugger Detected",
                    message = "A debugger or runtime tracer is attached to SHE Admin. For your security and to prevent memory theft, the app cannot run while being debugged."
                )
                return
            }

            if (SecurityIntegrityManager.isRooted()) {
                showSecurityViolationDialog(
                    title = "Security Alert: Rooted Device",
                    message = "This device appears to be rooted or running modified system firmware. Key integrity and hardware Keystore storage cannot be guaranteed on rooted devices."
                )
                return
            }

            if (SecurityIntegrityManager.isAdbEnabled(this)) {
                showSecurityViolationDialog(
                    title = "Security Alert: USB Debugging Enabled",
                    message = "USB Debugging (ADB) is enabled on this handset. To prevent unauthorized extraction of cryptographic keys, please disable USB debugging in Android Settings.",
                    intentAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )
                return
            }

            if (SecurityIntegrityManager.isDeveloperOptionsEnabled(this)) {
                showSecurityViolationDialog(
                    title = "Security Alert: Developer Options Active",
                    message = "Developer options are currently active. Please turn off Developer Options in Android Settings to ensure banking-level isolation.",
                    intentAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                )
                return
            }
        }
    }

    /**
     * Prompts for biometric authentication before granting access to sensitive screens or actions.
     */
    protected fun requireBiometricAuth(
        title: String = "Biometric Verification",
        subtitle: String = "Verify your identity to proceed",
        onSuccess: () -> Unit,
        onFailedOrCancelled: (() -> Unit)? = null
    ) {
        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val executor = ContextCompat.getMainExecutor(this)
                val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        isBiometricAuthenticated = true
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        onFailedOrCancelled?.invoke()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        // User failed biometric match, BiometricPrompt UI remains visible for retry
                    }
                })

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(authenticators)
                    .build()

                prompt.authenticate(promptInfo)
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                // If device has no biometric enrolled, alert user or allow fallback
                AlertDialog.Builder(this)
                    .setTitle("Enroll Screen Lock")
                    .setMessage("No biometric or screen lock PIN is enrolled on this phone. For banking-grade security, please set up a screen lock.")
                    .setPositiveButton("Settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }
                    .setNegativeButton("Proceed", null)
                    .show()
                onSuccess()
            }
            else -> {
                // Not supported on hardware or unavailable; pass through
                onSuccess()
            }
        }
    }

    private fun showSecurityViolationDialog(
        title: String,
        message: String,
        intentAction: String? = null
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(if (intentAction != null) "Open Settings" else "Exit App") { _, _ ->
                if (intentAction != null) {
                    try {
                        startActivity(Intent(intentAction))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                    finishAffinity()
                } else {
                    finishAffinity()
                }
            }
            .setNegativeButton("Close") { _, _ ->
                finishAffinity()
            }
            .show()
    }
}
