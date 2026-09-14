package com.rrv.mdm.dpc.mdm.device

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
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
    val admin: ComponentName get() = adminComponent
    val devicePolicyManager: DevicePolicyManager get() = dpm

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)
    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    /**
     * Programmatically grant all required runtime permissions for the MDM DPC app.
     */
    fun grantDpcRuntimePermissions() {
        if (!isDeviceOwner()) return
        val permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_NOTIFICATION_POLICY,
            android.Manifest.permission.WRITE_SETTINGS
        )
        for (perm in permissions) {
            try {
                dpm.setPermissionGrantState(adminComponent, context.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (e: Exception) {
                RrvLog.w(TAG, "Could not auto-grant DPC permission $perm: ${e.message}")
            }
        }
        try {
            dpm.setPermissionPolicy(adminComponent, DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT)
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not set auto-grant permission policy: ${e.message}")
        }

        // Auto-grant AppOps for WRITE_SETTINGS & SYSTEM_ALERT_WINDOW via reflection
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            if (appOps != null) {
                val uid = context.packageManager.getApplicationInfo(context.packageName, 0).uid
                val setMode = appOps.javaClass.getMethod("setMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                // OP_WRITE_SETTINGS = 23, OP_SYSTEM_ALERT_WINDOW = 24
                setMode.invoke(appOps, 23, uid, context.packageName, android.app.AppOpsManager.MODE_ALLOWED)
                setMode.invoke(appOps, 24, uid, context.packageName, android.app.AppOpsManager.MODE_ALLOWED)
                RrvLog.i(TAG, "🛡️ MDM DPC AppOps granted (WRITE_SETTINGS & SYSTEM_ALERT_WINDOW)")
            }
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not invoke AppOpsManager.setMode: ${e.message}")
        }
    }

    /**
     * Baseline lockdown on device enrollment or system boot.
     * Enforces anti-uninstall, anti-factory reset, and binds the enterprise launcher.
     */
    fun enforceBaselineSecurity() {
        if (!isAdminActive()) return
        try {
            if (isDeviceOwner()) {
                // 0. Auto-grant all runtime permissions to MDM DPC
                grantDpcRuntimePermissions()

                // 1. Prevent uninstalling or clearing MDM
                try {
                    dpm.setUninstallBlocked(adminComponent, context.packageName, true)
                } catch (e: Exception) {
                    RrvLog.w(TAG, "Could not set uninstall blocked: ${e.message}")
                }

                // 2. Lock down system tamper vectors
                setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, true)
                setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, true)
                setUserRestriction(UserManager.DISALLOW_ADD_USER, true)
                setUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, true)
                setUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS, true)

                // 3. Set persistent enterprise home launcher
                setAsDefaultHomeLauncher()

                // Keep USB debugging accessible for enterprise ADB diagnostics
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
                RrvLog.i(TAG, "✅ Baseline Device Owner security enforced: Anti-Uninstall, Anti-Exit, Safe Boot & Home Lock.")
            }
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to apply baseline restrictions", e)
        }
    }

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

    /**
     * Clear persistent preferred Home Activity.
     */
    fun clearDefaultHomeLauncher() {
        if (!isDeviceOwner()) return
        try {
            dpm.clearPackagePersistentPreferredActivities(adminComponent, context.packageName)
            RrvLog.i(TAG, "✓ Cleared default Home launcher")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to clear default home launcher", e)
        }
    }

    /**
     * Configure LockTask whitelist for COSU Kiosk mode.
     */
    fun setupKioskPackages(packages: List<String>, allowSystemInfo: Boolean = true, allowNotifications: Boolean = false) {
        if (!isDeviceOwner()) return
        try {
            val allWhitelisted = (packages + context.packageName).distinct().toTypedArray()
            dpm.setLockTaskPackages(adminComponent, allWhitelisted)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                var features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
                if (allowSystemInfo) {
                    features = features or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                }
                if (allowNotifications) {
                    features = features or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                }
                dpm.setLockTaskFeatures(adminComponent, features)
            }
            RrvLog.i(TAG, "Kiosk LockTask packages configured: ${allWhitelisted.joinToString()}")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to set LockTask packages", e)
        }
    }

    /**
     * Pin the activity into LockTask mode.
     */
    fun startKioskLock(activity: Activity) {
        try {
            RrvLog.i(TAG, "Pinning activity ${activity.localClassName} into LockTask Mode...")
            setAsDefaultHomeLauncher()
            setupKioskPackages(listOf(context.packageName))
            activity.startLockTask()
        } catch (e: Exception) {
            RrvLog.e(TAG, "Could not enter LockTask mode", e)
        }
    }

    /**
     * Stop and unpin LockTask mode.
     */
    fun stopKioskLock(activity: Activity) {
        try {
            RrvLog.i(TAG, "Stopping LockTask Mode...")
            activity.stopLockTask()
        } catch (e: Exception) {
            RrvLog.e(TAG, "Could not stop LockTask mode", e)
        }
    }

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

        RrvLog.i(TAG, "Enforcing policy profile '${policy.name}' (Version: ${policy.version}) directly to DPM hardware & system controls...")

        CoroutineScope(Dispatchers.Default).launch {
            try {
                // 1. Hardware Restrictions
                dpm.setCameraDisabled(adminComponent, policy.cameraDisabled)
                dpm.setScreenCaptureDisabled(adminComponent, policy.screenCaptureDisabled)
                setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, policy.usbDataTransferDisabled)
                setUserRestriction(UserManager.DISALLOW_BLUETOOTH, policy.bluetoothDisabled)
                setUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH, policy.bluetoothDisabled)
                setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, policy.sdCardDisabled)
                setUserRestriction(UserManager.DISALLOW_UNMUTE_MICROPHONE, policy.microphoneDisabled)
                (context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMicrophoneMute = policy.microphoneDisabled
                try {
                    dpm.setStatusBarDisabled(adminComponent, policy.statusBarDisabled)
                } catch (_: Exception) {}

                // 2. Network Restrictions & Corporate Wi-Fi Governance
                setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, policy.wifiConfigLock)
                setUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING, policy.tetheringDisabled)
                setUserRestriction(UserManager.DISALLOW_DATA_ROAMING, policy.dataRoamingDisabled)
                setUserRestriction(UserManager.DISALLOW_AIRPLANE_MODE, policy.airplaneModeDisabled)
                configureCorporateWifi(policy)

                // Always-On VPN
                try {
                    if (!policy.alwaysOnVpnPackage.isNullOrBlank()) {
                        dpm.setAlwaysOnVpnPackage(adminComponent, policy.alwaysOnVpnPackage, true)
                    } else {
                        dpm.setAlwaysOnVpnPackage(adminComponent, null, false)
                    }
                } catch (e: Exception) {
                    RrvLog.w(TAG, "AlwaysOnVpnPackage configuration skipped: ${e.message}")
                }

                // 3. Anti-Tamper & Security System Controls
                setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, policy.factoryResetDisabled)
                setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, policy.safeBootDisabled)
                setUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, policy.developerOptionsDisabled)
                setUserRestriction(UserManager.DISALLOW_ADD_USER, true)
                setUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, true)
                setUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS, policy.appUninstallDisabled)
                setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, policy.unknownSourcesDisabled)
                setUserRestriction(UserManager.DISALLOW_PRINTING, policy.printingDisabled)
                setUserRestriction(UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE, policy.clipboardDlpDisabled)
                setUserRestriction(UserManager.DISALLOW_APPS_CONTROL, true) // Block Settings -> Apps bypass/modifications

                // Auto-Time Enforced
                try {
                    dpm.setAutoTimeRequired(adminComponent, policy.autoTimeEnforced)
                } catch (e: Exception) {
                    RrvLog.w(TAG, "setAutoTimeRequired error: ${e.message}")
                }

                // Storage Encryption
                if (policy.externalStorageEncryptionRequired) {
                    try {
                        dpm.setStorageEncryption(adminComponent, true)
                    } catch (e: Exception) {
                        RrvLog.w(TAG, "setStorageEncryption error: ${e.message}")
                    }
                }

                // 3.5 Password Complexity Governance
                if (policy.minPasswordLength > 0 || policy.passwordQuality.isNotBlank()) {
                    try {
                        val quality = when (policy.passwordQuality.uppercase()) {
                            "COMPLEX", "ALPHANUMERIC_COMPLEX" -> DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
                            "ALPHANUMERIC" -> DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                            "NUMERIC_COMPLEX" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
                            "NUMERIC" -> DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                            "SOMETHING", "WEAK_BIOMETRIC" -> DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
                            "UNSPECIFIED" -> DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED
                            else -> DevicePolicyManager.PASSWORD_QUALITY_COMPLEX
                        }
                        @Suppress("DEPRECATION")
                        dpm.setPasswordQuality(adminComponent, quality)
                        if (policy.minPasswordLength > 0) {
                            @Suppress("DEPRECATION")
                            dpm.setPasswordMinimumLength(adminComponent, policy.minPasswordLength)
                        }
                        if (policy.maxFailedAttempts > 0) {
                            dpm.setMaximumFailedPasswordsForWipe(adminComponent, policy.maxFailedAttempts)
                        }
                    } catch (e: Exception) {
                        RrvLog.e(TAG, "Error applying password complexity policy: ${e.message}")
                    }
                }

                // Keyguard Features (Camera & Notifications)
                var keyguardFlags = 0
                if (policy.keyguardCameraDisabled) {
                    keyguardFlags = keyguardFlags or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
                }
                if (policy.keyguardNotificationsDisabled) {
                    keyguardFlags = keyguardFlags or DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS or DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS
                }
                try {
                    dpm.setKeyguardDisabledFeatures(adminComponent, keyguardFlags)
                } catch (e: Exception) {
                    RrvLog.e(TAG, "Error setting keyguard disabled features: ${e.message}")
                }

                // System Update Policy
                try {
                    when (policy.systemUpdatePolicy.uppercase()) {
                        "AUTOMATIC", "AUTO" -> {
                            dpm.setSystemUpdatePolicy(adminComponent, SystemUpdatePolicy.createAutomaticInstallPolicy())
                        }
                        "WINDOWED" -> {
                            dpm.setSystemUpdatePolicy(adminComponent, SystemUpdatePolicy.createWindowedInstallPolicy(120, 360))
                        }
                        "POSTPONE", "POSTPONED" -> {
                            dpm.setSystemUpdatePolicy(adminComponent, SystemUpdatePolicy.createPostponeInstallPolicy())
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    RrvLog.w(TAG, "SystemUpdatePolicy not supported: ${e.message}")
                }

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUserRestriction(UserManager.DISALLOW_CONFIG_BRIGHTNESS, true)
                }

                // 6. Audio & Volume Level Governance
                if (!policy.masterVolumeMuted) {
                    setMasterVolumeMuted(false)
                    policy.mediaVolumePercent?.let { setStreamVolumePercent(android.media.AudioManager.STREAM_MUSIC, it) }
                    policy.alarmVolumePercent?.let { setStreamVolumePercent(android.media.AudioManager.STREAM_ALARM, it) }
                    policy.ringVolumePercent?.let {
                        setStreamVolumePercent(android.media.AudioManager.STREAM_RING, it)
                        setStreamVolumePercent(android.media.AudioManager.STREAM_NOTIFICATION, it)
                        setStreamVolumePercent(android.media.AudioManager.STREAM_SYSTEM, it)
                    }
                } else {
                    setMasterVolumeMuted(true)
                }
                val volumeLocked = policy.volumeAdjustDisabled || policy.masterVolumeMuted
                setVolumeAdjustDisabled(volumeLocked)

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

                val explicitlyHidden = policy.applications
                    .filter { it.installType.uppercase() in listOf("HIDE", "HIDDEN", "DISABLED") }
                    .map { it.packageName }
                    .toSet()

                val explicitlyBlocked = policy.applications
                    .filter { it.installType.uppercase() in listOf("BLOCK", "BLOCKED", "RESTRICTED", "UNINSTALL", "REMOVED") }
                    .map { it.packageName }
                    .toSet()

                val explicitlyUninstalled = policy.applications
                    .filter { it.installType.uppercase() in listOf("UNINSTALL", "REMOVED") }
                    .map { it.packageName }
                    .toSet()

                val allowedKiosk = policy.allowedKioskPackages.toSet()
                // Whitelist must strictly exclude explicitly hidden and blocked apps
                val whitelisted = (explicitlyAllowed + allowedKiosk).filterNot { explicitlyHidden.contains(it) || explicitlyBlocked.contains(it) }.toMutableSet()
                val isGovernanceActive = whitelisted.isNotEmpty() || explicitlyHidden.isNotEmpty() || explicitlyBlocked.isNotEmpty()

                // Handle explicit uninstallation / removal requests
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

                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launchablePackages = try {
                    pm.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }.toSet()
                } catch (_: Exception) {
                    emptySet()
                }

                var allowedCount = 0
                var blockedCount = 0
                val userAppsToSuspend = mutableListOf<String>()
                val userAppsToUnsuspend = mutableListOf<String>()

                for (appInfo in allInstalledApps) {
                    val pkg = appInfo.packageName
                    if (pkg == context.packageName || CRITICAL_SYSTEM_PACKAGES.contains(pkg)) continue

                    val isOverlayOrTheme = pkg.contains("overlay", ignoreCase = true) ||
                            pkg.contains("rro", ignoreCase = true) ||
                            pkg.contains("theme", ignoreCase = true) ||
                            pkg.startsWith("com.samsung.internal") ||
                            pkg.startsWith("com.android.internal")
                    if (isOverlayOrTheme) continue

                    val isUserApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                    val isLaunchable = launchablePackages.contains(pkg)

                    // Only manage packages that are launchable by the user, user-installed, or explicitly governed
                    if (!isLaunchable && !isUserApp && !whitelisted.contains(pkg) && !explicitlyUninstalled.contains(pkg) && !explicitlyHidden.contains(pkg) && !explicitlyBlocked.contains(pkg)) {
                        continue
                    }

                    if (explicitlyHidden.contains(pkg)) {
                        // Explicitly hidden package — hide from launcher/system
                        try { dpm.setApplicationHidden(adminComponent, pkg, true) } catch (_: Exception) {}
                        userAppsToSuspend.add(pkg)
                        am?.killBackgroundProcesses(pkg)
                        blockedCount++
                    } else if (explicitlyBlocked.contains(pkg)) {
                        // Explicitly blocked/restricted package — hide and suspend
                        try { dpm.setApplicationHidden(adminComponent, pkg, true) } catch (_: Exception) {}
                        userAppsToSuspend.add(pkg)
                        am?.killBackgroundProcesses(pkg)
                        blockedCount++
                    } else if (whitelisted.contains(pkg)) {
                        // Whitelisted application — Enable, Unhide & Unsuspend
                        try { dpm.enableSystemApp(adminComponent, pkg) } catch (_: Exception) {}
                        try { dpm.setApplicationHidden(adminComponent, pkg, false) } catch (_: Exception) {}
                        userAppsToUnsuspend.add(pkg)
                        allowedCount++
                    } else if (isGovernanceActive) {
                        // Non-whitelisted application — Suspend & Hide completely from system/launcher/settings
                        try { dpm.setApplicationHidden(adminComponent, pkg, true) } catch (_: Exception) {}
                        userAppsToSuspend.add(pkg)
                        am?.killBackgroundProcesses(pkg)
                        blockedCount++
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

                // 11. Auto-Download and Silently Install Mandatory/Force-Installed Apps via REST API
                for (appPolicy in policy.applications) {
                    val isAutoInstall = appPolicy.installType.uppercase() in listOf(
                        "FORCE_INSTALLED", "MANDATORY_SILENT", "MANAGED_SILENT", "AUTO_INSTALL", "MANDATORY", "INSTALL", "REQUIRED"
                    )
                    if (!isAutoInstall || appPolicy.packageName.isBlank() || appPolicy.packageName == context.packageName) continue

                    val isInstalledAndUpToDate = try {
                        val pInfo = pm.getPackageInfo(appPolicy.packageName, 0)
                        val installedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pInfo.versionCode
                        }
                        installedVersion >= appPolicy.versionCode
                    } catch (_: Exception) {
                        false
                    }

                    if (!isInstalledAndUpToDate) {
                        val targetDownloadUrl = appPolicy.downloadUrl.takeIf { !it.isNullOrBlank() }
                            ?: if (!appPolicy.appId.isNullOrBlank()) "/api/v1/apps/${appPolicy.appId}/download" else ""

                        if (targetDownloadUrl.isNotBlank()) {
                            RrvLog.i(TAG, "📦 Auto-enqueuing silent installation for ${appPolicy.packageName} (v${appPolicy.versionCode}) from policy.")
                            val cmdId = "policy-app-${appPolicy.packageName}-${appPolicy.versionCode}-${System.currentTimeMillis()}"
                            com.rrv.mdm.dpc.worker.ApkDownloadWorker.enqueue(
                                context,
                                cmdId,
                                appPolicy.appId ?: "",
                                appPolicy.packageName,
                                appPolicy.title.ifBlank { appPolicy.packageName },
                                targetDownloadUrl,
                                appPolicy.sha256 ?: "",
                                appPolicy.versionCode,
                                appPolicy.versionName,
                                appPolicy.managedConfigJson ?: "{}"
                            )
                        }
                    }
                }

                RrvLog.i(TAG, "🛡️ Zero-Trust App Governance: $allowedCount apps ALLOWED/VISIBLE, $blockedCount apps BLOCKED/HIDDEN.")
                RrvLog.i(TAG, "✅ Zero-Trust Policy [${policy.name}] successfully applied via DPM.")
            } catch (e: Exception) {
                RrvLog.e(TAG, "Error applying DPM policy", e)
            }
        }
    }

    /**
     * Enterprise Wi-Fi Auto-Configuration and Hardware Blocking Engine.
     */
    private fun configureCorporateWifi(policy: PolicyPayload) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return

            // 1. If Wi-Fi is explicitly disabled/blocked by policy
            if (policy.wifiDisabled) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = false
                RrvLog.w(TAG, "🚫 Wi-Fi disabled/blocked on hardware by Enterprise Policy.")
                return
            }

            // 2. Auto-configure and connect to corporate Wi-Fi credentials if provided
            if (!policy.wifiSsid.isNullOrBlank() && policy.wifiAutoConnect) {
                val ssid = policy.wifiSsid.trim()
                val password = policy.wifiPassword ?: ""
                val security = (policy.wifiSecurityType ?: "WPA").uppercase()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val suggestionBuilder = android.net.wifi.WifiNetworkSuggestion.Builder()
                        .setSsid(ssid)
                        .setIsAppInteractionRequired(false)
                        .setIsUserInteractionRequired(false)

                    when {
                        security.contains("WPA3") -> {
                            if (password.isNotBlank()) suggestionBuilder.setWpa3Passphrase(password)
                        }
                        security.contains("WPA") || security.contains("PSK") -> {
                            if (password.isNotBlank()) suggestionBuilder.setWpa2Passphrase(password)
                        }
                        security == "OPEN" || security == "NONE" -> {
                            // Open network
                        }
                        else -> {
                            if (password.isNotBlank()) suggestionBuilder.setWpa2Passphrase(password)
                        }
                    }

                    val suggestions = listOf(suggestionBuilder.build())
                    val status = wifiManager.addNetworkSuggestions(suggestions)
                    RrvLog.i(TAG, "📶 Corporate Wi-Fi '$ssid' configured via WifiNetworkSuggestion (Status: $status)")
                } else {
                    @Suppress("DEPRECATION")
                    val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                        SSID = "\"$ssid\""
                        if (password.isNotBlank()) {
                            preSharedKey = "\"$password\""
                        } else {
                            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                        }
                    }
                    @Suppress("DEPRECATION")
                    val netId = wifiManager.addNetwork(wifiConfig)
                    if (netId != -1) {
                        @Suppress("DEPRECATION")
                        wifiManager.enableNetwork(netId, true)
                        @Suppress("DEPRECATION")
                        wifiManager.reconnect()
                        RrvLog.i(TAG, "📶 Connected to legacy Wi-Fi profile: $ssid (NetId: $netId)")
                    }
                }
            }
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to configure corporate Wi-Fi: ${e.message}", e)
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
            try {
                android.provider.Settings.System.putInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                    timeoutMs.toInt()
                )
                RrvLog.i(TAG, "⏰ Screen timeout configured to $seconds s ($timeoutMs ms).")
            } catch (se: Exception) {
                grantDpcRuntimePermissions()
                try {
                    android.provider.Settings.System.putInt(
                        context.contentResolver,
                        android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                        timeoutMs.toInt()
                    )
                    RrvLog.i(TAG, "⏰ Screen timeout configured to $seconds s after re-granting AppOp.")
                } catch (inner: Exception) {
                    RrvLog.w(TAG, "Could not set screen timeout in Settings.System: ${inner.message}")
                }
            }
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not set screen timeout to $seconds s: ${e.message}")
        }
    }

    /**
     * Set display brightness percentage (0..100%).
     */
    fun setScreenBrightness(percent: Int): Boolean {
        return try {
            val clamped = percent.coerceIn(0, 100)
            val brightnessValue = ((clamped * 255) / 100).coerceIn(1, 255)
            try {
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
            } catch (se: Exception) {
                grantDpcRuntimePermissions()
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
            }
            RrvLog.i(TAG, "🔆 Screen brightness set to $clamped% (raw $brightnessValue/255).")
            true
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not set screen brightness to $percent%: ${e.message}")
            false
        }
    }

    /**
     * Toggle Automatic (Adaptive) Display Brightness mode.
     */
    fun setAutoBrightness(enabled: Boolean): Boolean {
        return try {
            val mode = if (enabled) {
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } else {
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            }
            try {
                android.provider.Settings.System.putInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    mode
                )
            } catch (se: Exception) {
                grantDpcRuntimePermissions()
                android.provider.Settings.System.putInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    mode
                )
            }
            RrvLog.i(TAG, "🔆 Auto-brightness mode configured: $enabled")
            true
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not toggle auto brightness: ${e.message}")
            false
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
            val wasLocked = if (isDeviceOwner()) {
                val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
                val locked = userManager?.hasUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME) == true
                if (locked) {
                    try { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADJUST_VOLUME) } catch (_: Exception) {}
                }
                locked
            } else false

            try {
                if (percent > 0) {
                    if (isDeviceOwner()) {
                        try { dpm.setMasterVolumeMuted(adminComponent, false) } catch (_: Exception) {}
                    }
                    audioManager.adjustStreamVolume(streamType, android.media.AudioManager.ADJUST_UNMUTE, 0)
                    if (streamType == android.media.AudioManager.STREAM_MUSIC) {
                        audioManager.adjustVolume(android.media.AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
                val maxVol = audioManager.getStreamMaxVolume(streamType)
                val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { audioManager.getStreamMinVolume(streamType) } catch (_: Exception) { 0 }
                } else 0
                val target = Math.round(minVol + ((percent.coerceIn(0, 100).toDouble() / 100.0) * (maxVol - minVol))).toInt().coerceIn(minVol, maxVol)
                audioManager.setStreamVolume(streamType, target, 0)
                RrvLog.i(TAG, "🔊 Audio stream $streamType volume set to $percent% (level $target/$maxVol)")
            } finally {
                if (wasLocked && isDeviceOwner()) {
                    try { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADJUST_VOLUME) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to set audio stream $streamType volume to $percent%", e)
        }
    }

    /**
     * Trigger high-decibel Siren / Alarm sound for specified duration in seconds.
     */
    fun triggerAlarmSound(durationSeconds: Int = 10) {
        CoroutineScope(Dispatchers.Default).launch {
            var ringtone: android.media.Ringtone? = null
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (audioManager != null) {
                    val maxAlarm = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                    val maxMusic = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val maxRing = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxAlarm, 0)
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxMusic, 0)
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_RING, maxRing, 0)
                }

                val alarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                    ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI

                ringtone = android.media.RingtoneManager.getRingtone(context, alarmUri)
                if (ringtone != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ringtone.isLooping = true
                        ringtone.volume = 1.0f
                    }
                    ringtone.audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    ringtone.play()
                    RrvLog.w(TAG, "🚨 High-decibel alarm siren sounding for $durationSeconds seconds!")
                }

                kotlinx.coroutines.delay(durationSeconds * 1000L)
            } catch (e: Exception) {
                RrvLog.e(TAG, "Failed to play alarm siren sound", e)
            } finally {
                try {
                    ringtone?.stop()
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
     * Unlock device / clear lockscreen banner.
     */
    fun unlockDevice() {
        try {
            if (isDeviceOwner()) {
                dpm.setDeviceOwnerLockScreenInfo(adminComponent, null)
            }
            RrvLog.i(TAG, "Device unlocked and lock screen info cleared.")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to unlock device", e)
        }
    }

    /**
     * Disable lost mode.
     */
    fun disableLostMode() {
        unlockDevice()
    }

    /**
     * Enterprise Device Owner Wipe / Factory Reset.
     */
    fun wipeDevice(wipeSdCard: Boolean = true): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            RrvLog.w(TAG, "🚨 Executing ENTERPRISE DPM WIPE...")
            val flags = if (wipeSdCard) DevicePolicyManager.WIPE_EXTERNAL_STORAGE else 0
            dpm.wipeData(flags)
            true
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to execute wipe", e)
            false
        }
    }

    /**
     * Silent uninstallation of managed apps (Device Owner).
     */
    fun silentUninstall(packageName: String): Boolean {
        try {
            if (packageName.isBlank()) {
                RrvLog.e(TAG, "Cannot uninstall: Package name is blank.")
                return false
            }

            // 1. Guard MDM Core package against accidental removal
            if (packageName == context.packageName || packageName.startsWith("com.rrv.mdm")) {
                RrvLog.e(TAG, "🚨 BLOCKED: Attempt to uninstall MDM Core package [$packageName] rejected.")
                return false
            }

            val pm = context.packageManager
            val appInfo = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                RrvLog.w(TAG, "Package $packageName not installed on device.")
                return false
            }

            // 2. Guard Pre-Installed OEM/System Apps (Non-removable system partition binaries)
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) {
                RrvLog.e(TAG, "🚫 APPLICATION_NOT_UNINSTALLABLE: Package $packageName is a pre-installed system app.")
                return false
            }

            if (isDeviceOwner()) {
                val packageInstaller = pm.packageInstaller
                val intent = Intent("com.rrv.mdm.dpc.UNINSTALL_COMPLETE").setPackage(context.packageName)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = android.app.PendingIntent.getBroadcast(context, 0, intent, flags)
                packageInstaller.uninstall(packageName, pendingIntent.intentSender)
                RrvLog.i(TAG, "✓ Silent uninstall initiated for $packageName")
                return true
            } else {
                RrvLog.w(TAG, "Cannot silent uninstall: Agent is not Device Owner.")
                return false
            }
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to uninstall package $packageName", e)
            return false
        }
    }

    /**
     * Request device bugreport (Device Owner).
     */
    fun requestBugreport(): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                RrvLog.i(TAG, "Requesting device bugreport...")
                dpm.requestBugreport(adminComponent)
            } else false
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to request bugreport: ${e.message}", e)
            false
        }
    }

    fun setStreamVolume(streamType: Int, percent: Int) = setStreamVolumePercent(streamType, percent)

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
