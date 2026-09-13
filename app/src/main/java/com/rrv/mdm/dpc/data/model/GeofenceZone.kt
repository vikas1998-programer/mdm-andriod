package com.rrv.mdm.dpc.data.model

import com.google.gson.annotations.SerializedName

data class GeofencePoint(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double
)

data class GeofenceRule(
    @SerializedName("transitionTrigger") val transitionTrigger: String, // ON_EXIT, ON_ENTER, DWELL
    @SerializedName("actionType") val actionType: String, // LOCK_DEVICE, DISABLE_CAMERA, WIPE, NOTIFY_SECOPS
    @SerializedName("targetPolicyId") val targetPolicyId: String? = null
)

data class GeofenceZone(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("zoneType") val zoneType: String, // CIRCULAR, RECTANGULAR, POLYGON
    @SerializedName("centerLatitude") val centerLatitude: Double = 0.0,
    @SerializedName("centerLongitude") val centerLongitude: Double = 0.0,
    @SerializedName("radiusMeters") val radiusMeters: Double = 300.0,
    @SerializedName("polygonGeoJson") val polygonGeoJson: String? = null,
    @SerializedName("rules") val rules: List<GeofenceRule> = emptyList()
)
