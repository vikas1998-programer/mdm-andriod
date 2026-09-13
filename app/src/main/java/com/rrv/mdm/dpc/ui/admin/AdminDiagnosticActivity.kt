package com.rrv.mdm.dpc.ui.admin

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityAdminDiagnosticBinding

class AdminDiagnosticActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminDiagnosticBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as RrvMdmApplication

        val config = app.serverConfigProvider.getCurrentConfig()
        val env = config?.environment ?: "UNCONFIGURED"
        val configVer = config?.configurationVersion ?: 0
        val serverUrl = config?.apiBaseUrl ?: app.repository.serverUrl.takeIf { it.isNotBlank() } ?: "Not configured"
        val mqttUri = config?.mqtt?.serverUri ?: run {
            if (app.repository.mqttBrokerHost.isNotBlank()) "${app.repository.mqttBrokerHost}:${app.repository.mqttPort}" else "Not configured"
        }

        val logs = StringBuilder()
        logs.append("=== RRV DPC DIAGNOSTICS ===\n")
        logs.append("Package: ${packageName}\n")
        logs.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        logs.append("OS Version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        logs.append("Device Owner: ${app.policyManager.isDeviceOwner()}\n")
        logs.append("Admin Active: ${app.policyManager.isAdminActive()}\n")
        logs.append("Enrolled: ${app.repository.isEnrolled}\n")
        logs.append("Environment: $env (Config v$configVer)\n")
        logs.append("Server API Base: $serverUrl\n")
        logs.append("MQTT Broker: $mqttUri\n")
        logs.append("Active Policy: ${app.repository.getActivePolicy().name}\n")
        logs.append("Active Geofences: ${app.repository.getGeofences().size} zones\n\n")
        logs.append("--- RECENT AGENT LOGS ---\n")
        logs.append(com.rrv.mdm.dpc.util.RrvLog.getFormattedLogs())

        binding.tvDiagnosticLogs.text = logs.toString()

        binding.btnForceMqttSync.setOnClickListener {
            app.mqttManager.publishTelemetry(null, true)
            binding.tvDiagnosticLogs.text = logs.toString() + "\n" + com.rrv.mdm.dpc.util.RrvLog.getFormattedLogs()
            Toast.makeText(this, "📡 Telemetry published via MQTT topic!", Toast.LENGTH_SHORT).show()
        }

        binding.btnExitKioskMode.setOnClickListener {
            com.rrv.mdm.dpc.util.RrvLog.kiosk("Admin unlocked and exited Kiosk LockTask mode.")
            app.lockTaskController.stopKioskLock(this)
            Toast.makeText(this, "🔓 LockTask Mode Exited.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
