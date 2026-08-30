package com.rrv.mdm.dpc.geofence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.util.RrvLog

class LocationTrackerService : Service() {

    companion object {
        private const val TAG = "LocationTrackerService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "rrv_mdm_spatial_channel"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val transitionEvaluator = GeofenceTransitionEvaluator(
        deadbandMeters = 15.0,
        dwellThresholdMs = 30_000L,
        maxAcceptableAccuracyMeters = 35.0f
    )

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleNewLocation(location)
            }
        }

        requestLocationUpdates()
    }

    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000)
            .setMinUpdateIntervalMillis(5000)
            .setMinUpdateDistanceMeters(10f)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            RrvLog.geo("GPS High-Accuracy location sensor active (15s interval, 15m hysteresis, 30s dwell).")
        } catch (e: SecurityException) {
            RrvLog.e(TAG, "Location permission missing", e)
        }
    }

    private fun handleNewLocation(location: Location) {
        val app = applicationContext as RrvMdmApplication
        val zones = app.repository.getGeofences()

        if (zones.isEmpty()) {
            // Publish routine GPS Telemetry via MQTT
            app.mqttManager.publishTelemetry(location, true)
            return
        }

        for (zone in zones) {
            val event = transitionEvaluator.evaluate(location, zone)
            when (event) {
                is GeofenceTransitionEvent.BreachExit -> {
                    RrvLog.w(TAG, "🚨 GEOFENCE BREACH CONFIRMED: Device exited zone '${event.zone.name}' after ${event.elapsedOutsideMs / 1000}s dwell.")
                    app.mqttManager.publishSecurityAlert(
                        "GEOFENCE_EXIT_BREACH",
                        "Device confirmed outside zone '${event.zone.name}' (${event.elapsedOutsideMs / 1000}s dwell window elapsed)."
                    )
                    // Execute automated containment lockdown
                    app.policyManager.lockScreenNow()
                }

                is GeofenceTransitionEvent.ValidEntry -> {
                    RrvLog.geo("✓ GEOFENCE ENTRY CONFIRMED: Device re-entered zone '${event.zone.name}'.")
                    app.mqttManager.publishSecurityAlert(
                        "GEOFENCE_ENTER",
                        "Device confirmed inside authorized zone '${event.zone.name}'."
                    )
                }

                is GeofenceTransitionEvent.DwellCompliant -> {
                    RrvLog.d(TAG, "Zone '${event.zone.name}': Continuous dwell verified (${event.totalDwellMs / 1000}s).")
                }

                null -> {
                    // Jitter filtered or pending dwell confirmation
                }
            }
        }

        val isCompliant = transitionEvaluator.isDeviceCompliant()
        // Publish live telemetry via MQTT
        app.mqttManager.publishTelemetry(location, isCompliant)
    }

    private fun buildForegroundNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RRV MDM Spatial Security Service",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RRV Zero-Trust Spatial Shield")
            .setContentText("Hardware-enforced geofence & telemetry monitoring active.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
