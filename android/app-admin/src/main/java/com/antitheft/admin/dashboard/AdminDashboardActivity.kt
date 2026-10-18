package com.antitheft.admin.dashboard

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.antitheft.admin.R
import com.antitheft.admin.auth.AdminSessionManager
import com.antitheft.admin.crypto.AdminKeystoreManager
import com.antitheft.admin.network.ApiClient
import com.antitheft.admin.network.CommandDispatchRequest
import com.antitheft.admin.network.DeviceDto
import com.antitheft.admin.network.RegisterTrustedDeviceRequest
import com.antitheft.admin.network.TotpValidateRequest
import com.antitheft.admin.pairing.PairDeviceActivity
import com.antitheft.admin.vault.AdminVaultActivity
import com.antitheft.common.crypto.EcdsaSigner
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.Locale
import java.util.UUID

class AdminDashboardActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager
    private val adminKeystore = AdminKeystoreManager()
    private lateinit var rvDevices: RecyclerView
    private lateinit var deviceAdapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        sessionManager = AdminSessionManager(this)
        deviceAdapter = DeviceAdapter { device ->
            showCommandSelectionDialog(device)
        }

        rvDevices = findViewById(R.id.rvDevices)
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        findViewById<ExtendedFloatingActionButton>(R.id.fabPairDevice).setOnClickListener {
            startActivity(Intent(this, PairDeviceActivity::class.java))
        }

        findViewById<Button>(R.id.btnOpenVault).setOnClickListener {
            startActivity(Intent(this, AdminVaultActivity::class.java))
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            sessionManager.clearSession()
            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, com.antitheft.admin.auth.AdminLoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        handleIncomingCommandIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingCommandIntent(intent)
    }

    private fun handleIncomingCommandIntent(intent: Intent?) {
        if (!com.antitheft.admin.BuildConfig.DEBUG) return
        val cmd = intent?.getStringExtra("dispatch_cmd")
        val targetId = intent?.getStringExtra("target_device_id")
        val totp = intent?.getStringExtra("totp_code") ?: ""
        if (!cmd.isNullOrBlank() && !targetId.isNullOrBlank()) {
            dispatchCommandOnline(targetId, cmd, totp)
        }
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
    }

    private fun loadDevices() {
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionManager)
                val response = api.listDevices()
                response.devices.forEach { d ->
                    if (!d.pairing_id.isNullOrBlank()) {
                        sessionManager.saveDevicePairingId(d.id, d.pairing_id)
                    }
                }
                deviceAdapter.setDevices(response.devices)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val is401 = msg.contains("401", ignoreCase = true) ||
                        (e is retrofit2.HttpException && e.code() == 401)
                if (is401) {
                    sessionManager.clearSession()
                    Toast.makeText(this@AdminDashboardActivity, "Session expired or owner account reset. Please sign in or register.", Toast.LENGTH_LONG).show()
                    val loginIntent = Intent(this@AdminDashboardActivity, com.antitheft.admin.auth.AdminLoginActivity::class.java)
                    loginIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(loginIntent)
                    finish()
                } else {
                    Toast.makeText(this@AdminDashboardActivity, "Error loading devices: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCommandSelectionDialog(device: DeviceDto) {
        val commands = arrayOf(
            "LOCATE",
            "RING",
            "STOP_RING",
            "LOCK",
            "TEST_MODE (Drill)",
            "STOLEN_MODE (Real OG)",
            "EXIT_KIOSK",
            "UNPAIR",
            "CLEAR_DEVICE_OWNER"
        )
        AlertDialog.Builder(this)
            .setTitle("Send Command to ${device.device_alias}")
            .setItems(commands) { _, which ->
                when (val selectedCmd = commands[which]) {
                    "CLEAR_DEVICE_OWNER" -> showDestructiveClearOwnerDialog(device.id)
                    "TEST_MODE (Drill)" -> promptForTotpAndDispatch(device.id, "STOLEN_MODE", "{\"test_mode\": true}")
                    "STOLEN_MODE (Real OG)" -> promptForTotpAndDispatch(device.id, "STOLEN_MODE", "{\"test_mode\": false}")
                    else -> promptForTotpAndDispatch(device.id, selectedCmd, "{}")
                }
            }
            .setNeutralButton("ℹ️ Command Guide") { _, _ ->
                showCommandGuideDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCommandGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("🛰️ SHE Recovery Command Guide")
            .setMessage(
                "• 🛰️ LOCATE:\n" +
                "  Pings the handset's hardware GPS to capture and transmit fresh satellite coordinates over WebSocket immediately.\n\n" +
                "• 🔊 RING:\n" +
                "  Triggers loud acoustic emergency alarm at maximum hardware volume to pinpoint the handset nearby (overrides Silent/DND modes).\n\n" +
                "• 🔕 STOP_RING:\n" +
                "  Silences the active acoustic siren alert.\n\n" +
                "• 🔒 LOCK:\n" +
                "  Immediately turns off the screen and forces enterprise PIN/kiosk lockdown.\n\n" +
                "• 🧪 TEST_MODE (Drill):\n" +
                "  Executes a safe security drill simulation on the handset without triggering real emergency panic escalation.\n\n" +
                "• 🚨 STOLEN_MODE (Real OG):\n" +
                "  Arms comprehensive theft lockdown: hides status bar, suppresses notifications, locks into kiosk, and initiates high-frequency GPS tracking beacons.\n\n" +
                "• 🚪 EXIT_KIOSK:\n" +
                "  Releases kiosk lock and restores normal Android home screen launcher.\n\n" +
                "• 🔗 UNPAIR:\n" +
                "  Revokes mutual pairing handshake between this companion and the target handset.\n\n" +
                "• ⚠️ CLEAR_DEVICE_OWNER:\n" +
                "  Deprovisions Device Owner enterprise administration status (strictly requires 6-digit TOTP confirmation)."
            )
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showDestructiveClearOwnerDialog(targetDeviceId: String) {
        val input = EditText(this).apply {
            hint = "6-digit TOTP code (strictly required)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(32, 32, 32, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("⚠️ DANGER: CLEAR DEVICE OWNER")
            .setMessage("This command permanently deprovisions Device Owner status on the target device, clears all theft restrictions (safe boot, factory reset protection), and revokes enterprise lockdown.\n\nEnter your 6-digit TOTP authenticator code to confirm this destructive action:")
            .setView(input)
            .setPositiveButton("Deprovision Device") { _, _ ->
                val totpCode = input.text.toString().trim()
                if (totpCode.isBlank() || (totpCode.length != 6 && totpCode.length !in 8..9)) {
                    Toast.makeText(this, "Valid 6-digit TOTP or emergency recovery code (XXXX-XXXX) required", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                dispatchCommandOnline(targetDeviceId, "CLEAR_DEVICE_OWNER", totpCode, "{}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForTotpAndDispatch(targetDeviceId: String, commandType: String, paramsJson: String = "{}") {
        val input = EditText(this).apply {
            hint = "6-digit TOTP or recovery code (XXXX-XXXX)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(32, 32, 32, 32)
        }

        val explanation = when (commandType) {
            "LOCATE" -> "Pings hardware GPS to capture and stream fresh satellite coordinates."
            "RING" -> "Sounds loud acoustic alarm at maximum volume (overrides silent mode)."
            "STOP_RING" -> "Silences the active emergency siren alarm."
            "LOCK" -> "Immediately turns off screen and locks the device into kiosk security."
            "STOLEN_MODE" -> "Arms the handset: locks kiosk, suppresses notifications, and initiates high-frequency GPS beacons."
            "EXIT_KIOSK" -> "Releases kiosk lock and restores default Android home screen."
            "UNPAIR" -> "Revokes device pairing session from this controller."
            else -> "Dispatches signed cryptographic recovery command."
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm $commandType Command")
            .setMessage("$explanation\n\nEnter your 6-digit TOTP code or emergency recovery code (XXXX-XXXX) to sign and dispatch:")
            .setView(input)
            .setPositiveButton("Sign & Dispatch") { _, _ ->
                val totpCode = input.text.toString().trim()
                dispatchCommandOnline(targetDeviceId, commandType, totpCode, paramsJson)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun dispatchCommandOnline(targetDeviceId: String, commandType: String, totpCode: String, customParamsJson: String = "{}") {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = ApiClient.create(sessionManager)

                // 1. If TOTP code provided, upgrade session to 2FA
                if (totpCode.isNotBlank()) {
                    val tokenResp = api.validateTotp(TotpValidateRequest(totpCode))
                    sessionManager.saveToken(tokenResp.access_token)
                }

                // 2. Ensure Admin device is registered on backend
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

                // 3. Construct canonical bytes and sign on-device using Keystore EC P-256 key
                val cmdId = UUID.randomUUID().toString()
                val seq = sessionManager.getNextCommandSequence()
                val expiryEpoch = (System.currentTimeMillis() / 1000) + 300
                val paramsJson = customParamsJson
                val pairingId = sessionManager.getDevicePairingId(targetDeviceId) ?: ""

                val canonicalBytes = EcdsaSigner.createCanonicalCommandBytes(
                    pairingId = pairingId,
                    commandId = cmdId,
                    targetDeviceId = targetDeviceId,
                    commandType = commandType,
                    sequenceCounter = seq,
                    expiryEpoch = expiryEpoch,
                    parametersJson = paramsJson
                )

                val derSig = adminKeystore.signCommand(canonicalBytes)
                val sigB64 = Base64.getEncoder().encodeToString(derSig)

                // 4. Dispatch command to backend
                val dispatchReq = CommandDispatchRequest(
                    command_id = cmdId,
                    pairing_id = pairingId,
                    trusted_device_id = trustedId,
                    target_device_id = targetDeviceId,
                    command_type = commandType,
                    sequence_counter = seq,
                    expiry_epoch = expiryEpoch,
                    parameters_json = paramsJson,
                    admin_signature = sigB64
                )

                val result = api.dispatchCommand(dispatchReq)
                android.util.Log.i("AdminDashboard", "DISPATCHED_COMMAND ID:${result.id} TYPE:${result.command_type} STATUS:${result.status}")

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminDashboardActivity, "Dispatched $commandType (Status: ${result.status})", Toast.LENGTH_LONG).show()
                    loadDevices()
                }
            } catch (e: Exception) {
                android.util.Log.e("AdminDashboard", "Error dispatching command", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminDashboardActivity, "Dispatch failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

class DeviceAdapter(
    private val onSendCommand: (DeviceDto) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<DeviceDto>()

    fun setDevices(newList: List<DeviceDto>) {
        devices.clear()
        devices.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view, onSendCommand)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    class DeviceViewHolder(
        itemView: View,
        private val onSendCommand: (DeviceDto) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvDeviceAlias: TextView = itemView.findViewById(R.id.tvDeviceAlias)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tvStatusBadge)
        private val tvCoordinates: TextView = itemView.findViewById(R.id.tvCoordinates)
        private val tvCaptureTime: TextView = itemView.findViewById(R.id.tvCaptureTime)
        private val tvReceiptTime: TextView = itemView.findViewById(R.id.tvReceiptTime)
        private val btnLiveTracking: Button = itemView.findViewById(R.id.btnLiveTracking)
        private val btnViewOnMap: Button = itemView.findViewById(R.id.btnViewOnMap)
        private val btnLocationInfo: Button = itemView.findViewById(R.id.btnLocationInfo)
        private val btnSendCommand: Button = itemView.findViewById(R.id.btnSendCommand)

        fun bind(device: DeviceDto) {
            tvDeviceAlias.text = device.device_alias
            tvStatusBadge.text = device.calculated_status

            btnLiveTracking.setOnClickListener {
                val context = itemView.context
                val intent = Intent(context, com.antitheft.admin.tracking.LiveTrackingActivity::class.java).apply {
                    putExtra(com.antitheft.admin.tracking.LiveTrackingActivity.EXTRA_DEVICE_ID, device.id)
                    putExtra(com.antitheft.admin.tracking.LiveTrackingActivity.EXTRA_DEVICE_ALIAS, device.device_alias)
                    if (device.last_location != null) {
                        putExtra(com.antitheft.admin.tracking.LiveTrackingActivity.EXTRA_INITIAL_LAT, device.last_location.latitude)
                        putExtra(com.antitheft.admin.tracking.LiveTrackingActivity.EXTRA_INITIAL_LNG, device.last_location.longitude)
                        putExtra(com.antitheft.admin.tracking.LiveTrackingActivity.EXTRA_INITIAL_ACC, device.last_location.accuracy)
                    }
                }
                context.startActivity(intent)
            }

            when (device.calculated_status) {
                "LIVE" -> {
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                    tvStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                }
                "DELAYED" -> {
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#FFF3E0"))
                    tvStatusBadge.setTextColor(Color.parseColor("#E65100"))
                }
                else -> {
                    tvStatusBadge.setBackgroundColor(Color.parseColor("#FFEBEE"))
                    tvStatusBadge.setTextColor(Color.parseColor("#C62828"))
                }
            }

            val loc = device.last_location
            if (loc != null) {
                tvCoordinates.text = String.format(
                    Locale.US,
                    "%.5f, %.5f (±%.1fm)",
                    loc.latitude,
                    loc.longitude,
                    loc.accuracy
                )
                tvCaptureTime.text = "Capture: ${loc.capture_time}"
                tvReceiptTime.text = "Server Receipt: ${loc.server_receipt_time}"

                btnViewOnMap.isEnabled = true
                btnViewOnMap.setOnClickListener {
                    val context = itemView.context
                    try {
                        val label = Uri.encode(device.device_alias)
                        val geoUri = Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}($label)")
                        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
                        if (mapIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(mapIntent)
                        } else {
                            val webUri = Uri.parse("https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
                            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
                        }
                    } catch (e: Exception) {
                        val webUri = Uri.parse("https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
                        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
                    }
                }
            } else {
                tvCoordinates.text = "No location fix recorded"
                tvCaptureTime.text = "Capture: N/A"
                tvReceiptTime.text = "Server Receipt: N/A"

                btnViewOnMap.isEnabled = false
                btnViewOnMap.setOnClickListener {
                    Toast.makeText(itemView.context, "No location fix recorded yet for ${device.device_alias}", Toast.LENGTH_SHORT).show()
                }
            }

            btnLocationInfo.setOnClickListener {
                val context = itemView.context
                AlertDialog.Builder(context)
                    .setTitle("🛰️ Handset Telemetry & Location Guide")
                    .setMessage(
                        "• Real-Time GPS Fixes:\n" +
                        "  Guardian captures hardware GPS and cellular fixes, signing every coordinate packet with its hardware ECDSA key before sending it to your private server.\n\n" +
                        "• Direct Boot Resumption:\n" +
                        "  Even if a thief restarts the phone and leaves it locked at the PIN screen, tracking starts automatically in Device Encrypted Storage.\n\n" +
                        "• Margin of Accuracy (±meters):\n" +
                        "  Shows the GPS radius uncertainty. Values under ±20m indicate pinpoint satellite lock; larger values reflect cell tower approximation.\n\n" +
                        "• Timestamps:\n" +
                        "  - Capture Time: Moment satellites locked on the handset.\n" +
                        "  - Server Receipt: Moment your private server verified and stored the telemetry.\n\n" +
                        "• Zero-Telemetry Privacy:\n" +
                        "  Location data is stored strictly in your own database. It is never sold, shared, or accessible by third parties."
                    )
                    .setPositiveButton("Got It", null)
                    .show()
            }

            btnSendCommand.setOnClickListener {
                onSendCommand(device)
            }
        }
    }
}
