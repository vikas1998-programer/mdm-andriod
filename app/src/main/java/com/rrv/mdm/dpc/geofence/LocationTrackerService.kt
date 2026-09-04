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
    private var nativeLocationManager: android.location.LocationManager? = null
    private var nativeLocationListener: android.location.LocationListener? = null
    private var lastDispatchedLocation: Location? = null
    private var lastDispatchTimeMs: Long = 0L

    private val transitionEvaluator = GeofenceTransitionEvaluator(
        deadbandMeters = 5.0,
        dwellThresholdMs = 15_000L,
        maxAcceptableAccuracyMeters = 50.0f
    )

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        nativeLocationManager = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager

        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleNewLocation(location)
            }
        }

        nativeLocationListener = android.location.LocationListener { location ->
            handleNewLocation(location)
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    RrvLog.geo("Initial GPS fix obtained: ${loc.latitude}, ${loc.longitude}")
                    handleNewLocation(loc)
                }
            }
        } catch (_: Exception) {}

        requestLocationUpdates()
    }

    private fun requestLocationUpdates() {
        // High-frequency 5-meter displacement provider
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .setMinUpdateDistanceMeters(5.0f)
            .setMaxUpdateDelayMillis(0)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            RrvLog.geo("✓ Fused GPS active: 5m displacement threshold, 1s min interval.")
        } catch (e: SecurityException) {
            RrvLog.e(TAG, "Location permission missing for FusedLocationProviderClient", e)
        }

        // Native Android LocationManager dual fallback (GPS_PROVIDER & NETWORK_PROVIDER at 5 meters)
        try {
            nativeLocationManager?.let { lm ->
                nativeLocationListener?.let { listener ->
                    if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                        lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000L, 5.0f, listener, Looper.getMainLooper())
                    }
                    if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                        lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 1000L, 5.0f, listener, Looper.getMainLooper())
                    }
                }
            }
            RrvLog.geo("✓ Hardware GNSS active: 5-meter displacement listener registered.")
        } catch (e: SecurityException) {
            RrvLog.e(TAG, "Location permission missing for native LocationManager", e)
        } catch (e: Exception) {
            RrvLog.w(TAG, "Could not register native LocationManager listener: ${e.message}")
        }
    }

    private fun handleNewLocation(location: Location) {
        if (location.latitude == 0.0 && location.longitude == 0.0) return

        val app = applicationContext as RrvMdmApplication
        val lastLoc = lastDispatchedLocation
        val distanceMoved = if (lastLoc != null) location.distanceTo(lastLoc) else Float.MAX_VALUE
        val timeSinceLastDispatch = System.currentTimeMillis() - lastDispatchTimeMs

        // Trigger dispatch if device moved >= 5 meters OR at least 60 seconds elapsed
        if (lastLoc == null || distanceMoved >= 5.0f || timeSinceLastDispatch >= 60_000L) {
            lastDispatchedLocation = location
            lastDispatchTimeMs = System.currentTimeMillis()

            app.repository.lastLatitude = location.latitude
            app.repository.lastLongitude = location.longitude

            RrvLog.geo("📍 5m GPS Trigger: Displaced ${if (lastLoc == null) "Initial" else "${"%.1f".format(distanceMoved)}m"} -> [Lat: ${location.latitude}, Lng: ${location.longitude}, Acc: ±${"%.1f".format(location.accuracy)}m]")

            val zones = app.repository.getGeofences()
            if (zones.isNotEmpty()) {
                for (zone in zones) {
                    val event = transitionEvaluator.evaluate(location, zone)
                    when (event) {
                        is GeofenceTransitionEvent.BreachExit -> {
                            RrvLog.w(TAG, "🚨 GEOFENCE BREACH: Device exited zone '${event.zone.name}' (${event.elapsedOutsideMs / 1000}s dwell).")
                            app.mqttManager.publishSecurityAlert(
                                "GEOFENCE_EXIT_BREACH",
                                "Device confirmed outside zone '${event.zone.name}'."
                            )
                            app.policyManager.lockScreenNow()
                        }
                        is GeofenceTransitionEvent.ValidEntry -> {
                            RrvLog.geo("✓ GEOFENCE ENTRY: Device re-entered '${event.zone.name}'.")
                            app.mqttManager.publishSecurityAlert(
                                "GEOFENCE_ENTER",
                                "Device confirmed inside zone '${event.zone.name}'."
                            )
                        }
                        else -> {}
                    }
                }
            }

            val isCompliant = transitionEvaluator.isDeviceCompliant()
            // Publish live telemetry payload to server over MQTT
            app.mqttManager.publishTelemetry(location, isCompliant)
        }
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
            .setContentText("Continuous 5-meter GPS precision tracking active.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        nativeLocationListener?.let { listener ->
            try {
                nativeLocationManager?.removeUpdates(listener)
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
