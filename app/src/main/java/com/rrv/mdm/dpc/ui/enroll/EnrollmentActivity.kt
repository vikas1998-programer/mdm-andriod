package com.rrv.mdm.dpc.ui.enroll

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityEnrollmentBinding
import com.rrv.mdm.dpc.receiver.RrvDeviceAdminReceiver
import com.rrv.mdm.dpc.ui.kiosk.KioskLauncherActivity

class EnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEnrollmentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as RrvMdmApplication

        val passedUrl = intent.getStringExtra("EXTRA_SERVER_URL")
        val passedToken = intent.getStringExtra("EXTRA_ENROLLMENT_TOKEN")

        val cachedUrl = app.serverConfigProvider.getBootstrapServerUrl() 
            ?: app.serverConfigProvider.getApiBaseUrl() 
            ?: app.repository.serverUrl.takeIf { it.isNotBlank() }
            ?: "https://yang-neighbors-affair-disks.trycloudflare.com"
        val cachedToken = app.serverConfigProvider.getBootstrapEnrollmentToken() 
            ?: app.repository.enrollmentToken.takeIf { it.isNotBlank() }
            ?: "RRV-DEMO-2026"

        binding.etServerUrl.setText(passedUrl ?: cachedUrl)
        binding.etEnrollToken.setText(passedToken ?: cachedToken)

        binding.btnEnrollSubmit.setOnClickListener {
            val serverUrl = binding.etServerUrl.text.toString().trim()
            val token = binding.etEnrollToken.text.toString().trim()

            if (serverUrl.isBlank() || token.isBlank()) {
                Toast.makeText(this, "Please enter both Server URL and Token.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.tvEnrollStatus.text = "🔄 Contacting Enterprise Enrollment Gateway..."
            binding.btnEnrollSubmit.isEnabled = false

            app.apiClient.enrollDevice(serverUrl, token) { success, message ->
                runOnUiThread {
                    binding.btnEnrollSubmit.isEnabled = true
                    if (success) {
                        Toast.makeText(this, "🎉 Enrollment Complete!", Toast.LENGTH_LONG).show()

                        // Connect Real-Time MQTT Command Tunnel
                        app.mqttManager.connect()

                        // Start persistent foreground service (policy watchdog + MQTT guardian)
                        com.rrv.mdm.dpc.service.MdmPersistentService.start(this)

                        // Apply baseline security restrictions and default home launcher immediately if Device Owner
                        if (app.deviceManager.isDeviceOwner()) {
                            app.deviceManager.setAsDefaultHomeLauncher()
                            app.policyManager.enforceBaselineSecurity()
                            val policy = app.repository.getActivePolicy()
                            app.deviceManager.applyPolicy(policy)
                        }

                        val homeIntent = Intent(this, com.rrv.mdm.dpc.ui.home.RrvMdmHomeActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(homeIntent)
                        finish()
                    } else {
                        binding.tvEnrollStatus.text = "✕ Enrollment failed: $message"
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
