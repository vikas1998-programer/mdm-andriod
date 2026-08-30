package com.rrv.mdm.dpc.policy

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.rrv.mdm.dpc.receiver.RrvDeviceAdminReceiver
import com.rrv.mdm.dpc.ui.home.RrvMdmHomeActivity

/**
 * Manages Android LockTask Mode (COSU Kiosk pinning) & System Keyguard suppression.
 */
class LockTaskController(private val context: Context) {

    companion object {
        private const val TAG = "LockTaskController"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = RrvDeviceAdminReceiver.getComponentName(context)

    fun setupKioskPackages(packages: List<String>, allowSystemInfo: Boolean = true, allowNotifications: Boolean = false) {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return

        try {
            val allWhitelisted = (packages + context.packageName).distinct().toTypedArray()
            dpm.setLockTaskPackages(admin, allWhitelisted)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                var features = DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
                if (allowSystemInfo) {
                    features = features or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                }
                if (allowNotifications) {
                    features = features or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                }
                dpm.setLockTaskFeatures(admin, features)
            }
            Log.i(TAG, "Kiosk LockTask packages configured: ${allWhitelisted.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set LockTask packages", e)
        }
    }

    fun startKioskLock(activity: Activity) {
        try {
            Log.i(TAG, "Pinning activity ${activity.localClassName} into LockTask Mode...")
            setAsDefaultHomeLauncher()
            setupKioskPackages(listOf(context.packageName))
            activity.startLockTask()
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter LockTask mode", e)
        }
    }

    fun setAsDefaultHomeLauncher() {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        try {
            val component = ComponentName(context, RrvMdmHomeActivity::class.java)
            context.packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, component)
            
            // Prevent user from uninstalling or clearing the MDM agent
            try {
                dpm.setUninstallBlocked(admin, context.packageName, true)
            } catch (_: Exception) {}

            Log.i(TAG, "✓ Set RrvMdmHomeActivity as permanent default Home launcher")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set default home launcher", e)
        }
    }

    fun clearDefaultHomeLauncher() {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            Log.i(TAG, "✓ Cleared default Home launcher")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear default home launcher", e)
        }
    }

    fun stopKioskLock(activity: Activity) {
        try {
            Log.i(TAG, "Stopping LockTask Mode...")
            activity.stopLockTask()
        } catch (e: Exception) {
            Log.e(TAG, "Could not stop LockTask mode", e)
        }
    }
}
