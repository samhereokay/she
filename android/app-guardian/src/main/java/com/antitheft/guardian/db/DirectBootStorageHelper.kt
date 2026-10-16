package com.antitheft.guardian.db

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import java.security.SecureRandom
import java.util.UUID

/**
 * Manages device metadata and sequence numbers inside Direct Boot Device Protected Storage (DE).
 * Accessible before First Unlock.
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
        var secretHex = prefs.getString(KEY_PAIRING_SECRET, null)
        if (secretHex == null) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            secretHex = randomBytes.joinToString("") { "%02X".format(it) }
            prefs.edit().putString(KEY_PAIRING_SECRET, secretHex).apply()
        }
        return hexToByteArray(secretHex)
    }

    fun setPairingSecret(secretBytes: ByteArray) {
        val hex = secretBytes.joinToString("") { "%02X".format(it) }
        prefs.edit().putString(KEY_PAIRING_SECRET, hex).apply()
    }

    fun getDbPassphrase(): ByteArray {
        var passHex = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (passHex == null) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            passHex = randomBytes.joinToString("") { "%02X".format(it) }
            prefs.edit().putString(KEY_DB_PASSPHRASE, passHex).apply()
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

    companion object {
        private const val PREFS_NAME = "guardian_de_storage"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_ALIAS = "device_alias"
        private const val KEY_TRUSTED_PHONE = "trusted_phone"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SEQUENCE_COUNTER = "sequence_counter"
        private const val KEY_PAIRING_SECRET = "pairing_secret"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
    }
}
