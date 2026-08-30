package com.rrv.mdm.dpc.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rrv.mdm.dpc.RrvMdmApplication

/**
 * Periodic WorkManager task to ensure telemetry and MQTT connection integrity even if killed.
 */
class TelemetrySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("TelemetrySyncWorker", "⏰ Periodic MDM Heartbeat Triggered.")
        val app = applicationContext as RrvMdmApplication

        // Ensure MQTT is connected
        app.mqttManager.connect()

        // Publish background telemetry
        app.mqttManager.publishTelemetry(null, true)

        return Result.success()
    }
}
