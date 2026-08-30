package com.rrv.mdm.dpc.domain.usecase

import android.content.Context
import android.content.Intent
import com.rrv.mdm.dpc.domain.model.*
import com.rrv.mdm.dpc.domain.repository.*
import com.rrv.mdm.dpc.mdm.command.CommandProcessor
import kotlinx.coroutines.flow.Flow

class GetManagedAppsUseCase(private val appRepository: MdmAppRepository) {
    operator fun invoke(): Flow<List<ApplicationInfo>> = appRepository.getManagedApps()
}

class GetDeviceStatusUseCase(private val deviceRepository: MdmDeviceRepository) {
    operator fun invoke(): Flow<DeviceStatusInfo> = deviceRepository.getDeviceStatus()
    suspend fun refresh() = deviceRepository.refreshDeviceStatus()
}

class GetAdminMessagesUseCase(private val messageRepository: MdmMessageRepository) {
    fun getMessages(): Flow<List<AdminMessage>> = messageRepository.getMessages()
    fun getUnreadCount(): Flow<Int> = messageRepository.getUnreadCount()
    fun getLatestMessage(): Flow<AdminMessage?> = messageRepository.getLatestMessage()
    suspend fun markAsRead(id: String) = messageRepository.markAsRead(id)
}

class GetRecentCommandsUseCase(private val commandRepository: MdmCommandRepository) {
    fun getRecentCommands(limit: Int = 20): Flow<List<MdmCommand>> = commandRepository.getRecentCommands(limit)
    fun getActiveExecutingCommand(): Flow<MdmCommand?> = commandRepository.getActiveExecutingCommand()
}

class ProcessCommandUseCase(private val commandProcessor: CommandProcessor) {
    operator fun invoke(command: MdmCommand) = commandProcessor.processCommand(command)
}

class LaunchAppUseCase(private val context: Context) {
    operator fun invoke(packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
