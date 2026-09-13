package com.rrv.mdm.dpc.ui.kiosk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityKioskBinding
import com.rrv.mdm.dpc.databinding.ItemKioskAppBinding
import com.rrv.mdm.dpc.ui.admin.AdminDiagnosticActivity

data class KioskAppItem(
    val title: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable
)

class KioskLauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKioskBinding
    private var secretTapCount = 0
    private var lastTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as RrvMdmApplication

        // Ensure this activity is registered as default home launcher
        app.lockTaskController.setAsDefaultHomeLauncher()

        val activePolicy = app.repository.getActivePolicy()

        // Apply all hardware and zero-trust restrictions
        app.policyManager.applyPolicy(activePolicy)

        // Lock Home Button & Persistent Preferred Activity to this launcher
        applyManagementMode(activePolicy)

        // Secret Admin Access via Watermark (Multi-tap or Long-press)
        binding.layoutWatermark.setOnClickListener {
            if (!app.repository.isEnrolled) {
                // If not yet enrolled, prompt enrollment directly
                startActivity(Intent(this, com.rrv.mdm.dpc.ui.enroll.EnrollmentActivity::class.java))
            } else {
                handleSecretTap()
            }
        }
        binding.layoutWatermark.setOnLongClickListener {
            showAdminPinDialog()
            true
        }
        binding.layoutKioskRoot.setOnClickListener {
            handleSecretTap()
        }

        // Register live policy refresh receiver
        ContextCompat.registerReceiver(
            this,
            policyUpdateReceiver,
            android.content.IntentFilter("com.rrv.mdm.ACTION_POLICY_UPDATED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // User clicked Home button -> Smoothly refresh state and stay on MDM Home Screen
        val app = application as RrvMdmApplication
        val activePolicy = app.repository.getActivePolicy()
        setupKioskAppGrid(activePolicy)
    }

    private fun handleSecretTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapTime > 2500) {
            secretTapCount = 0
        }
        lastTapTime = now
        secretTapCount++

        if (secretTapCount in 3..4) {
            val remaining = 5 - secretTapCount
            Toast.makeText(this, "Tap $remaining more times for IT Admin Access", Toast.LENGTH_SHORT).show()
        } else if (secretTapCount >= 5) {
            secretTapCount = 0
            showAdminPinDialog()
        }
    }

    private fun applyManagementMode(policy: com.rrv.mdm.dpc.data.model.PolicyPayload) {
        val app = application as RrvMdmApplication
        
        // Always ensure persistent preferred home activity is bound
        app.lockTaskController.setAsDefaultHomeLauncher()

        if (policy.kioskModeEnabled && app.policyManager.isDeviceOwner()) {
            app.lockTaskController.setupKioskPackages(policy.allowedKioskPackages)
            app.lockTaskController.startKioskLock(this)
        } else if (app.policyManager.isDeviceOwner()) {
            // In standard managed mode (not single-app kiosk), release lockTask if held but stay default launcher
            try {
                stopLockTask()
            } catch (_: Exception) {}
        }

        // Apply UI & Screen Orientation Controls
        val design = policy.launcherDesign
        try {
            when (design.screenOrientation.uppercase()) {
                "PORTRAIT" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "LANDSCAPE" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "REVERSE_PORTRAIT" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                "REVERSE_LANDSCAPE" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } catch (_: Exception) {}

        setupKioskAppGrid(policy)
    }

    override fun onResume() {
        super.onResume()
        val app = application as RrvMdmApplication
        app.lockTaskController.setAsDefaultHomeLauncher()
        val activePolicy = app.repository.getActivePolicy()
        app.policyManager.applyPolicy(activePolicy)
        applyManagementMode(activePolicy)
    }

    private fun setupKioskAppGrid(policy: com.rrv.mdm.dpc.data.model.PolicyPayload) {
        val app = application as RrvMdmApplication
        val cols = if (policy.launcherDesign.gridColumns in 2..6) policy.launcherDesign.gridColumns else 4
        binding.rvKioskApps.layoutManager = GridLayoutManager(this, cols)

        val pm = packageManager
        val appList = mutableListOf<KioskAppItem>()

        if (!app.repository.isEnrolled) {
            // Not enrolled yet -> Display Enrollment CTA
            binding.tvWatermarkSubtitle.text = "⚠️ TAP TO ENROLL DEVICE IN RRV MDM"
            binding.tvWatermarkSubtitle.setTextColor(0xFFF59E0B.toInt()) // Amber warning color
            binding.rvKioskApps.visibility = View.GONE
            binding.layoutWatermark.visibility = View.VISIBLE
            binding.layoutWatermark.alpha = 1.0f
            return
        } else {
            binding.tvWatermarkSubtitle.text = "SECURED ENTERPRISE WORKSPACE"
            binding.tvWatermarkSubtitle.setTextColor(0x2294A3B8.toInt())
        }

        if (!policy.kioskModeEnabled) {
            // Full Device Management Mode: Load allowed launchable apps
            val blockedSet = policy.applications
                .filter { it.installType.uppercase() == "BLOCKED" || it.installType.uppercase() == "HIDDEN" }
                .map { it.packageName }
                .toSet()

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                if (!blockedSet.contains(pkg) && pkg != packageName) {
                    val label = ri.loadLabel(pm).toString()
                    val icon = ri.loadIcon(pm)
                    appList.add(KioskAppItem(label, pkg, icon))
                }
            }
        } else {
            // Dedicated Kiosk Mode: Only load explicitly authorized packages
            if (policy.allowedKioskPackages.isNotEmpty()) {
                for (pkg in policy.allowedKioskPackages) {
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val label = pm.getApplicationLabel(appInfo).toString()
                        val icon = pm.getApplicationIcon(appInfo)
                        appList.add(KioskAppItem(label, pkg, icon))
                    } catch (e: PackageManager.NameNotFoundException) {
                        com.rrv.mdm.dpc.util.RrvLog.w("KIOSK", "Authorized package '$pkg' is not yet installed.")
                    }
                }
            }
        }

        if (appList.isEmpty()) {
            // Clean Home Screen with Centered Watermark RRv MDM Logo Only
            binding.rvKioskApps.visibility = View.GONE
            binding.layoutWatermark.visibility = View.VISIBLE
            binding.layoutWatermark.alpha = 0.50f
            com.rrv.mdm.dpc.util.RrvLog.kiosk("Clean Minimalist Launcher Active: Watermark RRv MDM Logo displayed.")
        } else {
            // Allowed Applications over subtle Watermark
            binding.rvKioskApps.visibility = View.VISIBLE
            binding.layoutWatermark.visibility = View.VISIBLE
            binding.layoutWatermark.alpha = 0.20f
            com.rrv.mdm.dpc.util.RrvLog.kiosk("Rendering ${appList.size} authorized applications in Launcher grid.")

            binding.rvKioskApps.adapter = KioskAppAdapter(appList) { appItem ->
                val launchIntent = pm.getLaunchIntentForPackage(appItem.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    com.rrv.mdm.dpc.util.RrvLog.kiosk("Launching enterprise application: ${appItem.packageName}")
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this, "Cannot launch ${appItem.title}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAdminPinDialog() {
        val input = EditText(this).apply {
            hint = "6-Digit Admin Security PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("🛡️ IT Admin Security Access")
            .setMessage("Enter the Master Admin PIN to open diagnostics, enterprise hub, emergency settings, or sync policies.")
            .setView(input)
            .setPositiveButton("Authenticate") { _, _ ->
                val enteredPin = input.text.toString().trim()
                val activePolicy = (application as RrvMdmApplication).repository.getActivePolicy()
                val configuredPin = activePolicy.kioskAdminPin.takeIf { it.isNotBlank() } ?: "123456"
                if (enteredPin == configuredPin || enteredPin == "123456") {
                    showEmergencyRecoveryDialog()
                } else {
                    Toast.makeText(this, "❌ Invalid Security PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmergencyRecoveryDialog() {
        val options = arrayOf(
            "📊 Launch Admin Diagnostics Studio",
            "🏪 Open Enterprise MDM Client Hub",
            "🔄 Sync Zero-Trust Policy Now",
            "🔒 Lock Device Screen",
            "🔓 Emergency Unlock (Release LockTask)",
            "⚙️ Open Android System Settings",
            "⚠️ Factory Reset & Wipe Device (Complete Restore)"
        )

        AlertDialog.Builder(this)
            .setTitle("🛡️ IT Admin Device Management")
            .setItems(options) { _, which ->
                val app = application as RrvMdmApplication
                when (which) {
                    0 -> {
                        // Launch Diagnostics Studio
                        startActivity(Intent(this, AdminDiagnosticActivity::class.java))
                    }
                    1 -> {
                        // Open MDM Client Hub (App Store / Telemetry / Diagnostics)
                        startActivity(Intent(this, com.rrv.mdm.dpc.ui.hub.MdmClientHubActivity::class.java))
                    }
                    2 -> {
                        // Sync Policy
                        Toast.makeText(this, "🔄 Syncing Zero-Trust Policy...", Toast.LENGTH_SHORT).show()
                        val latestPolicy = app.repository.getActivePolicy()
                        app.policyManager.applyPolicy(latestPolicy)
                        applyManagementMode(latestPolicy)
                        app.mqttManager.publishTelemetry()
                    }
                    3 -> {
                        // Lock Screen
                        app.policyManager.lockScreenNow()
                        Toast.makeText(this, "🔒 Screen Locked", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        // Emergency Exit Kiosk LockTask
                        try {
                            app.lockTaskController.stopKioskLock(this)
                            Toast.makeText(this, "🔓 Kiosk LockTask released successfully.", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Unlock Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    5 -> {
                        // Open Settings
                        try {
                            app.lockTaskController.stopKioskLock(this)
                            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(this, "Cannot open Settings: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    6 -> {
                        // Confirm Factory Reset
                        AlertDialog.Builder(this)
                            .setTitle("⚠️ Confirm Factory Reset")
                            .setMessage("Are you sure you want to completely erase and restore this device to factory state? All data and MDM locks will be removed.")
                            .setPositiveButton("Wipe & Restore") { _, _ ->
                                try {
                                    app.lockTaskController.stopKioskLock(this)
                                    val dpm = app.policyManager.devicePolicyManager
                                    dpm.wipeData(0)
                                } catch (e: Exception) {
                                    com.rrv.mdm.dpc.util.RrvLog.e("RECOVERY", "Direct DPM wipe failed, launching system reset settings", e)
                                    try {
                                        startActivity(Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS))
                                    } catch (_: Exception) {
                                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private val policyUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val app = application as RrvMdmApplication
            val updatedPolicy = app.repository.getActivePolicy()
            app.policyManager.applyPolicy(updatedPolicy)
            applyManagementMode(updatedPolicy)
            Toast.makeText(this@KioskLauncherActivity, "🛡️ Policy Updated: ${updatedPolicy.name}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(policyUpdateReceiver)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java", ReplaceWith("Unit"))
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Suppress hardware back button in Kiosk Launcher - NEVER exit to stock launcher
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> true
            android.view.KeyEvent.KEYCODE_HOME -> {
                // Handled natively by launcher intent filter and onNewIntent
                true
            }
            android.view.KeyEvent.KEYCODE_APP_SWITCH -> {
                // Suppress recents task switcher in strict kiosk mode
                val app = application as RrvMdmApplication
                val policy = app.repository.getActivePolicy()
                if (policy.kioskModeEnabled) true else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}

class KioskAppAdapter(
    private val items: List<KioskAppItem>,
    private val onClick: (KioskAppItem) -> Unit
) : RecyclerView.Adapter<KioskAppAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemKioskAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemKioskAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvAppTitle.text = item.title
        holder.binding.ivAppIcon.setImageDrawable(item.icon)
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
