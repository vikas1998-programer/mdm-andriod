package com.rrv.mdm.dpc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.util.RrvLog

/**
 * Handles asynchronous installation commit results dispatched by Android's PackageInstaller Session API.
 */
class SilentInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SilentInstallReceiver"
        const val ACTION_SILENT_INSTALL_RESULT = "com.rrv.mdm.dpc.SILENT_INSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SILENT_INSTALL_RESULT) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

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
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                RrvLog.w(TAG, "⚠️ PackageInstaller requested user confirmation for '$packageName'")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed: INSUFFICIENT_STORAGE ($message)")
                reportFailure(repository?.deviceId, mqttManager, packageName, "INSUFFICIENT_STORAGE: $message")
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed: INVALID_APK ($message)")
                reportFailure(repository?.deviceId, mqttManager, packageName, "INVALID_APK: $message")
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed: SIGNATURE_CONFLICT ($message)")
                reportFailure(repository?.deviceId, mqttManager, packageName, "SIGNATURE_CONFLICT: $message")
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed: INCOMPATIBLE_SDK ($message)")
                reportFailure(repository?.deviceId, mqttManager, packageName, "INCOMPATIBLE_SDK: $message")
            }

            else -> {
                RrvLog.e(TAG, "✕ Silent install of '$packageName' failed with code $status ($message)")
                reportFailure(repository?.deviceId, mqttManager, packageName, "INSTALL_FAILED_CODE_$status: $message")
            }
        }
    }

    private fun reportFailure(deviceId: String?, mqttManager: com.rrv.mdm.dpc.network.MdmMqttManager?, packageName: String, errorReason: String) {
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
