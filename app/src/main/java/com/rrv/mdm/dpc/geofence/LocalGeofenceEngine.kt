package com.rrv.mdm.dpc.geofence

import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rrv.mdm.dpc.data.model.GeofencePoint
import com.rrv.mdm.dpc.data.model.GeofenceZone
import kotlin.math.*

/**
 * Offline On-Device Spatial Geofencing Engine.
 * Evaluates device GPS coordinates against circular, rectangular, and custom polygon perimeters.
 */
class LocalGeofenceEngine {

    companion object {
        private const val TAG = "LocalGeofenceEngine"
        private const val EARTH_RADIUS_METERS = 6371000.0
    }

    private val gson = Gson()

    /**
     * Checks if current GPS point is inside the given GeofenceZone
     */
    fun isInsideZone(location: Location, zone: GeofenceZone): Boolean {
        val lat = location.latitude
        val lng = location.longitude

        return when (zone.zoneType.uppercase()) {
            "CIRCULAR" -> isInsideCircular(lat, lng, zone.centerLatitude, zone.centerLongitude, zone.radiusMeters)
            "RECTANGULAR" -> isInsideRectangular(lat, lng, zone)
            "POLYGON" -> isInsidePolygon(lat, lng, zone.polygonGeoJson)
            else -> isInsideCircular(lat, lng, zone.centerLatitude, zone.centerLongitude, zone.radiusMeters)
        }
    }

    /**
     * Mathematical Haversine Distance (in meters)
     */
    fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    private fun isInsideCircular(lat: Double, lng: Double, centerLat: Double, centerLng: Double, radiusM: Double): Boolean {
        val distance = calculateHaversineDistance(lat, lng, centerLat, centerLng)
        return distance <= radiusM
    }

    private fun isInsideRectangular(lat: Double, lng: Double, zone: GeofenceZone): Boolean {
        if (zone.polygonGeoJson.isNullOrBlank()) {
            return isInsideCircular(lat, lng, zone.centerLatitude, zone.centerLongitude, zone.radiusMeters)
        }

        try {
            val dimensions = gson.fromJson(zone.polygonGeoJson, Map::class.java)
            val widthM = (dimensions["width"] as? Number)?.toDouble() ?: 500.0
            val heightM = (dimensions["height"] as? Number)?.toDouble() ?: 350.0

            val latOffset = (heightM / 2.0) / 111320.0
            val lngOffset = (widthM / 2.0) / (111320.0 * cos(Math.toRadians(zone.centerLatitude)))

            val minLat = zone.centerLatitude - latOffset
            val maxLat = zone.centerLatitude + latOffset
            val minLng = zone.centerLongitude - lngOffset
            val maxLng = zone.centerLongitude + lngOffset

            return (lat in minLat..maxLat) && (lng in minLng..maxLng)
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating rectangular bounds", e)
            return false
        }
    }

    /**
     * Ray-Casting (Even-Odd) Algorithm for Multi-Point Polygon Containment
     */
    private fun isInsidePolygon(lat: Double, lng: Double, polygonGeoJson: String?): Boolean {
        if (polygonGeoJson.isNullOrBlank()) return false

        val vertices: List<GeofencePoint> = try {
            val type = object : TypeToken<List<GeofencePoint>>() {}.type
            gson.fromJson(polygonGeoJson, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse polygon vertices", e)
            return false
        }

        if (vertices.size < 3) return false

        var inside = false
        var j = vertices.size - 1

        for (i in vertices.indices) {
            val xi = vertices[i].lat
            val yi = vertices[i].lng
            val xj = vertices[j].lat
            val yj = vertices[j].lng

            val intersect = ((yi > lng) != (yj > lng)) &&
                    (lat < (xj - xi) * (lng - yi) / (yj - yi) + xi)

            if (intersect) {
                inside = !inside
            }
            j = i
        }

        return inside
    }
}
