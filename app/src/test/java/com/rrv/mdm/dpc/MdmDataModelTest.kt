package com.rrv.mdm.dpc

import com.google.gson.Gson
import com.rrv.mdm.dpc.data.model.MqttCommandAck
import com.rrv.mdm.dpc.data.model.MqttCommandPayload
import com.rrv.mdm.dpc.data.model.PolicyPayload
import org.junit.Assert.*
import org.junit.Test

class MdmDataModelTest {

    private val gson = Gson()

    @Test
    fun testMqttCommandPayloadSerialization() {
        val payload = MqttCommandPayload(
            commandId = "cmd-12345",
            commandType = "LOCK_DEVICE",
            payloadJson = "{\"lockPin\":\"1234\"}",
            issuedAt = 1787800000L,
            requireAck = true
        )

        val json = gson.toJson(payload)
        assertTrue(json.contains("\"commandId\":\"cmd-12345\""))
        assertTrue(json.contains("\"commandType\":\"LOCK_DEVICE\""))

        val deserialized = gson.fromJson(json, MqttCommandPayload::class.java)
        assertEquals("cmd-12345", deserialized.commandId)
        assertEquals("LOCK_DEVICE", deserialized.commandType)
        assertTrue(deserialized.requireAck)
    }

    @Test
    fun testMqttCommandAckFormat() {
        val ack = MqttCommandAck(
            commandId = "cmd-12345",
            deviceId = "dev-android-01",
            status = "EXECUTED",
            message = "Screen locked successfully",
            executedAt = 1787800050L
        )

        val json = gson.toJson(ack)
        assertTrue(json.contains("\"status\":\"EXECUTED\""))
        assertTrue(json.contains("\"deviceId\":\"dev-android-01\""))
    }

    @Test
    fun testPolicyPayloadParsing() {
        val policyJson = """
            {
                "policyId": "pol-kiosk-99",
                "name": "Kiosk Hardened Profile",
                "version": 3,
                "cameraDisabled": true,
                "screenCaptureDisabled": true,
                "usbDataTransferDisabled": true,
                "kioskModeEnabled": true,
                "kioskAdminPin": "987654",
                "allowedKioskPackages": ["com.rrv.logistics.kiosk", "com.android.calculator2"]
            }
        """.trimIndent()

        val policy = PolicyPayload.fromJson(policyJson)
        assertNotNull(policy)
        assertEquals("pol-kiosk-99", policy.policyId)
        assertTrue(policy.cameraDisabled)
        assertTrue(policy.screenCaptureDisabled)
        assertTrue(policy.usbDataTransferDisabled)
        assertTrue(policy.kioskModeEnabled)
        assertEquals("987654", policy.kioskAdminPin)
        assertEquals(2, policy.allowedKioskPackages.size)
    }
}
