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
                            binding.tvCommandCardTitle.text = "Executing ${cmd.commandType.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}"
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
            val isInstalled = try { packageManager.getPackageInfo(app.packageName, 0); true } catch (_: Exception) { false }
            if (!isInstalled) {
                Toast.makeText(this, "${app.appName} is not installed on this device.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "${app.appName} is restricted by IT policy.", Toast.LENGTH_SHORT).show()
            }
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

    private var lastVolumeToast: Toast? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                    handleVolumeKeyPress(isVolumeUp = true)
                    return true
                }
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    handleVolumeKeyPress(isVolumeUp = false)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleVolumeKeyPress(isVolumeUp: Boolean) {
        val app = application as com.rrv.mdm.dpc.RrvMdmApplication
        val userManager = getSystemService(android.content.Context.USER_SERVICE) as? android.os.UserManager
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
        val policy = app.repository.getActivePolicy()

        val isLocked = policy.volumeAdjustDisabled ||
                (userManager?.hasUserRestriction(android.os.UserManager.DISALLOW_ADJUST_VOLUME) == true)
        val isMuted = policy.masterVolumeMuted

        val currentVol = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
        val maxVol = audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
        val minVol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try { audioManager?.getStreamMinVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0 } catch (_: Exception) { 0 }
        } else 0

        val currentPercent = if (maxVol > minVol) {
            (((currentVol - minVol).toDouble() / (maxVol - minVol).toDouble()) * 100).toInt().coerceIn(0, 100)
        } else 0

        lastVolumeToast?.cancel()

        if (isMuted) {
            val msg = "🔇 Volume Muted by IT Administrator (0%)\nHardware buttons restricted by Policy."
            lastVolumeToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
            lastVolumeToast?.show()
            com.rrv.mdm.dpc.util.RrvLog.w("VolumeControl", "🚫 Hardware volume press rejected: Master volume is muted by IT admin.")
            return
        }

        if (isLocked) {
            val keyName = if (isVolumeUp) "Volume Up (+)" else "Volume Down (-)"
            val msg = "🔒 Volume Adjustment Restricted by IT Admin\nLevel locked at $currentPercent% ($keyName blocked by Policy)"
            lastVolumeToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
            lastVolumeToast?.show()
            com.rrv.mdm.dpc.util.RrvLog.w("VolumeControl", "🚫 Hardware $keyName press rejected: DISALLOW_ADJUST_VOLUME restriction active.")
            return
        }

        // When Unlocked: Adjust stream volume smoothly
        val direction = if (isVolumeUp) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
        audioManager?.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            direction,
            android.media.AudioManager.FLAG_SHOW_UI or android.media.AudioManager.FLAG_PLAY_SOUND
        )
        val newVol = audioManager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: currentVol
        val newPercent = if (maxVol > minVol) {
            (((newVol - minVol).toDouble() / (maxVol - minVol).toDouble()) * 100).toInt().coerceIn(0, 100)
        } else 0

        val keyName = if (isVolumeUp) "▲ Volume Raised" else "▼ Volume Lowered"
        val msg = "🔊 $keyName: $newPercent% [User Control Allowed]"
        lastVolumeToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
        lastVolumeToast?.show()
        com.rrv.mdm.dpc.util.RrvLog.d("VolumeControl", "✓ Hardware volume button pressed: Media volume adjusted to $newPercent%")
    }

    override fun onResume() {
        super.onResume()
        val app = application as com.rrv.mdm.dpc.RrvMdmApplication
        viewModel.refreshDeviceStatus()

        if (app.deviceManager.isDeviceOwner()) {
            val activePolicy = app.repository.getActivePolicy()
            if (activePolicy.kioskModeEnabled) {
                app.lockTaskController.startKioskLock(this)
            }
        }
    }
}
