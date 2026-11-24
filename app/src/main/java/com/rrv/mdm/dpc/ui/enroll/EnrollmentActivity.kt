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

    private fun updateTargetHost() {
        val host = com.rrv.mdm.dpc.util.EnrollmentGuards.hostOf(binding.etServerUrl.text.toString())
        binding.tvEnrollTargetHost.text = if (host == null) "Enrolling with: (invalid server URL)" else "Enrolling with: $host"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Extras are untrusted and never used; just re-sync the fields with stored configuration.
        val app = application as RrvMdmApplication
        val storedUrl = app.serverConfigProvider.getBootstrapServerUrl()
            ?: app.serverConfigProvider.getApiBaseUrl()
            ?: app.repository.serverUrl.takeIf { it.isNotBlank() }
            ?: com.rrv.mdm.dpc.data.config.MdmGlobalConfig.SERVER_URL
        binding.etServerUrl.setText(storedUrl)
        updateTargetHost()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as RrvMdmApplication

        // AND-024: server URL / token extras from callers are untrusted and intentionally ignored;
        // fields are only pre-filled from this device's own stored configuration.
        val cachedUrl = app.serverConfigProvider.getBootstrapServerUrl() 
            ?: app.serverConfigProvider.getApiBaseUrl() 
            ?: app.repository.serverUrl.takeIf { it.isNotBlank() }
            ?: com.rrv.mdm.dpc.data.config.MdmGlobalConfig.SERVER_URL
        val cachedToken = app.serverConfigProvider.getBootstrapEnrollmentToken() 
            ?: app.repository.enrollmentToken.takeIf { it.isNotBlank() }
            ?: ""

        binding.etServerUrl.setText(cachedUrl)
        binding.etEnrollToken.setText(cachedToken)
        updateTargetHost()
        binding.etServerUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateTargetHost()
        })

        binding.btnEnrollSubmit.setOnClickListener {
            val serverUrl = binding.etServerUrl.text.toString().trim()
            val token = binding.etEnrollToken.text.toString().trim()

            if (serverUrl.isBlank() || token.isBlank()) {
                Toast.makeText(this, "Please enter both Server URL and Token.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.tvEnrollStatus.text = "Contacting Enterprise Enrollment Gateway..."
            binding.btnEnrollSubmit.isEnabled = false

            app.apiClient.enrollDevice(serverUrl, token) { success, message ->
                runOnUiThread {
                    binding.btnEnrollSubmit.isEnabled = true
                    if (success) {
                        Toast.makeText(this, "Enrollment Complete!", Toast.LENGTH_LONG).show()

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

                        // Trigger mandatory server policy fetch
                        val devId = app.repository.deviceId.ifBlank { app.mqttManager.getEffectiveDeviceId() }
                        if (devId.isNotBlank()) {
                            app.apiClient.fetchAndApplyPolicy(devId) { _ ->
                                if (app.deviceManager.isDeviceOwner()) {
                                    val freshPolicy = app.repository.getActivePolicy()
                                    app.deviceManager.applyPolicy(freshPolicy, force = true)
                                }
                            }
                        }

                        val homeIntent = Intent(this, com.rrv.mdm.dpc.ui.home.RrvMdmHomeActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(homeIntent)
                        finish()
                    } else {
                        binding.tvEnrollStatus.text = "Enrollment failed: $message"
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
