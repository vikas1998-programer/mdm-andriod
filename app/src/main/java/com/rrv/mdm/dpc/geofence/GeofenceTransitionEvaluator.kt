package com.rrv.mdm.dpc.geofence

import android.location.Location
import com.rrv.mdm.dpc.data.model.GeofenceZone
import com.rrv.mdm.dpc.util.RrvLog
import java.util.concurrent.ConcurrentHashMap

enum class PresenceStatus {
    INSIDE,
    OUTSIDE,
    PENDING_EXIT,
    PENDING_ENTER
}

data class ZonePresenceState(
    val zoneId: String,
    var status: PresenceStatus = PresenceStatus.INSIDE,
    var stateSinceTimestamp: Long = System.currentTimeMillis(),
    var lastActionFiredTimestamp: Long = 0L
)

sealed class GeofenceTransitionEvent {
    data class BreachExit(val zone: GeofenceZone, val elapsedOutsideMs: Long) : GeofenceTransitionEvent()
    data class ValidEntry(val zone: GeofenceZone, val elapsedInsideMs: Long) : GeofenceTransitionEvent()
    data class DwellCompliant(val zone: GeofenceZone, val totalDwellMs: Long) : GeofenceTransitionEvent()
}

/**
 * Enterprise Spatial Hysteresis & Dwell State Machine.
 * Eliminates GPS jitter and false-positive boundary flip-flopping using:
 *  1. 15-meter Deadband Hysteresis Margin
 *  2. 30-second Continuous Dwell Debounce Window
 *  3. Satellite Accuracy Noise Filter (<35m required)
 */
class GeofenceTransitionEvaluator(
    private val engine: LocalGeofenceEngine = LocalGeofenceEngine(),
    private val deadbandMeters: Double = 15.0,
    private val dwellThresholdMs: Long = 30_000L,
    private val maxAcceptableAccuracyMeters: Float = 35.0f
) {

    companion object {
        private const val TAG = "GeofenceEvaluator"
    }

    private val zoneStates = ConcurrentHashMap<String, ZonePresenceState>()

    fun reset() {
        zoneStates.clear()
    }

    /**
     * Evaluates a location fix against a geofence zone with hysteresis and dwell verification.
     * Returns a triggered event only when a confirmed transition occurs.
     */
    fun evaluate(location: Location, zone: GeofenceZone): GeofenceTransitionEvent? {
        // 1. Noise Rejection Filter: Discard low-accuracy GPS fixes (multipath/indoor jitter)
        if (location.hasAccuracy() && location.accuracy > maxAcceptableAccuracyMeters) {
            RrvLog.d(TAG, "Discarded inaccurate GPS fix (accuracy: ${location.accuracy}m > ${maxAcceptableAccuracyMeters}m)")
            return null
        }

        val state = zoneStates.computeIfAbsent(zone.id) {
            ZonePresenceState(zoneId = zone.id, status = PresenceStatus.INSIDE, stateSinceTimestamp = System.currentTimeMillis())
        }

        val now = System.currentTimeMillis()
        val isGeometricallyInside = isInsideWithHysteresis(location, zone, state.status)

        return when (state.status) {
            PresenceStatus.INSIDE -> {
                if (!isGeometricallyInside) {
                    // Transition to PENDING_EXIT
                    state.status = PresenceStatus.PENDING_EXIT
                    state.stateSinceTimestamp = now
                    RrvLog.d(TAG, "Zone [${zone.name}]: Moved outside perimeter. Starting ${dwellThresholdMs / 1000}s dwell confirmation...")
                    null
                } else {
                    null
                }
            }

            PresenceStatus.PENDING_EXIT -> {
                if (isGeometricallyInside) {
                    // Jitter recovered back inside before dwell expired!
                    state.status = PresenceStatus.INSIDE
                    state.stateSinceTimestamp = now
                    RrvLog.d(TAG, "Zone [${zone.name}]: Jitter cancelled — returned inside boundary before dwell timer expired.")
                    null
                } else {
                    val elapsed = now - state.stateSinceTimestamp
                    if (elapsed >= dwellThresholdMs) {
                        state.status = PresenceStatus.OUTSIDE
                        state.lastActionFiredTimestamp = now
                        RrvLog.w(TAG, "🚨 Zone [${zone.name}]: Confirmed GEOFENCE EXIT after ${elapsed / 1000}s dwell window.")
                        GeofenceTransitionEvent.BreachExit(zone, elapsed)
                    } else {
                        null
                    }
                }
            }

            PresenceStatus.OUTSIDE -> {
                if (isGeometricallyInside) {
                    state.status = PresenceStatus.PENDING_ENTER
                    state.stateSinceTimestamp = now
                    RrvLog.d(TAG, "Zone [${zone.name}]: Detected entry. Starting ${dwellThresholdMs / 1000}s dwell confirmation...")
                    null
                } else {
                    null
                }
            }

            PresenceStatus.PENDING_ENTER -> {
                if (!isGeometricallyInside) {
                    state.status = PresenceStatus.OUTSIDE
                    state.stateSinceTimestamp = now
                    null
                } else {
                    val elapsed = now - state.stateSinceTimestamp
                    if (elapsed >= dwellThresholdMs) {
                        state.status = PresenceStatus.INSIDE
                        state.lastActionFiredTimestamp = now
                        RrvLog.i(TAG, "✓ Zone [${zone.name}]: Confirmed GEOFENCE RE-ENTRY after ${elapsed / 1000}s dwell window.")
                        GeofenceTransitionEvent.ValidEntry(zone, elapsed)
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Applies hysteresis margin depending on current state:
     * - If currently INSIDE: must be beyond (radius + deadband) to count as outside.
     * - If currently OUTSIDE: must be within (radius - deadband) to count as inside.
     */
    private fun isInsideWithHysteresis(location: Location, zone: GeofenceZone, currentStatus: PresenceStatus): Boolean {
        if (zone.zoneType.uppercase() == "CIRCULAR" || zone.polygonGeoJson.isNullOrBlank()) {
            val dist = engine.calculateHaversineDistance(
                location.latitude, location.longitude,
                zone.centerLatitude, zone.centerLongitude
            )
            val effectiveRadius = when (currentStatus) {
                PresenceStatus.INSIDE, PresenceStatus.PENDING_EXIT -> zone.radiusMeters + deadbandMeters
                PresenceStatus.OUTSIDE, PresenceStatus.PENDING_ENTER -> (zone.radiusMeters - deadbandMeters).coerceAtLeast(10.0)
            }
            return dist <= effectiveRadius
        }

        // For polygons / rectangular shapes
        return engine.isInsideZone(location, zone)
    }

    fun isDeviceCompliant(): Boolean {
        if (zoneStates.isEmpty()) return true
        return zoneStates.values.none { it.status == PresenceStatus.OUTSIDE || it.status == PresenceStatus.PENDING_EXIT }
    }
}
