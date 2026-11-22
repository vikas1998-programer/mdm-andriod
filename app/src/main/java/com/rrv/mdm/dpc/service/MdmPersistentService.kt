package com.rrv.mdm.dpc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var volumeReceiver: android.content.BroadcastReceiver? = null
    private var volumeObserver: android.database.ContentObserver? = null

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
        val app = application as RrvMdmApplication
        registerNetworkCallback(app)
        startVolumeWatcher(app)
        Log.i(TAG, "Kubernetes Securities Persistent Service started — agent and volume governance active.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as RrvMdmApplication

        if (!app.mqttManager.isConnected()) {
            Log.i(TAG, "MQTT not connected — reconnecting...")
            app.mqttManager.connect()
        }

        // Trigger immediate policy fetch & command sync upon service startup
        val devId = app.repository.deviceId.ifBlank { app.mqttManager.getEffectiveDeviceId() }
        if (devId.isNotBlank()) {
            app.apiClient.fetchAndApplyPolicy(devId) { ok ->
                Log.i(TAG, "[Service Start] Immediate policy sync status: $ok")
            }
            app.mqttManager.fetchPendingCommandsFromServer()
        }

        startPolicyWatchdog(app)
        startPeriodicHeartbeat(app)

        return START_STICKY
    }

    private fun registerNetworkCallback(app: RrvMdmApplication) {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.i(TAG, "Network connectivity available — triggering immediate policy sync & MQTT connect.")
                    if (!app.mqttManager.isConnected()) {
                        app.mqttManager.connect()
                    }
                    val devId = app.repository.deviceId.ifBlank { app.mqttManager.getEffectiveDeviceId() }
                    if (devId.isNotBlank()) {
                        app.apiClient.fetchAndApplyPolicy(devId) { ok ->
                            Log.i(TAG, "[Network Available] Policy fetch status: $ok")
                        }
                        app.mqttManager.fetchPendingCommandsFromServer()
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    private fun startPeriodicHeartbeat(app: RrvMdmApplication) {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    if (app.repository.isEnrolled || app.deviceManager.isDeviceOwner()) {
                        if (app.mqttManager.isConnected()) {
                            app.mqttManager.publishTelemetry(null, true)
                        } else {
                            // MQTT offline fallback: Transmit REST heartbeat and trigger reconnect
                            val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                            val batteryPct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
                            val isCharging = bm?.isCharging ?: false
                            val deviceId = app.repository.deviceId.ifBlank { app.mqttManager.getEffectiveDeviceId() }
                            if (deviceId.isNotBlank()) {
                                app.apiClient.sendHeartbeat(deviceId, batteryPct, isCharging)
                            }
                            Log.d(TAG, "MQTT disconnected — REST fallback heartbeat sent, reconnecting MQTT...")
                            app.mqttManager.connect()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Persistent service heartbeat tick exception: ${e.message}")
                }
                delay(45_000L) // Transmit status & telemetry to MDM server every 45 seconds
            }
        }
        Log.i(TAG, "45-second high-frequency device telemetry heartbeat loop active.")
    }

    private fun startPolicyWatchdog(app: RrvMdmApplication) {
        watchdogJob?.cancel()
        watchdogJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val devId = app.repository.deviceId.ifBlank { app.mqttManager.getEffectiveDeviceId() }
                    if (!app.mqttManager.isConnected()) {
                        Log.i(TAG, "[15-Min REST Fallback] MQTT offline — executing 15-minute periodic REST sync for commands and policy...")
                        app.mqttManager.connect()
                        if (devId.isNotBlank()) {
                            app.mqttManager.fetchPendingCommandsFromServer()
                            app.apiClient.fetchAndApplyPolicy(devId, force = true) { ok ->
                                Log.d(TAG, "[15-Min REST Fallback] Policy sync result: $ok")
                            }
                        }
                    } else {
                        Log.d(TAG, "[Watchdog] MQTT is active and connected — skipping REST polling.")
                    }

                    if (app.deviceManager.isDeviceOwner() && (app.repository.isEnrolled || app.repository.deviceId.isNotBlank())) {
                        val policy = app.repository.getActivePolicy()
                        Log.d(TAG, "Watchdog: Active policy verified [${policy.name}] (Version: ${policy.version}).")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog error: ${e.message}")
                }
                delay(15 * 60 * 1000L) // 15-minute REST fallback interval
            }
        }
        Log.i(TAG, "Policy watchdog active — 15-minute fallback poll and periodic enforcement.")
    }

    private fun startVolumeWatcher(app: RrvMdmApplication) {
        if (volumeReceiver == null) {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                        val policy = app.repository.getActivePolicy()
                        if (!policy.masterVolumeMuted) {
                            if (streamType != -1) {
                                val targetPercent = when (streamType) {
                                    android.media.AudioManager.STREAM_MUSIC -> policy.mediaVolumePercent
                                    android.media.AudioManager.STREAM_ALARM -> policy.alarmVolumePercent
                                    android.media.AudioManager.STREAM_RING -> policy.ringVolumePercent
                                    else -> null
                                }
                                if (targetPercent != null) {
                                    app.deviceManager.scheduleVolumeAutoRevert(streamType, targetPercent, 1500L)
                                }
                            } else {
                                policy.mediaVolumePercent?.let { app.deviceManager.scheduleVolumeAutoRevert(android.media.AudioManager.STREAM_MUSIC, it, 1500L) }
                                policy.alarmVolumePercent?.let { app.deviceManager.scheduleVolumeAutoRevert(android.media.AudioManager.STREAM_ALARM, it, 1500L) }
                                policy.ringVolumePercent?.let { app.deviceManager.scheduleVolumeAutoRevert(android.media.AudioManager.STREAM_RING, it, 1500L) }
                            }
                        }
                    }
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
                volumeReceiver = receiver
                Log.i(TAG, "System-wide volume change BroadcastReceiver registered.")
            } catch (e: Exception) {
                Log.w(TAG, "Could not register volume BroadcastReceiver: ${e.message}")
            }
        }

        if (volumeObserver == null) {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    val policy = app.repository.getActivePolicy()
                    if (!policy.masterVolumeMuted) {
                        policy.mediaVolumePercent?.let { target ->
                            app.deviceManager.scheduleVolumeAutoRevert(android.media.AudioManager.STREAM_MUSIC, target, 1500L)
                        }
                        policy.alarmVolumePercent?.let { target ->
                            app.deviceManager.scheduleVolumeAutoRevert(android.media.AudioManager.STREAM_ALARM, target, 1500L)
                        }
                        policy.ringVolumePercent?.let { target ->
                            app.deviceManager.scheduleVolumeAutoRevert(android.media.AudioManager.STREAM_RING, target, 1500L)
                        }
                    }
                }
            }
            try {
                contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, observer)
                volumeObserver = observer
                Log.i(TAG, "System-wide volume ContentObserver registered.")
            } catch (e: Exception) {
                Log.w(TAG, "Could not register volume ContentObserver: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        heartbeatJob?.cancel()
        try {
            volumeReceiver?.let {
                unregisterReceiver(it)
                volumeReceiver = null
            }
        } catch (_: Exception) {}
        try {
            volumeObserver?.let {
                contentResolver.unregisterContentObserver(it)
                volumeObserver = null
            }
        } catch (_: Exception) {}
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        Log.w(TAG, "MDM Persistent Service destroyed — START_STICKY will auto-restart.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kubernetes Securities Device Management",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kubernetes Securities is actively managing and protecting this device."
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
            .setContentTitle("Kubernetes Securities — Device Managed")
            .setContentText("This device is protected by Kubernetes Securities Zero-Trust MDM.")
            .setSmallIcon(R.drawable.ic_kubernetes_security_logo)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }
}
