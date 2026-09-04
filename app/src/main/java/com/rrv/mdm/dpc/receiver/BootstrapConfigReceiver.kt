package com.rrv.mdm.dpc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.data.config.BootstrapConfiguration
import com.rrv.mdm.dpc.data.config.MqttConfiguration
import com.rrv.mdm.dpc.data.config.ServerConfiguration
import com.rrv.mdm.dpc.util.RrvLog

/**
 * BroadcastReceiver for dynamic bootstrap and development configuration injection.
 * Enables zero-code-change environment switching across LOCAL, TEST, STAGING, and PRODUCTION.
 *
 * Example ADB Commands:
 *  - Inject Development Bridge:
 *    adb shell am broadcast -a com.rrv.mdm.ACTION_CONFIGURE_SERVER \
 *      --es server_url "http://127.0.0.1:8080" \
 *      --es mqtt_host "127.0.0.1" \
 *      --ei mqtt_port 1883 \
 *      --ez mqtt_tls false \
 *      --es environment "DEVELOPMENT"
 *
 *  - Inject Bootstrap Token:
 *    adb shell am broadcast -a com.rrv.mdm.ACTION_BOOTSTRAP_ENROLL \
 *      --es server_url "https://mdm.enterprise.com" \
 *      --es token "rrv-tok-abcdef123456"
 */
class BootstrapConfigReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootstrapConfigReceiver"
        const val ACTION_CONFIGURE_SERVER = "com.rrv.mdm.ACTION_CONFIGURE_SERVER"
        const val ACTION_BOOTSTRAP_ENROLL = "com.rrv.mdm.ACTION_BOOTSTRAP_ENROLL"
        const val ACTION_ROLLBACK_CONFIG = "com.rrv.mdm.ACTION_ROLLBACK_CONFIG"
        const val ACTION_FETCH_COMMANDS = "com.rrv.mdm.ACTION_FETCH_COMMANDS"
        const val ACTION_SYNC_POLICY = "com.rrv.mdm.ACTION_SYNC_POLICY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext as? RrvMdmApplication ?: return
        val configProvider = app.serverConfigProvider

        when (action) {
            ACTION_FETCH_COMMANDS, ACTION_SYNC_POLICY -> {
                RrvLog.i(TAG, "⚡ Instant Command & Policy Sync triggered via broadcast")
                app.mqttManager.fetchPendingCommandsFromServer()
            }
            ACTION_CONFIGURE_SERVER -> {
                val serverUrl = intent.getStringExtra("server_url") ?: intent.getStringExtra("api_base_url")
                if (serverUrl.isNullOrBlank()) {
                    RrvLog.w(TAG, "Missing server_url extra in ACTION_CONFIGURE_SERVER")
                    return
                }

                val uri = try { java.net.URI(serverUrl) } catch (_: Exception) { null }
                val defaultHost = uri?.host ?: "127.0.0.1"
                val defaultTls = uri?.scheme?.equals("https", ignoreCase = true) == true
                val defaultPort = if (defaultTls) 8883 else 1883

                val mqttHost = intent.getStringExtra("mqtt_host") ?: defaultHost
                val mqttPort = intent.getIntExtra("mqtt_port", defaultPort)
                val mqttTls = intent.getBooleanExtra("mqtt_tls", defaultTls)
                val env = intent.getStringExtra("environment") ?: if (defaultTls) "PRODUCTION" else "DEVELOPMENT"
                val version = intent.getIntExtra("version", configProvider.getConfigurationVersion() + 1)

                val newConfig = ServerConfiguration(
                    apiBaseUrl = serverUrl.trimEnd('/'),
                    mqtt = MqttConfiguration(
                        host = mqttHost,
                        port = mqttPort,
                        tls = mqttTls
                    ),
                    environment = env,
                    configurationVersion = version
                )

                val ok = configProvider.applyServerConfiguration(newConfig, testConnectivity = false)
                if (ok) {
                    app.repository.serverUrl = newConfig.apiBaseUrl
                    app.repository.mqttBrokerHost = newConfig.mqtt.host
                    app.repository.mqttPort = newConfig.mqtt.port
                    RrvLog.i(TAG, "✅ Server configuration dynamically applied from broadcast: $newConfig")
                    app.mqttManager.reconnect()
                } else {
                    RrvLog.e(TAG, "❌ Failed to apply server configuration from broadcast")
                }
            }

            ACTION_BOOTSTRAP_ENROLL -> {
                val serverUrl = intent.getStringExtra("server_url")
                val token = intent.getStringExtra("token") ?: intent.getStringExtra("enrollment_token")
                val orgId = intent.getStringExtra("org_id")

                val bootstrap = BootstrapConfiguration(
                    enrollmentServerUrl = serverUrl,
                    enrollmentToken = token,
                    orgId = orgId,
                    isDevelopmentOverride = true
                )
                configProvider.saveBootstrap(bootstrap)
                RrvLog.i(TAG, "⚡ Bootstrap parameters saved from broadcast: server=$serverUrl")

                // Trigger immediate enrollment if token is present
                if (!serverUrl.isNullOrBlank() && !token.isNullOrBlank()) {
                    app.apiClient.enrollDevice(serverUrl, token) { success, msg ->
                        RrvLog.i(TAG, "Dynamic enrollment result: success=$success, msg=$msg")
                    }
                }
            }

            ACTION_ROLLBACK_CONFIG -> {
                val ok = configProvider.rollbackToLastKnownGood()
                RrvLog.i(TAG, "Rollback to Last-Known-Good configuration: $ok")
            }
        }
    }
}
