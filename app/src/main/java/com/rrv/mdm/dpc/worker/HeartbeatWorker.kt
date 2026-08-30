package com.rrv.mdm.dpc.worker

import android.content.Context
import androidx.work.*
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.util.RrvLog
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager task that publishes a device heartbeat to the MQTT broker
 * every 60 seconds.
 *
 * Payload published to: rrv/devices/{deviceId}/heartbeat
 * {
 *   "deviceId": "uuid",
 *   "batteryLevel": 82,
 *   "isCharging": true,
 *   "storageFreeBytes": 12345678,
 *   "storageTotalBytes": 64000000000,
 *   "ramFreeBytes": 2048000,
 *   "latitude": 28.5355,
 *   "longitude": 77.3910,
 *   "networkType": "WIFI",
 *   "lastKnownIp": "192.168.1.105",
 *   "timestamp": 1723730538000
 * }
 */
class HeartbeatWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val WORK_NAME = "rrv-mdm-heartbeat"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            RrvLog.i(TAG, "✅ HeartbeatWorker scheduled — 15m background interval")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            RrvLog.i(TAG, "HeartbeatWorker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        val app = context.applicationContext as RrvMdmApplication
        val mqttManager = app.mqttManager
        val repository  = app.repository

        if (mqttManager == null) {
            RrvLog.w(TAG, "MQTT manager not initialized — skipping heartbeat")
            return Result.retry()
        }

        if (!mqttManager.isConnected()) {
            RrvLog.w(TAG, "MQTT not connected — attempting reconnect before heartbeat")
            mqttManager.connect()
            return Result.retry()
        }

        try {
            // Collect current hardware metrics
            val deviceId = repository.deviceId
            if (deviceId.isBlank()) {
                RrvLog.w(TAG, "Device ID not set — skipping heartbeat")
                return Result.retry()
            }

            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val batteryLevel = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val isCharging = batteryManager?.isCharging ?: false

            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val storageFreeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val storageTotalBytes = stat.blockCountLong * stat.blockSizeLong

            val runtime = Runtime.getRuntime()
            val ramFreeBytes = runtime.freeMemory()

            val networkType = getNetworkType()
            val lastKnownIp = getLocalIpAddress()

            val heartbeatJson = """
                {
                  "deviceId": "$deviceId",
                  "batteryLevel": $batteryLevel,
                  "isCharging": $isCharging,
                  "storageFreeBytes": $storageFreeBytes,
                  "storageTotalBytes": $storageTotalBytes,
                  "ramFreeBytes": $ramFreeBytes,
                  "latitude": ${repository.lastLatitude},
                  "longitude": ${repository.lastLongitude},
                  "networkType": "$networkType",
                  "lastKnownIp": "$lastKnownIp",
                  "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()

            mqttManager.publishHeartbeat(heartbeatJson)
            RrvLog.d(TAG, "💓 Heartbeat sent — bat=$batteryLevel% storage=${storageFreeBytes / 1024 / 1024}MB free")
            return Result.success()

        } catch (e: Exception) {
            RrvLog.e(TAG, "Heartbeat error: ${e.message}", e)
            return Result.retry()
        }
    }

    private fun getNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return "UNKNOWN"
        val activeNetwork = cm.activeNetwork ?: return "OFFLINE"
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return "UNKNOWN"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "UNKNOWN"
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (ignored: Exception) {}
        return "unknown"
    }
}
