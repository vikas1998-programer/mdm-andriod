package com.rrv.mdm.dpc.receiver

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.ui.kiosk.KioskLauncherActivity

/**
 * Primary Android Enterprise Device Policy Controller (DPC) Receiver.
 * Intercepts Zero-Touch, QR Provisioning, and Hardware Lifecycle Events.
 */
class RrvDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "RrvDeviceAdminReceiver"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, RrvDeviceAdminReceiver::class.java)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.rrv.mdm.ACTION_UNENROLL") {
            Log.w(TAG, "⚠️ Received ACTION_UNENROLL broadcast. Clearing Device Owner and Device Admin privileges...")
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            try {
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.clearDeviceOwnerApp(context.packageName)
                    Log.i(TAG, "✓ Device Owner cleared successfully.")
                }
                dpm.removeActiveAdmin(getComponentName(context))
                Log.i(TAG, "✓ Active Admin removed successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing Device Owner", e)
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "🛡️ RRV MDM Device Admin Enabled successfully.")
        val app = context.applicationContext as RrvMdmApplication
        if (app.policyManager.isDeviceOwner()) {
            app.lockTaskController.setAsDefaultHomeLauncher()
            app.policyManager.enforceBaselineSecurity()
        }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "🎉 Profile / Device Provisioning Complete. Initializing zero-trust hardware attestation...")

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = getComponentName(context)

        // Read QR code / Zero-Touch provisioning extras bundle if supplied
        val extras: PersistableBundle? = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        if (extras != null) {
            val serverUrl = extras.getString("server_url", "https://mdm.rrvsoftware.com")
            val token = extras.getString("enroll_token", "")
            val repo = (context.applicationContext as RrvMdmApplication).repository
            repo.serverUrl = serverUrl
            repo.enrollmentToken = token
            Log.i(TAG, "Provisioning bundle parsed: Server = $serverUrl")

            if (!token.isNullOrBlank()) {
                val apiClient = com.rrv.mdm.dpc.network.MdmApiClient(context)
                apiClient.enrollDevice(serverUrl, token) { success, message ->
                    if (success) {
                        Log.i(TAG, "✓ Zero-Touch automatic device enrollment succeeded.")
                        (context.applicationContext as RrvMdmApplication).mqttManager.connect()
                    } else {
                        Log.e(TAG, "✕ Zero-Touch enrollment error: $message")
                    }
                }
            } else {
                repo.isEnrolled = true
                (context.applicationContext as RrvMdmApplication).mqttManager.connect()
            }
        } else {
            (context.applicationContext as RrvMdmApplication).mqttManager.connect()
        }

        // Enable Kiosk Launcher as Default Home Intent
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            Log.i(TAG, "App is confirmed DEVICE OWNER. Enforcing baseline zero-trust restrictions...")
            val app = context.applicationContext as RrvMdmApplication
            app.lockTaskController.setAsDefaultHomeLauncher()
            app.policyManager.enforceBaselineSecurity()

            val launcherIntent = Intent(context, com.rrv.mdm.dpc.ui.home.RrvMdmHomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launcherIntent)
        }
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.i(TAG, "🔒 Entered Kiosk LockTask mode for package: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.w(TAG, "⚠️ Exited Kiosk LockTask mode. Checking policy containment...")
        val repo = (context.applicationContext as RrvMdmApplication).repository
        val activePolicy = repo.getActivePolicy()
        if (activePolicy.kioskModeEnabled) {
            // Re-pin into Kiosk
            val launchIntent = Intent(context, KioskLauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
        }
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "🚨 Password failure event intercepted. Reporting to MDM MQTT telemetry...")
        (context.applicationContext as RrvMdmApplication).mqttManager.publishSecurityAlert("PASSWORD_FAILED", "Incorrect PIN/password entered on device.")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.i(TAG, "✓ Device successfully unlocked by authorized operator.")
    }
}
