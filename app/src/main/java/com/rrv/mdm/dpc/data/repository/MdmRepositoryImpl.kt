package com.rrv.mdm.dpc.data.repository

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.rrv.mdm.dpc.data.database.RrvMdmDatabase
import com.rrv.mdm.dpc.data.entity.AdminMessageEntity
import com.rrv.mdm.dpc.data.entity.ApplicationEntity
import com.rrv.mdm.dpc.data.entity.CommandEntity
import com.rrv.mdm.dpc.domain.model.*
import com.rrv.mdm.dpc.domain.repository.MdmAppRepository
import com.rrv.mdm.dpc.domain.repository.MdmCommandRepository
import com.rrv.mdm.dpc.domain.repository.MdmDeviceRepository
import com.rrv.mdm.dpc.domain.repository.MdmMessageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MdmRepositoryImpl(
    private val context: Context,
    private val database: RrvMdmDatabase,
    private val legacyPrefsRepo: MdmRepository
) : MdmAppRepository, MdmCommandRepository, MdmMessageRepository, MdmDeviceRepository {

    private val pm: PackageManager = context.packageManager
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val deviceStatusFlow = MutableStateFlow(computeDeviceStatus())

    // ── Application Repository Implementation ─────────────────────────────────

    override fun getManagedApps(): Flow<List<ApplicationInfo>> {
        return database.applicationDao().getManagedAppsFlow().map { entities ->
            entities.map { entity ->
                val icon = try {
                    pm.getApplicationIcon(entity.packageName)
                } catch (_: Exception) {
                    null
                }
                ApplicationInfo(
                    packageName = entity.packageName,
                    appName = entity.appName,
                    icon = icon,
                    iconUrl = entity.iconUrl,
                    versionName = entity.versionName,
                    versionCode = entity.versionCode,
                    isLaunchable = entity.isLaunchable,
                    isEnabled = entity.isEnabled,
                    isManaged = entity.isManaged,
                    installStatus = try { InstallStatus.valueOf(entity.installStatus) } catch (_: Exception) { InstallStatus.INSTALLED },
                    downloadProgress = entity.downloadProgress,
                    description = entity.description
                )
            }
        }
    }

    override suspend fun updateAppStatus(packageName: String, status: InstallStatus, progress: Int) {
        withContext(Dispatchers.IO) {
            database.applicationDao().updateInstallProgress(packageName, status.name, progress)
        }
    }

    override suspend fun syncAppsFromPolicy(apps: List<ApplicationInfo>) {
        withContext(Dispatchers.IO) {
            val entities = apps.map { app ->
                ApplicationEntity(
                    packageName = app.packageName,
                    appName = app.appName,
                    iconUrl = app.iconUrl,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    isLaunchable = app.isLaunchable,
                    isEnabled = app.isEnabled,
                    isManaged = app.isManaged,
                    installStatus = app.installStatus.name,
                    downloadProgress = app.downloadProgress,
                    description = app.description,
                    lastUpdated = System.currentTimeMillis()
                )
            }
            database.applicationDao().clearAllApps()
            if (entities.isNotEmpty()) {
                database.applicationDao().insertApps(entities)
            }
        }
    }

    override suspend fun getAppDetails(packageName: String): ApplicationInfo? {
        return withContext(Dispatchers.IO) {
            val entity = database.applicationDao().getAppByPackageName(packageName) ?: return@withContext null
            val icon = try { pm.getApplicationIcon(packageName) } catch (_: Exception) { null }
            ApplicationInfo(
                packageName = entity.packageName,
                appName = entity.appName,
                icon = icon,
                iconUrl = entity.iconUrl,
                versionName = entity.versionName,
                versionCode = entity.versionCode,
                isLaunchable = entity.isLaunchable,
                isEnabled = entity.isEnabled,
                isManaged = entity.isManaged,
                installStatus = try { InstallStatus.valueOf(entity.installStatus) } catch (_: Exception) { InstallStatus.INSTALLED },
                downloadProgress = entity.downloadProgress,
                description = entity.description
            )
        }
    }

    // ── Command Repository Implementation ─────────────────────────────────────

    override fun getRecentCommands(limit: Int): Flow<List<MdmCommand>> {
        return database.commandDao().getRecentCommands(limit).map { entities ->
            entities.map { entity ->
                MdmCommand(
                    commandId = entity.commandId,
                    commandType = entity.commandType,
                    timestamp = entity.timestamp,
                    payloadJson = entity.payloadJson,
                    priority = entity.priority,
                    expiresAt = entity.expiresAt,
                    status = try { CommandStatus.valueOf(entity.status) } catch (_: Exception) { CommandStatus.RECEIVED },
                    resultMessage = entity.resultMessage,
                    progress = entity.progress,
                    executedAt = entity.executedAt
                )
            }
        }
    }

    override fun getActiveExecutingCommand(): Flow<MdmCommand?> {
        return database.commandDao().getActiveExecutingCommandFlow().map { entity ->
            entity?.let {
                MdmCommand(
                    commandId = it.commandId,
                    commandType = it.commandType,
                    timestamp = it.timestamp,
                    payloadJson = it.payloadJson,
                    priority = it.priority,
                    expiresAt = it.expiresAt,
                    status = try { CommandStatus.valueOf(it.status) } catch (_: Exception) { CommandStatus.RECEIVED },
                    resultMessage = it.resultMessage,
                    progress = it.progress,
                    executedAt = it.executedAt
                )
            }
        }
    }

    override suspend fun recordCommandReceived(command: MdmCommand): Boolean {
        return withContext(Dispatchers.IO) {
            val existing = database.commandDao().getCommandById(command.commandId)
            if (existing != null && existing.status != CommandStatus.PENDING.name) {
                // Duplicate command already processed or running
                return@withContext false
            }
            val entity = CommandEntity(
                commandId = command.commandId,
                commandType = command.commandType,
                timestamp = command.timestamp,
                payloadJson = command.payloadJson,
                priority = command.priority,
                expiresAt = command.expiresAt,
                status = command.status.name,
                resultMessage = command.resultMessage,
                progress = command.progress,
                receivedAt = System.currentTimeMillis()
            )
            database.commandDao().insertCommand(entity)
            true
        }
    }

    override suspend fun updateCommandStatus(
        commandId: String,
        status: CommandStatus,
        resultMessage: String?,
        progress: Int
    ) {
        withContext(Dispatchers.IO) {
            database.commandDao().updateCommandStatus(
                commandId = commandId,
                status = status.name,
                resultMessage = resultMessage,
                progress = progress,
                executedAt = if (status == CommandStatus.SUCCESS || status == CommandStatus.FAILED) System.currentTimeMillis() else null
            )
        }
    }

    override suspend fun getCommand(commandId: String): MdmCommand? {
        return withContext(Dispatchers.IO) {
            val entity = database.commandDao().getCommandById(commandId) ?: return@withContext null
            MdmCommand(
                commandId = entity.commandId,
                commandType = entity.commandType,
                timestamp = entity.timestamp,
                payloadJson = entity.payloadJson,
                priority = entity.priority,
                expiresAt = entity.expiresAt,
                status = try { CommandStatus.valueOf(entity.status) } catch (_: Exception) { CommandStatus.RECEIVED },
                resultMessage = entity.resultMessage,
                progress = entity.progress,
                executedAt = entity.executedAt
            )
        }
    }

    // ── Message Repository Implementation ─────────────────────────────────────

    override fun getMessages(): Flow<List<AdminMessage>> {
        return database.adminMessageDao().getMessagesFlow().map { entities ->
            entities.map { entity ->
                AdminMessage(
                    id = entity.id,
                    title = entity.title,
                    message = entity.message,
                    priority = try { MessagePriority.valueOf(entity.priority) } catch (_: Exception) { MessagePriority.INFO },
                    sender = entity.sender,
                    timestamp = entity.timestamp,
                    expiresAt = entity.expiresAt,
                    isRead = entity.isRead,
                    actionCommand = entity.actionCommand
                )
            }
        }
    }

    override fun getUnreadCount(): Flow<Int> {
        return database.adminMessageDao().getUnreadCountFlow()
    }

    override fun getLatestMessage(): Flow<AdminMessage?> {
        return database.adminMessageDao().getLatestMessageFlow().map { entity ->
            entity?.let {
                AdminMessage(
                    id = it.id,
                    title = it.title,
                    message = it.message,
                    priority = try { MessagePriority.valueOf(it.priority) } catch (_: Exception) { MessagePriority.INFO },
                    sender = it.sender,
                    timestamp = it.timestamp,
                    expiresAt = it.expiresAt,
                    isRead = it.isRead,
                    actionCommand = it.actionCommand
                )
            }
        }
    }

    override suspend fun addMessage(message: AdminMessage) {
        withContext(Dispatchers.IO) {
            val entity = AdminMessageEntity(
                id = message.id,
                title = message.title,
                message = message.message,
                priority = message.priority.name,
                sender = message.sender,
                timestamp = message.timestamp,
                expiresAt = message.expiresAt,
                isRead = message.isRead,
                actionCommand = message.actionCommand
            )
            database.adminMessageDao().insertMessage(entity)
        }
    }

    override suspend fun markAsRead(messageId: String) {
        withContext(Dispatchers.IO) {
            database.adminMessageDao().markAsRead(messageId)
        }
    }

    override suspend fun deleteMessage(messageId: String) {
        withContext(Dispatchers.IO) {
            database.adminMessageDao().deleteMessage(messageId)
        }
    }

    // ── Device Status Repository Implementation ───────────────────────────────

    override fun getDeviceStatus(): Flow<DeviceStatusInfo> = deviceStatusFlow.asStateFlow()

    override suspend fun updateDeviceStatus(info: DeviceStatusInfo) {
        deviceStatusFlow.value = info
    }

    override suspend fun refreshDeviceStatus() {
        withContext(Dispatchers.Default) {
            deviceStatusFlow.value = computeDeviceStatus()
        }
    }

    suspend fun queueEvent(
        eventType: String,
        severity: String = "INFO",
        tag: String = "RRV-MDM-DPC",
        source: String = "DPC_AGENT",
        message: String,
        metadataJson: String = "{}"
    ) {
        withContext(Dispatchers.IO) {
            val event = com.rrv.mdm.dpc.data.entity.QueuedDeviceEventEntity(
                eventType = eventType,
                severity = severity,
                tag = tag,
                source = source,
                message = message,
                metadataJson = metadataJson,
                timestamp = System.currentTimeMillis()
            )
            database.queuedDeviceEventDao().insertEvent(event)
        }
    }

    suspend fun flushQueuedEvents(apiClient: com.rrv.mdm.dpc.network.MdmApiClient) {
        withContext(Dispatchers.IO) {
            val pending = database.queuedDeviceEventDao().getPendingEvents(50)
            if (pending.isNotEmpty()) {
                apiClient.uploadEvents(pending) { success, _ ->
                    if (success) {
                        kotlinx.coroutines.GlobalScope.let {
                            // Cleanup uploaded events
                            kotlinx.coroutines.runBlocking {
                                database.queuedDeviceEventDao().deleteEvents(pending.map { it.id })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun computeDeviceStatus(): DeviceStatusInfo {
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        val isEnrolled = legacyPrefsRepo.isEnrolled

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val isCharging = bm?.isCharging ?: false

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val freeGb = (freeBytes / (1024.0 * 1024.0 * 1024.0) * 10.0).toInt() / 10.0
        val totalGb = (totalBytes / (1024.0 * 1024.0 * 1024.0) * 10.0).toInt() / 10.0

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiSsid = try {
            val raw = wifiManager?.connectionInfo?.ssid ?: ""
            if (raw.startsWith("\"") && raw.endsWith("\"")) raw.drop(1).dropLast(1) else raw
        } catch (_: Exception) { null }

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val lastSyncFormatted = timeFormat.format(Date())

        val complianceLevel = when {
            !isEnrolled -> ComplianceLevel.WARNING
            !isDeviceOwner -> ComplianceLevel.WARNING
            else -> ComplianceLevel.SECURE
        }

        val title = when (complianceLevel) {
            ComplianceLevel.SECURE -> "Device is secure"
            ComplianceLevel.WARNING -> "Action required"
            ComplianceLevel.NON_COMPLIANT -> "Device requires attention"
            ComplianceLevel.OFFLINE -> "Offline"
        }

        val subtitle = when (complianceLevel) {
            ComplianceLevel.SECURE -> "Connected to RRV MDM"
            ComplianceLevel.WARNING -> "Device owner verification pending"
            ComplianceLevel.NON_COMPLIANT -> "Policy compliance violations detected"
            ComplianceLevel.OFFLINE -> "Last synchronized: $lastSyncFormatted"
        }

        return DeviceStatusInfo(
            complianceLevel = complianceLevel,
            complianceTitle = title,
            complianceSubtitle = subtitle,
            lastSyncFormatted = lastSyncFormatted,
            lastSyncTimestamp = System.currentTimeMillis(),
            batteryLevel = batteryPct,
            isCharging = isCharging,
            networkType = if (!wifiSsid.isNullOrBlank() && wifiSsid != "<unknown ssid>") "Wi-Fi" else "Cellular",
            wifiSsid = wifiSsid,
            storageFreeGb = freeGb,
            storageTotalGb = totalGb,
            isDeviceOwner = isDeviceOwner,
            isOnline = true
        )
    }
}
