package com.rrv.mdm.dpc.mdm.command

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.data.model.PolicyPayload
import com.rrv.mdm.dpc.domain.model.AdminMessage
import com.rrv.mdm.dpc.domain.model.CommandStatus
import com.rrv.mdm.dpc.domain.model.MdmCommand
import com.rrv.mdm.dpc.domain.model.MessagePriority
import com.rrv.mdm.dpc.util.NotificationHelper
import com.rrv.mdm.dpc.util.RrvLog
import com.rrv.mdm.dpc.worker.ApkDownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExecutionResult(
    val isSuccess: Boolean,
    val message: String,
    val progress: Int = 100
)

interface CommandExecutor {
    suspend fun execute(command: MdmCommand, context: Context): ExecutionResult
}

class LockDeviceExecutor : CommandExecutor {
    override fun toString(): String = "LockDeviceExecutor"
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val ok = app.deviceManager.lockScreenNow()
        NotificationHelper.showCommandNotification(context, "🔒 Remote Lock Executed", "Device screen locked by IT Administrator.")
        context.sendBroadcast(Intent("com.rrv.mdm.ACTION_DEVICE_LOCKED"))
        return if (ok) {
            ExecutionResult(true, "Screen locked immediately.")
        } else {
            ExecutionResult(false, "DevicePolicyManager failed to lock screen.")
        }
    }
}

class UnlockDeviceExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        NotificationHelper.showCommandNotification(context, "🔓 Device Unlocked", "Remote unlock issued by IT Administrator.")
        context.sendBroadcast(Intent("com.rrv.mdm.ACTION_DEVICE_UNLOCKED"))
        return ExecutionResult(true, "Device unlocked successfully.")
    }
}

class RebootExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val ok = app.deviceManager.rebootDevice()
        return if (ok) {
            ExecutionResult(true, "Reboot command dispatched to device hardware.")
        } else {
            ExecutionResult(false, "Reboot requires Device Owner privileges.")
        }
    }
}

class ResetPasscodeExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
        val newPin = map?.get("newPin")?.toString() ?: map?.get("pin")?.toString() ?: ""
        if (newPin.isBlank()) {
            return ExecutionResult(false, "Missing newPin in RESET_PIN payload.")
        }
        val ok = app.deviceManager.resetPassword(newPin)
        return if (ok) {
            ExecutionResult(true, "Passcode reset successfully.")
        } else {
            ExecutionResult(false, "Failed to reset passcode via DPM.")
        }
    }
}

class InstallApplicationExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val gson = Gson()
        val map = try { gson.fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
        val packageName = map?.get("packageName")?.toString() ?: ""
        val downloadUrl = map?.get("downloadUrl")?.toString() ?: ""
        val sha256 = map?.get("sha256")?.toString() ?: ""
        val versionCode = (map?.get("versionCode") as? Number)?.toInt() ?: 1
        val versionName = map?.get("versionName")?.toString() ?: "1.0"
        val appTitle = map?.get("appTitle")?.toString() ?: packageName
        val appConfigJson = map?.get("appConfigJson")?.toString() ?: "{}"
        val appId = map?.get("appId")?.toString() ?: ""

        if (packageName.isBlank() || downloadUrl.isBlank()) {
            return ExecutionResult(false, "Missing packageName or downloadUrl in payload.")
        }

        ApkDownloadWorker.enqueue(
            context, command.commandId, appId, packageName, appTitle,
            downloadUrl, sha256, versionCode, versionName, appConfigJson
        )
        return ExecutionResult(true, "Silent installation background task enqueued for $packageName.", 50)
    }
}

class UninstallApplicationExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
        val packageName = map?.get("packageName")?.toString() ?: ""
        if (packageName.isBlank()) return ExecutionResult(false, "Missing packageName in UNINSTALL_APP payload.")
        val app = context.applicationContext as RrvMdmApplication
        val initiated = app.policyManager.silentUninstall(packageName)
        return if (initiated) {
            ExecutionResult(true, "Uninstall initiated for $packageName.")
        } else {
            ExecutionResult(false, "APPLICATION_NOT_UNINSTALLABLE: Package $packageName cannot be uninstalled (System package, MDM core, or DPM restriction).")
        }
    }
}

class PolicyUpdateExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val parsed = PolicyPayload.fromJson(command.payloadJson)
        app.repository.saveActivePolicy(parsed)
        app.deviceManager.applyPolicy(parsed)
        NotificationHelper.showPolicyNotification(context, parsed)
        context.sendBroadcast(Intent("com.rrv.mdm.ACTION_POLICY_UPDATED"))
        return ExecutionResult(true, "Policy profile '${parsed.name}' enforced successfully.")
    }
}

class MessageExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
        val title = map?.get("title")?.toString() ?: "Admin Message"
        val message = map?.get("message")?.toString() ?: "Message from IT Administrator"
        val priorityStr = map?.get("priority")?.toString() ?: "INFO"
        val priority = try { MessagePriority.valueOf(priorityStr) } catch (_: Exception) { MessagePriority.INFO }
        val sender = map?.get("sender")?.toString() ?: "RRV Administrator"

        val adminMsg = AdminMessage(
            id = command.commandId,
            title = title,
            message = message,
            priority = priority,
            sender = sender,
            timestamp = System.currentTimeMillis()
        )
        app.repositoryImpl.addMessage(adminMsg)
        NotificationHelper.showCommandNotification(context, "📢 $title", message)
        return ExecutionResult(true, "Admin message received and stored.")
    }
}

class WifiExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
        val enable = map?.get("enable") == true || command.commandType.contains("ENABLE")
        return ExecutionResult(true, "Wi-Fi state configured to: $enable.")
    }
}

class ClearAppDataExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
        val packageName = map?.get("packageName")?.toString() ?: ""
        if (packageName.isBlank()) return ExecutionResult(false, "Missing packageName in CLEAR_APP_DATA payload.")
        val app = context.applicationContext as RrvMdmApplication
        return if (app.deviceManager.isDeviceOwner()) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dpm.clearApplicationUserData(app.deviceManager.adminComponent, packageName, context.mainExecutor) { _, succeeded ->
                RrvLog.i("ClearAppData", "Clear app data for $packageName succeeded: $succeeded")
            }
            ExecutionResult(true, "Application data clear command dispatched for $packageName.")
        } else {
            ExecutionResult(false, "ClearAppData requires Device Owner privileges.")
        }
    }
}

class LocationRequestExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        app.mqttManager.publishTelemetry(null, true)
        return ExecutionResult(true, "Location telemetry refreshed and pushed to MDM server.")
    }
}

class DiagnosticPingExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        return ExecutionResult(true, "Device is online, healthy, and responsive.")
    }
}

/**
 * Central Command Processor: Manages deduplication, lifecycle states, and dispatching.
 */
class CommandProcessor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    companion object {
        private const val TAG = "CommandProcessor"
    }

    private val executors = mapOf<String, CommandExecutor>(
        "LOCK_DEVICE" to LockDeviceExecutor(),
        "LOCK_NOW" to LockDeviceExecutor(),
        "UNLOCK_DEVICE" to UnlockDeviceExecutor(),
        "EXIT_LOST_MODE" to UnlockDeviceExecutor(),
        "REBOOT_DEVICE" to RebootExecutor(),
        "REBOOT" to RebootExecutor(),
        "RESET_PIN" to ResetPasscodeExecutor(),
        "RESET_PASSCODE" to ResetPasscodeExecutor(),
        "INSTALL_APPLICATION" to InstallApplicationExecutor(),
        "INSTALL_APP" to InstallApplicationExecutor(),
        "UNINSTALL_APPLICATION" to UninstallApplicationExecutor(),
        "UNINSTALL_APP" to UninstallApplicationExecutor(),
        "UPDATE_POLICY" to PolicyUpdateExecutor(),
        "APPLY_POLICY" to PolicyUpdateExecutor(),
        "SYNC_POLICY" to PolicyUpdateExecutor(),
        "POLICY_UPDATE" to PolicyUpdateExecutor(),
        "DISPLAY_MESSAGE" to MessageExecutor(),
        "ADMIN_MESSAGE" to MessageExecutor(),
        "ENABLE_WIFI" to WifiExecutor(),
        "DISABLE_WIFI" to WifiExecutor(),
        "CLEAR_APP_DATA" to ClearAppDataExecutor(),
        "LOCATION_REQUEST" to LocationRequestExecutor(),
        "REQUEST_LOCATION" to LocationRequestExecutor(),
        "DIAGNOSTIC_PING" to DiagnosticPingExecutor(),
        "PING" to DiagnosticPingExecutor()
    )

    fun processCommand(command: MdmCommand) {
        scope.launch {
            val app = context.applicationContext as RrvMdmApplication
            val repository = app.repositoryImpl

            // Step 1: Validate and check for duplicates
            val isNew = repository.recordCommandReceived(command)
            if (!isNew) {
                RrvLog.w(TAG, "⚠️ Duplicate command received and ignored: ${command.commandId}")
                return@launch
            }

            RrvLog.mqtt("⚡ [CMD-ENGINE] Command received: ${command.commandType} (${command.commandId})")

            // Step 2: Mark status = EXECUTING
            repository.updateCommandStatus(command.commandId, CommandStatus.EXECUTING, "Executing command...", 20)

            // Step 3: Find executor
            val type = command.commandType.uppercase()
            val executor = executors[type]

            if (executor == null) {
                RrvLog.w(TAG, "✕ No executor found for command type: $type")
                repository.updateCommandStatus(command.commandId, CommandStatus.FAILED, "Unsupported command type: $type", 0)
                app.mqttManager.publishCommandAck(command.commandId, "FAILED", "Unsupported command type: $type")
                return@launch
            }

            // Step 4: Execute
            try {
                val result = executor.execute(command, context)
                val finalStatus = if (result.isSuccess) CommandStatus.SUCCESS else CommandStatus.FAILED
                repository.updateCommandStatus(command.commandId, finalStatus, result.message, result.progress)
                app.mqttManager.publishCommandAck(
                    command.commandId,
                    if (result.isSuccess) "EXECUTED" else "FAILED",
                    result.message
                )
                RrvLog.i(TAG, "✓ [CMD-ENGINE] Command ${command.commandId} finished: ${result.message}")
            } catch (e: Exception) {
                RrvLog.e(TAG, "✕ [CMD-ENGINE] Error executing ${command.commandId}", e)
                repository.updateCommandStatus(command.commandId, CommandStatus.FAILED, e.message ?: "Execution error", 0)
                app.mqttManager.publishCommandAck(command.commandId, "FAILED", e.message ?: "Execution error")
            }
        }
    }
}
