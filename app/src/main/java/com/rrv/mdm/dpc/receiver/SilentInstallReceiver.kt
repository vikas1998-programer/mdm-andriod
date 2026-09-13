package com.rrv.mdm.dpc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.domain.model.CommandStatus
import com.rrv.mdm.dpc.util.RrvLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles asynchronous installation commit results dispatched by Android's PackageInstaller Session API.
 */
class SilentInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SilentInstallReceiver"
        const val ACTION_SILENT_INSTALL_RESULT = "com.rrv.mdm.dpc.SILENT_INSTALL_RESULT"
        const val EXTRA_COMMAND_ID = "EXTRA_COMMAND_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SILENT_INSTALL_RESULT) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val commandId = intent.getStringExtra(EXTRA_COMMAND_ID)

        val app = context.applicationContext as? RrvMdmApplication
        val mqttManager = app?.mqttManager
        val repository = app?.repository

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                RrvLog.i(TAG, "✅ Package '$packageName' installed successfully via Device Owner PackageInstaller Session.")
                val deviceId = repository?.deviceId ?: ""
                if (deviceId.isNotBlank() && mqttManager != null) {
                    val topic = "rrv/devices/$deviceId/app_events"
                    val payload = """
                        {
                          "event": "APP_INSTALLED",
                          "packageName": "$packageName",
                          "installer": "MDM_SILENT_PUSH",
                          "status": "SUCCESS",
                          "timestamp": ${System.currentTimeMillis()}
                        }
                    """.trimIndent()
                    mqttManager.publishRaw(topic, payload, qos = 1, retained = false)
                }

                if (!commandId.isNullOrBlank()) {
                    app?.repositoryImpl?.let { repo ->
                        CoroutineScope(Dispatchers.IO).launch {
                            repo.updateCommandStatus(commandId, CommandStatus.SUCCESS, "Package $packageName installed successfully.", 100)
                        }
                    }
                    mqttManager?.publishCommandAck(commandId, "EXECUTED", "Package $packageName installed successfully.")
                }

                app?.deviceManager?.applyPolicy(app.repository.getActivePolicy())
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                RrvLog.w(TAG, "⚠️ PackageInstaller requested user confirmation for '$packageName'")
                @Suppress("DEPRECATION")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed: INSUFFICIENT_STORAGE ($message)")
                reportFailure(app, commandId, packageName, "INSUFFICIENT_STORAGE: $message")
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed: INVALID_APK ($message)")
                reportFailure(app, commandId, packageName, "INVALID_APK: $message")
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed: SIGNATURE_CONFLICT ($message)")
                reportFailure(app, commandId, packageName, "SIGNATURE_CONFLICT: $message")
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed: INCOMPATIBLE_SDK ($message)")
                reportFailure(app, commandId, packageName, "INCOMPATIBLE_SDK: $message")
            }

            else -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed with code $status ($message)")
                reportFailure(app, commandId, packageName, "INSTALL_FAILED_CODE_$status: $message")
            }
        }
    }

    private fun reportFailure(app: RrvMdmApplication?, commandId: String?, packageName: String, errorReason: String) {
        val mqttManager = app?.mqttManager
        val repository = app?.repository
        val deviceId = repository?.deviceId

        if (!commandId.isNullOrBlank()) {
            app?.repositoryImpl?.let { repo ->
                CoroutineScope(Dispatchers.IO).launch {
                    repo.updateCommandStatus(commandId, CommandStatus.FAILED, errorReason, 0)
                }
            }
            mqttManager?.publishCommandAck(commandId, "FAILED", errorReason)
        }

        if (deviceId.isNullOrBlank() || mqttManager == null) return
        val topic = "rrv/devices/$deviceId/app_events"
        val payload = """
            {
              "event": "APP_INSTALL_FAILED",
              "packageName": "$packageName",
              "installer": "MDM_SILENT_PUSH",
              "status": "FAILED",
              "errorMessage": "$errorReason",
              "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
        mqttManager.publishRaw(topic, payload, qos = 1, retained = false)
    }
}
