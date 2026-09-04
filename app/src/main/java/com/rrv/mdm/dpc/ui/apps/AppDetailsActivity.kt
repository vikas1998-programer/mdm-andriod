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

        val isInstalled = try { packageManager.getPackageInfo(packageName, 0); true } catch (_: Exception) { false }
        if (!isInstalled) {
            binding.btnLaunchApp.text = "Not Installed on Device"
            binding.btnLaunchApp.alpha = 0.6f
        }

        binding.btnLaunchApp.setOnClickListener {
            val app = application as RrvMdmApplication
            if (!isInstalled) {
                Toast.makeText(this, "Cannot launch: $appName is not installed on this device.", Toast.LENGTH_SHORT).show()
            } else {
                val launched = app.launchAppUseCase(packageName)
                if (!launched) {
                    Toast.makeText(this, "Cannot launch: $appName is restricted by IT policy.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
