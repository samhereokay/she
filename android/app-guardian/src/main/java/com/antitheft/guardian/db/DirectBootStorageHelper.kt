package com.antitheft.guardian.db

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import java.security.SecureRandom
import java.util.UUID

import com.antitheft.common.crypto.KeystoreAesGcmStorage

import com.antitheft.guardian.sms.InboundSmsStorage

/**
 * Manages device metadata and sequence numbers inside Direct Boot Device Protected Storage (DE).
 * Accessible before First Unlock.
 * Sensitive keys (SQLCipher db_passphrase and pairing_secret) are encrypted at rest with hardware Keystore AES-256-GCM.
 */
class DirectBootStorageHelper(context: Context) : InboundSmsStorage {

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

    override fun getDeviceAlias(): String {
        return prefs.getString(KEY_DEVICE_ALIAS, "GuardianDevice") ?: "GuardianDevice"
    }

    fun setDeviceAlias(alias: String) {
        prefs.edit().putString(KEY_DEVICE_ALIAS, alias).apply()
    }

    override fun getTrustedPhoneNumber(): String {
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
    override fun getNextSequenceNumber(): Long {
        val current = prefs.getLong(KEY_SEQUENCE_COUNTER, 0L)
        val next = current + 1
        prefs.edit().putLong(KEY_SEQUENCE_COUNTER, next).commit()
        return next
    }

    override fun getPairingSecret(): ByteArray {
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

    @Synchronized
    fun rotateDbPassphrase(): ByteArray {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val passHex = randomBytes.joinToString("") { "%02X".format(it) }
        secureStorage.putEncryptedString(KEY_DB_PASSPHRASE, passHex)
        return randomBytes
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

    fun getPairingId(): String? {
        return prefs.getString(KEY_PAIRING_ID, null)
    }

    fun setPairingId(pairingId: String) {
        prefs.edit().putString(KEY_PAIRING_ID, pairingId).apply()
    }

    @Synchronized
    fun getLastAdminCommandSequence(trustedDeviceId: String = ""): Long {
        val key = if (trustedDeviceId.isNotBlank()) "${KEY_LAST_ADMIN_CMD_SEQ}_$trustedDeviceId" else KEY_LAST_ADMIN_CMD_SEQ
        return prefs.getLong(key, 0L)
    }

    @Synchronized
    fun setLastAdminCommandSequence(seq: Long) {
        setLastAdminCommandSequence("", seq)
    }

    @Synchronized
    fun setLastAdminCommandSequence(trustedDeviceId: String, seq: Long) {
        val key = if (trustedDeviceId.isNotBlank()) "${KEY_LAST_ADMIN_CMD_SEQ}_$trustedDeviceId" else KEY_LAST_ADMIN_CMD_SEQ
        prefs.edit().putLong(key, seq).commit()
    }

    fun isStolenMode(): Boolean {
        return prefs.getBoolean(KEY_IS_STOLEN_MODE, false)
    }

    fun setStolenMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IS_STOLEN_MODE, enabled).apply()
    }

    fun isPaired(): Boolean {
        return !getAdminPublicKeyPem().isNullOrBlank()
    }

    @Synchronized
    fun clearPairing() {
        prefs.edit()
            .remove(KEY_ADMIN_PUBLIC_KEY)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PAIRING_ID)
            .remove(KEY_LAST_ADMIN_CMD_SEQ)
            .remove(KEY_LAST_INBOUND_SMS_SEQ)
            .remove(KEY_IS_STOLEN_MODE)
            .remove(KEY_TRUSTED_PHONE)
            .remove(KEY_RECOVERY_FAILED_ATTEMPTS)
            .remove(KEY_RECOVERY_LOCKOUT_UNTIL)
            .commit()
        secureStorage.remove(KEY_PAIRING_SECRET)
        secureStorage.remove(KEY_OFFLINE_RECOVERY_CODE)
    }

    @Synchronized
    override fun getLastInboundSmsSequence(): Long {
        return prefs.getLong(KEY_LAST_INBOUND_SMS_SEQ, 0L)
    }

    @Synchronized
    override fun setLastInboundSmsSequence(seq: Long) {
        prefs.edit().putLong(KEY_LAST_INBOUND_SMS_SEQ, seq).commit()
    }

    fun generateOfflineRecoveryCode(): String {
        // Crockford Base32 alphabet (32 characters, excluding I, L, O, U for readability)
        val charset = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val sr = SecureRandom()
        val sb = StringBuilder("RECOVER-")
        for (i in 0 until 8) {
            sb.append(charset[sr.nextInt(charset.length)])
        }
        return sb.toString()
    }

    fun getOfflineRecoveryCode(): String {
        var code = secureStorage.getDecryptedString(KEY_OFFLINE_RECOVERY_CODE, null)
        if (code == null) {
            code = generateOfflineRecoveryCode()
            secureStorage.putEncryptedString(KEY_OFFLINE_RECOVERY_CODE, code)
        }
        return code
    }

    fun setOfflineRecoveryCode(code: String) {
        secureStorage.putEncryptedString(KEY_OFFLINE_RECOVERY_CODE, code)
    }

    @Synchronized
    fun getRecoveryLockoutRemainingSeconds(): Long {
        val lockoutUntil = prefs.getLong(KEY_RECOVERY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return if (lockoutUntil > now) (lockoutUntil - now + 999) / 1000 else 0L
    }

    @Synchronized
    fun getFailedRecoveryAttempts(): Int {
        return prefs.getInt(KEY_RECOVERY_FAILED_ATTEMPTS, 0)
    }

    @Synchronized
    fun recordFailedRecoveryAttempt(): Long {
        val attempts = getFailedRecoveryAttempts() + 1
        prefs.edit().putInt(KEY_RECOVERY_FAILED_ATTEMPTS, attempts).commit()
        val delaySeconds: Long = when {
            attempts <= 3 -> 0L
            attempts == 4 -> 30L
            attempts == 5 -> 300L       // 5 minutes
            attempts == 6 -> 1800L      // 30 minutes
            else -> 86400L             // 24 hours (7+ attempts)
        }
        if (delaySeconds > 0) {
            val lockoutUntil = System.currentTimeMillis() + (delaySeconds * 1000)
            prefs.edit().putLong(KEY_RECOVERY_LOCKOUT_UNTIL, lockoutUntil).commit()
        }
        return delaySeconds
    }

    @Synchronized
    fun resetFailedRecoveryAttempts() {
        prefs.edit()
            .remove(KEY_RECOVERY_FAILED_ATTEMPTS)
            .remove(KEY_RECOVERY_LOCKOUT_UNTIL)
            .commit()
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
        private const val KEY_LAST_INBOUND_SMS_SEQ = "last_inbound_sms_seq"
        private const val KEY_IS_STOLEN_MODE = "is_stolen_mode"
        private const val KEY_PAIRING_ID = "pairing_id"
        private const val KEY_OFFLINE_RECOVERY_CODE = "offline_recovery_code"
        private const val KEY_RECOVERY_FAILED_ATTEMPTS = "recovery_failed_attempts"
        private const val KEY_RECOVERY_LOCKOUT_UNTIL = "recovery_lockout_until"
    }
}
