package com.antitheft.admin.dashboard

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.antitheft.admin.R
import com.antitheft.admin.auth.AdminSessionManager
import com.antitheft.admin.network.ApiClient
import com.antitheft.admin.network.DeviceDto
import com.antitheft.admin.pairing.PairDeviceActivity
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.launch
import java.util.Locale

class AdminDashboardActivity : AppCompatActivity() {

    private lateinit var sessionManager: AdminSessionManager
    private lateinit var rvDevices: RecyclerView
    private val deviceAdapter = DeviceAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        sessionManager = AdminSessionManager(this)
        rvDevices = findViewById(R.id.rvDevices)
        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        findViewById<ExtendedFloatingActionButton>(R.id.fabPairDevice).setOnClickListener {
            startActivity(Intent(this, PairDeviceActivity::class.java))
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
                deviceAdapter.setDevices(response.devices)
            } catch (e: Exception) {
                Toast.makeText(this@AdminDashboardActivity, "Error loading devices: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<DeviceDto>()

    fun setDevices(newList: List<DeviceDto>) {
        devices.clear()
        devices.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDeviceAlias: TextView = itemView.findViewById(R.id.tvDeviceAlias)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tvStatusBadge)
        private val tvCoordinates: TextView = itemView.findViewById(R.id.tvCoordinates)
        private val tvCaptureTime: TextView = itemView.findViewById(R.id.tvCaptureTime)
        private val tvReceiptTime: TextView = itemView.findViewById(R.id.tvReceiptTime)

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
        }
    }
}
