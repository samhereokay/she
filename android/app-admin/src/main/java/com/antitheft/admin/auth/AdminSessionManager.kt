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

    fun saveRefreshToken(refreshToken: String) {
        secureStorage.putEncryptedString(KEY_REFRESH_TOKEN, refreshToken)
    }

    fun getRefreshToken(): String? {
        return secureStorage.getDecryptedString(KEY_REFRESH_TOKEN)
    }

    fun clearSession() {
        secureStorage.putEncryptedString(KEY_TOKEN, null)
        secureStorage.putEncryptedString(KEY_REFRESH_TOKEN, null)
    }

    fun isLoggedIn(): Boolean {
        return !getToken().isNullOrBlank()
    }

    fun getServerUrl(): String {
        val defaultUrl = if (isEmulator()) "https://10.0.2.2" else "https://she-92h6.onrender.com"
        return secureStorage.getDecryptedString(KEY_SERVER_URL, defaultUrl) ?: defaultUrl
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

    @Synchronized
    fun updateCommandSequence(minSequence: Long) {
        val currentStr = secureStorage.getDecryptedString(KEY_COMMAND_SEQ, "0") ?: "0"
        val saved = currentStr.toLongOrNull() ?: 0L
        if (minSequence > saved) {
            secureStorage.putEncryptedString(KEY_COMMAND_SEQ, minSequence.toString())
        }
    }

    fun saveDevicePairingId(deviceId: String, pairingId: String) {
        secureStorage.putEncryptedString("pairing_id_$deviceId", pairingId)
    }

    fun getDevicePairingId(deviceId: String): String? {
        return secureStorage.getDecryptedString("pairing_id_$deviceId")
    }

    fun saveDevicePairingSecret(deviceId: String, secretHex: String) {
        secureStorage.putEncryptedString("pairing_secret_$deviceId", secretHex)
    }

    fun getDevicePairingSecret(deviceId: String): String? {
        return secureStorage.getDecryptedString("pairing_secret_$deviceId")
    }

    fun getAdminPhoneNumber(): String {
        return secureStorage.getDecryptedString(KEY_ADMIN_PHONE, "") ?: ""
    }

    fun setAdminPhoneNumber(phone: String) {
        val normalized = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeToE164(phone)
        secureStorage.putEncryptedString(KEY_ADMIN_PHONE, normalized)
    }

    fun isEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT
        val model = android.os.Build.MODEL
        val hw = android.os.Build.HARDWARE
        return (fp.startsWith("generic") || fp.startsWith("unknown")
                || model.contains("google_sdk") || model.contains("Emulator")
                || model.contains("Android SDK built for x86")
                || hw.contains("goldfish") || hw.contains("ranchu"))
    }

    fun getCartoApiKey(): String {
        return secureStorage.getDecryptedString(KEY_CARTO_API_KEY, "") ?: ""
    }

    fun setCartoApiKey(apiKey: String) {
        secureStorage.putEncryptedString(KEY_CARTO_API_KEY, apiKey.trim())
    }

    fun getBasemapProvider(): String {
        return secureStorage.getDecryptedString(KEY_BASEMAP_PROVIDER, "osm_dark") ?: "osm_dark"
    }

    fun setBasemapProvider(provider: String) {
        secureStorage.putEncryptedString(KEY_BASEMAP_PROVIDER, provider.trim())
    }

    fun isBiometricEnabled(): Boolean {
        val str = secureStorage.getDecryptedString(KEY_BIOMETRIC_ENABLED, "true") ?: "true"
        return str.toBoolean()
    }

    fun setBiometricEnabled(enabled: Boolean) {
        secureStorage.putEncryptedString(KEY_BIOMETRIC_ENABLED, enabled.toString())
    }

    companion object {
        private const val KEY_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TRUSTED_DEVICE_ID = "trusted_device_id"
        private const val KEY_COMMAND_SEQ = "command_sequence_counter"
        private const val KEY_ADMIN_PHONE = "admin_phone_number"
        private const val KEY_CARTO_API_KEY = "carto_api_key"
        private const val KEY_BASEMAP_PROVIDER = "basemap_provider"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_app_lock_enabled"
    }
}
