package com.antitheft.guardian.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.antitheft.guardian.crypto.KeystoreManager
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.db.GuardianDatabase
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class LocationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val storageHelper = DirectBootStorageHelper(appContext)
    private val db = GuardianDatabase.getInstance(appContext, storageHelper)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pendingLocations = db.locationDao().getPendingLocations()
        if (pendingLocations.isEmpty()) {
            Log.d(TAG, "No pending locations to sync.")
            return@withContext Result.success()
        }

        Log.i(TAG, "Attempting to sync ${pendingLocations.size} location fixes.")

        val deviceId = storageHelper.getDeviceId()
        val serverUrl = storageHelper.getServerUrl().trimEnd('/')
        val endpoint = "$serverUrl/api/v1/locations/batch"

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val eventList = pendingLocations.map { loc ->
            mapOf(
                "sequence_number" to loc.sequenceNumber,
                "latitude" to loc.latitude,
                "longitude" to loc.longitude,
                "accuracy" to loc.accuracy,
                "altitude" to loc.altitude,
                "velocity" to loc.velocity,
                "capture_time" to isoFormat.format(Date(loc.captureTimeUtc * 1000)),
                "signature" to loc.signatureDerBase64
            )
        }

        val requestPayload = mapOf(
            "device_id" to deviceId,
            "events" to eventList
        )

        val jsonBody = gson.toJson(requestPayload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Server returned error: ${response.code}")
                return@withContext Result.retry()
            }

            val responseBody = response.body?.string() ?: ""
            val jsonResponse = gson.fromJson(responseBody, Map::class.java)
            val ackList = jsonResponse["acknowledged_sequences"] as? List<*>

            val acknowledgedSeqs = ackList?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList()
            if (acknowledgedSeqs.isNotEmpty()) {
                db.locationDao().markAsSynced(acknowledgedSeqs)
                Log.i(TAG, "Successfully synced ${acknowledgedSeqs.size} location events.")
                
                // Prune synced records older than 14 days
                val fourteenDaysAgo = (System.currentTimeMillis() / 1000) - (14 * 86400)
                db.locationDao().pruneSyncedLocations(fourteenDaysAgo)
            }

            // Also transmit periodic signed heartbeat
            try {
                sendHeartbeat(applicationContext, httpClient, storageHelper, gson)
            } catch (e: Exception) {
                Log.d(TAG, "Heartbeat opportunistic dispatch: ${e.message}")
            }

            Result.success()
        } catch (e: Exception) {
            val pinFailure = generateSequence(e as Throwable?) { it.cause }.any { cause ->
                cause is java.security.cert.CertificateException &&
                (cause.message?.contains("Pin verification failed", ignoreCase = true) == true ||
                 cause.message?.contains("checkPins", ignoreCase = true) == true)
            }
            if (pinFailure) {
                Log.e(TAG, "SECURITY: Certificate pin verification failed during location sync — " +
                    "possible network interception. Retrying with exponential backoff.", e)
                Result.retry()
            } else {
                Log.e(TAG, "Network exception during sync", e)
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "LocationSyncWorker"

        val LITE_CAPABILITIES = listOf(
            "locate_sms",
            "live_gps",
            "siren",
            "ring",
            "battery_report",
            "remote_photo",
            "stolen_mode"
        )

        suspend fun sendHeartbeatNow(context: Context): Boolean = withContext(Dispatchers.IO) {
            try {
                val storageHelper = DirectBootStorageHelper(context)
                val httpClient = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val gson = Gson()
                sendHeartbeat(context, httpClient, storageHelper, gson)
            } catch (e: Exception) {
                Log.d(TAG, "sendHeartbeatNow exception: ${e.message}")
                false
            }
        }

        private fun sendHeartbeat(
            context: Context,
            httpClient: OkHttpClient,
            storageHelper: DirectBootStorageHelper,
            gson: Gson
        ): Boolean {
            val deviceId = storageHelper.getDeviceId()
            val serverUrl = storageHelper.getServerUrl().trimEnd('/')
            if (deviceId.isBlank() || serverUrl.isBlank()) return false

            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, batteryFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryLevel = if (level >= 0 && scale > 0) (level * 100) / scale else 100
            val bStatus = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = bStatus == BatteryManager.BATTERY_STATUS_CHARGING || bStatus == BatteryManager.BATTERY_STATUS_FULL

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            val connType = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }

            val nowTs = System.currentTimeMillis() / 1000
            val canonical = "$deviceId:$nowTs:$batteryLevel:$connType:False"
            val keystore = KeystoreManager()
            val sigBytes = keystore.signData(canonical.toByteArray(Charsets.UTF_8))
            val sigBase64 = java.util.Base64.getEncoder().encodeToString(sigBytes)

            val fineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val bgLocation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            val smsGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECEIVE_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val notifsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val batteryOptIgnored = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val secureLockScreen = keyguardManager?.isDeviceSecure ?: false

            val permHealth = mapOf(
                "fine_location" to fineLocation,
                "background_location" to bgLocation,
                "sms" to smsGranted,
                "notifications" to notifsEnabled,
                "battery_optimization_ignored" to batteryOptIgnored,
                "secure_lock_screen" to secureLockScreen
            )

            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val simOperator = tm?.simOperatorName?.takeIf { it.isNotBlank() }
                ?: tm?.networkOperatorName?.takeIf { it.isNotBlank() }

            val payload = mutableMapOf<String, Any?>(
                "battery_level" to batteryLevel,
                "is_charging" to isCharging,
                "connectivity_type" to connType,
                "sim_changed" to false,
                "capabilities" to LITE_CAPABILITIES,
                "permission_health" to permHealth,
                "timestamp" to nowTs,
                "signature" to sigBase64
            )
            if (!simOperator.isNullOrBlank()) {
                payload["sim_operator"] = simOperator
            }

            val req = Request.Builder()
                .url("$serverUrl/api/v1/devices/$deviceId/heartbeat")
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "Guardian heartbeat delivered successfully: battery=$batteryLevel%, conn=$connType")
                    return true
                } else {
                    Log.w(TAG, "Guardian heartbeat rejected with code: ${resp.code}")
                    return false
                }
            }
        }

        suspend fun syncPendingLocationsNow(context: Context): Boolean = withContext(Dispatchers.IO) {
            try {
                val storageHelper = DirectBootStorageHelper(context)
                val db = GuardianDatabase.getInstance(context, storageHelper)
                val pendingLocations = db.locationDao().getPendingLocations()

                val deviceId = storageHelper.getDeviceId()
                val serverUrl = storageHelper.getServerUrl().trimEnd('/')
                if (serverUrl.isBlank() || deviceId.isBlank()) return@withContext false

                val httpClient = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
                val gson = Gson()

                // Dispatch opportunistic heartbeat alongside location sync
                try {
                    sendHeartbeat(context, httpClient, storageHelper, gson)
                } catch (_: Exception) {}

                if (pendingLocations.isEmpty()) return@withContext true

                val endpoint = "$serverUrl/api/v1/locations/batch"
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                val eventList = pendingLocations.map { loc ->
                    mapOf(
                        "sequence_number" to loc.sequenceNumber,
                        "latitude" to loc.latitude,
                        "longitude" to loc.longitude,
                        "accuracy" to loc.accuracy,
                        "altitude" to loc.altitude,
                        "velocity" to loc.velocity,
                        "capture_time" to isoFormat.format(Date(loc.captureTimeUtc * 1000)),
                        "signature" to loc.signatureDerBase64
                    )
                }

                val jsonBody = gson.toJson(mapOf("device_id" to deviceId, "events" to eventList))
                val request = Request.Builder()
                    .url(endpoint)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val jsonResponse = gson.fromJson(body, Map::class.java)
                        val ackList = jsonResponse["acknowledged_sequences"] as? List<*>
                        val acknowledgedSeqs = ackList?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList()
                        if (acknowledgedSeqs.isNotEmpty()) {
                            db.locationDao().markAsSynced(acknowledgedSeqs)
                            Log.i(TAG, "Direct sync: uploaded ${acknowledgedSeqs.size} location fixes.")
                        }
                        true
                    } else {
                        Log.w(TAG, "Direct sync server returned ${response.code}")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Direct sync skipped or transient: ${e.message}")
                false
            }
        }
    }
}
