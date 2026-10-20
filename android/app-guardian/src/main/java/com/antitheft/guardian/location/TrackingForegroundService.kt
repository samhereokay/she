package com.antitheft.guardian.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antitheft.guardian.commands.CommandProcessor
import com.antitheft.guardian.commands.CommandValidationResult
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Foreground Service compliant with Android 14 (API 34) foregroundServiceType="location".
 * Executes visible periodic tracking and background recovery command polling.
 */
class TrackingForegroundService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationCaptureManager: LocationCaptureManager
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        locationCaptureManager = LocationCaptureManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        setupLocationUpdates()
        setupCommandPolling()
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        val storage = DirectBootStorageHelper(this)
        val isStolen = storage.isStolenMode()
        val intervalMs = if (isStolen) 15000L else 60000L
        val minIntervalMs = if (isStolen) 10000L else 30000L

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(minIntervalMs)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                serviceScope.launch {
                    locationCaptureManager.captureCurrentLocation()
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun setupCommandPolling() {
        val storageHelper = DirectBootStorageHelper(this)
        val commandProcessor = CommandProcessor(this, storageHelper)

        serviceScope.launch {
            while (isActive) {
                try {
                    // Flush pending location fixes to eliminate data delay
                    com.antitheft.guardian.sync.LocationSyncWorker.syncPendingLocationsNow(this@TrackingForegroundService)

                    val serverUrl = storageHelper.getServerUrl().trimEnd('/')
                    val deviceId = storageHelper.getDeviceId()
                    val endpoint = "$serverUrl/api/v1/devices/$deviceId/commands/pending"

                    val nowSec = System.currentTimeMillis() / 1000
                    val pollPayload = "POLL_COMMANDS|$deviceId|$nowSec".toByteArray(Charsets.UTF_8)
                    val sig = com.antitheft.guardian.crypto.KeystoreManager().signData(pollPayload)
                    val sigBase64 = java.util.Base64.getEncoder().encodeToString(sig)

                    val request = Request.Builder()
                        .url(endpoint)
                        .header("X-Device-Timestamp", nowSec.toString())
                        .header("X-Device-Signature", sigBase64)
                        .get()
                        .build()
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        if (body.isNotBlank() && body != "[]") {
                            val commands = gson.fromJson(body, Array<com.antitheft.guardian.commands.CommandDto>::class.java)
                            for (cmd in commands) {
                                val result = commandProcessor.processCommand(cmd)
                                val isSuccess = result is CommandValidationResult.Success
                                val ackStatus = if (isSuccess) "EXECUTED" else "REJECTED"
                                val ackReason = if (isSuccess) "OK" else result.javaClass.simpleName

                                val ackPayload = mapOf(
                                    "status" to ackStatus,
                                    "reason" to ackReason,
                                    "timestamp" to (System.currentTimeMillis() / 1000)
                                )
                                val ackReq = Request.Builder()
                                    .url("$serverUrl/api/v1/devices/$deviceId/commands/${cmd.id}/ack")
                                    .post(gson.toJson(ackPayload).toRequestBody("application/json".toMediaType()))
                                    .build()
                                httpClient.newCall(ackReq).execute().close()

                                if (isSuccess) {
                                    Log.i(TAG, "Command ${cmd.id} (${cmd.command_type}) successfully executed and acknowledged.")
                                } else {
                                    Log.w(TAG, "Command ${cmd.id} (${cmd.command_type}) rejected: $ackReason. Reported rejection to server.")
                                }
                            }
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    val pinFailure = generateSequence(e as Throwable?) { it.cause }.any { cause ->
                        cause is java.security.cert.CertificateException &&
                        (cause.message?.contains("Pin verification failed", ignoreCase = true) == true ||
                         cause.message?.contains("checkPins", ignoreCase = true) == true)
                    }
                    if (pinFailure) {
                        // Certificate pin verification failed. This is not a transient error.
                        // The connection may be actively intercepted. Log at ERROR level so it
                        // stands out in logcat and any log aggregation system.
                        Log.e(TAG, "SECURITY: Certificate pin verification failed during command polling — " +
                            "possible network interception. Backing off 60 s.", e)
                        delay(60_000L)
                    } else {
                        Log.d(TAG, "Command polling transient exception: ${e.message}")
                    }
                }

                val pollDelay = if (storageHelper.isStolenMode()) 5000L else 15000L
                delay(pollDelay)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Guardian Active Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent protection and recovery status"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val isStolen = DirectBootStorageHelper(this).isStolenMode()
        val title = if (isStolen) "EMERGENCY RECOVERY ACTIVE" else "Guardian Protection Active"
        val text = if (isStolen) "Emergency high-cadence tracking active." else "Autonomous anti-theft tracking is active."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(if (isStolen) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TrackingForeground"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "guardian_tracking_channel"

        fun start(context: Context) {
            val intent = Intent(context, TrackingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
