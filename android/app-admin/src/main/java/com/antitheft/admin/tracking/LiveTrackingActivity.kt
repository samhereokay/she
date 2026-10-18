package com.antitheft.admin.tracking

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antitheft.admin.R
import com.antitheft.admin.auth.AdminSessionManager
import com.antitheft.admin.crypto.AdminKeystoreManager
import com.antitheft.admin.network.ApiClient
import com.antitheft.admin.network.CommandDispatchRequest
import com.antitheft.admin.network.RegisterTrustedDeviceRequest
import com.antitheft.admin.network.TotpValidateRequest
import com.antitheft.common.crypto.EcdsaSigner
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class LiveTrackingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_DEVICE_ALIAS = "extra_device_alias"
        const val EXTRA_INITIAL_LAT = "extra_initial_lat"
        const val EXTRA_INITIAL_LNG = "extra_initial_lng"
        const val EXTRA_INITIAL_ACC = "extra_initial_acc"
    }

    private lateinit var sessionManager: AdminSessionManager
    private val adminKeystore = AdminKeystoreManager()

    private lateinit var webViewMap: WebView
    private lateinit var tvDeviceTitle: TextView
    private lateinit var tvLiveStatus: TextView
    private lateinit var tvHudCoordinates: TextView
    private lateinit var tvHudAccuracy: TextView
    private lateinit var tvHudSpeed: TextView
    private lateinit var tvHudSequence: TextView
    private lateinit var tvHudSignalTime: TextView
    private lateinit var switchFollowTarget: SwitchMaterial
    private lateinit var btnCenterTarget: Button
    private lateinit var btnSimulateDrill: Button
    private lateinit var btnPingLocate: Button
    private lateinit var btnRingSiren: Button
    private lateinit var btnSearchOnMap: Button
    private lateinit var btnCommandInfo: Button

    private var deviceId: String = ""
    private var deviceAlias: String = ""
    private var isMapLoaded = false
    private var currentFixLat: Double? = null
    private var currentFixLng: Double? = null

    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var reconnectJob: Job? = null
    private var tickerJob: Job? = null
    private var simulationJob: Job? = null
    private var isSimulating = false
    private var lastFixEpochMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tracking)

        sessionManager = AdminSessionManager(this)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
        deviceAlias = intent.getStringExtra(EXTRA_DEVICE_ALIAS) ?: "Guardian Handset"

        initViews()
        setupWebView()
        startTickerLoop()
        connectWebSocket()
    }

    private fun initViews() {
        webViewMap = findViewById(R.id.webViewMap)
        tvDeviceTitle = findViewById(R.id.tvDeviceTitle)
        tvLiveStatus = findViewById(R.id.tvLiveStatus)
        tvHudCoordinates = findViewById(R.id.tvHudCoordinates)
        tvHudAccuracy = findViewById(R.id.tvHudAccuracy)
        tvHudSpeed = findViewById(R.id.tvHudSpeed)
        tvHudSequence = findViewById(R.id.tvHudSequence)
        tvHudSignalTime = findViewById(R.id.tvHudSignalTime)
        switchFollowTarget = findViewById(R.id.switchFollowTarget)
        btnCenterTarget = findViewById(R.id.btnCenterTarget)
        btnSimulateDrill = findViewById(R.id.btnSimulateDrill)
        btnPingLocate = findViewById(R.id.btnPingLocate)
        btnRingSiren = findViewById(R.id.btnRingSiren)
        btnSearchOnMap = findViewById(R.id.btnSearchOnMap)
        btnCommandInfo = findViewById(R.id.btnCommandInfo)

        tvDeviceTitle.text = deviceAlias
        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }

        switchFollowTarget.setOnCheckedChangeListener { _, isChecked ->
            if (isMapLoaded) {
                webViewMap.evaluateJavascript("window.setFollow($isChecked);", null)
            }
        }

        btnCenterTarget.setOnClickListener {
            if (isMapLoaded) {
                webViewMap.evaluateJavascript("window.centerOnTarget();", null)
            }
        }

        btnSimulateDrill.setOnClickListener {
            toggleDemoSimulation()
        }

        btnPingLocate.setOnClickListener {
            promptTotpAndDispatch("LOCATE", "{}")
        }

        btnRingSiren.setOnClickListener {
            promptTotpAndDispatch("RING", "{}")
        }

        btnSearchOnMap.setOnClickListener {
            val lat = currentFixLat
            val lng = currentFixLng
            if (lat != null && lng != null) {
                try {
                    val label = android.net.Uri.encode(deviceAlias)
                    val geoUri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
                    val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, geoUri)
                    if (mapIntent.resolveActivity(packageManager) != null) {
                        startActivity(mapIntent)
                    } else {
                        val webUri = android.net.Uri.parse("https://maps.google.com/?q=$lat,$lng")
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, webUri))
                    }
                } catch (e: Exception) {
                    val webUri = android.net.Uri.parse("https://maps.google.com/?q=$lat,$lng")
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, webUri))
                }
            } else {
                Toast.makeText(this, "Awaiting first satellite fix from handset...", Toast.LENGTH_SHORT).show()
            }
        }

        btnCommandInfo.setOnClickListener {
            showCommandInfoDialog()
        }

        // Show initial coordinates if passed
        if (intent.hasExtra(EXTRA_INITIAL_LAT) && intent.hasExtra(EXTRA_INITIAL_LNG)) {
            val initialLat = intent.getDoubleExtra(EXTRA_INITIAL_LAT, 0.0)
            val initialLng = intent.getDoubleExtra(EXTRA_INITIAL_LNG, 0.0)
            val initialAcc = intent.getFloatExtra(EXTRA_INITIAL_ACC, 10.0f)
            updateHudUI(initialLat, initialLng, initialAcc, 0.0, 1L, "Cached Last Known Fix")
        }
    }

    private fun setupWebView() {
        webViewMap.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webViewMap.webChromeClient = WebChromeClient()
        webViewMap.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isMapLoaded = true
                if (intent.hasExtra(EXTRA_INITIAL_LAT) && intent.hasExtra(EXTRA_INITIAL_LNG)) {
                    val lat = intent.getDoubleExtra(EXTRA_INITIAL_LAT, 28.61393)
                    val lng = intent.getDoubleExtra(EXTRA_INITIAL_LNG, 77.22950)
                    val acc = intent.getFloatExtra(EXTRA_INITIAL_ACC, 10.0f)
                    webViewMap.evaluateJavascript(
                        String.format(Locale.US, "window.updateTargetFix(%.6f, %.6f, %.1f, 0.0, 1);", lat, lng, acc),
                        null
                    )
                }
            }
        }

        val leafletHtml = buildLeafletHtml()
        webViewMap.loadDataWithBaseURL("https://leafletjs.com", leafletHtml, "text/html", "UTF-8", null)
    }

    private fun buildLeafletHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body, html, #map { width: 100%; height: 100%; background: #000000; }
                .radar-pin-wrap {
                    position: relative;
                    width: 36px;
                    height: 36px;
                }
                .radar-pin-dot {
                    position: absolute;
                    top: 11px;
                    left: 11px;
                    width: 14px;
                    height: 14px;
                    background: #10b981;
                    border: 2px solid #ffffff;
                    border-radius: 50%;
                    box-shadow: 0 0 12px #10b981;
                    z-index: 2;
                }
                .radar-pin-pulse {
                    position: absolute;
                    top: 0;
                    left: 0;
                    width: 36px;
                    height: 36px;
                    border-radius: 50%;
                    border: 2px solid #00d2ff;
                    background: rgba(0, 210, 255, 0.35);
                    animation: radar-expand-pin 1.8s infinite ease-out;
                    z-index: 1;
                }
                @keyframes radar-expand-pin {
                    0% { transform: scale(0.3); opacity: 1; }
                    100% { transform: scale(2.2); opacity: 0; }
                }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', { zoomControl: false }).setView([28.61393, 77.22950], 15);
                L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
                    maxZoom: 19,
                    attribution: ''
                }).addTo(map);

                var radarIcon = L.divIcon({
                    className: 'radar-custom-icon',
                    html: '<div class="radar-pin-wrap"><div class="radar-pin-pulse"></div><div class="radar-pin-dot"></div></div>',
                    iconSize: [36, 36],
                    iconAnchor: [18, 18]
                });

                var targetMarker = null;
                var accuracyCircle = null;
                var trailPolyline = L.polyline([], {
                    color: '#00d2ff',
                    weight: 4,
                    opacity: 0.85,
                    lineCap: 'round',
                    lineJoin: 'round'
                }).addTo(map);

                var followTarget = true;

                window.setFollow = function(follow) {
                    followTarget = follow;
                };

                window.centerOnTarget = function() {
                    if (targetMarker) {
                        map.panTo(targetMarker.getLatLng(), { animate: true, duration: 0.6 });
                    }
                };

                window.loadTrail = function(pointsJsonStr) {
                    try {
                        var points = JSON.parse(pointsJsonStr);
                        if (!Array.isArray(points) || points.length === 0) return;
                        var latLngs = [];
                        for (var i = 0; i < points.length; i++) {
                            var p = points[i];
                            latLngs.push([p.latitude, p.longitude]);
                        }
                        trailPolyline.setLatLngs(latLngs);
                        var last = points[points.length - 1];
                        window.updateTargetFix(last.latitude, last.longitude, last.accuracy || 10, last.velocity || 0, last.sequence_number || 0);
                        if (latLngs.length > 1) {
                            map.fitBounds(trailPolyline.getBounds(), { padding: [40, 40] });
                        }
                    } catch(e) {
                        console.error("loadTrail error:", e);
                    }
                };

                window.updateTargetFix = function(lat, lng, acc, speed, seq) {
                    var latLng = [lat, lng];
                    if (!targetMarker) {
                        targetMarker = L.marker(latLng, { icon: radarIcon }).addTo(map);
                        accuracyCircle = L.circle(latLng, {
                            radius: acc || 10,
                            color: '#00d2ff',
                            weight: 1.5,
                            fillOpacity: 0.12,
                            fillColor: '#00d2ff'
                        }).addTo(map);
                        map.setView(latLng, 16);
                    } else {
                        targetMarker.setLatLng(latLng);
                        if (accuracyCircle) {
                            accuracyCircle.setLatLng(latLng);
                            accuracyCircle.setRadius(acc || 10);
                        }
                    }
                    trailPolyline.addLatLng(latLng);
                    if (followTarget) {
                        map.panTo(latLng, { animate: true, duration: 0.7 });
                    }
                };
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun connectWebSocket() {
        if (deviceId.isBlank()) {
            updateStatusBadge("NO DEVICE ID", "#EF4444", "#33EF4444")
            return
        }

        val serverUrl = sessionManager.getServerUrl().trimEnd('/')
        val token = sessionManager.getToken() ?: ""

        val wsBase = serverUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")

        val wsUrl = "$wsBase/api/v1/ws/devices/$deviceId?token=$token"
        Log.i("LiveTracking", "Connecting WebSocket to: $wsUrl")
        updateStatusBadge("CONNECTING...", "#F59E0B", "#33F59E0B")

        val request = Request.Builder().url(wsUrl).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("LiveTracking", "WebSocket Connected successfully")
                mainHandler.post {
                    updateStatusBadge("LIVE (WS)", "#10B981", "#3310B981")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                mainHandler.post {
                    processIncomingFix(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("LiveTracking", "WebSocket Failure: ${t.message}")
                mainHandler.post {
                    updateStatusBadge("RECONNECTING...", "#F59E0B", "#33F59E0B")
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("LiveTracking", "WebSocket Closed code: $code, reason: $reason")
                mainHandler.post {
                    updateStatusBadge("OFFLINE", "#EF4444", "#33EF4444")
                    if (!isFinishing && !isDestroyed) {
                        scheduleReconnect()
                    }
                }
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(3500)
            if (!isFinishing && !isDestroyed) {
                connectWebSocket()
            }
        }
    }

    private fun processIncomingFix(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val type = json.optString("type")

            when (type) {
                "INITIAL_STATE" -> {
                    val trail = json.optJSONArray("trail") ?: JSONArray()
                    val latest = json.optJSONObject("latest_location")

                    if (isMapLoaded) {
                        val escapedJson = trail.toString().replace("'", "\\'")
                        webViewMap.evaluateJavascript("window.loadTrail('$escapedJson');", null)
                    }

                    if (latest != null) {
                        val lat = latest.optDouble("latitude")
                        val lng = latest.optDouble("longitude")
                        val acc = latest.optDouble("accuracy", 10.0).toFloat()
                        val speed = latest.optDouble("velocity", 0.0)
                        val seq = latest.optLong("sequence_number", 1L)
                        val capTime = latest.optString("capture_time", "")
                        updateHudUI(lat, lng, acc, speed, seq, capTime)
                    }
                }
                "LOCATION_UPDATE" -> {
                    val lat = json.optDouble("latitude")
                    val lng = json.optDouble("longitude")
                    val acc = json.optDouble("accuracy", 10.0).toFloat()
                    val speed = json.optDouble("velocity", 0.0)
                    val seq = json.optLong("sequence_number", 1L)
                    val capTime = json.optString("capture_time", "")

                    if (isMapLoaded) {
                        webViewMap.evaluateJavascript(
                            String.format(
                                Locale.US,
                                "window.updateTargetFix(%.6f, %.6f, %.1f, %.1f, %d);",
                                lat, lng, acc, speed, seq
                            ),
                            null
                        )
                    }
                    updateHudUI(lat, lng, acc, speed, seq, capTime)
                }
            }
        } catch (e: Exception) {
            Log.e("LiveTracking", "Error parsing incoming fix", e)
        }
    }

    private fun updateHudUI(
        lat: Double,
        lng: Double,
        acc: Float,
        speed: Double,
        seq: Long,
        capTime: String
    ) {
        currentFixLat = lat
        currentFixLng = lng
        lastFixEpochMs = System.currentTimeMillis()
        tvHudCoordinates.text = String.format(Locale.US, "%.5f, %.5f", lat, lng)
        tvHudAccuracy.text = String.format(Locale.US, "±%.1fm", acc)

        val speedKmh = speed * 3.6
        tvHudSpeed.text = if (speedKmh > 0.5) {
            String.format(Locale.US, "Speed: %.1f km/h", speedKmh)
        } else {
            "Speed: 0.0 km/h (Stationary)"
        }

        tvHudSequence.text = "Fix #$seq"
        tvHudSignalTime.text = if (capTime.isNotBlank()) "Sat: ${capTime.takeLast(12)}" else "Signal: Active"
    }

    private fun showCommandInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("SHE Recovery Command Guide")
            .setMessage(
                "• LOCATE:\n" +
                "  Pings the handset's hardware GPS to capture and transmit fresh satellite coordinates over WebSocket immediately.\n\n" +
                "• RING:\n" +
                "  Triggers loud acoustic emergency alarm at maximum hardware volume to pinpoint the handset nearby (overrides Silent/DND modes).\n\n" +
                "• STOP_RING:\n" +
                "  Silences the active acoustic siren alert.\n\n" +
                "• LOCK:\n" +
                "  Immediately turns off the screen and forces enterprise PIN/kiosk lockdown.\n\n" +
                "• TEST_MODE (Drill):\n" +
                "  Executes a safe security drill simulation on the handset without triggering real emergency panic escalation.\n\n" +
                "• STOLEN_MODE (Real OG):\n" +
                "  Arms comprehensive theft lockdown: hides status bar, suppresses notifications, locks into kiosk, and initiates high-frequency GPS tracking beacons.\n\n" +
                "• EXIT_KIOSK:\n" +
                "  Releases kiosk lock and restores normal Android home screen launcher.\n\n" +
                "• UNPAIR:\n" +
                "  Revokes mutual pairing handshake between this companion and the target handset.\n\n" +
                "• CLEAR_DEVICE_OWNER:\n" +
                "  Deprovisions Device Owner enterprise administration status (strictly requires 6-digit TOTP confirmation)."
            )
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun updateStatusBadge(text: String, textColorHex: String, bgHex: String) {
        tvLiveStatus.text = text
        tvLiveStatus.setTextColor(Color.parseColor(textColorHex))
        tvLiveStatus.setBackgroundColor(Color.parseColor(bgHex))
    }

    private fun startTickerLoop() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                if (lastFixEpochMs > 0) {
                    val elapsedSec = (System.currentTimeMillis() - lastFixEpochMs) / 1000
                    tvHudSignalTime.text = if (elapsedSec < 3) {
                        "Updated: Just now"
                    } else {
                        "Updated: ${elapsedSec}s ago"
                    }
                }
            }
        }
    }

    private fun toggleDemoSimulation() {
        if (isSimulating) {
            simulationJob?.cancel()
            isSimulating = false
            btnSimulateDrill.text = "Demo Drill"
            Toast.makeText(this, "Simulation drill stopped", Toast.LENGTH_SHORT).show()
        } else {
            isSimulating = true
            btnSimulateDrill.text = "Stop Drill"
            Toast.makeText(this, "Live drill started (India Gate Benchmark)", Toast.LENGTH_SHORT).show()

            simulationJob = lifecycleScope.launch {
                var angle = 0.0
                var seq = 100L
                val centerLat = 28.61393
                val centerLng = 77.22950

                while (isActive && isSimulating) {
                    val r = 0.0018 + (0.0004 * kotlin.math.sin(angle * 2))
                    val curLat = centerLat + (r * kotlin.math.cos(angle))
                    val curLng = centerLng + (r * kotlin.math.sin(angle))
                    val speed = 12.0 + (5.0 * kotlin.math.sin(angle))
                    val acc = 8.5f + (2.0f * kotlin.math.cos(angle)).toFloat()

                    withContext(Dispatchers.Main) {
                        if (isMapLoaded) {
                            webViewMap.evaluateJavascript(
                                String.format(
                                    Locale.US,
                                    "window.updateTargetFix(%.6f, %.6f, %.1f, %.1f, %d);",
                                    curLat, curLng, acc, speed / 3.6, seq
                                ),
                                null
                            )
                        }
                        updateHudUI(curLat, curLng, acc, speed / 3.6, seq, "Simulation Fix")
                    }

                    angle += 0.12
                    seq++
                    delay(2000)
                }
            }
        }
    }

    private fun promptTotpAndDispatch(commandType: String, paramsJson: String) {
        val input = EditText(this).apply {
            hint = "6-digit TOTP or recovery code (XXXX-XXXX)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(32, 32, 32, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm $commandType Command")
            .setMessage("Two-factor authentication is required to dispatch security recovery commands:")
            .setView(input)
            .setPositiveButton("Sign & Dispatch") { _, _ ->
                val totpCode = input.text.toString().trim()
                dispatchCommandOnline(commandType, totpCode, paramsJson)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dispatchCommandOnline(commandType: String, totpCode: String, customParamsJson: String = "{}") {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = ApiClient.create(sessionManager)

                // 1. Upgrade session if TOTP provided
                if (totpCode.isNotBlank()) {
                    val tokenResp = api.validateTotp(TotpValidateRequest(totpCode))
                    sessionManager.saveToken(tokenResp.access_token)
                }

                // 2. Ensure Admin device registered
                var trustedId = sessionManager.getTrustedDeviceId()
                if (trustedId.isNullOrBlank()) {
                    val adminPem = adminKeystore.getPublicKeyPem()
                    val regResp = api.registerTrustedDevice(
                        RegisterTrustedDeviceRequest(
                            device_name = "Admin Controller Phone",
                            public_key_pem = adminPem
                        )
                    )
                    trustedId = regResp.id
                    sessionManager.saveTrustedDeviceId(trustedId)
                }

                // 3. Construct canonical bytes and sign
                val cmdId = UUID.randomUUID().toString()
                val seq = sessionManager.getNextCommandSequence()
                val expiryEpoch = (System.currentTimeMillis() / 1000) + 300
                val pairingId = sessionManager.getDevicePairingId(deviceId) ?: ""

                val canonicalBytes = EcdsaSigner.createCanonicalCommandBytes(
                    pairingId = pairingId,
                    commandId = cmdId,
                    targetDeviceId = deviceId,
                    commandType = commandType,
                    sequenceCounter = seq,
                    expiryEpoch = expiryEpoch,
                    parametersJson = customParamsJson
                )

                val derSig = adminKeystore.signCommand(canonicalBytes)
                val sigB64 = Base64.getEncoder().encodeToString(derSig)

                // 4. Dispatch command
                val dispatchReq = CommandDispatchRequest(
                    command_id = cmdId,
                    pairing_id = pairingId,
                    trusted_device_id = trustedId,
                    target_device_id = deviceId,
                    command_type = commandType,
                    sequence_counter = seq,
                    expiry_epoch = expiryEpoch,
                    parameters_json = customParamsJson,
                    admin_signature = sigB64
                )

                val result = api.dispatchCommand(dispatchReq)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LiveTrackingActivity, "Dispatched $commandType (Status: ${result.status})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LiveTrackingActivity, "Command dispatch failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        tickerJob?.cancel()
        simulationJob?.cancel()
        try {
            webSocket?.close(1000, "Activity Destroyed")
        } catch (_: Exception) {}
        webViewMap.destroy()
    }
}
