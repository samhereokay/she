package com.antitheft.admin.vault

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.antitheft.admin.R
import com.antitheft.admin.auth.AdminSessionManager
import com.antitheft.admin.network.ApiClient
import com.antitheft.admin.network.TotpValidateRequest
import com.antitheft.admin.network.VaultItemCreateDto
import com.antitheft.admin.network.VaultItemDto
import com.antitheft.common.vault.VaultEncryptedContainer
import retrofit2.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AdminVaultActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager
    private val vaultManager = AdminVaultManager()
    private var isVaultUnlocked = false

    private lateinit var tvStatus: TextView
    private lateinit var containerItems: LinearLayout
    private lateinit var btnAddSecret: Button
    private lateinit var btnExportBackup: Button
    private lateinit var btnEmergencyShred: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)

        sessionManager = AdminSessionManager(this)
        tvStatus = findViewById(R.id.tvVaultStatus)
        containerItems = findViewById(R.id.containerVaultItems)
        btnAddSecret = findViewById(R.id.btnAddSecret)
        btnExportBackup = findViewById(R.id.btnExportBackup)
        btnEmergencyShred = findViewById(R.id.btnEmergencyShred)

        btnAddSecret.setOnClickListener { showAddSecretDialog() }
        btnExportBackup.setOnClickListener { showExportBackupDialog() }
        btnEmergencyShred.setOnClickListener { confirmEmergencyShred() }

        authenticateBiometricGate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isVaultUnlocked) {
            handleIncomingVaultIntent(intent)
        }
    }

    private fun handleIncomingVaultIntent(intent: Intent?) {
        if (!com.antitheft.admin.BuildConfig.DEBUG) return
        val action = intent?.getStringExtra("vault_action") ?: return
        Log.i(TAG, "HANDLING_VAULT_INTENT action:$action")
        when (action) {
            "add_secret" -> {
                val title = intent.getStringExtra("title") ?: "Recovery Secret"
                val type = intent.getStringExtra("type") ?: "RECOVERY_CODE"
                val secret = intent.getStringExtra("secret") ?: ""
                if (secret.isNotBlank()) {
                    encryptAndUploadSecret(title, type, secret)
                }
            }
            "export_backup" -> {
                val passphrase = intent.getStringExtra("passphrase") ?: "MasterPass123!"
                exportVaultWithPassphrase(passphrase)
            }
            "emergency_shred" -> {
                confirmEmergencyShred()
            }
        }
    }

    private fun authenticateBiometricGate() {
        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                showBiometricPrompt()
            }
            else -> {
                // Device credential fallback (PIN/Pattern) or software test fallback
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (keyguardManager != null && keyguardManager.isDeviceSecure) {
                    val intent = keyguardManager.createConfirmDeviceCredentialIntent(
                        "Unlock Recovery Vault",
                        "Authenticate using your screen lock PIN or biometric"
                    )
                    if (intent != null) {
                        startActivityForResult(intent, REQUEST_CODE_CREDENTIAL)
                        return
                    }
                }
                // Emulators without screen lock configured: allow direct entry with warning
                isVaultUnlocked = true
                tvStatus.text = "Vault Unlocked (Device credential not enrolled on test device)"
                loadVaultItems()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CREDENTIAL) {
            if (resultCode == RESULT_OK) {
                isVaultUnlocked = true
                tvStatus.text = "Vault Unlocked via Screen Lock Credential."
                loadVaultItems()
                handleIncomingVaultIntent(intent)
            } else {
                tvStatus.text = "Authentication required to view vault."
                finish()
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isVaultUnlocked = true
                tvStatus.text = "Vault Unlocked via Biometric Authentication."
                loadVaultItems()
                handleIncomingVaultIntent(intent)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                tvStatus.text = "Biometric error: $errString"
                // Fallback to device PIN if available
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (keyguardManager?.isDeviceSecure == true) {
                    val intent = keyguardManager.createConfirmDeviceCredentialIntent("Unlock Vault", "Enter screen PIN")
                    if (intent != null) {
                        startActivityForResult(intent, REQUEST_CODE_CREDENTIAL)
                        return
                    }
                }
                Toast.makeText(this@AdminVaultActivity, "Vault locked: $errString", Toast.LENGTH_SHORT).show()
                finish()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(this@AdminVaultActivity, "Biometric unrecognized. Try again.", Toast.LENGTH_SHORT).show()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Recovery Vault")
            .setSubtitle("Confirm identity to decrypt stored recovery secrets")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun loadVaultItems() {
        if (!isVaultUnlocked) return
        tvStatus.text = "Fetching encrypted items from server..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = ApiClient.create(sessionManager)
                val response = api.listVaultItems()
                val items = response.items

                withContext(Dispatchers.Main) {
                    containerItems.removeAllViews()
                    tvStatus.text = "Vault active: ${items.size} item(s) secured."

                    if (items.isEmpty()) {
                        val emptyTv = TextView(this@AdminVaultActivity).apply {
                            text = "No vault items stored yet.\nTap '+ Add Secret' to securely store recovery codes, IMEI, or insurance documents."
                            setTextColor(Color.parseColor("#94A3B8"))
                            setPadding(16, 32, 16, 16)
                            textSize = 14f
                        }
                        containerItems.addView(emptyTv)
                        return@withContext
                    }

                    for (item in items) {
                        val card = createVaultItemCard(item)
                        containerItems.addView(card)
                    }
                }
            } catch (e: HttpException) {
                if (e.code() == 403) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "2FA Required for Vault Access"
                        promptTotpUpgrade { loadVaultItems() }
                    }
                } else {
                    Log.e(TAG, "Failed loading vault items HTTP ${e.code()}", e)
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Error fetching vault: HTTP ${e.code()}"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading vault items", e)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error fetching vault: ${e.message}"
                }
            }
        }
    }

    private fun promptTotpUpgrade(onSuccess: () -> Unit) {
        val input = EditText(this).apply {
            hint = "6-digit TOTP code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Two-Factor Authentication Required")
            .setMessage("The Recovery Vault requires verified 2FA authorization. Please enter your authenticator TOTP code:")
            .setView(input)
            .setPositiveButton("Verify & Unlock") { _, _ ->
                val code = input.text.toString().trim()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val api = ApiClient.create(sessionManager)
                        val resp = api.validateTotp(TotpValidateRequest(code))
                        sessionManager.saveToken(resp.access_token)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AdminVaultActivity, "2FA Authenticated", Toast.LENGTH_SHORT).show()
                            onSuccess()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AdminVaultActivity, "2FA validation failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createVaultItemCard(item: VaultItemDto): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 20, 24, 20)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
            layoutParams = params
        }

        var decryptedTitle = item.title
        var decryptedType = item.item_type
        var decryptedSecret: String

        try {
            val container = VaultEncryptedContainer(
                ciphertextBase64 = item.encrypted_blob,
                ivBase64 = item.iv,
                tagBase64 = item.tag,
                keyVersion = item.key_version
            )
            val decryptedPayload = vaultManager.decryptSecret(container)
            if (decryptedPayload.trim().startsWith("{")) {
                try {
                    val obj = JSONObject(decryptedPayload)
                    decryptedTitle = obj.optString("title", item.title)
                    decryptedType = obj.optString("type", item.item_type)
                    decryptedSecret = obj.optString("secret", decryptedPayload)
                } catch (e: Exception) {
                    decryptedSecret = decryptedPayload
                }
            } else {
                decryptedSecret = decryptedPayload
            }
        } catch (e: Exception) {
            decryptedSecret = "[Decryption Failed: Key mismatch or tampered data]"
        }

        val tvTitle = TextView(this).apply {
            text = decryptedTitle
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        card.addView(tvTitle)

        val tvMeta = TextView(this).apply {
            text = "Type: $decryptedType • Key v${item.key_version} • Created: ${item.created_at.take(10)}"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            setPadding(0, 4, 0, 8)
        }
        card.addView(tvMeta)

        // Decrypted Secret Display
        val tvSecret = TextView(this).apply {
            text = decryptedSecret
            setTextColor(Color.parseColor("#A7F3D0"))
            textSize = 13f
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(16, 12, 16, 12)
        }
        card.addView(tvSecret)

        // Delete button
        val btnDelete = Button(this).apply {
            text = "Delete"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#7F1D1D"))
            setTextColor(Color.WHITE)
            val bParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8
            }
            layoutParams = bParams
            setOnClickListener {
                confirmDeleteItem(item.id, decryptedTitle)
            }
        }
        card.addView(btnDelete)

        return card
    }

    private fun showAddSecretDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val etTitle = EditText(this).apply {
            hint = "Title (e.g. Pixel 8 IMEI & Purchase Receipt)"
        }
        layout.addView(etTitle)

        val spinnerType = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AdminVaultActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("RECOVERY_CODE", "DEVICE_IDENTIFIER", "INSURANCE_POLICY", "PURCHASE_RECEIPT", "CONTACTS_BACKUP")
            )
        }
        layout.addView(spinnerType)

        val etSecret = EditText(this).apply {
            hint = "Plaintext Secret Content (never sent to server)"
            minLines = 3
        }
        layout.addView(etSecret)

        AlertDialog.Builder(this)
            .setTitle("Add Recovery Secret")
            .setView(layout)
            .setPositiveButton("Encrypt & Store") { _, _ ->
                val title = etTitle.text.toString().trim()
                val type = spinnerType.selectedItem.toString()
                val secret = etSecret.text.toString().trim()
                if (title.isNotBlank() && secret.isNotBlank()) {
                    encryptAndUploadSecret(title, type, secret)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun encryptAndUploadSecret(title: String, type: String, plaintextSecret: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Client-side AES-256-GCM encryption of full JSON object (title + type + secret)
                // True Zero-Knowledge: Plaintext title and type are NEVER sent to the server.
                val fullPayload = JSONObject().apply {
                    put("title", title)
                    put("type", type)
                    put("secret", plaintextSecret)
                }.toString()

                val container = vaultManager.encryptSecret(fullPayload, keyVersion = 1)

                val req = VaultItemCreateDto(
                    item_type = "ENCRYPTED",
                    title = "ENCRYPTED_VAULT_ITEM",
                    encrypted_blob = container.ciphertextBase64,
                    iv = container.ivBase64,
                    tag = container.tagBase64,
                    key_version = 1
                )
                val api = ApiClient.create(sessionManager)
                api.createVaultItem(req)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminVaultActivity, "Secret encrypted and saved!", Toast.LENGTH_SHORT).show()
                    loadVaultItems()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed creating vault item", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminVaultActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDeleteItem(itemId: String, title: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Vault Item")
            .setMessage("Are you sure you want to permanently delete '$title' from the recovery vault?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val api = ApiClient.create(sessionManager)
                        api.deleteVaultItem(itemId)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AdminVaultActivity, "Item deleted.", Toast.LENGTH_SHORT).show()
                            loadVaultItems()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AdminVaultActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExportBackupDialog() {
        val input = EditText(this).apply {
            hint = "Enter Master Recovery Passphrase"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Export Encrypted Paper Backup")
            .setMessage("Provide a strong passphrase to encrypt your paper backup. You will need this passphrase to restore your vault on another phone.")
            .setView(input)
            .setPositiveButton("Generate Backup") { _, _ ->
                val passphrase = input.text.toString().trim()
                if (passphrase.length < 8) {
                    Toast.makeText(this, "Passphrase must be at least 8 characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                exportVaultWithPassphrase(passphrase)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportVaultWithPassphrase(passphrase: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = ApiClient.create(sessionManager)
                val items = api.listVaultItems().items
                val array = JSONArray()

                for (item in items) {
                    try {
                        val container = VaultEncryptedContainer(
                            ciphertextBase64 = item.encrypted_blob,
                            ivBase64 = item.iv,
                            tagBase64 = item.tag,
                            keyVersion = item.key_version
                        )
                        val decrypted = vaultManager.decryptSecret(container)
                        val obj = JSONObject().apply {
                            put("title", item.title)
                            put("item_type", item.item_type)
                            put("secret", decrypted)
                        }
                        array.put(obj)
                    } catch (e: Exception) {
                        // Skip items that cannot be decrypted
                    }
                }

                val fullJson = array.toString()
                val exportBlob = vaultManager.exportPaperBackup(fullJson, passphrase)

                withContext(Dispatchers.Main) {
                    showExportResultDialog(exportBlob)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminVaultActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showExportResultDialog(exportBase64: String) {
        val tv = EditText(this).apply {
            setText(exportBase64)
            isFocusable = true
            isClickable = true
        }

        AlertDialog.Builder(this)
            .setTitle("Encrypted Backup String")
            .setMessage("Copy and store this string securely or print as a paper backup. It is encrypted with your recovery passphrase.")
            .setView(tv)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("VaultBackup", exportBase64)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Backup string copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmEmergencyShred() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ EMERGENCY VAULT SHRED")
            .setMessage("This will permanently delete all encrypted vault items from the server and SHRED the master encryption key from this device's Android Keystore.\n\nAll recovery codes and documents will be permanently lost. Proceed?")
            .setPositiveButton("SHRED EVERYTHING") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val api = ApiClient.create(sessionManager)
                        api.purgeVault()
                        vaultManager.shredVaultMasterKey()

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AdminVaultActivity, "Vault successfully shredded.", Toast.LENGTH_LONG).show()
                            loadVaultItems()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AdminVaultActivity, "Purge error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val TAG = "AdminVault"
        private const val REQUEST_CODE_CREDENTIAL = 9001
    }
}
