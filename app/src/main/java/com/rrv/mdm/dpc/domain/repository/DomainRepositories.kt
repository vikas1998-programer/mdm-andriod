package com.rrv.mdm.dpc.domain.repository

import com.rrv.mdm.dpc.domain.model.*
import kotlinx.coroutines.flow.Flow

interface MdmAppRepository {
    fun getManagedApps(): Flow<List<ApplicationInfo>>
    suspend fun updateAppStatus(packageName: String, status: InstallStatus, progress: Int = 0)
    suspend fun syncAppsFromPolicy(apps: List<ApplicationInfo>)
    suspend fun getAppDetails(packageName: String): ApplicationInfo?
}

interface MdmCommandRepository {
    fun getRecentCommands(limit: Int = 20): Flow<List<MdmCommand>>
    fun getActiveExecutingCommand(): Flow<MdmCommand?>
    suspend fun recordCommandReceived(command: MdmCommand): Boolean // returns false if duplicate
    suspend fun updateCommandStatus(commandId: String, status: CommandStatus, resultMessage: String?, progress: Int = 0)
    suspend fun getCommand(commandId: String): MdmCommand?
}

interface MdmMessageRepository {
    fun getMessages(): Flow<List<AdminMessage>>
    fun getUnreadCount(): Flow<Int>
    fun getLatestMessage(): Flow<AdminMessage?>
    suspend fun addMessage(message: AdminMessage)
    suspend fun markAsRead(messageId: String)
    suspend fun deleteMessage(messageId: String)
}

interface MdmDeviceRepository {
    fun getDeviceStatus(): Flow<DeviceStatusInfo>
    suspend fun updateDeviceStatus(info: DeviceStatusInfo)
    suspend fun refreshDeviceStatus()
}
