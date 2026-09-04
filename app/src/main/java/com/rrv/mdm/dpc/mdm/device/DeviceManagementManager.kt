package com.rrv.mdm.dpc.mdm.device

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.data.model.PolicyPayload
import com.rrv.mdm.dpc.domain.model.ApplicationInfo
import com.rrv.mdm.dpc.domain.model.InstallStatus
import com.rrv.mdm.dpc.receiver.RrvDeviceAdminReceiver
import com.rrv.mdm.dpc.ui.home.RrvMdmHomeActivity
import com.rrv.mdm.dpc.util.RrvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class DeviceManagementManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceManagementManager"

        val CRITICAL_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.ext.services",
            "com.google.android.ext.shared",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.incallui",
            "com.samsung.android.biometrics",
            "com.samsung.android.knox.containercore",
            "com.samsung.klmsagent"
        )
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, RrvDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)
    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    /**
     * Lock the physical device screen immediately.
     */
    fun lockScreenNow(): Boolean {
        return try {
            dpm.lockNow()
            RrvLog.i(TAG, "🔒 Screen locked successfully via DPM.")
            true
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to lock screen", e)
            false
        }
    }

    /**
     * Reboot device (Device Owner only)
     */
    fun rebootDevice(): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.reboot(adminComponent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to reboot device", e)
            false
        }
    }

    /**
     * Reset device PIN / Passcode
     */
    fun resetPassword(newPin: String): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val token = "rrv_mdm_reset_token".toByteArray()
                dpm.setResetPasswordToken(adminComponent, token)
                dpm.resetPasswordWithToken(adminComponent, newPin, token, 0)
            } else {
                @Suppress("DEPRECATION")
                dpm.resetPassword(newPin, DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
            }
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to reset passcode", e)
            false
        }
    }

    /**
     * Set persistent preferred Home Activity (binds MDM as unbreakable launcher)
     */
    fun setAsDefaultHomeLauncher() {
        if (!isDeviceOwner()) return
        try {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val launcherComponent = ComponentName(context, RrvMdmHomeActivity::class.java)
            dpm.addPersistentPreferredActivity(adminComponent, filter, launcherComponent)
            dpm.setUninstallBlocked(adminComponent, context.packageName, true)
            RrvLog.i(TAG, "🏠 Bounded RrvMdmHomeActivity as permanent Default Home Launcher.")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to set default home launcher", e)
        }
    }

    @Volatile
    private var lastAppliedPolicyVersion: String? = null

    /**
     * Apply comprehensive Zero-Trust Policy onto device hardware and system.
     * Default-blocks and hides all unmanaged apps on the device (Alarm, Notes, etc.).
     * Only approved/whitelisted apps become visible and runnable.
     */
    fun applyPolicy(policy: PolicyPayload, force: Boolean = false) {
        if (!isDeviceOwner()) {
            RrvLog.w(TAG, "Cannot enforce DPM policies: Not Device Owner.")
            return
        }

        val policySignature = "${policy.version}_${policy.name}_${policy.applications.hashCode()}_${policy.allowedKioskPackages.hashCode()}"
        if (!force && lastAppliedPolicyVersion == policySignature) {
            RrvLog.d(TAG, "Policy $policySignature already applied — skipping redundant enforcement.")
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                // 1. Hardware Restrictions
                dpm.setCameraDisabled(adminComponent, policy.cameraDisabled)
                dpm.setScreenCaptureDisabled(adminComponent, policy.screenCaptureDisabled)
                setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, policy.usbDataTransferDisabled)
                setUserRestriction(UserManager.DISALLOW_BLUETOOTH, policy.bluetoothDisabled)
                setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, policy.sdCardDisabled)
                setUserRestriction(UserManager.DISALLOW_UNMUTE_MICROPHONE, policy.microphoneDisabled)

                // 2. Network Restrictions
                setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, policy.wifiConfigLock)
                setUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING, policy.tetheringDisabled)
                setUserRestriction(UserManager.DISALLOW_DATA_ROAMING, policy.dataRoamingDisabled)
                setUserRestriction(UserManager.DISALLOW_AIRPLANE_MODE, policy.airplaneModeDisabled)

                // 3. Anti-Tamper System Controls
                setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, policy.factoryResetDisabled)
                setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, policy.safeBootDisabled)
                setUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, policy.developerOptionsDisabled)
                setUserRestriction(UserManager.DISALLOW_ADD_USER, true)
                setUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, true)
                setUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS, policy.appUninstallDisabled)
                setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, policy.unknownSourcesDisabled)
                setUserRestriction(UserManager.DISALLOW_APPS_CONTROL, true) // Block Settings -> Apps bypass/modifications

                // 4. Anti-Uninstall MDM
                dpm.setUninstallBlocked(adminComponent, context.packageName, true)

                // 5. Display & Screen Timeout Governance
                if (policy.screenTimeoutSeconds > 0) {
                    setScreenTimeout(policy.screenTimeoutSeconds)
                }
                if (policy.autoBrightnessEnabled) {
                    setAutoBrightness(true)
                } else {
                    setAutoBrightness(false)
                    policy.screenBrightnessPercent?.let { setScreenBrightness(it) }
                }
                if (policy.screenBrightnessPercent != null && !policy.autoBrightnessEnabled) {
                    setScreenBrightness(policy.screenBrightnessPercent)
                }

                // 6. Audio & Volume Level Governance
                if (!policy.masterVolumeMuted) {
                    policy.mediaVolumePercent?.let { setStreamVolumePercent(android.media.AudioManager.STREAM_MUSIC, it) }
                    policy.alarmVolumePercent?.let { setStreamVolumePercent(android.media.AudioManager.STREAM_ALARM, it) }
                    policy.ringVolumePercent?.let {
                        setStreamVolumePercent(android.media.AudioManager.STREAM_RING, it)
                        setStreamVolumePercent(android.media.AudioManager.STREAM_NOTIFICATION, it)
                    }
                }
                setMasterVolumeMuted(policy.masterVolumeMuted)
                setVolumeAdjustDisabled(policy.volumeAdjustDisabled)

                // 7. Ensure Home Launcher is registered
                setAsDefaultHomeLauncher()

                // 8. Zero-Trust Application Package Governance (Safe Whitelisting & Launcher Filtering)
                val pm = context.packageManager
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager

                val allInstalledApps = try {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                } catch (_: Exception) {
                    pm.getInstalledApplications(0)
                }

                val explicitlyAllowed = policy.applications
                    .filter { it.installType.uppercase() in listOf("SHOW", "VISIBLE", "INSTALL", "FORCE_INSTALLED", "AVAILABLE", "ALLOWED", "REQUIRED", "MANAGED", "MANDATORY", "MANDATORY_SILENT", "MANAGED_SILENT", "SILENT", "OPTIONAL", "AUTO_INSTALL") }
                    .map { it.packageName }
                    .toSet()

                val explicitlyUninstalled = policy.applications
                    .filter { it.installType.uppercase() in listOf("UNINSTALL", "REMOVED") }
                    .map { it.packageName }
                    .toSet()

                val allowedKiosk = policy.allowedKioskPackages.toSet()
                val whitelisted = (explicitlyAllowed + allowedKiosk).toMutableSet()

                // Handle explicit uninstallation requests
                for (pkg in explicitlyUninstalled) {
                    try {
                        val isInstalled = try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
                        if (isInstalled && pkg != context.packageName && !CRITICAL_SYSTEM_PACKAGES.contains(pkg)) {
                            dpm.setApplicationHidden(adminComponent, pkg, true)
                            dpm.setPackagesSuspended(adminComponent, arrayOf(pkg), true)
                            am?.killBackgroundProcesses(pkg)
                            RrvLog.i(TAG, "🗑️ Enforced UNINSTALL/Removal policy on package $pkg")
                        }
                    } catch (e: Exception) {
                        RrvLog.e(TAG, "Failed to uninstall/remove $pkg", e)
                    }
                }

                var allowedCount = 0
                var blockedCount = 0
                val userAppsToSuspend = mutableListOf<String>()
                val userAppsToUnsuspend = mutableListOf<String>()

                for (appInfo in allInstalledApps) {
                    val pkg = appInfo.packageName
                    if (pkg == context.packageName || CRITICAL_SYSTEM_PACKAGES.contains(pkg)) continue

                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                    // Always ensure system apps and overlays are unhidden to prevent Zygote idmap crashes
                    try { dpm.setApplicationHidden(adminComponent, pkg, false) } catch (_: Exception) {}

                    if (whitelisted.contains(pkg)) {
                        userAppsToUnsuspend.add(pkg)
                        allowedCount++
                    } else if (!isSystem && policy.applications.isNotEmpty()) {
                        // Only suspend user-installed non-system applications
                        userAppsToSuspend.add(pkg)
                        blockedCount++
                    }
                }

                // 8.5 Ensure pre-installed system apps are enabled, unhidden, and unsuspended if whitelisted
                if (isDeviceOwner()) {
                    for (pkg in whitelisted) {
                        if (pkg != context.packageName) {
                            try { dpm.enableSystemApp(adminComponent, pkg) } catch (_: Exception) {}
                            try { dpm.setApplicationHidden(adminComponent, pkg, false) } catch (_: Exception) {}
                            try { dpm.setPackagesSuspended(adminComponent, arrayOf(pkg), false) } catch (_: Exception) {}
                        }
                    }
                }

                if (userAppsToUnsuspend.isNotEmpty()) {
                    try {
                        dpm.setPackagesSuspended(adminComponent, userAppsToUnsuspend.toTypedArray(), false)
                    } catch (_: Exception) {}
                }
                if (userAppsToSuspend.isNotEmpty()) {
                    try {
                        dpm.setPackagesSuspended(adminComponent, userAppsToSuspend.toTypedArray(), true)
                    } catch (_: Exception) {}
                }

                // 9. Configure LockTask allowlist for Overview / Recents Containment
                val lockTaskPackages = (whitelisted + context.packageName).distinct().toTypedArray()
                dpm.setLockTaskPackages(adminComponent, lockTaskPackages)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                    dpm.setLockTaskFeatures(adminComponent, features)
                }

                lastAppliedPolicyVersion = policySignature

                // 10. Update Local Database with Whitelisted Apps
                val app = context.applicationContext as? RrvMdmApplication
                if (app != null) {
                    val appList = whitelisted.map { pkg ->
                        val label = try {
                            val info = pm.getApplicationInfo(pkg, 0)
                            pm.getApplicationLabel(info).toString()
                        } catch (_: Exception) {
                            policy.applications.find { it.packageName == pkg }?.title?.takeIf { it.isNotBlank() && it != pkg }
                                ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                        }
                        val isInstalled = try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
                        ApplicationInfo(
                            packageName = pkg,
                            appName = label,
                            versionName = "1.0",
                            isLaunchable = true,
                            isEnabled = true,
                            isManaged = true,
                            installStatus = if (isInstalled) InstallStatus.INSTALLED else InstallStatus.AVAILABLE
                        )
                    }
                    app.repositoryImpl.syncAppsFromPolicy(appList)
                    app.repositoryImpl.queueEvent(
                        eventType = "POLICY_ENFORCED",
                        severity = "INFO",
                        message = "Zero-Trust App Governance: $allowedCount apps ALLOWED, $blockedCount apps BLOCKED at OS level (DPM Suspension + Hidden).",
                        metadataJson = "{\"allowedCount\":$allowedCount,\"blockedCount\":$blockedCount}"
                    )
                }

                RrvLog.i(TAG, "🛡️ Zero-Trust App Governance: $allowedCount apps ALLOWED/VISIBLE, $blockedCount apps BLOCKED/HIDDEN.")
                RrvLog.i(TAG, "✅ Zero-Trust Policy [${policy.name}] successfully applied via DPM.")
            } catch (e: Exception) {
                RrvLog.e(TAG, "Error applying DPM policy", e)
            }
        }
    }

    /**
     * Set display sleep timeout in seconds (both via DPM maximumTimeToLock and System Settings).
     */
    fun setScreenTimeout(seconds: Int) {
        if (seconds <= 0) return
        val timeoutMs = seconds * 1000L
        try {
            if (isDeviceOwner() || isAdminActive()) {
                dpm.setMaximumTimeToLock(adminComponent, timeoutMs)
            }
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                timeoutMs.toInt()
            )
            RrvLog.i(TAG, "⏰ Screen timeout configured to $seconds s ($timeoutMs ms).")
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not set screen timeout to $seconds s: ${e.message}")
        }
    }

    /**
     * Set display brightness percentage (0..100%).
     */
    fun setScreenBrightness(percent: Int) {
        try {
            val clamped = percent.coerceIn(0, 100)
            val brightnessValue = ((clamped * 255) / 100).coerceIn(1, 255)
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            RrvLog.i(TAG, "🔆 Screen brightness set to $clamped% (raw $brightnessValue/255).")
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not set screen brightness to $percent%: ${e.message}")
        }
    }

    /**
     * Toggle Automatic (Adaptive) Display Brightness mode.
     */
    fun setAutoBrightness(enabled: Boolean) {
        try {
            val mode = if (enabled) {
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } else {
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            }
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                mode
            )
            RrvLog.i(TAG, "🔆 Auto-brightness mode configured: $enabled")
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not toggle auto brightness: ${e.message}")
        }
    }

    /**
     * Device Owner Master Volume Mute.
     */
    fun setMasterVolumeMuted(muted: Boolean) {
        try {
            if (isDeviceOwner()) {
                dpm.setMasterVolumeMuted(adminComponent, muted)
            }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (muted) {
                audioManager?.adjustVolume(android.media.AudioManager.ADJUST_MUTE, 0)
            } else {
                audioManager?.adjustVolume(android.media.AudioManager.ADJUST_UNMUTE, 0)
                audioManager?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
            }
            RrvLog.i(TAG, "🔇 Master volume muted: $muted")
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not set master volume muted to $muted: ${e.message}")
        }
    }

    /**
     * Restrict hardware volume button adjustments.
     */
    fun setVolumeAdjustDisabled(disabled: Boolean) {
        setUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME, disabled)
        RrvLog.i(TAG, "🔇 Volume button adjustment locked: $disabled")
    }

    /**
     * Set stream volume level by percentage (0..100%).
     */
    fun setStreamVolumePercent(streamType: Int, percent: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            if (percent > 0) {
                audioManager.adjustStreamVolume(streamType, android.media.AudioManager.ADJUST_UNMUTE, 0)
                if (streamType == android.media.AudioManager.STREAM_MUSIC) {
                    audioManager.adjustVolume(android.media.AudioManager.ADJUST_UNMUTE, 0)
                }
            }
            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(streamType) else 0
            val target = (minVol + ((percent.coerceIn(0, 100).toDouble() / 100.0) * (maxVol - minVol))).toInt().coerceIn(minVol, maxVol)
            audioManager.setStreamVolume(streamType, target, 0)
            RrvLog.i(TAG, "🔊 Audio stream $streamType volume set to $percent% (level $target/$maxVol)")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to set audio stream $streamType volume to $percent%", e)
        }
    }

    /**
     * Trigger high-decibel Siren / Alarm sound for specified duration in seconds.
     */
    fun triggerAlarmSound(durationSeconds: Int = 10) {
        CoroutineScope(Dispatchers.Default).launch {
            var mediaPlayer: android.media.MediaPlayer? = null
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (audioManager != null) {
                    val maxAlarm = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxAlarm, 0)
                }

                val alarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)

                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(context, alarmUri)
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    start()
                }
                RrvLog.w(TAG, "🚨 High-decibel alarm siren sounding for $durationSeconds seconds!")

                kotlinx.coroutines.delay(durationSeconds * 1000L)
            } catch (e: Exception) {
                RrvLog.e(TAG, "Failed to play alarm siren sound", e)
            } finally {
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                } catch (_: Exception) {}
                RrvLog.i(TAG, "🚨 Alarm siren playback completed.")
            }
        }
    }

    /**
     * Lost mode banner and immediate screen lock.
     */
    fun enableLostMode(message: String?, phone: String?) {
        try {
            if (isDeviceOwner()) {
                val lockInfo = buildString {
                    append(message ?: "This device is managed by IT and marked as LOST.")
                    if (!phone.isNullOrBlank()) {
                        append("\nPlease call: ").append(phone)
                    }
                }
                dpm.setDeviceOwnerLockScreenInfo(adminComponent, lockInfo)
            }
            lockScreenNow()
            RrvLog.w(TAG, "🛡️ Lost Mode enabled with message: $message")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to enable lost mode", e)
        }
    }

    /**
     * Dismiss lost mode.
     */
    fun disableLostMode() {
        try {
            if (isDeviceOwner()) {
                dpm.setDeviceOwnerLockScreenInfo(adminComponent, null)
            }
            RrvLog.i(TAG, "🟢 Lost Mode dismissed.")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to disable lost mode", e)
        }
    }

    private fun setUserRestriction(key: String, enable: Boolean) {
        try {
            if (enable) {
                dpm.addUserRestriction(adminComponent, key)
            } else {
                dpm.clearUserRestriction(adminComponent, key)
            }
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to toggle user restriction $key=$enable", e)
        }
    }
}
