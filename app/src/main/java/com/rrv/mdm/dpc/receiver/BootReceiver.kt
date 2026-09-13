package com.rrv.mdm.dpc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.geofence.LocationTrackerService
import com.rrv.mdm.dpc.service.MdmPersistentService
import com.rrv.mdm.dpc.ui.home.RrvMdmHomeActivity

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "⚡ Boot complete — reinitializing RRV MDM agent...")
            val app = context.applicationContext as RrvMdmApplication

            if (!app.repository.isEnrolled) {
                Log.w("BootReceiver", "Device not enrolled — skipping agent start.")
                return
            }

            // 1. Start persistent MDM foreground service (policy watchdog + MQTT guardian)
            MdmPersistentService.start(context)

            // 2. Start GPS Location / Geofence Tracker Service
            val trackerIntent = Intent(context, LocationTrackerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(trackerIntent)
            } else {
                context.startService(trackerIntent)
            }

            // 3. Connect MQTT command tunnel
            app.mqttManager.connect()

            // 4. Re-apply latest cached policy and lock Home Launcher immediately on boot
            if (app.deviceManager.isDeviceOwner()) {
                app.lockTaskController.setAsDefaultHomeLauncher()
                app.policyManager.enforceBaselineSecurity()
                app.deviceManager.applyPolicy(app.repository.getActivePolicy())
                Log.i("BootReceiver", "✅ Zero-Trust Policy and Home Launcher reapplied on boot.")
            }

            // 5. Bring RRV MDM Managed Launcher to front
            val launcherIntent = Intent(context, RrvMdmHomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(launcherIntent)

            // 6. Fetch any pending commands missed while device was off
            app.mqttManager.fetchPendingCommandsFromServer()
        }
    }
}
