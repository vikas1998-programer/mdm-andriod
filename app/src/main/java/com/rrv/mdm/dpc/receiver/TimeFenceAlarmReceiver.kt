package com.rrv.mdm.dpc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.util.RrvLog

/**
 * Autonomous On-Device Time-Fence & Curfew Alarm Receiver.
 * Wakes up at scheduled curfew boundaries (e.g. 21:30 and 06:00) to transition
 * app suspension and curfew restrictions without requiring any network or server connection.
 */
class TimeFenceAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimeFenceAlarmReceiver"
        const val ACTION_TIME_FENCE_TRANSITION = "com.rrv.mdm.ACTION_TIME_FENCE_TRANSITION"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        RrvLog.i(TAG, "TimeFence alarm triggered. Evaluating curfew state...")
        val app = context.applicationContext as? RrvMdmApplication ?: return

        if (!app.repository.isEnrolled) {
            RrvLog.w(TAG, "Device not enrolled. Skipping TimeFence evaluation.")
            return
        }

        try {
            val activePolicy = app.repository.getActivePolicy()
            app.deviceManager.evaluateAndScheduleTimeFence(activePolicy)
            RrvLog.i(TAG, "TimeFence curfew transition evaluated and enforced successfully.")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Error evaluating TimeFence curfew transition: ${e.message}")
        }
    }
}
