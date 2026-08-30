package com.rrv.mdm.dpc.ui.device

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityDeviceStatusBinding
import kotlinx.coroutines.launch

class DeviceStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceStatusBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val app = application as RrvMdmApplication

        lifecycleScope.launch {
            app.getDeviceStatusUseCase().collect { info ->
                binding.tvComplianceTitle.text = info.complianceTitle
                binding.tvComplianceSubtitle.text = info.complianceSubtitle

                binding.tvDeviceModel.text = "Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
                binding.tvOsVersion.text = "OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                binding.tvBatteryStatus.text = "Battery: ${info.batteryLevel}% ${if (info.isCharging) "(Charging)" else ""}"
                binding.tvNetworkStatus.text = "Network: ${info.networkType} ${if (!info.wifiSsid.isNullOrBlank()) "(${info.wifiSsid})" else ""}"
                binding.tvStorageStatus.text = "Storage: ${info.storageFreeGb} GB Free / ${info.storageTotalGb} GB Total"

                val isDO = app.deviceManager.isDeviceOwner()
                binding.tvDeviceOwnerStatus.text = if (isDO) "✓ Android Enterprise Device Owner: Active" else "⚠ Device Owner: Not provisioned"
            }
        }

        binding.btnForceSync.setOnClickListener {
            Toast.makeText(this, "Refreshing MDM state & telemetry...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                app.getDeviceStatusUseCase.refresh()
                app.mqttManager.publishTelemetry(null, true)
                Toast.makeText(this@DeviceStatusActivity, "✓ Device synchronized successfully.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
