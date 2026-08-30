package com.rrv.mdm.dpc.data.model

import com.google.gson.annotations.SerializedName

data class DeviceTelemetryPayload(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("serialNumber") val serialNumber: String,
    @SerializedName("imei") val imei: String?,
    @SerializedName("manufacturer") val manufacturer: String,
    @SerializedName("model") val model: String,
    @SerializedName("osVersion") val osVersion: String,
    @SerializedName("sdkInt") val sdkInt: Int,
    @SerializedName("batteryLevel") val batteryLevel: Int,
    @SerializedName("isCharging") val isCharging: Boolean,
    @SerializedName("batteryTemperature") val batteryTemperature: Float,
    @SerializedName("freeStorageBytes") val freeStorageBytes: Long,
    @SerializedName("totalStorageBytes") val totalStorageBytes: Long,
    @SerializedName("freeRamBytes") val freeRamBytes: Long,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("gpsAccuracy") val gpsAccuracy: Float?,
    @SerializedName("wifiSsid") val wifiSsid: String?,
    @SerializedName("carrierName") val carrierName: String?,
    @SerializedName("isKnoxAttested") val isKnoxAttested: Boolean,
    @SerializedName("activePolicyId") val activePolicyId: String?,
    @SerializedName("isGeofenceCompliant") val isGeofenceCompliant: Boolean,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
