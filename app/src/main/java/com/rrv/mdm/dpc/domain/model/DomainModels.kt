package com.rrv.mdm.dpc.domain.model

import android.graphics.drawable.Drawable

enum class ComplianceLevel {
    SECURE,
    WARNING,
    NON_COMPLIANT,
    OFFLINE
}

enum class CommandStatus {
    PENDING,
    RECEIVED,
    EXECUTING,
    SUCCESS,
    FAILED,
    CANCELLED,
    EXPIRED
}

enum class InstallStatus {
    INSTALLED,
    AVAILABLE,
    DOWNLOADING,
    INSTALLING,
    FAILED,
    BLOCKED
}

enum class MessagePriority {
    INFO,
    WARNING,
    URGENT
}

data class ApplicationInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val iconUrl: String? = null,
    val versionName: String = "1.0",
    val versionCode: Int = 1,
    val isLaunchable: Boolean = true,
    val isEnabled: Boolean = true,
    val isManaged: Boolean = true,
    val installStatus: InstallStatus = InstallStatus.INSTALLED,
    val downloadProgress: Int = 0,
    val description: String = ""
)

data class DeviceStatusInfo(
    val complianceLevel: ComplianceLevel = ComplianceLevel.SECURE,
    val complianceTitle: String = "Device is secure",
    val complianceSubtitle: String = "Connected to RRV MDM",
    val lastSyncFormatted: String = "Just now",
    val lastSyncTimestamp: Long = System.currentTimeMillis(),
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val networkType: String = "Wi-Fi",
    val wifiSsid: String? = null,
    val storageFreeGb: Double = 0.0,
    val storageTotalGb: Double = 0.0,
    val ipAddress: String = "—",
    val isDeviceOwner: Boolean = true,
    val isOnline: Boolean = true
)

data class MdmCommand(
    val commandId: String,
    val commandType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val payloadJson: String = "{}",
    val priority: Int = 1,
    val expiresAt: Long = 0L,
    val status: CommandStatus = CommandStatus.RECEIVED,
    val resultMessage: String? = null,
    val progress: Int = 0,
    val executedAt: Long? = null
)

data class AdminMessage(
    val id: String,
    val title: String,
    val message: String,
    val priority: MessagePriority = MessagePriority.INFO,
    val sender: String = "RRV Administrator",
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val isRead: Boolean = false,
    val actionCommand: String? = null
)
