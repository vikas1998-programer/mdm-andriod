package com.rrv.mdm.dpc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.util.RrvLog

/**
 * Listens for Android system package install/uninstall broadcasts and
 * publishes structured app_event messages to the MQTT broker.
 *
 * Published to: rrv/devices/{deviceId}/app_events (QoS 1)
 * {
 *   "event":       "APP_INSTALLED" | "APP_UNINSTALLED" | "APP_UPDATED",
 *   "packageName": "com.example.app",
 *   "versionCode": 42,
 *   "versionName": "3.1.0",
 *   "installer":   "MDM_SILENT_PUSH" | "USER" | "PLAY_STORE" | "SYSTEM",
 *   "timestamp":   1723730538000
 * }
 *
 * Register this in AndroidManifest.xml OR call registerDynamically() in Application.onCreate().
 */
class AppEventPublisher : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppEventPublisher"

        /** Call from Application.onCreate() to register programmatically */
        fun registerDynamically(context: Context): AppEventPublisher {
            val receiver = AppEventPublisher()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            context.registerReceiver(receiver, filter)
            RrvLog.i(TAG, "✅ AppEventPublisher registered for package install/uninstall broadcasts")
            return receiver
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        val event: String = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED    -> if (isReplacing) "APP_UPDATED" else "APP_INSTALLED"
            Intent.ACTION_PACKAGE_REPLACED -> "APP_UPDATED"
            Intent.ACTION_PACKAGE_REMOVED  -> if (isReplacing) return else "APP_UNINSTALLED"
            else -> return
        }

        RrvLog.i(TAG, "📦 Package event: $event pkg=$packageName")

        val app = context.applicationContext as? RrvMdmApplication ?: return
        val mqttManager = app.mqttManager
        val repository  = app.repository

        // Enforce Strict DEFAULT DENY for newly installed packages
        if (intent.action == Intent.ACTION_PACKAGE_ADDED) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val admin = com.rrv.mdm.dpc.receiver.RrvDeviceAdminReceiver.getComponentName(context)
                val activePolicy = repository.getActivePolicy()
                val isApproved = activePolicy.applications.any { 
                    it.packageName == packageName && it.installType.uppercase() in listOf("VISIBLE", "ALLOWED", "REQUIRED", "MANAGED", "FORCE_INSTALLED") 
                } || activePolicy.allowedKioskPackages.contains(packageName)

                if (!isApproved) {
                    try {
                        dpm.setApplicationHidden(admin, packageName, true)
                        dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
                        RrvLog.w(TAG, "🚫 Newly installed package '$packageName' is NOT in approved server policy. Enforced DEFAULT DENY (Hidden & Suspended).")
                    } catch (e: Exception) {
                        RrvLog.e(TAG, "Error applying default deny on $packageName", e)
                    }
                }
            }
        }

        // Determine installer source
        val installerSource = try {
            @Suppress("DEPRECATION")
            val installerPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                context.packageManager.getInstallerPackageName(packageName)
            }
            when {
                installerPkg == null -> "SYSTEM"
                installerPkg.contains("rrv.mdm") -> "MDM_SILENT_PUSH"
                installerPkg.contains("android.vending") -> "PLAY_STORE"
                else -> "USER"
            }
        } catch (e: Exception) { "UNKNOWN" }

        // Get version info
        val (versionCode, versionName) = try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
            Pair(code, info.versionName ?: "unknown")
        } catch (e: Exception) { Pair(0, "unknown") }

        // Get app title
        val appTitle = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) { packageName }

        val deviceId = repository.deviceId
        val topic = "rrv/devices/$deviceId/app_events"
        val payload = """
            {
              "event": "$event",
              "packageName": "$packageName",
              "appTitle": "$appTitle",
              "versionCode": $versionCode,
              "versionName": "$versionName",
              "installer": "$installerSource",
              "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        mqttManager.publishRaw(topic, payload, qos = 1, retained = false)
        RrvLog.d(TAG, "✅ app_event published: $event $packageName v$versionName source=$installerSource")
    }
}
