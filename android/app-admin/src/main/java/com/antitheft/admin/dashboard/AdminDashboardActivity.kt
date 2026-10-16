package com.antitheft.admin.dashboard

import android.content.Intent
import android.graphics.Color
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

        handleIncomingCommandIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingCommandIntent(intent)
    }

    private fun handleIncomingCommandIntent(intent: Intent?) {
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
                Toast.makeText(this@AdminDashboardActivity, "Error loading devices: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCommandSelectionDialog(device: DeviceDto) {
        val commands = arrayOf("LOCK", "RING", "STOP_RING", "LOCATE")
        AlertDialog.Builder(this)
            .setTitle("Send Command to ${device.device_alias}")
            .setItems(commands) { _, which ->
                val selectedCmd = commands[which]
                promptForTotpAndDispatch(device.id, selectedCmd)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForTotpAndDispatch(targetDeviceId: String, commandType: String) {
        val input = EditText(this).apply {
            hint = "6-digit TOTP code (or leave empty if session is 2FA)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(32, 32, 32, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Confirm $commandType Command")
            .setMessage("Two-factor authentication is required to dispatch security recovery commands:")
            .setView(input)
            .setPositiveButton("Sign & Dispatch") { _, _ ->
                val totpCode = input.text.toString().trim()
                dispatchCommandOnline(targetDeviceId, commandType, totpCode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun dispatchCommandOnline(targetDeviceId: String, commandType: String, totpCode: String) {
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
                val paramsJson = "{}"
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
        private val btnSendCommand: Button = itemView.findViewById(R.id.btnSendCommand)

        fun bind(device: DeviceDto) {
            tvDeviceAlias.text = device.device_alias
            tvStatusBadge.text = device.calculated_status

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
            } else {
                tvCoordinates.text = "No location fix recorded"
                tvCaptureTime.text = "Capture: N/A"
                tvReceiptTime.text = "Server Receipt: N/A"
            }

            btnSendCommand.setOnClickListener {
                onSendCommand(device)
            }
        }
    }
}
