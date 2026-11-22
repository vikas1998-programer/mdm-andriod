package com.rrv.mdm.dpc.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityAboutMdmBinding

class AboutMdmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutMdmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutMdmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val app = application as RrvMdmApplication
        val config = app.serverConfigProvider.getCurrentConfig()
        val serverUrl = config?.apiBaseUrl ?: app.repository.serverUrl.takeIf { it.isNotBlank() } ?: "Not configured"
        val mqttUri = config?.mqtt?.serverUri ?: run {
            if (app.repository.mqttBrokerHost.isNotBlank()) "${app.repository.mqttBrokerHost}:${app.repository.mqttPort}" else "Not configured"
        }
        val env = config?.environment ?: "UNCONFIGURED"
        val version = config?.configurationVersion ?: 0

        binding.tvAboutServer.text = "MDM Server: $serverUrl\nEnvironment: $env (Config v$version)\nMQTT Broker: $mqttUri"
        binding.tvAboutDeviceId.text = "Device ID: ${app.repository.deviceId.takeIf { it.isNotBlank() } ?: "Unenrolled"}"
        val isDO = app.deviceManager.isDeviceOwner()
        binding.tvAboutEnrollment.text = "Enrollment State: ${if (app.repository.isEnrolled) "ENROLLED" else "UNENROLLED"} (${if (isDO) "Device Owner" else "Profile"})"
    }
}
