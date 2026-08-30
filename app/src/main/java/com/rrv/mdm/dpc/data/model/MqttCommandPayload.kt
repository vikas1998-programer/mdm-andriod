package com.rrv.mdm.dpc.data.model

import com.google.gson.annotations.SerializedName

/**
 * Remote Push Command dispatched from MDM Server via MQTT topic `rrv/devices/{deviceId}/commands`
 *
 * NEW ARCHITECTURE — Signal + REST Fetch:
 *   Server sends: { commandId, commandType } only — ~150 bytes
 *   Device calls: GET /api/v1/commands/{commandId} to fetch full payload via REST
 *   payloadJson will be null/empty in new pattern — device fetches it via REST
 */
data class MqttCommandPayload(
    @SerializedName("commandId") val commandId: String = "",
    @SerializedName("commandType") val commandType: String = "",
    @SerializedName("payloadJson") val payloadJson: String? = null,  // nullable — signal-only MQTT
    @SerializedName("issuedAt") val issuedAt: Long = System.currentTimeMillis(),
    @SerializedName("requireAck") val requireAck: Boolean = true
)

data class MqttCommandAck(
    @SerializedName("commandId") val commandId: String,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("status") val status: String, // EXECUTED, FAILED, REJECTED
    @SerializedName("message") val message: String = "",
    @SerializedName("executedAt") val executedAt: Long = System.currentTimeMillis()
)
