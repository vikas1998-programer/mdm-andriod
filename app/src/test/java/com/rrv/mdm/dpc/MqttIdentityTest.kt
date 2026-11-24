package com.rrv.mdm.dpc

import com.rrv.mdm.dpc.util.MqttIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttIdentityTest {
    private val uuid = "3f2b8c1e-5a47-4d0e-9b6a-1c2d3e4f5a6b"

    @Test fun blankIdMeansNoConnection() {
        for (v in listOf(null, "", "   ", "unknown", "all")) {
            assertNull(MqttIdentity.username(v))
            assertNull(MqttIdentity.clientId(v))
            assertTrue(MqttIdentity.subscribeTopics(v).isEmpty())
            assertNull(MqttIdentity.publishTopic(v, "telemetry"))
        }
    }

    @Test fun uuidYieldsExactlyTheAllowedTopics() {
        assertEquals(uuid, MqttIdentity.username(uuid))
        assertEquals("rrv-dpc-$uuid", MqttIdentity.clientId(uuid))
        assertEquals(
            listOf(
                "rrv/devices/$uuid/commands",
                "rrv/devices/$uuid/policy",
                "rrv/devices/$uuid/notifications",
                "rrv/devices/all/commands",
                "rrv/policies/broadcast/commands"
            ),
            MqttIdentity.subscribeTopics(uuid)
        )
    }

    @Test fun publishTopicsStayUnderOwnId() {
        for (s in MqttIdentity.PUBLISH_SUFFIXES) assertEquals("rrv/devices/$uuid/$s", MqttIdentity.publishTopic(uuid, s))
        assertNull(MqttIdentity.publishTopic(uuid, "commands"))
        assertNull(MqttIdentity.publishTopic(uuid, "../x"))
    }

    @Test fun wildcardOrSeparatorIdsAreRejected() {
        for (bad in listOf("a+b", "#", "a/b", "x/#", "a b", "a\u0000b", "+")) {
            assertNull(bad, MqttIdentity.canonicalId(bad))
            assertTrue(MqttIdentity.subscribeTopics(bad).isEmpty())
        }
        for (t in MqttIdentity.subscribeTopics(uuid).take(3)) assertTrue(!t.contains('+') && !t.contains('#'))
    }

    @Test fun idIsTrimmed() {
        assertEquals(uuid, MqttIdentity.canonicalId("  $uuid "))
    }
}
