package com.rrv.mdm.dpc.mdm.device

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

    /**
     * Apply comprehensive Zero-Trust Policy onto device hardware and system.
     * Default-blocks and hides all unmanaged apps on the device (Alarm, Notes, etc.).
     * Only approved/whitelisted apps become visible and runnable.
     */
    fun applyPolicy(policy: PolicyPayload) {
        if (!isDeviceOwner()) {
            RrvLog.w(TAG, "Cannot enforce DPM policies: Not Device Owner.")
            return
        }

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

            // 5. Ensure Home Launcher is registered
            setAsDefaultHomeLauncher()

            // 6. Zero-Trust Application Package Governance (Default-Hide & OS-Level Suspension)
            val pm = context.packageManager
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager

            val installedAppPkgs = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    .map { it.packageName }
            } catch (_: Exception) {
                pm.getInstalledApplications(0).map { it.packageName }
            }

            val launcherAppPkgs = try {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    PackageManager.MATCH_ALL
                ).mapNotNull { it.activityInfo?.packageName }
            } catch (_: Exception) { emptyList() }

            val allDiscoveredPkgs = (installedAppPkgs + launcherAppPkgs).toSet()

            val explicitlyAllowed = policy.applications
                .filter { it.installType.uppercase() in listOf("SHOW", "VISIBLE", "INSTALL", "FORCE_INSTALLED", "AVAILABLE", "ALLOWED", "REQUIRED", "MANAGED") }
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
                    if (isInstalled) {
                        dpm.setApplicationHidden(adminComponent, pkg, true)
                        dpm.setPackagesSuspended(adminComponent, arrayOf(pkg), true)
                        am?.killBackgroundProcesses(pkg)
                        RrvLog.i(TAG, "🗑️ Enforced UNINSTALL/Removal policy on package $pkg")
                    }
                } catch (e: Exception) {
                    RrvLog.e(TAG, "Failed to uninstall/remove $pkg", e)
                }
            }

            // Strict Zero-Trust Default Deny: Whitelist contains only administrator-approved apps
            var allowedCount = 0
            var blockedCount = 0

            for (pkg in allDiscoveredPkgs) {
                if (CRITICAL_SYSTEM_PACKAGES.contains(pkg) || pkg == context.packageName) continue

                val isApproved = whitelisted.contains(pkg)
                try {
                    if (isApproved) {
                        try { dpm.setApplicationHidden(adminComponent, pkg, false) } catch (_: Exception) {}
                        try { dpm.setPackagesSuspended(adminComponent, arrayOf(pkg), false) } catch (_: Exception) {}
                        allowedCount++
                    } else {
                        // Strict Zero-Trust: Hide and Suspend unapproved apps (Alarm, Clock, Chrome, Settings, etc.)
                        try { dpm.setApplicationHidden(adminComponent, pkg, true) } catch (_: Exception) {}
                        try { dpm.setPackagesSuspended(adminComponent, arrayOf(pkg), true) } catch (_: Exception) {}
                        try { am?.killBackgroundProcesses(pkg) } catch (_: Exception) {}
                        blockedCount++
                    }
                } catch (e: Exception) {
                    // System protection or unsupported package
                }
            }

            // 7. Configure LockTask allowlist for Overview / Recents Containment
            val lockTaskPackages = (whitelisted + context.packageName).distinct().toTypedArray()
            dpm.setLockTaskPackages(adminComponent, lockTaskPackages)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                dpm.setLockTaskFeatures(adminComponent, features)
            }

            // 8. Update Local Database with Whitelisted Apps
            val app = context.applicationContext as? RrvMdmApplication
            if (app != null) {
                CoroutineScope(Dispatchers.IO).launch {
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
            }

            RrvLog.i(TAG, "🛡️ Zero-Trust App Governance: $allowedCount apps ALLOWED/VISIBLE, $blockedCount apps BLOCKED/HIDDEN.")
            RrvLog.i(TAG, "✅ Zero-Trust Policy [${policy.name}] successfully applied via DPM.")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Error applying DPM policy", e)
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
