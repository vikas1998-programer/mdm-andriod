package com.rrv.mdm.dpc.ui.apps

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityAppDetailsBinding

class AppDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDetailsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val packageName = intent.getStringExtra("EXTRA_PACKAGE_NAME") ?: ""
        val appName = intent.getStringExtra("EXTRA_APP_NAME") ?: packageName
        val appDesc = intent.getStringExtra("EXTRA_APP_DESC") ?: "Enterprise managed application."
        val appStatus = intent.getStringExtra("EXTRA_APP_STATUS") ?: "INSTALLED"

        binding.tvDetailAppName.text = appName
        binding.tvDetailPackageName.text = packageName
        binding.tvDetailDesc.text = appDesc
        binding.tvDetailStatus.text = "Status: $appStatus"

        try {
            val icon = packageManager.getApplicationIcon(packageName)
            binding.ivDetailAppIcon.setImageDrawable(icon)
        } catch (_: Exception) {}

        val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(this, "com.rrv.mdm.dpc.mdm.receiver.MdmDeviceAdminReceiver")
        try {
            dpm?.enableSystemApp(admin, packageName)
            dpm?.setApplicationHidden(admin, packageName, false)
        } catch (_: Exception) {}

        val isInstalled = try { packageManager.getPackageInfo(packageName, 0); true } catch (_: Exception) { false }
        if (!isInstalled) {
            binding.btnLaunchApp.text = "Install Application"
            binding.btnLaunchApp.alpha = 1.0f
        }

        binding.btnLaunchApp.setOnClickListener {
            val app = application as RrvMdmApplication
            val isStillInstalled = try { packageManager.getPackageInfo(packageName, 0); true } catch (_: Exception) { false }
            if (!isStillInstalled) {
                val activePolicy = app.repository.getActivePolicy()
                val appPolicy = activePolicy.applications.find { it.packageName == packageName }
                val targetDownloadUrl = appPolicy?.downloadUrl.takeIf { !it.isNullOrBlank() }
                    ?: if (!appPolicy?.appId.isNullOrBlank()) "/api/v1/apps/${appPolicy?.appId}/download" else ""

                if (!targetDownloadUrl.isNullOrBlank() && appPolicy != null) {
                    Toast.makeText(this, "Downloading & installing $appName...", Toast.LENGTH_SHORT).show()
                    val cmdId = "autoheal-details-${packageName}-${System.currentTimeMillis()}"
                    com.rrv.mdm.dpc.worker.ApkDownloadWorker.enqueue(
                        this,
                        cmdId,
                        appPolicy.appId ?: "",
                        packageName,
                        appName,
                        targetDownloadUrl,
                        appPolicy.sha256 ?: "",
                        appPolicy.versionCode,
                        appPolicy.versionName,
                        appPolicy.managedConfigJson ?: "{}"
                    )
                    finish()
                    return@setOnClickListener
                }
            }

            val launched = app.launchAppUseCase(packageName)
            if (!launched) {
                val stillInstalled = try { packageManager.getPackageInfo(packageName, 0); true } catch (_: Exception) { false }
                if (!stillInstalled) {
                    Toast.makeText(this, "Cannot launch: $appName is not installed on this device.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Cannot launch: $appName is restricted by IT policy.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
