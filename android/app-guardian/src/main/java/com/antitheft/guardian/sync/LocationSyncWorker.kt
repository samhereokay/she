package com.antitheft.guardian.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Network exception during sync", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LocationSyncWorker"

        suspend fun syncPendingLocationsNow(context: Context): Boolean = withContext(Dispatchers.IO) {
            try {
                val storageHelper = DirectBootStorageHelper(context)
                val db = GuardianDatabase.getInstance(context, storageHelper)
                val pendingLocations = db.locationDao().getPendingLocations()
                if (pendingLocations.isEmpty()) return@withContext true

                val deviceId = storageHelper.getDeviceId()
                val serverUrl = storageHelper.getServerUrl().trimEnd('/')
                if (serverUrl.isBlank() || deviceId.isBlank()) return@withContext false

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

                val httpClient = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
                val gson = Gson()
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
