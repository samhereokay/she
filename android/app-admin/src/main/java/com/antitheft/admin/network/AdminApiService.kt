package com.antitheft.admin.network

import com.antitheft.admin.auth.AdminSessionManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

data class LoginRequest(val username: String, val password: String)
data class TokenResponse(val access_token: String, val token_type: String)

data class DeviceLocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val capture_time: String,
    val server_receipt_time: String,
    val sequence_number: Long
)

data class DeviceDto(
    val id: String,
    val device_alias: String,
    val public_key_pem: String,
    val trusted_phone_number: String,
    val status: String,
    val pairing_id: String? = null,
    val confirmation_code: String? = null,
    val calculated_status: String, // LIVE, DELAYED, UNAVAILABLE
    val last_location: DeviceLocationDto?
)

data class DeviceListDto(val devices: List<DeviceDto>)

data class TotpValidateRequest(val code: String)

data class PairingTokenRequest(val trusted_device_id: String? = null)
data class PairingTokenResponse(val pairing_token: String, val expires_in_seconds: Int)

data class ConfirmPairingRequest(val confirmation_code: String)
data class ConfirmPairingResponse(val device_id: String, val status: String, val pairing_id: String)

data class RegisterTrustedDeviceRequest(
    val device_name: String,
    val public_key_pem: String
)

data class TrustedDeviceResponse(
    val id: String,
    val device_name: String,
    val public_key_pem: String,
    val is_active: Boolean = true
)

data class RevokeTrustedDeviceResponse(
    val status: String,
    val trusted_device_id: String,
    val device_name: String? = null
)

data class CommandDispatchRequest(
    val command_id: String? = null,
    val pairing_id: String? = null,
    val trusted_device_id: String,
    val target_device_id: String,
    val command_type: String,
    val sequence_counter: Long,
    val expiry_epoch: Long,
    val parameters_json: String,
    val admin_signature: String
)

data class CommandDispatchResponse(
    val id: String,
    val pairing_id: String? = null,
    val command_type: String,
    val status: String,
    val sequence_counter: Long
)

data class VaultItemCreateDto(
    val item_type: String,
    val title: String,
    val encrypted_blob: String,
    val iv: String,
    val tag: String,
    val key_version: Long = 1
)

data class VaultItemUpdateDto(
    val title: String? = null,
    val item_type: String? = null,
    val encrypted_blob: String? = null,
    val iv: String? = null,
    val tag: String? = null,
    val key_version: Long? = null
)

data class VaultItemDto(
    val id: String,
    val user_id: String,
    val item_type: String,
    val title: String,
    val encrypted_blob: String,
    val iv: String,
    val tag: String,
    val key_version: Long,
    val created_at: String,
    val updated_at: String
)

data class VaultItemListDto(
    val items: List<VaultItemDto>,
    val total: Int
)

data class VaultPurgeResponse(
    val status: String,
    val deleted_count: Int
)

interface AdminApiService {
    @POST("auth/login/json")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @POST("auth/totp/validate")
    suspend fun validateTotp(@Body request: TotpValidateRequest): TokenResponse

    @POST("auth/pairing-token")
    suspend fun createPairingToken(@Body request: PairingTokenRequest): PairingTokenResponse

    @POST("api/v1/devices/trusted")
    suspend fun registerTrustedDevice(@Body request: RegisterTrustedDeviceRequest): TrustedDeviceResponse

    @POST("auth/trusted-devices/{id}/revoke")
    suspend fun revokeTrustedDevice(@Path("id") id: String): RevokeTrustedDeviceResponse

    @GET("api/v1/devices")
    suspend fun listDevices(): DeviceListDto

    @GET("api/v1/devices/pending-confirmations")
    suspend fun listPendingConfirmations(): List<DeviceDto>

    @POST("api/v1/devices/{id}/confirm-pairing")
    suspend fun confirmPairing(@Path("id") id: String, @Body request: ConfirmPairingRequest): ConfirmPairingResponse

    @GET("api/v1/devices/{id}")
    suspend fun getDevice(@Path("id") id: String): DeviceDto

    @GET("api/v1/devices/{id}/locations")
    suspend fun getDeviceLocations(@Path("id") id: String): List<DeviceLocationDto>

    @POST("api/v1/devices/commands/dispatch")
    suspend fun dispatchCommand(@Body request: CommandDispatchRequest): CommandDispatchResponse

    @POST("api/v1/vault/items")
    suspend fun createVaultItem(@Body request: VaultItemCreateDto): VaultItemDto

    @GET("api/v1/vault/items")
    suspend fun listVaultItems(): VaultItemListDto

    @GET("api/v1/vault/items/{id}")
    suspend fun getVaultItem(@Path("id") id: String): VaultItemDto

    @retrofit2.http.PUT("api/v1/vault/items/{id}")
    suspend fun updateVaultItem(@Path("id") id: String, @Body request: VaultItemUpdateDto): VaultItemDto

    @retrofit2.http.DELETE("api/v1/vault/items/{id}")
    suspend fun deleteVaultItem(@Path("id") id: String): Map<String, Any>

    @retrofit2.http.DELETE("api/v1/vault/purge")
    suspend fun purgeVault(): VaultPurgeResponse
}

object ApiClient {
    fun create(sessionManager: AdminSessionManager): AdminApiService {
        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val token = sessionManager.getToken()
            val requestBuilder = original.newBuilder()
            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
            chain.proceed(requestBuilder.build())
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val baseUrl = sessionManager.getServerUrl().trimEnd('/') + "/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AdminApiService::class.java)
    }
}
