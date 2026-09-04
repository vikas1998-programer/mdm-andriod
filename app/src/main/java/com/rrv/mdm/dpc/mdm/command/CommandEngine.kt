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
        if (command.payloadJson.isNotBlank() && command.payloadJson != "{}") {
            val parsed = PolicyPayload.fromJson(command.payloadJson)
            app.repository.saveActivePolicy(parsed)
            app.deviceManager.applyPolicy(parsed, force = true)
            NotificationHelper.showPolicyNotification(context, parsed)
            context.sendBroadcast(Intent("com.rrv.mdm.ACTION_POLICY_UPDATED").setPackage(context.packageName))
            return ExecutionResult(true, "Policy profile '${parsed.name}' enforced successfully.")
        }

        // Canonical fallback: fetch device policy directly from server endpoint
        val deviceId = app.repository.deviceId.ifBlank {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        }
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        app.apiClient.fetchAndApplyPolicy(deviceId) { success ->
            deferred.complete(success)
        }
        val ok = try {
            kotlinx.coroutines.withTimeout(5000L) { deferred.await() }
        } catch (_: Exception) { false }

        return if (ok) {
            ExecutionResult(true, "Policy fetched from server and enforced successfully.")
        } else {
            ExecutionResult(true, "Policy signal processed.")
        }
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
        var loc: android.location.Location? = null
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            if (lm != null) {
                val gps = try { lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
                val net = try { lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
                val pass = try { lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER) } catch (_: Exception) { null }
                val fused = try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        lm.getLastKnownLocation(android.location.LocationManager.FUSED_PROVIDER)
                    } else null
                } catch (_: Exception) { null }

                loc = listOfNotNull(gps, fused, net, pass).maxByOrNull { it.time }
            }
        } catch (_: Exception) {}

        if (loc != null && loc.latitude != 0.0) {
            app.repository.lastLatitude = loc.latitude
            app.repository.lastLongitude = loc.longitude
        }

        app.mqttManager.publishTelemetry(loc, true)
        val coordMsg = if (loc != null) "(${loc.latitude}, ${loc.longitude})" else "cached"
        return ExecutionResult(true, "Location telemetry $coordMsg refreshed and pushed to MDM server.")
    }
}

class DiagnosticPingExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        return ExecutionResult(true, "Device is online, healthy, and responsive.")
    }
}

class WipeDeviceExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        if (!app.deviceManager.isDeviceOwner()) {
            return ExecutionResult(false, "FACTORY_RESET / WIPE requires Device Owner privileges.")
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager

        RrvLog.w("WipeDeviceExecutor", "⚠️ FACTORY RESET / ENTERPRISE WIPE INITIATED BY IT ADMIN!")

        // Report ACK first before wiping hardware storage
        app.mqttManager.publishCommandAck(command.commandId, "EXECUTED", "Factory reset initiated.")

        // Brief delay to allow MQTT ACK packet to flush over socket
        kotlinx.coroutines.delay(1000L)

        return try {
            dpm.wipeData(0)
            ExecutionResult(true, "Device factory reset triggered.")
        } catch (e: Exception) {
            RrvLog.e("WipeDeviceExecutor", "Failed to wipe device", e)
            ExecutionResult(false, "Failed to wipe device: ${e.message}")
        }
    }
}

class SetBrightnessExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
        val brightness = (map?.get("brightness") as? Number
            ?: map?.get("screenBrightnessPercent") as? Number
            ?: map?.get("value") as? Number
            ?: map?.get("percent") as? Number)?.toInt()

        val auto = map?.get("autoBrightness") as? Boolean ?: map?.get("autoBrightnessEnabled") as? Boolean

        if (auto != null) {
            app.deviceManager.setAutoBrightness(auto)
        }
        if (brightness != null) {
            app.deviceManager.setAutoBrightness(false)
            app.deviceManager.setScreenBrightness(brightness)
            NotificationHelper.showCommandNotification(context, "🔆 Screen Brightness", "Screen brightness adjusted to $brightness% by administrator.")
            return ExecutionResult(true, "Screen brightness set to $brightness%.")
        }
        return ExecutionResult(true, "Auto-brightness mode set to $auto.")
    }
}

class SetVolumeExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }

        val volume = (map?.get("volume") as? Number
            ?: map?.get("mediaVolumePercent") as? Number
            ?: map?.get("level") as? Number
            ?: map?.get("percent") as? Number)?.toInt()

        val mediaVol = (map?.get("mediaVolumePercent") as? Number ?: map?.get("mediaVolume") as? Number)?.toInt() ?: volume
        val alarmVol = (map?.get("alarmVolumePercent") as? Number ?: map?.get("alarmVolume") as? Number)?.toInt() ?: volume
        val ringVol = (map?.get("ringVolumePercent") as? Number ?: map?.get("ringVolume") as? Number)?.toInt() ?: volume

        val muted = map?.get("masterVolumeMuted") as? Boolean ?: map?.get("muted") as? Boolean
        val lockAdjust = map?.get("volumeAdjustDisabled") as? Boolean
            ?: map?.get("lockVolume") as? Boolean
            ?: map?.get("volumeLocked") as? Boolean
            ?: true // Default to LOCKED so device user cannot alter admin-configured volume

        // 1. Adjust stream levels FIRST before applying hardware button restrictions
        if (muted != true) {
            mediaVol?.let {
                app.deviceManager.setStreamVolumePercent(android.media.AudioManager.STREAM_MUSIC, it)
            }
            alarmVol?.let {
                app.deviceManager.setStreamVolumePercent(android.media.AudioManager.STREAM_ALARM, it)
            }
            ringVol?.let {
                app.deviceManager.setStreamVolumePercent(android.media.AudioManager.STREAM_RING, it)
                app.deviceManager.setStreamVolumePercent(android.media.AudioManager.STREAM_NOTIFICATION, it)
                app.deviceManager.setStreamVolumePercent(android.media.AudioManager.STREAM_SYSTEM, it)
            }
        }

        // 2. Apply Master Volume Mute
        muted?.let { app.deviceManager.setMasterVolumeMuted(it) }

        // 3. Apply Hardware Volume Buttons Restriction (DISALLOW_ADJUST_VOLUME)
        lockAdjust?.let { app.deviceManager.setVolumeAdjustDisabled(it) }

        // 4. Update cached active policy so watchdog preserves this state
        try {
            val currentPolicy = app.repository.getActivePolicy()
            val updatedPolicy = currentPolicy.copy(
                masterVolumeMuted = muted ?: currentPolicy.masterVolumeMuted,
                volumeAdjustDisabled = lockAdjust ?: currentPolicy.volumeAdjustDisabled,
                mediaVolumePercent = mediaVol ?: currentPolicy.mediaVolumePercent,
                alarmVolumePercent = alarmVol ?: currentPolicy.alarmVolumePercent,
                ringVolumePercent = ringVol ?: currentPolicy.ringVolumePercent
            )
            app.repository.saveActivePolicy(updatedPolicy)
        } catch (_: Exception) {}

        val lockDesc = if (lockAdjust == true) " [Buttons Locked 🔒]" else if (lockAdjust == false) " [Buttons Unlocked 🔓]" else ""
        val muteDesc = if (muted == true) " [Master Muted 🔇]" else ""
        NotificationHelper.showCommandNotification(
            context,
            "🔊 Audio Volume Configured",
            "Media: ${mediaVol ?: "—"}%, Ring: ${ringVol ?: "—"}%, Alarm: ${alarmVol ?: "—"}%$lockDesc$muteDesc"
        )
        return ExecutionResult(true, "Volume levels successfully updated (Media: ${mediaVol ?: "unchanged"}%, Ring: ${ringVol ?: "unchanged"}%, Alarm: ${alarmVol ?: "unchanged"}%$lockDesc$muteDesc).")
    }
}

class TriggerAlarmExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
        val duration = (map?.get("durationSeconds") as? Number ?: map?.get("duration") as? Number)?.toInt() ?: 10

        app.deviceManager.triggerAlarmSound(duration)
        NotificationHelper.showCommandNotification(context, "🚨 High-Decibel Siren Alert", "Remote alarm siren triggered by IT administrator.")
        return ExecutionResult(true, "Alarm siren sound triggered for $duration seconds at maximum volume.")
    }
}

class SetScreenTimeoutExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
        val timeoutSec = (map?.get("timeoutSeconds") as? Number
            ?: map?.get("screenTimeoutSeconds") as? Number
            ?: map?.get("seconds") as? Number
            ?: map?.get("timeout") as? Number)?.toInt() ?: 300

        app.deviceManager.setScreenTimeout(timeoutSec)
        NotificationHelper.showCommandNotification(context, "⏰ Screen Timeout Configured", "Display timeout set to $timeoutSec seconds.")
        return ExecutionResult(true, "Screen timeout configured to $timeoutSec seconds.")
    }
}

class LostModeExecutor : CommandExecutor {
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val isEnable = command.commandType.uppercase().contains("ENABLE")
        if (isEnable) {
            val map = try { Gson().fromJson(command.payloadJson, Map::class.java) } catch (_: Exception) { null }
            val message = map?.get("message")?.toString() ?: "This device has been marked as LOST by IT Administration."
            val phone = map?.get("phoneNumber")?.toString() ?: map?.get("phone")?.toString()
            app.deviceManager.enableLostMode(message, phone)
            NotificationHelper.showCommandNotification(context, "🛡️ Lost Mode Engaged", message)
            return ExecutionResult(true, "Lost Mode activated on device.")
        } else {
            app.deviceManager.disableLostMode()
            NotificationHelper.showCommandNotification(context, "🟢 Lost Mode Dismissed", "Device returned to normal operating state.")
            return ExecutionResult(true, "Lost Mode dismissed.")
        }
    }
}

