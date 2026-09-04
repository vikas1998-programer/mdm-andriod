package com.rrv.mdm.dpc.data.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.rrv.mdm.dpc.util.RrvLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Authoritative Central Provider for MDM Runtime Server and MQTT Broker Endpoints.
 *
 * Implements enterprise-grade dynamic configuration with:
 *  - Encrypted local storage (AES-256 GCM)
 *  - Zero hardcoded production/staging/local URLs in binary bytecode
 *  - Configuration versioning and change notifications
 *  - Safe connectivity validation before switching
 *  - Last-Known-Good (LKG) rollback protection
 *  - Clean factory reset purge
 */
class ServerConfigurationProvider(private val context: Context) {

    companion object {
        private const val TAG = "ServerConfigProvider"
        private const val VAULT_NAME = "rrv_mdm_server_config_vault"
        private const val KEY_CURRENT_CONFIG = "KEY_ACTIVE_SERVER_CONFIG"
        private const val KEY_LKG_CONFIG = "KEY_LAST_KNOWN_GOOD_CONFIG"
        private const val KEY_BOOTSTRAP_URL = "KEY_BOOTSTRAP_ENROLL_URL"
        private const val KEY_BOOTSTRAP_TOKEN = "KEY_BOOTSTRAP_ENROLL_TOKEN"
        private const val KEY_BOOTSTRAP_ORG_ID = "KEY_BOOTSTRAP_ORG_ID"
    }

