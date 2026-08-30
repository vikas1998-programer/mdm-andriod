package com.rrv.mdm.dpc.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.rrv.mdm.dpc.R
import com.rrv.mdm.dpc.databinding.ActivityRrvMdmHomeBinding
import com.rrv.mdm.dpc.domain.model.ApplicationInfo
import com.rrv.mdm.dpc.domain.model.CommandStatus
import com.rrv.mdm.dpc.domain.model.ComplianceLevel
import com.rrv.mdm.dpc.ui.admin.AdminDiagnosticActivity
import com.rrv.mdm.dpc.ui.apps.AppDetailsActivity
import com.rrv.mdm.dpc.ui.device.DeviceStatusActivity
import com.rrv.mdm.dpc.ui.messages.AdminMessagesActivity
import com.rrv.mdm.dpc.ui.settings.ManagedSettingsActivity
import kotlinx.coroutines.launch

class RrvMdmHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRrvMdmHomeBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: ManagedAppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRrvMdmHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Enforce Home Launcher retention (Back button does not exit launcher)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing — stay securely on managed home screen
            }
        })

        val app = application as com.rrv.mdm.dpc.RrvMdmApplication
        if (app.deviceManager.isDeviceOwner()) {
            val activePolicy = app.repository.getActivePolicy()
            app.deviceManager.applyPolicy(activePolicy)
        }

        // 2. Setup Responsive App Grid
        setupAppGrid()

        // 3. Setup Header & Navigation Actions
        setupHeaderActions()

        // 4. Observe Reactive StateFlow Streams
        observeState()
    }

    private fun setupAppGrid() {
        val screenWidthDp = resources.configuration.screenWidthDp
        val isTablet = screenWidthDp >= 600
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val spanCount = when {
            isTablet && isLandscape -> 6
            isTablet -> 5
            isLandscape -> 5
            else -> 3 // Default portrait phone (matching Contoso reference)
        }

        adapter = ManagedAppAdapter(
            onAppClick = { app -> handleAppLaunch(app) },
            onAppLongClick = { app -> openAppDetails(app) }
        )

        binding.rvAppGrid.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvAppGrid.adapter = adapter
    }

    private fun setupHeaderActions() {
        binding.btnNotifications.setOnClickListener {
            startActivity(Intent(this, AdminMessagesActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, ManagedSettingsActivity::class.java))
        }

        binding.pillDeviceSecurity.setOnClickListener {
            startActivity(Intent(this, DeviceStatusActivity::class.java))
        }

        binding.pillCompliantBadge.setOnClickListener {
            startActivity(Intent(this, DeviceStatusActivity::class.java))
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Clock & Date updates
                launch {
                    viewModel.currentTime.collect { time ->
                        binding.tvClockTime.text = time
                    }
                }
                launch {
                    viewModel.currentDate.collect { date ->
                        binding.tvClockDate.text = "$date • Managed"
                    }
                }

                // Dynamic App Grid updates
                launch {
                    viewModel.apps.collect { appList ->
                        adapter.submitList(appList)
                        if (appList.isEmpty()) {
                            binding.layoutEmptyApps.visibility = View.VISIBLE
                            binding.rvAppGrid.visibility = View.GONE
                        } else {
                            binding.layoutEmptyApps.visibility = View.GONE
                            binding.rvAppGrid.visibility = View.VISIBLE
                        }
                    }
                }

                // Device Security & Compliance Status updates
                launch {
                    viewModel.deviceStatus.collect { status ->
                        binding.tvSecurityStatusTitle.text = status.complianceTitle
                        binding.tvSecurityLastSynced.text = "Last synced: ${status.lastSyncFormatted}"

                        when (status.complianceLevel) {
                            ComplianceLevel.SECURE -> {
                                binding.ivSecurityShield.setColorFilter(Color.parseColor("#10B981"))
                                binding.pillCompliantBadge.text = "Compliant ✓"
                                binding.pillCompliantBadge.setTextColor(Color.parseColor("#10B981"))
                                binding.pillCompliantBadge.setBackgroundResource(R.drawable.bg_status_badge_green)
                            }
                            ComplianceLevel.WARNING -> {
                                binding.ivSecurityShield.setColorFilter(Color.parseColor("#F59E0B"))
                                binding.pillCompliantBadge.text = "Action Required"
                                binding.pillCompliantBadge.setTextColor(Color.parseColor("#F59E0B"))
                            }
                            ComplianceLevel.NON_COMPLIANT -> {
                                binding.ivSecurityShield.setColorFilter(Color.parseColor("#EF4444"))
                                binding.pillCompliantBadge.text = "Non-Compliant ⚠"
                                binding.pillCompliantBadge.setTextColor(Color.parseColor("#EF4444"))
                            }
                            ComplianceLevel.OFFLINE -> {
                                binding.ivSecurityShield.setColorFilter(Color.parseColor("#94A3B8"))
                                binding.pillCompliantBadge.text = "Offline"
                                binding.pillCompliantBadge.setTextColor(Color.parseColor("#94A3B8"))
                            }
                        }
                    }
                }

                // Notification Badge Counter
                launch {
                    viewModel.unreadMessageCount.collect { count ->
                        if (count > 0) {
                            binding.tvNotificationBadge.visibility = View.VISIBLE
                            binding.tvNotificationBadge.text = count.toString()
                        } else {
                            binding.tvNotificationBadge.visibility = View.GONE
                        }
                    }
                }

                // Floating Admin Message Card
                launch {
                    viewModel.latestAdminMessage.collect { msg ->
                        if (msg != null && !msg.isRead) {
                            binding.cardAdminMessage.visibility = View.VISIBLE
                            binding.tvAdminMsgTitle.text = msg.title
                            binding.tvAdminMsgBody.text = msg.message
                            binding.btnDismissAdminMsg.setOnClickListener {
                                viewModel.dismissAdminMessage(msg.id)
                            }
                        } else {
                            binding.cardAdminMessage.visibility = View.GONE
                        }
                    }
                }

                // Floating Real-Time Command Status Card
                launch {
                    viewModel.executingCommand.collect { cmd ->
                        if (cmd != null && cmd.status == CommandStatus.EXECUTING) {
                            binding.cardCommandStatus.visibility = View.VISIBLE
                            binding.tvCommandCardTitle.text = "Executing ${cmd.commandType.replace('_', ' ').lowercase().capitalize()}"
                            binding.tvCommandCardSubtitle.text = cmd.commandId
                            binding.pbCommandProgress.progress = if (cmd.progress > 0) cmd.progress else 45
                            binding.tvCommandStatusText.text = cmd.resultMessage ?: "Applying MDM configuration..."
                        } else {
                            binding.cardCommandStatus.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun handleAppLaunch(app: ApplicationInfo) {
        val launched = viewModel.launchApp(app.packageName)
        if (!launched) {
            Toast.makeText(this, "${app.appName} is managed by IT policy.", Toast.LENGTH_SHORT).show()
            openAppDetails(app)
        }
    }

    private fun openAppDetails(app: ApplicationInfo) {
        val intent = Intent(this, AppDetailsActivity::class.java).apply {
            putExtra("EXTRA_PACKAGE_NAME", app.packageName)
            putExtra("EXTRA_APP_NAME", app.appName)
            putExtra("EXTRA_APP_DESC", app.description)
            putExtra("EXTRA_APP_STATUS", app.installStatus.name)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        val app = application as com.rrv.mdm.dpc.RrvMdmApplication
        viewModel.refreshDeviceStatus()

        if (app.deviceManager.isDeviceOwner()) {
            val activePolicy = app.repository.getActivePolicy()
            app.deviceManager.applyPolicy(activePolicy)
            if (activePolicy.kioskModeEnabled) {
                app.lockTaskController.startKioskLock(this)
            }
        }
    }
}
