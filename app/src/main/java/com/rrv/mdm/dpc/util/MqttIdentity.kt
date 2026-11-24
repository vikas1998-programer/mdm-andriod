package com.rrv.mdm.dpc.util

/**
 * Pure builder for the MQTT identity of an enrolled device. The broker ACL only lets a device use
 * topics under rrv/devices/<its own username>, so the server-issued device UUID is the single id for the
 * username, the client id and every topic. No Android types: unit-testable on the JVM.
 */
object MqttIdentity {

    /** Device-originated topic suffixes the backend subscribes to (rrv/devices/+/<suffix>). */
    val PUBLISH_SUFFIXES: Set<String> =
        setOf("telemetry", "heartbeat", "status", "ack", "acks", "logs", "events", "app_events")

    private val FORBIDDEN = charArrayOf('+', '#', '/', '\u0000')

    /** The id to use on the broker, or null when there is none (not enrolled) or it is unsafe in a topic. */
    fun canonicalId(serverAssignedId: String?): String? {
        val id = serverAssignedId?.trim().orEmpty()
        if (id.isEmpty() || id.equals("unknown", ignoreCase = true) || id.equals("all", ignoreCase = true)) return null
        if (id.any { it in FORBIDDEN || it.isWhitespace() }) return null
        return id
    }

    fun username(serverAssignedId: String?): String? = canonicalId(serverAssignedId)

    fun clientId(serverAssignedId: String?): String? = canonicalId(serverAssignedId)?.let { "rrv-dpc-$it" }

    /** Exactly the subscriptions the ACL allows; empty when there is no usable id. */
    fun subscribeTopics(serverAssignedId: String?): List<String> {
        val id = canonicalId(serverAssignedId) ?: return emptyList()
        return listOf(
            "rrv/devices/$id/commands",
            "rrv/devices/$id/policy",
            "rrv/devices/$id/notifications",
            "rrv/devices/all/commands",
            "rrv/policies/broadcast/commands"
        )
    }

    /** rrv/devices/<id>/<suffix> for an allowed suffix, otherwise null. */
    fun publishTopic(serverAssignedId: String?, suffix: String): String? {
        val id = canonicalId(serverAssignedId) ?: return null
        if (suffix !in PUBLISH_SUFFIXES) return null
        return "rrv/devices/$id/$suffix"
    }
}
