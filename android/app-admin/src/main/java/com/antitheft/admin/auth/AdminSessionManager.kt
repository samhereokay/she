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

    companion object {
        private const val KEY_TOKEN = "access_token"
        private const val KEY_SERVER_URL = "server_url"
    }
}
