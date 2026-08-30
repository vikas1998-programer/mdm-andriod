package com.rrv.mdm.dpc.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import com.rrv.mdm.dpc.data.model.PolicyPayload
import com.rrv.mdm.dpc.receiver.RrvDeviceAdminReceiver
import com.rrv.mdm.dpc.util.RrvLog

/**
 * Handles Android Enterprise DevicePolicyManager (DPM) restrictions and hardware peripherals.
 */
class DpmPolicyManager(private val context: Context) {

    companion object {
        private const val TAG = "DpmPolicyManager"
    }

    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = RrvDeviceAdminReceiver.getComponentName(context)

    val devicePolicyManager: DevicePolicyManager get() = dpm
    val adminComponent: android.content.ComponentName get() = admin

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)
    fun isAdminActive(): Boolean = dpm.isAdminActive(admin)

    fun enforceBaselineSecurity() {
        if (!isAdminActive()) return

        try {
            if (isDeviceOwner()) {
                // 1. Prevent uninstalling or clearing the MDM agent
                try {
                    dpm.setUninstallBlocked(admin, context.packageName, true)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set uninstall blocked: ${e.message}")
                }

                // 2. Lock down factory reset & safe boot
                dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)

                // 3. Set RrvMdmHomeActivity as permanent default Home launcher
                try {
                    val component = android.content.ComponentName(context, com.rrv.mdm.dpc.ui.home.RrvMdmHomeActivity::class.java)
                    context.packageManager.setComponentEnabledSetting(
                        component,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                    val filter = android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                        addCategory(android.content.Intent.CATEGORY_HOME)
                        addCategory(android.content.Intent.CATEGORY_DEFAULT)
                    }
                    dpm.addPersistentPreferredActivity(admin, filter, component)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not bind persistent preferred activity: ${e.message}")
                }

                // NOTE: Keeping USB debugging open so RRV IT team can diagnose via ADB
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
                Log.i(TAG, "✅ Baseline Device Owner security enforced: Anti-Uninstall, Anti-Exit, Safe Boot & Home Lock.")
            } else {
                Log.w(TAG, "⚠️ enforceBaselineSecurity called but app is NOT Device Owner — skipping.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply baseline restrictions", e)
        }
    }

    fun applyPolicy(policy: PolicyPayload) {
        if (!isAdminActive()) return

        try {
            Log.i(TAG, "Enforcing policy [${policy.name}]...")

            // 1. Camera Restriction (Hardware HAL Level)
            dpm.setCameraDisabled(admin, policy.cameraDisabled)

            // 2. Screen Capture (Screenshots & Screen Recording)
            if (isDeviceOwner()) {
                dpm.setScreenCaptureDisabled(admin, policy.screenCaptureDisabled)
            }

            // 3. USB Data Transfer & Storage
            if (isDeviceOwner()) {
                if (policy.usbDataTransferDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
                    Log.i(TAG, "🚫 USB Data Transfer: DISALLOWED")
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
                    Log.i(TAG, "✓ USB Data Transfer: ALLOWED")
                }
            }

            // 4. Bluetooth Restriction
            if (isDeviceOwner()) {
                if (policy.bluetoothDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_BLUETOOTH)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_BLUETOOTH)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_BLUETOOTH_SHARING)
                    try {
                        @Suppress("DEPRECATION")
                        val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                        if (btAdapter?.isEnabled == true) {
                            @Suppress("DEPRECATION")
                            btAdapter.disable()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not turn off Bluetooth adapter directly: ${e.message}")
                    }
                    Log.i(TAG, "🚫 Bluetooth restrictions ENFORCED (DISALLOW_BLUETOOTH + DISALLOW_CONFIG_BLUETOOTH)")
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_BLUETOOTH)
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_BLUETOOTH)
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_BLUETOOTH_SHARING)
                    Log.i(TAG, "✓ Bluetooth restrictions CLEARED")
                }
            }

            // 5. Network Restrictions (Mobile Hotspot & Tethering, Wi-Fi Lock, Roaming, Airplane Mode)
            if (isDeviceOwner()) {
                // Mobile Hotspot & Cellular Tethering
                if (policy.tetheringDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
                    Log.i(TAG, "🚫 Mobile Hotspot / Tethering: DISALLOWED")
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_TETHERING)
                    Log.i(TAG, "✓ Mobile Hotspot / Tethering: ALLOWED")
                }

                // Wi-Fi Config Lock
                if (policy.wifiConfigLock) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI)
                }

                // Data Roaming
                if (policy.dataRoamingDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_DATA_ROAMING)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_DATA_ROAMING)
                }

                // Airplane Mode
                if (policy.airplaneModeDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE)
                }
            }

            // 6. System & Hardware Security Restrictions
            if (isDeviceOwner()) {
                // Factory Reset Protection (FRP)
                if (policy.factoryResetDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                }

                // Safe Boot
                if (policy.safeBootDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                }

                // Developer Options & USB Debugging
                if (policy.developerOptionsDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                    Log.i(TAG, "🚫 Developer Options / ADB: DISALLOWED")
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                    Log.i(TAG, "✓ Developer Options / ADB: ALLOWED")
                }

                // SD Card / Physical Media
                if (policy.sdCardDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
                }

                // Microphone Mute
                if (policy.microphoneDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_UNMUTE_MICROPHONE)
                    (context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMicrophoneMute = true
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNMUTE_MICROPHONE)
                    (context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMicrophoneMute = false
                }

                // DLP Cross Profile Clipboard
                if (policy.clipboardDlpDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE)
                }

                // App Uninstall Protection
                if (policy.appUninstallDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
                }

                // Unknown Sources / Sideloading
                if (policy.unknownSourcesDisabled) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                }
            }

            // 7. Password Complexity
            try {
                dpm.setPasswordQuality(admin, DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX)
                dpm.setPasswordMinimumLength(admin, policy.minPasswordLength)
                dpm.setMaximumFailedPasswordsForWipe(admin, policy.maxFailedAttempts)
            } catch (e: Exception) {
                Log.w(TAG, "Password policy setup warning: ${e.message}")
            }

            // 8. Display & Screen Timeout
            try {
                if (policy.screenTimeoutSeconds > 0) {
                    dpm.setMaximumTimeToLock(admin, policy.screenTimeoutSeconds * 1000L)
                }
                policy.screenBrightnessPercent?.let { setScreenBrightness(it) }
                setAutoBrightness(policy.autoBrightnessEnabled)
            } catch (e: Exception) {
                Log.w(TAG, "Display settings warning: ${e.message}")
            }

            // 9. Audio & Volume Restrictions
            try {
                if (isDeviceOwner()) {
                    dpm.setMasterVolumeMuted(admin, policy.masterVolumeMuted)
                    if (policy.volumeAdjustDisabled) {
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME)
                    } else {
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME)
                    }
                }
                policy.mediaVolumePercent?.let { setStreamVolume(android.media.AudioManager.STREAM_MUSIC, it) }
                policy.alarmVolumePercent?.let { setStreamVolume(android.media.AudioManager.STREAM_ALARM, it) }
                policy.ringVolumePercent?.let { setStreamVolume(android.media.AudioManager.STREAM_RING, it) }
            } catch (e: Exception) {
                Log.w(TAG, "Audio settings warning: ${e.message}")
            }

            // 10. Zero-Trust Whitelist Governance (Default BLOCK/HIDE un-whitelisted packages)
            if (isDeviceOwner()) {
                val pm = context.packageManager
                
                // Query both installed applications and all launcher activities across the system
                val launcherApps = try {
                    pm.queryIntentActivities(
                        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),
                        android.content.pm.PackageManager.MATCH_ALL
                    ).mapNotNull { it.activityInfo?.packageName }
                } catch (_: Exception) { emptyList() }

                val installedApps = try {
                    pm.getInstalledApplications(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
                        .map { it.packageName }
                } catch (_: Exception) {
                    pm.getInstalledApplications(0).map { it.packageName }
                }

                val allDiscoveredPackages = (installedApps + launcherApps).toSet()

                val allowedPackages = policy.applications
                    .filter { it.installType.uppercase() in listOf("VISIBLE", "ALLOWED", "REQUIRED", "MANAGED") }
                    .map { it.packageName }
                    .toSet()

                // Essential system infrastructure that must never be suspended to avoid bricking OS
                val criticalPackages = setOf(
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
                    "com.samsung.klmsagent",
                    context.packageName
                )

                val effectiveAllowed = if (allowedPackages.isNotEmpty()) {
                    allowedPackages + policy.allowedKioskPackages
                } else {
                    setOf(
                        "com.microsoft.teams",
                        "com.rrv.inventory",
                        "com.microsoft.office.excel",
                        "com.microsoft.office.onenote",
                        "com.microsoft.emmx",
                        "com.rrv.portal",
                        "com.android.chrome",
                        "com.sec.android.app.myfiles",
                        "com.android.settings"
                    )
                }

                var allowedCount = 0
                var blockedCount = 0

                for (pkg in allDiscoveredPackages) {
                    if (criticalPackages.contains(pkg)) continue

                    val isWhitelisted = effectiveAllowed.contains(pkg)

                    try {
                        if (isWhitelisted) {
                            // Unhide and unsuspend explicitly whitelisted apps
                            dpm.setApplicationHidden(admin, pkg, false)
                            dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                            allowedCount++
                        } else {
                            // Default-Block: Hide and Suspend all unmanaged apps (Alarm, Clock, etc.)
                            dpm.setApplicationHidden(admin, pkg, true)
                            dpm.setPackagesSuspended(admin, arrayOf(pkg), true)
                            blockedCount++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "App governance state error for $pkg: ${e.message}")
                    }
                }
                RrvLog.dpm("🛡️ Zero-Trust Whitelist Enforced: $allowedCount apps ALLOWED/VISIBLE, $blockedCount apps BLOCKED/HIDDEN by default.")

                // Configure LockTask allowlist for Overview / Recents Containment
                val lockTaskPackages = (effectiveAllowed + context.packageName).distinct().toTypedArray()
                dpm.setLockTaskPackages(admin, lockTaskPackages)
            }

            // 11. MDM Home Launcher Enforcement
            if (isDeviceOwner()) {
                val homeComponent = android.content.ComponentName(context, com.rrv.mdm.dpc.ui.home.RrvMdmHomeActivity::class.java)
                val filter = android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                    addCategory(android.content.Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(admin, filter, homeComponent)
                dpm.setUninstallBlocked(admin, context.packageName, true)
                Log.i(TAG, "🔒 RRV MDM Home Launcher enforced as persistent default home.")
            }

            Log.i(TAG, "✓ Policy [${policy.name}] successfully applied to hardware.")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying DPM policy", e)
        }
    }

    fun setScreenBrightness(percent: Int) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !android.provider.Settings.System.canWrite(context)) {
                return
            }
            val clamped = percent.coerceIn(0, 100)
            val brightnessValue = (clamped * 255) / 100
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            Log.i(TAG, "Screen brightness set to $clamped% ($brightnessValue/255)")
        } catch (e: Throwable) {
            Log.w(TAG, "Could not adjust screen brightness: ${e.message}")
        }
    }

    fun setAutoBrightness(enabled: Boolean) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !android.provider.Settings.System.canWrite(context)) {
                return
            }
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
            Log.i(TAG, "Auto brightness mode set to $enabled")
        } catch (e: Throwable) {
            Log.w(TAG, "Could not adjust auto brightness: ${e.message}")
        }
    }

    fun setStreamVolume(streamType: Int, percent: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val targetVol = ((percent.coerceIn(0, 100) * maxVol) / 100).coerceIn(0, maxVol)
            audioManager.setStreamVolume(streamType, targetVol, 0)
            Log.i(TAG, "Audio stream $streamType volume set to $percent% ($targetVol/$maxVol)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set audio stream volume", e)
        }
    }

    fun triggerAlarmSound() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.setStreamVolume(
                android.media.AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM),
                0
            )
            val alarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            val ringtone = android.media.RingtoneManager.getRingtone(context, alarmUri)
            ringtone?.play()
            Log.w(TAG, "🚨 High-decibel alarm siren triggered on device!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play alarm sound", e)
        }
    }

    fun lockScreenNow() {
        try {
            Log.i(TAG, "Executing immediate DPM lock...")
            dpm.lockNow()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock screen", e)
        }
    }

    fun unlockDevice() {
        try {
            if (isDeviceOwner()) {
                dpm.setDeviceOwnerLockScreenInfo(admin, null)
            }
            Log.i(TAG, "Device unlocked and lock screen info cleared.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unlock device", e)
        }
    }

    fun wipeDevice(wipeSdCard: Boolean = true) {
        try {
            Log.w(TAG, "🚨 Executing ENTERPRISE DPM WIPE...")
            val flags = if (wipeSdCard) DevicePolicyManager.WIPE_EXTERNAL_STORAGE else 0
            dpm.wipeData(flags)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute wipe", e)
        }
    }

    fun silentUninstall(packageName: String): Boolean {
        try {
            if (packageName.isBlank()) {
                Log.e(TAG, "Cannot uninstall: Package name is blank.")
                return false
            }

            // 1. Guard MDM Core package against accidental removal
            if (packageName == context.packageName || packageName.startsWith("com.rrv.mdm")) {
                Log.e(TAG, "🚨 BLOCKED: Attempt to uninstall MDM Core package [$packageName] rejected.")
                return false
            }

            val pm = context.packageManager
            val appInfo = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Package $packageName not installed on device.")
                return false
            }

            // 2. Guard Pre-Installed OEM/System Apps (Non-removable system partition binaries)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) {
                Log.e(TAG, "🚫 APPLICATION_NOT_UNINSTALLABLE: Package $packageName is a pre-installed system app and cannot be uninstalled. Use HIDE or BLOCK policy actions instead.")
                return false
            }

            if (isDeviceOwner()) {
                val packageInstaller = pm.packageInstaller
                val intent = android.content.Intent("com.rrv.mdm.dpc.UNINSTALL_COMPLETE")
                val pendingIntent = android.app.PendingIntent.getBroadcast(
                    context, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                packageInstaller.uninstall(packageName, pendingIntent.intentSender)
                Log.i(TAG, "✓ Silent uninstall initiated for $packageName")
                return true
            } else {
                Log.w(TAG, "Cannot silent uninstall: Agent is not Device Owner.")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to uninstall package $packageName", e)
            return false
        }
    }

    fun rebootDevice() {
        try {
            if (isDeviceOwner()) {
                Log.i(TAG, "Executing Device Owner reboot...")
                dpm.reboot(admin)
            } else {
                Log.w(TAG, "Cannot reboot: Agent is not Device Owner.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reboot device", e)
        }
    }

    @Suppress("DEPRECATION")
    fun resetPassword(newPin: String): Boolean {
        return try {
            if (isDeviceOwner() || isAdminActive()) {
                Log.i(TAG, "Executing remote password reset...")
                val success = dpm.resetPassword(newPin, DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY)
                if (success) {
                    lockScreenNow()
                }
                success
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset password: ${e.message}", e)
            false
        }
    }

    fun enableLostMode(message: String?, phone: String?) {
        try {
            if (isDeviceOwner()) {
                val lockInfo = buildString {
                    append(message ?: "This device is managed by IT and marked as lost.")
                    if (!phone.isNullOrBlank()) {
                        append("\nPlease call: ").append(phone)
                    }
                }
                dpm.setDeviceOwnerLockScreenInfo(admin, lockInfo)
            }
            lockScreenNow()
            Log.i(TAG, "Lost mode enabled.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable lost mode", e)
        }
    }

    fun disableLostMode() {
        unlockDevice()
    }

    fun requestBugreport(): Boolean {
        return try {
            if (isDeviceOwner()) {
                Log.i(TAG, "Requesting device bugreport...")
                dpm.requestBugreport(admin)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request bugreport: ${e.message}", e)
            false
        }
    }
}
