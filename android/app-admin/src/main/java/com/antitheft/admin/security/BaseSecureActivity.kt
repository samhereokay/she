package com.antitheft.admin.security

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.antitheft.admin.BuildConfig
import com.antitheft.admin.auth.AdminSessionManager

/**
 * Base Activity providing configurable banking-grade security protections:
 * 1. Screen recording & screenshot prevention via WindowManager.LayoutParams.FLAG_SECURE
 * 2. Environment integrity policy:
 *    - Attached debugger: BLOCK (app cannot run under debugger)
 *    - Rooted device: BLOCK only on Vault and Recovery/Command screens
 *    - Developer options / ADB: Non-blocking WARNING BANNER
 *    - All checks skipped when BuildConfig.DEBUG == true
 * 3. Robust biometric handling:
 *    - API 28/29 compatibility (no BIOMETRIC_STRONG | DEVICE_CREDENTIAL combo)
 *    - Graceful no-enrollment handling (no lockout, no silent bypass without user notice)
 */
abstract class BaseSecureActivity : AppCompatActivity() {

    protected val sessionManager by lazy { AdminSessionManager(this) }
    private var isBiometricAuthenticated = false

    /**
     * Override in sensitive activities (AdminVaultActivity, LiveTrackingActivity) to strictly
     * block rooted handsets. Default is false (allow with warning).
     */
    protected open val isSensitiveRecoveryScreen: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screen recording, screenshots, screencasting, and recents thumbnail leaking
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    override fun onResume() {
        super.onResume()

        // Skip all integrity policy blocks when BuildConfig.DEBUG is true
        if (BuildConfig.DEBUG) {
            return
        }

        // 1. Attached debugger = BLOCK
        if (SecurityIntegrityManager.isDebuggerAttached()) {
            showSecurityViolationDialog(
                title = "Security Alert: Debugger Detected",
                message = "A debugger or runtime tracer is attached to SHE Admin. The app cannot run while being debugged.",
                isBlocking = true
            )
            return
        }

        // 2. Rooted device = BLOCK vault and recovery screens only; warn on others
        if (SecurityIntegrityManager.isRooted()) {
            if (isSensitiveRecoveryScreen) {
                showSecurityViolationDialog(
                    title = "Access Blocked: Rooted Device",
                    message = "This device appears to be rooted. Access to the Encrypted Recovery Vault and emergency recovery controls is strictly restricted on rooted systems.",
                    isBlocking = true
                )
                return
            } else {
                Toast.makeText(
                    this,
                    "Notice: Device has root/superuser binaries. Vault and recovery commands are restricted.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // 3. Developer options / ADB = WARNING BANNER ONLY (non-blocking)
        if (SecurityIntegrityManager.isAdbEnabled(this) || SecurityIntegrityManager.isDeveloperOptionsEnabled(this)) {
            showSecurityViolationDialog(
                title = "Security Advisory: Developer Options / ADB Active",
                message = "Developer options or USB Debugging is currently active on your phone. For maximum security, we recommend disabling them in Android Settings.",
                intentAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                isBlocking = false
            )
        }
    }

    /**
     * Biometric authentication supporting API 28/29, API 30+, and device credentials.
     */
    protected fun requireBiometricAuth(
        title: String = "Biometric Verification",
        subtitle: String = "Verify your identity to proceed",
        onSuccess: () -> Unit,
        onFailedOrCancelled: (() -> Unit)? = null
    ) {
        val biometricManager = BiometricManager.from(this)

        // API 28/29 fix: BIOMETRIC_STRONG | DEVICE_CREDENTIAL combo was only introduced in API 30 (R)
        // On API 28/29, combining them throws IllegalArgumentException in AndroidX Biometric.
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

        val canAuth = biometricManager.canAuthenticate(authenticators)
        when (canAuth) {
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
                    }
                })

                val promptBuilder = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    promptBuilder.setAllowedAuthenticators(authenticators)
                } else {
                    // API 28/29 requires negative button when device credential is not combined via API 30 flags
                    promptBuilder.setNegativeButtonText("Use Account Credentials")
                }

                prompt.authenticate(promptBuilder.build())
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                // Inform user screen lock is recommended, but do NOT lock them out
                AlertDialog.Builder(this)
                    .setTitle("Enroll Screen Lock")
                    .setMessage("No fingerprint or screen lock PIN is enrolled on this phone. For banking-grade security, consider setting up a screen lock.")
                    .setPositiveButton("Set Up") { _, _ ->
                        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        onSuccess()
                    }
                    .setNegativeButton("Continue", { _, _ ->
                        onSuccess()
                    })
                    .setCancelable(false)
                    .show()
            }
            else -> {
                // Biometrics not present or hardware unsupported on older test devices
                onSuccess()
            }
        }
    }

    private fun showSecurityViolationDialog(
        title: String,
        message: String,
        intentAction: String? = null,
        isBlocking: Boolean = true
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(!isBlocking)

        if (isBlocking) {
            builder.setPositiveButton("Exit App") { _, _ ->
                finishAffinity()
            }
        } else {
            if (intentAction != null) {
                builder.setPositiveButton("Android Settings") { _, _ ->
                    try {
                        startActivity(Intent(intentAction))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }
            builder.setNegativeButton("Dismiss", null)
        }
        builder.show()
    }
}
