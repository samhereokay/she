package com.antitheft.guardian.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.antitheft.guardian.R
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.db.GuardianDatabase
import com.antitheft.guardian.location.LocationCaptureManager
import com.antitheft.guardian.location.TrackingForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class GuardianMainActivity : AppCompatActivity() {

    private lateinit var storageHelper: DirectBootStorageHelper
    private lateinit var captureManager: LocationCaptureManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineLocationGranted) {
            startTrackingService()
            requestBackgroundLocation()
        } else {
            Toast.makeText(this, "Location permission is required for anti-theft tracking", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        storageHelper = DirectBootStorageHelper(this)
        captureManager = LocationCaptureManager(this)

        setupViews()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardData()
    }

    private fun setupViews() {
        findViewById<TextView>(R.id.tvDeviceId).text = "Device ID: ${storageHelper.getDeviceId()}"

        findViewById<Button>(R.id.btnCaptureNow).setOnClickListener {
            lifecycleScope.launch {
                val loc = captureManager.captureCurrentLocation()
                if (loc != null) {
                    Toast.makeText(this@GuardianMainActivity, "Fix acquired: ${loc.latitude}, ${loc.longitude}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@GuardianMainActivity, "Acquiring location...", Toast.LENGTH_SHORT).show()
                }
                refreshDashboardData()
            }
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            captureManager.enqueueSyncWork()
            Toast.makeText(this, "Sync scheduled with backend", Toast.LENGTH_SHORT).show()
            refreshDashboardData()
        }
    }

    private fun refreshDashboardData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = GuardianDatabase.getInstance(this@GuardianMainActivity, storageHelper.getDbPassphrase())
            val latest = db.locationDao().getLatestLocation()
            val pendingCount = db.locationDao().getPendingCount()

            withContext(Dispatchers.Main) {
                val tvLastLocation = findViewById<TextView>(R.id.tvLastLocation)
                val tvQueueCount = findViewById<TextView>(R.id.tvQueueCount)

                if (latest != null) {
                    tvLastLocation.text = String.format(
                        Locale.US,
                        "Last Fix: %.5f, %.5f (acc: %.1fm)",
                        latest.latitude,
                        latest.longitude,
                        latest.accuracy
                    )
                } else {
                    tvLastLocation.text = "Last Fix: None"
                }

                tvQueueCount.text = "Offline Queue: $pendingCount pending"
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startTrackingService()
            requestBackgroundLocation()
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                // Background location must be requested separately on Android 10+
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
        }
    }

    private fun startTrackingService() {
        TrackingForegroundService.start(this)
    }
}
