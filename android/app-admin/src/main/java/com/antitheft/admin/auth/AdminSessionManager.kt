package com.antitheft.admin.auth

import android.content.Context
import com.antitheft.common.crypto.KeystoreAesGcmStorage

/**
 * Session manager backed by hardware-backed Keystore AES-256-GCM storage.
 * Completely replaces deprecated EncryptedSharedPreferences library.
 */
class AdminSessionManager(context: Context) {

    private val secureStorage = KeystoreAesGcmStorage(
        context = context,
        prefsName = "admin_secure_storage",
        keyAlias = "admin_session_aes_key"
    )

    fun saveToken(token: String) {
        secureStorage.putEncryptedString(KEY_TOKEN, token)
    }

    fun getToken(): String? {
        return secureStorage.getDecryptedString(KEY_TOKEN)
    }

    fun clearSession() {
        secureStorage.putEncryptedString(KEY_TOKEN, null)
    }

    fun isLoggedIn(): Boolean {
        return !getToken().isNullOrBlank()
    }

    fun getServerUrl(): String {
        return secureStorage.getDecryptedString(KEY_SERVER_URL, "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000"
    }

    fun setServerUrl(url: String) {
        secureStorage.putEncryptedString(KEY_SERVER_URL, url)
    }

    fun getTrustedDeviceId(): String? {
        return secureStorage.getDecryptedString(KEY_TRUSTED_DEVICE_ID)
    }

    fun saveTrustedDeviceId(id: String) {
        secureStorage.putEncryptedString(KEY_TRUSTED_DEVICE_ID, id)
    }

    @Synchronized
    fun getNextCommandSequence(): Long {
        val currentStr = secureStorage.getDecryptedString(KEY_COMMAND_SEQ, "0") ?: "0"
        val saved = currentStr.toLongOrNull() ?: 0L
        val nowEpochSec = System.currentTimeMillis() / 1000L
        val next = AdminSequenceCalculator.computeNextSequence(saved, nowEpochSec)
        secureStorage.putEncryptedString(KEY_COMMAND_SEQ, next.toString())
        return next
    }

    fun saveDevicePairingId(deviceId: String, pairingId: String) {
        secureStorage.putEncryptedString("pairing_id_$deviceId", pairingId)
    }

    fun getDevicePairingId(deviceId: String): String? {
        return secureStorage.getDecryptedString("pairing_id_$deviceId")
    }

    fun getAdminPhoneNumber(): String {
        return secureStorage.getDecryptedString(KEY_ADMIN_PHONE, "+15550199") ?: "+15550199"
    }

    fun setAdminPhoneNumber(phone: String) {
        secureStorage.putEncryptedString(KEY_ADMIN_PHONE, phone)
    }

    companion object {
        private const val KEY_TOKEN = "access_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TRUSTED_DEVICE_ID = "trusted_device_id"
        private const val KEY_COMMAND_SEQ = "command_sequence_counter"
        private const val KEY_ADMIN_PHONE = "admin_phone_number"
    }
}
