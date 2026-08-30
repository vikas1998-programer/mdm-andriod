package com.rrv.mdm.dpc.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.rrv.mdm.dpc.domain.model.CommandStatus
import com.rrv.mdm.dpc.domain.model.InstallStatus
import com.rrv.mdm.dpc.domain.model.MessagePriority

@Entity(tableName = "commands")
data class CommandEntity(
    @PrimaryKey
    val commandId: String,
    val commandType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val payloadJson: String = "{}",
    val priority: Int = 1,
    val expiresAt: Long = 0L,
    val status: String = CommandStatus.RECEIVED.name,
    val resultMessage: String? = null,
    val progress: Int = 0,
    val receivedAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

@Entity(tableName = "managed_apps")
data class ApplicationEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val iconUrl: String? = null,
    val versionName: String = "1.0",
    val versionCode: Int = 1,
    val isLaunchable: Boolean = true,
    val isEnabled: Boolean = true,
    val isManaged: Boolean = true,
    val installStatus: String = InstallStatus.INSTALLED.name,
    val downloadProgress: Int = 0,
    val description: String = "",
    val installType: String = "VISIBLE",
    val managedConfigJson: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "admin_messages")
data class AdminMessageEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val message: String,
    val priority: String = MessagePriority.INFO.name,
    val sender: String = "RRV Administrator",
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val isRead: Boolean = false,
    val actionCommand: String? = null
)

@Entity(tableName = "device_policies")
data class PolicyEntity(
    @PrimaryKey
    val policyId: String,
    val name: String,
    val version: Int = 1,
    val payloadJson: String = "{}",
    val isDefaultPolicy: Boolean = true,
    val appliedAt: Long = System.currentTimeMillis()
)