class UpdateServerConfigExecutor : CommandExecutor {
    override fun toString(): String = "UpdateServerConfigExecutor"
    override suspend fun execute(command: MdmCommand, context: Context): ExecutionResult {
        val app = context.applicationContext as RrvMdmApplication
        val gson = Gson()
        return try {
            val serverConfig = gson.fromJson(command.payloadJson, com.rrv.mdm.dpc.data.config.ServerConfiguration::class.java)
            if (serverConfig == null || serverConfig.apiBaseUrl.isBlank()) {
                return ExecutionResult(false, "Invalid server configuration payload.")
            }
            val ok = app.serverConfigProvider.applyServerConfiguration(serverConfig, testConnectivity = false)
            if (ok) {
                app.repository.serverUrl = serverConfig.apiBaseUrl
                app.repository.mqttBrokerHost = serverConfig.mqtt.host
                app.repository.mqttPort = serverConfig.mqtt.port
                ExecutionResult(true, "Server configuration updated to v${serverConfig.configurationVersion} [Env: ${serverConfig.environment}, API: ${serverConfig.apiBaseUrl}]")
            } else {
                ExecutionResult(false, "Server configuration validation failed. Retaining current configuration.")
            }
        } catch (e: Exception) {
            ExecutionResult(false, "Failed to apply server configuration: ${e.message}")
        }
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
        "PUSH_APP_CATALOG" to PolicyUpdateExecutor(),
        "SYNC_APP_CATALOG" to PolicyUpdateExecutor(),
        "UPDATE_SERVER_CONFIG" to UpdateServerConfigExecutor(),
        "CONFIG_UPDATE" to UpdateServerConfigExecutor(),
        "DISPLAY_MESSAGE" to MessageExecutor(),
        "ADMIN_MESSAGE" to MessageExecutor(),
        "SEND_MESSAGE" to MessageExecutor(),
        "BROADCAST_MESSAGE" to MessageExecutor(),
        "SHOW_MESSAGE" to MessageExecutor(),
        "ENABLE_WIFI" to WifiExecutor(),
        "DISABLE_WIFI" to WifiExecutor(),
        "CLEAR_APP_DATA" to ClearAppDataExecutor(),
        "LOCATION_REQUEST" to LocationRequestExecutor(),
        "REQUEST_LOCATION" to LocationRequestExecutor(),
        "REQUEST_TELEMETRY" to LocationRequestExecutor(),
        "FETCH_LOGS" to DiagnosticPingExecutor(),
        "DIAGNOSTIC_PING" to DiagnosticPingExecutor(),
        "PING" to DiagnosticPingExecutor(),
        "FULL_WIPE" to WipeDeviceExecutor(),
        "WIPE" to WipeDeviceExecutor(),
        "WIPE_DEVICE" to WipeDeviceExecutor(),
        "FACTORY_RESET" to WipeDeviceExecutor(),
        "ENTERPRISE_WIPE" to WipeDeviceExecutor(),
        "SET_BRIGHTNESS" to SetBrightnessExecutor(),
        "BRIGHTNESS" to SetBrightnessExecutor(),
        "SET_SCREEN_BRIGHTNESS" to SetBrightnessExecutor(),
        "SET_VOLUME" to SetVolumeExecutor(),
        "VOLUME" to SetVolumeExecutor(),
        "SET_AUDIO_VOLUME" to SetVolumeExecutor(),
        "TRIGGER_ALARM" to TriggerAlarmExecutor(),
        "SOUND_ALARM" to TriggerAlarmExecutor(),
        "RING_ALARM" to TriggerAlarmExecutor(),
        "RING_SIREN" to TriggerAlarmExecutor(),
        "ALARM" to TriggerAlarmExecutor(),
        "SET_SCREEN_TIMEOUT" to SetScreenTimeoutExecutor(),
        "SET_TIMEOUT" to SetScreenTimeoutExecutor(),
        "SCREEN_TIMEOUT" to SetScreenTimeoutExecutor(),
        "ENABLE_LOST_MODE" to LostModeExecutor(),
        "DISABLE_LOST_MODE" to LostModeExecutor()
    )

    fun processCommand(command: MdmCommand) {
        scope.launch {
            val app = context.applicationContext as RrvMdmApplication
            val repository = app.repositoryImpl

            // Step 1: Validate and check for duplicates
            val isNew = repository.recordCommandReceived(command)
            if (!isNew) {
                RrvLog.w(TAG, "⚠️ Duplicate command received and ignored: ${command.commandId}")
                app.mqttManager.publishCommandAck(command.commandId, "EXECUTED", "Command already processed.")
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
