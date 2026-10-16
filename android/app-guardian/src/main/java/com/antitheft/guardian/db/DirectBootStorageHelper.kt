package com.antitheft.guardian.db

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import java.security.SecureRandom
import java.util.UUID

import com.antitheft.common.crypto.KeystoreAesGcmStorage

/**
 * Manages device metadata and sequence numbers inside Direct Boot Device Protected Storage (DE).
 * Accessible before First Unlock.
 * Sensitive keys (SQLCipher db_passphrase and pairing_secret) are encrypted at rest with hardware Keystore AES-256-GCM.
 */
class DirectBootStorageHelper(context: Context) {

    private val deContext: Context = if (context.isDeviceProtectedStorage) {
        context
    } else {
        context.createDeviceProtectedStorageContext()
    }

    private val prefs: SharedPreferences = deContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val secureStorage = KeystoreAesGcmStorage(
        context = deContext,
        prefsName = SECURE_PREFS_NAME,
        keyAlias = KEYSTORE_AES_ALIAS
    )

    init {
        migratePlaintextSecretsIfPresent()
    }

    private fun migratePlaintextSecretsIfPresent() {
        val legacyDbPass = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (legacyDbPass != null) {
            secureStorage.putEncryptedString(KEY_DB_PASSPHRASE, legacyDbPass)
            prefs.edit().remove(KEY_DB_PASSPHRASE).apply()
        }

        val legacyPairingSecret = prefs.getString(KEY_PAIRING_SECRET, null)
        if (legacyPairingSecret != null) {
            secureStorage.putEncryptedString(KEY_PAIRING_SECRET, legacyPairingSecret)
            prefs.edit().remove(KEY_PAIRING_SECRET).apply()
        }
    }

    val isUserUnlocked: Boolean
        get() {
            val userManager = deContext.getSystemService(Context.USER_SERVICE) as? UserManager
            return userManager?.isUserUnlocked ?: true
        }

    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun setDeviceId(id: String) {
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
    }

    fun getDeviceAlias(): String {
        return prefs.getString(KEY_DEVICE_ALIAS, "GuardianDevice") ?: "GuardianDevice"
    }

    fun setDeviceAlias(alias: String) {
        prefs.edit().putString(KEY_DEVICE_ALIAS, alias).apply()
    }

    fun getTrustedPhoneNumber(): String {
        return prefs.getString(KEY_TRUSTED_PHONE, "") ?: ""
    }

    fun setTrustedPhoneNumber(phone: String) {
        prefs.edit().putString(KEY_TRUSTED_PHONE, phone).apply()
    }

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000"
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    @Synchronized
    fun getNextSequenceNumber(): Long {
        val current = prefs.getLong(KEY_SEQUENCE_COUNTER, 0L)
        val next = current + 1
        prefs.edit().putLong(KEY_SEQUENCE_COUNTER, next).commit()
        return next
    }

    fun getPairingSecret(): ByteArray {
        var secretHex = secureStorage.getDecryptedString(KEY_PAIRING_SECRET, null)
        if (secretHex == null) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            secretHex = randomBytes.joinToString("") { "%02X".format(it) }
            secureStorage.putEncryptedString(KEY_PAIRING_SECRET, secretHex)
        }
        return hexToByteArray(secretHex)
    }

    fun setPairingSecret(secretBytes: ByteArray) {
        val hex = secretBytes.joinToString("") { "%02X".format(it) }
        secureStorage.putEncryptedString(KEY_PAIRING_SECRET, hex)
    }

    fun getDbPassphrase(): ByteArray {
        var passHex = secureStorage.getDecryptedString(KEY_DB_PASSPHRASE, null)
        if (passHex == null) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            passHex = randomBytes.joinToString("") { "%02X".format(it) }
            secureStorage.putEncryptedString(KEY_DB_PASSPHRASE, passHex)
        }
        return hexToByteArray(passHex)
    }

    private fun hexToByteArray(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in 0 until hex.length step 2) {
            result[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    fun getAdminPublicKeyPem(): String? {
        return prefs.getString(KEY_ADMIN_PUBLIC_KEY, null)
    }

    fun setAdminPublicKeyPem(pem: String) {
        prefs.edit().putString(KEY_ADMIN_PUBLIC_KEY, pem).apply()
    }

    @Synchronized
    fun getLastAdminCommandSequence(): Long {
        return prefs.getLong(KEY_LAST_ADMIN_CMD_SEQ, 0L)
    }

    @Synchronized
    fun setLastAdminCommandSequence(seq: Long) {
        prefs.edit().putLong(KEY_LAST_ADMIN_CMD_SEQ, seq).commit()
    }

    fun isStolenMode(): Boolean {
        return prefs.getBoolean(KEY_IS_STOLEN_MODE, false)
    }

    fun setStolenMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IS_STOLEN_MODE, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "guardian_de_storage"
        private const val SECURE_PREFS_NAME = "guardian_de_secure_storage"
        private const val KEYSTORE_AES_ALIAS = "guardian_de_keystore_aes_key"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_ALIAS = "device_alias"
        private const val KEY_TRUSTED_PHONE = "trusted_phone"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SEQUENCE_COUNTER = "sequence_counter"
        private const val KEY_PAIRING_SECRET = "pairing_secret"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val KEY_ADMIN_PUBLIC_KEY = "admin_public_key"
        private const val KEY_LAST_ADMIN_CMD_SEQ = "last_admin_cmd_seq"
        private const val KEY_IS_STOLEN_MODE = "is_stolen_mode"
    }
}