    interface OnConfigurationChangedListener {
        fun onConfigurationChanged(newConfig: ServerConfiguration)
    }

    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<OnConfigurationChangedListener>()

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                VAULT_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            RrvLog.w(TAG, "EncryptedSharedPreferences fallback engaged: ${e.message}")
            context.getSharedPreferences("${VAULT_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _configFlow = MutableStateFlow<ServerConfiguration?>(null)
    val configFlow: StateFlow<ServerConfiguration?> = _configFlow.asStateFlow()

    init {
        loadPersistedConfiguration()
    }

    private fun loadPersistedConfiguration() {
        val json = prefs.getString(KEY_CURRENT_CONFIG, null)
        if (!json.isNullOrBlank()) {
            try {
                val config = gson.fromJson(json, ServerConfiguration::class.java)
                _configFlow.value = config
                RrvLog.d(TAG, "Loaded active configuration: v${config.configurationVersion} [Env: ${config.environment}, API: ${config.apiBaseUrl}, MQTT: ${config.mqtt.serverUri}]")
            } catch (e: Exception) {
                RrvLog.e(TAG, "Failed to parse saved server config JSON", e)
            }
        }
    }

    fun getCurrentConfig(): ServerConfiguration? = _configFlow.value

    fun hasValidConfiguration(): Boolean = _configFlow.value != null

    fun getApiBaseUrl(): String? = _configFlow.value?.apiBaseUrl

    fun getMqttServerUri(): String? = _configFlow.value?.mqtt?.serverUri

    fun getConfigurationVersion(): Int = _configFlow.value?.configurationVersion ?: 0

    fun addListener(listener: OnConfigurationChangedListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: OnConfigurationChangedListener) {
        listeners.remove(listener)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Bootstrap Handling (QR code, Zero-Touch, NFC, ADB Intent)
    // ──────────────────────────────────────────────────────────────────────────

    fun saveBootstrap(bootstrap: BootstrapConfiguration) {
        prefs.edit().apply {
            if (bootstrap.enrollmentServerUrl != null) {
                putString(KEY_BOOTSTRAP_URL, bootstrap.enrollmentServerUrl.trimEnd('/'))
            }
            if (bootstrap.enrollmentToken != null) {
                putString(KEY_BOOTSTRAP_TOKEN, bootstrap.enrollmentToken)
            }
            if (bootstrap.orgId != null) {
                putString(KEY_BOOTSTRAP_ORG_ID, bootstrap.orgId)
            }
            apply()
        }
        RrvLog.d(TAG, "Bootstrap configuration cached: url=${bootstrap.enrollmentServerUrl}, tokenPresent=${bootstrap.enrollmentToken != null}")
    }

    fun getBootstrapServerUrl(): String? = prefs.getString(KEY_BOOTSTRAP_URL, null)

    fun getBootstrapEnrollmentToken(): String? = prefs.getString(KEY_BOOTSTRAP_TOKEN, null)

    fun getBootstrapOrgId(): String? = prefs.getString(KEY_BOOTSTRAP_ORG_ID, null)

    // ──────────────────────────────────────────────────────────────────────────
    // Authoritative Configuration Application & Safe Migration
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Validates and applies a new server configuration.
     * If testConnectivity is true, verifies the new endpoint responds before persisting.
     */
    fun applyServerConfiguration(
        newConfig: ServerConfiguration,
        testConnectivity: Boolean = false,
        httpClient: OkHttpClient? = null
    ): Boolean {
        if (!validateConfiguration(newConfig)) {
            RrvLog.e(TAG, "Rejecting invalid configuration payload: $newConfig")
            return false
        }

        // Connectivity pre-flight check if requested
        if (testConnectivity && httpClient != null) {
            val isReachable = testEndpointConnectivity(newConfig.apiBaseUrl, httpClient)
            if (!isReachable) {
                RrvLog.e(TAG, "Connectivity verification failed for: ${newConfig.apiBaseUrl}. Retaining current configuration.")
                return false
            }
        }

        val current = _configFlow.value
        val json = gson.toJson(newConfig)

        // Snapshot current as Last-Known-Good before switching
        if (current != null) {
            prefs.edit().putString(KEY_LKG_CONFIG, gson.toJson(current)).apply()
        }

        // Persist new authoritative configuration
        prefs.edit().putString(KEY_CURRENT_CONFIG, json).apply()
        _configFlow.value = newConfig

        RrvLog.i(TAG, "✅ Applied Server Configuration v${newConfig.configurationVersion} [Env: ${newConfig.environment}, BaseUrl: ${newConfig.apiBaseUrl}, MQTT: ${newConfig.mqtt.serverUri}]")

        // Notify subscribers (MQTT Client, REST Engine, Policy Sync)
        for (listener in listeners) {
            try {
                listener.onConfigurationChanged(newConfig)
            } catch (e: Exception) {
                RrvLog.e(TAG, "Error notifying configuration listener: ${e.message}", e)
            }
        }

        return true
    }

    /**
     * Rollback to Last-Known-Good configuration in case runtime failures occur with a newly applied config.
     */
    fun rollbackToLastKnownGood(): Boolean {
        val lkgJson = prefs.getString(KEY_LKG_CONFIG, null)
        if (lkgJson.isNullOrBlank()) {
            RrvLog.w(TAG, "No Last-Known-Good configuration available for rollback.")
            return false
        }

        try {
            val lkgConfig = gson.fromJson(lkgJson, ServerConfiguration::class.java)
            prefs.edit().putString(KEY_CURRENT_CONFIG, lkgJson).apply()
            _configFlow.value = lkgConfig
            RrvLog.w(TAG, "⚠️ Rolled back to Last-Known-Good Configuration v${lkgConfig.configurationVersion} [${lkgConfig.apiBaseUrl}]")

            for (listener in listeners) {
                try {
                    listener.onConfigurationChanged(lkgConfig)
                } catch (e: Exception) {
                    RrvLog.e(TAG, "Error notifying listener during rollback", e)
                }
            }
            return true
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to parse Last-Known-Good configuration", e)
            return false
        }
    }

    fun validateConfiguration(config: ServerConfiguration): Boolean {
        if (config.apiBaseUrl.isBlank() || config.mqtt.host.isBlank()) return false
        if (config.mqtt.port !in 1..65535) return false

        return try {
            val uri = URI(config.apiBaseUrl)
            uri.scheme != null && (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true))
        } catch (_: Exception) {
            false
        }
    }

    private fun testEndpointConnectivity(url: String, client: OkHttpClient): Boolean {
        return try {
            val cleanUrl = url.trimEnd('/')
            val req = Request.Builder()
                .url("$cleanUrl/api/v1/health")
                .header("ngrok-skip-browser-warning", "true")
                .head()
                .build()
            val resp = client.newCall(req).execute()
            resp.close()
            resp.isSuccessful || resp.code in 200..499 // Any response from host indicates reachability
        } catch (e: Exception) {
            RrvLog.w(TAG, "Pre-flight endpoint check error for $url: ${e.message}")
            // Fallback check root URL
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("ngrok-skip-browser-warning", "true")
                    .head()
                    .build()
                val resp = client.newCall(req).execute()
                resp.close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Purges runtime configuration upon enterprise factory reset / retirement.
     */
    fun clearOnFactoryReset() {
        prefs.edit().clear().apply()
        _configFlow.value = null
        RrvLog.i(TAG, "Purged all server configuration on factory reset.")
    }
}
