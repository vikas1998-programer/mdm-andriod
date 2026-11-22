package com.rrv.mdm.dpc

import com.rrv.mdm.dpc.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CommandEngineTest {

    @Test
    fun testCommandLifecycleStates() {
        val command = MdmCommand(
            commandId = "cmd-12345",
            commandType = "LOCK_DEVICE",
            status = CommandStatus.RECEIVED
        )

        assertEquals("cmd-12345", command.commandId)
        assertEquals("LOCK_DEVICE", command.commandType)
        assertEquals(CommandStatus.RECEIVED, command.status)

        val executingCommand = command.copy(
            status = CommandStatus.EXECUTING,
            progress = 50,
            resultMessage = "Applying lock..."
        )
        assertEquals(CommandStatus.EXECUTING, executingCommand.status)
        assertEquals(50, executingCommand.progress)

        val completedCommand = executingCommand.copy(
            status = CommandStatus.SUCCESS,
            progress = 100,
            resultMessage = "Screen locked successfully."
        )
        assertEquals(CommandStatus.SUCCESS, completedCommand.status)
        assertEquals(100, completedCommand.progress)
    }

    @Test
    fun testComplianceLevels() {
        val secureStatus = DeviceStatusInfo(
            complianceLevel = ComplianceLevel.SECURE,
            complianceTitle = "Device is secure",
            isDeviceOwner = true,
            isOnline = true
        )
        assertEquals(ComplianceLevel.SECURE, secureStatus.complianceLevel)
        assertTrue(secureStatus.isDeviceOwner)

        val warningStatus = DeviceStatusInfo(
            complianceLevel = ComplianceLevel.WARNING,
            complianceTitle = "Action required",
            isDeviceOwner = false
        )
        assertEquals(ComplianceLevel.WARNING, warningStatus.complianceLevel)
        assertFalse(warningStatus.isDeviceOwner)
    }

    @Test
    fun testAdminMessagePriorities() {
        val urgentMsg = AdminMessage(
            id = "msg-001",
            title = "Mandatory Security Update",
            message = "Please restart device to complete enterprise security patch.",
            priority = MessagePriority.URGENT,
            isRead = false
        )
        assertEquals(MessagePriority.URGENT, urgentMsg.priority)
        assertFalse(urgentMsg.isRead)

        val readMsg = urgentMsg.copy(isRead = true)
        assertTrue(readMsg.isRead)
    }

    @Test
    fun testApplicationInfoInstallStates() {
        val app = ApplicationInfo(
            packageName = "com.microsoft.teams",
            appName = "Teams",
            installStatus = InstallStatus.INSTALLED,
            isManaged = true
        )
        assertEquals(InstallStatus.INSTALLED, app.installStatus)
        assertTrue(app.isManaged)

        val downloadingApp = app.copy(
            installStatus = InstallStatus.DOWNLOADING,
            downloadProgress = 65
        )
        assertEquals(InstallStatus.DOWNLOADING, downloadingApp.installStatus)
        assertEquals(65, downloadingApp.downloadProgress)
    }
}
