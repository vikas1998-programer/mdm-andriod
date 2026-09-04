package com.rrv.mdm.dpc.data.config

import com.google.gson.annotations.SerializedName

/**
 * Authoritative runtime server and MQTT broker configuration provided dynamically
 * by the MDM platform upon enrollment or via remote configuration update.
 */
data class ServerConfiguration(
    @SerializedName("apiBaseUrl")
    val apiBaseUrl: String,

    @SerializedName("mqtt")
    val mqtt: MqttConfiguration,

    @SerializedName("environment")
    val environment: String = "PRODUCTION",

    @SerializedName("apiVersion")
    val apiVersion: String = "v1",

    @SerializedName("configurationVersion")
    val configurationVersion: Int = 1,

    @SerializedName("lastUpdatedTimestamp")
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    val isTlsRequired: Boolean
        get() = mqtt.tls || apiBaseUrl.startsWith("https://", ignoreCase = true)
}

data class MqttConfiguration(
    @SerializedName("host")
    val host: String,

    @SerializedName("port")
    val port: Int,

    @SerializedName("tls")
    val tls: Boolean = false
) {
    val serverUri: String
        get() = if (tls) "ssl://$host:$port" else "tcp://$host:$port"
}

/**
 * Ephemeral bootstrap parameters passed during initial Android Enterprise provisioning
 * (via QR code extra bundle, NFC, Knox Mobile Enrollment, Zero-Touch, or local ADB).
 */
data class BootstrapConfiguration(
    val enrollmentServerUrl: String? = null,
    val enrollmentToken: String? = null,
    val orgId: String? = null,
    val isDevelopmentOverride: Boolean = false
)
