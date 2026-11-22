package com.rrv.mdm.dpc.data.config

import com.rrv.mdm.dpc.BuildConfig

/**
 * Enterprise RRV MDM Single Source of Truth for Global Network Endpoints.
 * All default server hosts, ports, protocols, and endpoints are centrally resolved here.
 */
object MdmGlobalConfig {
    const val SERVER_HOST: String = BuildConfig.GLOBAL_SERVER_HOST
    const val API_PORT: Int = BuildConfig.GLOBAL_API_PORT
    const val MQTT_PORT: Int = BuildConfig.GLOBAL_MQTT_PORT
    const val MQTT_TLS_PORT: Int = BuildConfig.GLOBAL_MQTT_TLS_PORT
    const val PORTAL_PORT: Int = BuildConfig.GLOBAL_PORTAL_PORT
    const val SERVER_URL: String = BuildConfig.GLOBAL_SERVER_URL

    val MQTT_TCP_URI: String get() = "tcp://$SERVER_HOST:$MQTT_PORT"
    val MQTT_TLS_URI: String get() = "ssl://$SERVER_HOST:$MQTT_TLS_PORT"
    val PORTAL_URL: String get() = "http://$SERVER_HOST:$PORTAL_PORT"
}
