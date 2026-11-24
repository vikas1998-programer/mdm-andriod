package com.rrv.mdm.dpc

import com.rrv.mdm.dpc.util.EnrollmentGuards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentGuardsTest {

    // AND-013
    @Test fun devBootstrapOnlyInDebugWhenUnenrolled() {
        assertTrue(EnrollmentGuards.allowDevBootstrap(isDebugBuild = true, isEnrolled = false))
        assertFalse(EnrollmentGuards.allowDevBootstrap(isDebugBuild = true, isEnrolled = true))
        assertFalse(EnrollmentGuards.allowDevBootstrap(isDebugBuild = false, isEnrolled = false))
        assertFalse(EnrollmentGuards.allowDevBootstrap(isDebugBuild = false, isEnrolled = true))
    }

    // AND-024 / token over cleartext
    @Test fun cleartextToRemoteHostIsRejected() {
        assertTrue(EnrollmentGuards.isCleartextToRemoteHost("http://evil.example.com"))
        assertTrue(EnrollmentGuards.isCleartextToRemoteHost("http://8.8.8.8:8080"))
        assertTrue(EnrollmentGuards.isCleartextToRemoteHost("not a url"))
        assertFalse(EnrollmentGuards.isCleartextToRemoteHost("https://mdm.example.com"))
        assertFalse(EnrollmentGuards.isCleartextToRemoteHost("http://127.0.0.1:8088"))
        assertFalse(EnrollmentGuards.isCleartextToRemoteHost("http://192.168.1.20:8088"))
        assertFalse(EnrollmentGuards.isCleartextToRemoteHost("http://10.0.2.2:8088"))
        assertFalse(EnrollmentGuards.isCleartextToRemoteHost("http://172.20.1.1"))
        assertTrue(EnrollmentGuards.isCleartextToRemoteHost("http://172.32.1.1"))
    }

    @Test fun hostOfParsesAndLowercases() {
        assertEquals("mdm.example.com", EnrollmentGuards.hostOf("HTTPS://MDM.Example.com:8443/api"))
        assertEquals(null, EnrollmentGuards.hostOf(null))
    }

    // AND-017
    @Test fun serverConfigSameHostIsTrusted() {
        assertTrue(EnrollmentGuards.isServerConfigTrusted("http://192.168.1.5:8088", "http://192.168.1.5:8088/api/v1"))
    }

    @Test fun serverConfigDifferentHostOverCleartextIsNotTrusted() {
        assertFalse(EnrollmentGuards.isServerConfigTrusted("http://192.168.1.5:8088", "http://attacker.example:8088"))
    }

    @Test fun serverConfigDifferentHostOverHttpsEnrolmentIsTrusted() {
        assertTrue(EnrollmentGuards.isServerConfigTrusted("https://mdm.example.com", "https://api.example.com"))
    }

    @Test fun serverConfigUnparseableIsNotTrusted() {
        assertFalse(EnrollmentGuards.isServerConfigTrusted("http://192.168.1.5", ""))
    }

    // AND-008
    @Test fun kioskPinOnlyAcceptsConfiguredPin() {
        assertTrue(EnrollmentGuards.isKioskPinValid("482913", "482913"))
        assertFalse(EnrollmentGuards.isKioskPinValid("123456", "482913"))
        assertFalse(EnrollmentGuards.isKioskPinValid("123456", ""))
        assertFalse(EnrollmentGuards.isKioskPinValid("123456", null))
        assertFalse(EnrollmentGuards.isKioskPinValid("", ""))
    }

    @Test fun adminPinResolutionNeverFallsBackToBuiltInValue() {
        assertNull(EnrollmentGuards.resolveAdminPin(null, ""))
        assertNull(EnrollmentGuards.resolveAdminPin())
        assertEquals("4821", EnrollmentGuards.resolveAdminPin(null, " 4821 "))
        assertEquals("1111", EnrollmentGuards.resolveAdminPin("1111", "2222"))
    }

    @Test fun adminPinCheckRejectsLegacyBackdoors() {
        for (legacy in listOf("998877", "123456", "000000", "992841")) {
            assertFalse(EnrollmentGuards.isAdminPinValid(legacy, "482913"))
            assertFalse(EnrollmentGuards.isAdminPinValid(legacy, null))
            assertFalse(EnrollmentGuards.isAdminPinValid(legacy, ""))
        }
        assertTrue(EnrollmentGuards.isAdminPinValid("482913", "482913"))
    }
}
