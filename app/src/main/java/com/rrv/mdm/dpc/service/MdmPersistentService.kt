package com.rrv.mdm.dpc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rrv.mdm.dpc.R
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.ui.admin.AdminDiagnosticActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RRV MDM Persistent Foreground Service.
 * Keeps the MDM agent alive permanently — survives battery optimization, Doze mode, memory pressure.
 * START_STICKY guarantees Android auto-restarts it if killed.
 * Watchdog reapplies policy every 5 minutes to prevent restriction drift.
 */
class MdmPersistentService : Service() {

    companion object {
        private const val TAG = "MdmPersistentService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "rrv_mdm_persistent_channel"
        const val CHANNEL_NAME = "RRV MDM Device Management"

        fun start(context: Context) {
            val intent = Intent(context, MdmPersistentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MdmPersistentService::class.java))
        }
    }

    private var watchdogJob: Job? = null
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildPersistentNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                Log.w(TAG, "startForeground typed fallback: ${e.message}")
                startForeground(NOTIFICATION_ID, buildPersistentNotification())
            }
        } else {
            startForeground(NOTIFICATION_ID, buildPersistentNotification())
        }
        Log.i(TAG, "✅ RRV MDM Persistent Service started — agent is protected from kill.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as RrvMdmApplication

        if (!app.mqttManager.isConnected()) {
            Log.i(TAG, "🔄 MQTT not connected — reconnecting...")
            app.mqttManager.connect()
        }

        startPolicyWatchdog(app)
        startPeriodicHeartbeat(app)

        return START_STICKY
    }

    private fun startPeriodicHeartbeat(app: RrvMdmApplication) {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(45_000L) // Transmit status & telemetry to MDM server every 45 seconds
                try {
                    if (app.repository.isEnrolled || app.deviceManager.isDeviceOwner()) {
                        if (app.mqttManager.isConnected()) {
                            app.mqttManager.publishTelemetry(null, true)
                        } else {
                            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                            val batteryPct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
                            val isCharging = bm?.isCharging ?: false
                            val deviceId = app.repository.deviceId
                            if (deviceId.isNotBlank()) {
                                app.apiClient.sendHeartbeat(deviceId, batteryPct, isCharging)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Persistent service 45s heartbeat tick exception: ${e.message}")
                }
            }
        }
        Log.i(TAG, "⏰ 45-second high-frequency device telemetry heartbeat loop active.")
    }

    private fun startPolicyWatchdog(app: RrvMdmApplication) {
        watchdogJob?.cancel()
        watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(5 * 60 * 1000L)
                try {
                    if (!app.mqttManager.isConnected()) {
                        Log.w(TAG, "⚠️ Watchdog: MQTT disconnected — reconnecting...")
                        app.mqttManager.connect()
                    }
                    if (app.policyManager.isDeviceOwner() && app.repository.isEnrolled) {
                        val policy = app.repository.getActivePolicy()
                        Log.d(TAG, "🔒 Watchdog: Reapplying policy [${policy.name}]...")
                        app.policyManager.applyPolicy(policy)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog error: ${e.message}")
                }
            }
        }
        Log.i(TAG, "🐶 Policy watchdog started — reapplying restrictions every 5 min.")
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        heartbeatJob?.cancel()
        Log.w(TAG, "⚠️ MDM Persistent Service destroyed — START_STICKY will auto-restart.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "RRV MDM is actively managing this device."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildPersistentNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, AdminDiagnosticActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RRV MDM — Device Managed")
            .setContentText("This device is under IT management by RRV Software Pvt Ltd.")
            .setSmallIcon(R.drawable.ic_mdm_launcher)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }
}
