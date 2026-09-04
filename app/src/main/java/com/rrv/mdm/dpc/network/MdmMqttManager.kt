package com.rrv.mdm.dpc.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.google.gson.Gson
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.data.config.ServerConfiguration
import com.rrv.mdm.dpc.data.config.ServerConfigurationProvider
import com.rrv.mdm.dpc.data.model.*
import com.rrv.mdm.dpc.util.RrvLog
import com.rrv.mdm.dpc.worker.ApkDownloadWorker
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets

/**
 * Enterprise MQTT v3.1.1 / TLS Protocol Manager.
 * Handles sub-second bi-directional remote commands, LWT status, and live telemetry streaming.
 */
@SuppressLint("HardwareIds", "MissingPermission")
class MdmMqttManager(private val context: Context) : MqttCallbackExtended, ServerConfigurationProvider.OnConfigurationChangedListener {

    companion object {
        private const val TAG = "MdmMqttManager"
        private const val QOS_COMMANDS = 1
        private const val QOS_TELEMETRY = 0
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("ngrok-skip-browser-warning", "true")
                .build()
            chain.proceed(req)
        }
        .build()

    private var mqttClient: MqttAsyncClient? = null
    private var isConnecting = false

    private val app get() = context.applicationContext as? RrvMdmApplication
    private val repository get() = (context.applicationContext as RrvMdmApplication).repository
    private val policyManager get() = (context.applicationContext as RrvMdmApplication).policyManager
    private val configProvider get() = app?.serverConfigProvider

    init {
        RrvLog.onLogPublished = { entry ->
            publishDeviceLog(entry)
        }
        configProvider?.addListener(this)
    }

    override fun onConfigurationChanged(newConfig: ServerConfiguration) {
        RrvLog.i(TAG, "🔄 Dynamic Server Configuration changed to v${newConfig.configurationVersion} [Broker: ${newConfig.mqtt.serverUri}]. Reconnecting...")
        reconnect()
    }

    private val connectLock = Any()
    private var onConnectJob: Job? = null
    private var heartbeatJob: Job? = null

    fun reconnect() {
        synchronized(connectLock) {
            try {
                mqttClient?.setCallback(null)
                if (mqttClient?.isConnected == true) {
                    mqttClient?.disconnectForcibly(1000L)
                }
                mqttClient?.close()
            } catch (_: Exception) {}
            mqttClient = null
            isConnecting = false
        }
        connect()
    }

    private fun publishDeviceLog(entry: RrvLog.DeviceLogEntry) {
        if (!isConnected()) return
        val deviceId = getEffectiveDeviceId()
        val topic = "rrv/devices/$deviceId/logs"
        val payload = gson.toJson(entry)
        publish(topic, payload, 0, false)
    }

    fun connect() {
        val deviceId = getEffectiveDeviceId()
        if (deviceId.isBlank()) {
            RrvLog.w(TAG, "Cannot connect MQTT: Device ID is empty.")
            return
        }

        synchronized(connectLock) {
            if (mqttClient?.isConnected == true || isConnecting) {
                RrvLog.d(TAG, "MQTT already connected or in connection progress.")
                return
            }

            if (mqttClient != null) {
                try {
                    mqttClient?.setCallback(null)
                    mqttClient?.close()
                } catch (_: Exception) {}
                mqttClient = null
            }

            isConnecting = true
            val currentConfig = configProvider?.getCurrentConfig()
            val serverUri = currentConfig?.mqtt?.serverUri?.takeIf { it.isNotBlank() && !it.endsWith(":0") && !it.contains("://:0") }
                ?: run {
                    val brokerHost = repository.mqttBrokerHost
                    val port = repository.mqttPort
                    if (brokerHost.isNotBlank() && port > 0) {
                        if (port == 8883) "ssl://$brokerHost:$port" else "tcp://$brokerHost:$port"
                    } else {
                        val sUrl = configProvider?.getBootstrapServerUrl() ?: repository.serverUrl
                        val uri = try { if (sUrl.isNotBlank()) java.net.URI(sUrl) else null } catch (_: Exception) { null }
                        val host = uri?.host ?: ""
                        if (host.contains("ngrok") || host.contains("trycloudflare") || host.contains("cloudflare") || host.isBlank()) {
                            "tcp://127.0.0.1:1883"
                        } else {
                            val isHttps = uri?.scheme?.equals("https", ignoreCase = true) == true
                            val defaultPort = if (isHttps) 8883 else 1883
                            if (isHttps) "ssl://$host:$defaultPort" else "tcp://$host:$defaultPort"
                        }
                    }
                }

            if (serverUri.isNullOrBlank() || serverUri.endsWith(":0") || serverUri.contains("://:0") || serverUri.startsWith("tcp://:") || serverUri.startsWith("ssl://:")) {
                isConnecting = false
                RrvLog.d(TAG, "Cannot connect MQTT: Valid server/broker URI is not configured yet (Current: '$serverUri').")
                return
            }
            val clientId = "rrv-dpc-$deviceId"

            try {
                RrvLog.mqtt("Initializing Enterprise MQTT Client -> $serverUri (ClientID: $clientId)...")
                val client = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
                client.setCallback(this)
                mqttClient = client

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    keepAliveInterval = 30
                    connectionTimeout = 15
                    maxInflight = 1000

                    // Authenticate with Device ID and Signed Device JWT Token
                    userName = deviceId
                    if (repository.deviceJwt.isNotBlank()) {
                        password = repository.deviceJwt.toCharArray()
                    }

                    // Last Will and Testament (LWT) for instant offline detection
                    val lwtTopic = "rrv/devices/$deviceId/status"
                    val lwtPayload = """{"status":"OFFLINE","reason":"UNEXPECTED_DISCONNECT","timestamp":${System.currentTimeMillis()}}"""
                    setWill(lwtTopic, lwtPayload.toByteArray(StandardCharsets.UTF_8), 1, true)
                }

                client.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        isConnecting = false
                        RrvLog.mqtt("✓ Enterprise MQTT Connected -> $serverUri!")
                        onConnectedSuccessfully()
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        isConnecting = false
                        RrvLog.e(TAG, "✕ Enterprise MQTT Connection Failed: ${exception?.message}", exception)
                        if (serverUri != "tcp://127.0.0.1:1883") {
                            RrvLog.i(TAG, "Attempting loopback fallback connection to tcp://127.0.0.1:1883...")
                            try {
                                val fallbackClient = MqttAsyncClient("tcp://127.0.0.1:1883", clientId, MemoryPersistence())
                                fallbackClient.setCallback(this@MdmMqttManager)
                                mqttClient = fallbackClient
                                fallbackClient.connect(options, null, object : IMqttActionListener {
                                    override fun onSuccess(t: IMqttToken?) {
                                        RrvLog.mqtt("✓ Enterprise MQTT Fallback Connected -> tcp://127.0.0.1:1883!")
                                        onConnectedSuccessfully()
                                    }
                                    override fun onFailure(t: IMqttToken?, e: Throwable?) {
                                        RrvLog.w(TAG, "Fallback MQTT connection failed: ${e?.message}")
                                    }
                                })
                            } catch (_: Exception) {}
                        }
                    }
                })
            } catch (e: Exception) {
                isConnecting = false
                RrvLog.e(TAG, "Error initiating MQTT client", e)
            }
        }
    }

    private fun onConnectedSuccessfully() {
        onConnectJob?.cancel()
        onConnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(300L) // Ensure Paho internal client state transition completes
            if (!isConnected()) return@launch
            val deviceId = getEffectiveDeviceId()
            val realSerial = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Build.getSerial()
                } else {
                    @Suppress("DEPRECATION")
                    Build.SERIAL
                }
            } catch (_: Exception) { "" }

            // 1. Subscribe to Dynamic Device-Specific Command Topics
            subscribe("rrv/devices/$deviceId/commands", QOS_COMMANDS)
            if (realSerial.isNotBlank() && realSerial != "unknown" && realSerial != deviceId) {
                subscribe("rrv/devices/$realSerial/commands", QOS_COMMANDS)
            }
            subscribe("rrv/devices/all/commands", QOS_COMMANDS)

            // 2. Publish Online Status (Retained)
            val statusTopic = "rrv/devices/$deviceId/status"
            val onlinePayload = """{"status":"ONLINE","osVersion":"${Build.VERSION.RELEASE}","timestamp":${System.currentTimeMillis()}}"""
            publish(statusTopic, onlinePayload, 1, true)
            if (realSerial.isNotBlank() && realSerial != "unknown" && realSerial != deviceId) {
                publish("rrv/devices/$realSerial/status", onlinePayload, 1, true)
            }

            // 3. Publish Immediate Heartbeat & Application Inventory
            publishTelemetry(null, true)
            publishAppInventory()
            startHeartbeatLoop()

            // 4. Fetch any pending commands missed while offline
            fetchPendingCommandsFromServer()
        }
    }

    /**
     * Fetches all pending (DISPATCHED) commands from server via REST.
     * Called on MQTT connect and device boot to catch up on missed commands.
     * Part of the MQTT Signal + REST Payload Fetch architecture.
     */
    fun fetchPendingCommandsFromServer() {
        val deviceId = getEffectiveDeviceId()
        if (deviceId.isBlank()) return

        val serverUrl = (configProvider?.getApiBaseUrl() ?: repository.serverUrl).trimEnd('/')
        val jwt = repository.deviceJwt
        val endpoint = "$serverUrl/api/v1/commands/device/$deviceId/pending"

        RrvLog.i(TAG, "🔍 Requesting pending commands from $endpoint...")
        val request = okhttp3.Request.Builder()
            .url(endpoint)
            .get()
            .apply { if (jwt.isNotBlank()) header("Authorization", "Bearer $jwt") }
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                RrvLog.w(TAG, "⚠️ Could not fetch pending commands from server: ${e.message}")
                if (e is java.net.UnknownHostException && !serverUrl.contains("127.0.0.1") && !serverUrl.contains("localhost")) {
                    RrvLog.i(TAG, "Attempting ADB reverse loopback commands fetch from http://127.0.0.1:8080...")
                    val fallbackEndpoint = "http://127.0.0.1:8080/api/v1/commands/device/$deviceId/pending"
                    val fallbackReq = request.newBuilder().url(fallbackEndpoint).build()
                    httpClient.newCall(fallbackReq).enqueue(object : okhttp3.Callback {
                        override fun onFailure(c: okhttp3.Call, ex: java.io.IOException) {
                            RrvLog.w(TAG, "Fallback pending commands failed: ${ex.message}")
                        }
                        override fun onResponse(c: okhttp3.Call, resp: okhttp3.Response) {
                            if (!resp.isSuccessful) return
                            val respBody = resp.body?.string() ?: "[]"
                            try {
                                val type = object : com.google.gson.reflect.TypeToken<List<MqttCommandPayload>>() {}.type
                                val pendingCmds: List<MqttCommandPayload> = gson.fromJson(respBody, type) ?: emptyList()
                                if (pendingCmds.isNotEmpty()) {
                                    RrvLog.i(TAG, "📬 [Fallback] Fetched ${pendingCmds.size} pending commands from server — executing...")
                                    pendingCmds.forEach { cmd -> handleInboundCommand(cmd) }
                                }
                            } catch (e: Exception) {
                                RrvLog.e(TAG, "Failed to parse fallback pending commands", e)
                            }
                        }
                    })
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                RrvLog.d(TAG, "Pending commands HTTP response code: ${response.code}")
                if (!response.isSuccessful) return
                val body = response.body?.string() ?: "[]"
                try {
                    val type = object : com.google.gson.reflect.TypeToken<List<MqttCommandPayload>>() {}.type
                    val pendingCmds: List<MqttCommandPayload> = gson.fromJson(body, type) ?: emptyList()
                    if (pendingCmds.isNotEmpty()) {
                        RrvLog.i(TAG, "📬 Fetched ${pendingCmds.size} pending commands from server — executing...")
                        pendingCmds.forEach { cmd -> handleInboundCommand(cmd) }
                    } else {
                        RrvLog.d(TAG, "No pending commands found on server.")
                    }
                } catch (e: Exception) {
                    RrvLog.e(TAG, "Failed to parse pending commands response", e)
                }
            }
        })
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(30_000L) // Stream device telemetry & status every 30 seconds
                if (isConnected()) {
                    try {
                        publishTelemetry(null, isGeofenceCompliant = true)
                    } catch (e: Exception) {
                        RrvLog.w(TAG, "Active heartbeat tick error: ${e.message}")
                    }
                } else if (repository.isEnrolled || app?.deviceManager?.isDeviceOwner() == true) {
                    RrvLog.d(TAG, "Heartbeat tick: MQTT client disconnected, reconnecting...")
                    reconnect()
                }
            }
        }
    }

    private fun stopHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        isConnecting = false
        RrvLog.mqtt("✓ MQTT ConnectComplete (Reconnect: $reconnect) -> $serverURI")
        onConnectedSuccessfully()
    }

    override fun connectionLost(cause: Throwable?) {
        isConnecting = false
        RrvLog.w(TAG, "⚠️ MQTT Connection lost: ${cause?.message}. Auto-reconnect active in background...")
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        if (message == null || topic == null) return
        val payloadStr = String(message.payload, StandardCharsets.UTF_8)
        RrvLog.mqtt("📥 Inbound MQTT signal on [$topic]: $payloadStr")

        try {
            val cmd = gson.fromJson(payloadStr, MqttCommandPayload::class.java)

            // ── SENIOR ARCHITECT PATTERN: MQTT Signal + REST Payload Fetch ──────────
            // MQTT carries only: { commandId, commandType } — ~150 bytes
            // Full payload is fetched from REST: GET /api/v1/commands/{commandId}
            // This keeps MQTT lean and avoids TooLongFrameException on broker side.
            // ─────────────────────────────────────────────────────────────────────────
            val hasInlinePayload = !cmd.payloadJson.isNullOrBlank() &&
                                   cmd.payloadJson != "{}" &&
                                   cmd.payloadJson != "null"

            if (!hasInlinePayload && !cmd.commandId.isNullOrBlank()) {
                // Fetch full payload from REST API
                fetchCommandAndExecute(cmd.commandId, cmd.commandType)
            } else {
                // Legacy path: inline payload present (backward compatible)
                handleInboundCommand(cmd, payloadStr)
            }
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to parse command signal", e)
        }
    }

    private fun fetchCommandAndExecute(commandId: String, commandType: String) {
        val serverUrl = (configProvider?.getApiBaseUrl() ?: repository.serverUrl).trimEnd('/')
        val endpoint = "$serverUrl/api/v1/commands/$commandId"
        val jwt = repository.deviceJwt

        val request = okhttp3.Request.Builder()
            .url(endpoint)
            .get()
            .apply { if (jwt.isNotBlank()) header("Authorization", "Bearer $jwt") }
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                RrvLog.e(TAG, "❌ REST fetch failed for commandId=$commandId: ${e.message}")
                if (e is java.net.UnknownHostException && !serverUrl.contains("127.0.0.1") && !serverUrl.contains("localhost")) {
                    val fallbackEndpoint = "http://127.0.0.1:8080/api/v1/commands/$commandId"
                    val fallbackReq = request.newBuilder().url(fallbackEndpoint).build()
                    httpClient.newCall(fallbackReq).enqueue(object : okhttp3.Callback {
                        override fun onFailure(c: okhttp3.Call, ex: java.io.IOException) {
                            val fallback = MqttCommandPayload(commandId, commandType, "{}")
                            handleInboundCommand(fallback)
                        }
                        override fun onResponse(c: okhttp3.Call, r: okhttp3.Response) {
                            val body = r.body?.string() ?: "{}"
                            if (r.isSuccessful) {
                                try {
                                    val fullCmd = gson.fromJson(body, MqttCommandPayload::class.java)
                                    handleInboundCommand(fullCmd, body)
                                } catch (_: Exception) {
                                    handleInboundCommand(MqttCommandPayload(commandId, commandType, "{}"))
                                }
                            } else {
                                handleInboundCommand(MqttCommandPayload(commandId, commandType, "{}"))
                            }
                        }
                    })
                    return
                }
                // Execute with empty payload as fallback
                val fallback = MqttCommandPayload(commandId, commandType, "{}")
                handleInboundCommand(fallback)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    RrvLog.mqtt("✅ REST fetched full command payload for $commandId (${body.length} bytes)")
                    try {
                        val fullCmd = gson.fromJson(body, MqttCommandPayload::class.java)
                        handleInboundCommand(fullCmd, body)
                    } catch (e: Exception) {
                        RrvLog.e(TAG, "Failed to parse REST command response", e)
                    }
                } else {
                    RrvLog.w(TAG, "⚠️ REST fetch returned HTTP ${response.code} for $commandId — executing with signal only")
                    val fallback = MqttCommandPayload(commandId, commandType, "{}")
                    handleInboundCommand(fallback)
                }
            }
        })
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {
        // Telemetry packet delivered
    }

    private fun handleInboundCommand(cmd: MqttCommandPayload, @Suppress("UNUSED_PARAMETER") rawMessageStr: String = "") {
        val app = context.applicationContext as RrvMdmApplication
        val mdmCommand = com.rrv.mdm.dpc.domain.model.MdmCommand(
            commandId = cmd.commandId,
            commandType = cmd.commandType,
            payloadJson = if (!cmd.payloadJson.isNullOrBlank() && cmd.payloadJson != "null") cmd.payloadJson else "{}"
        )
        app.commandProcessor.processCommand(mdmCommand)
    }

    fun publishTelemetry(location: Location? = null, isGeofenceCompliant: Boolean = true) {
        val deviceId = getEffectiveDeviceId()
        if (deviceId.isBlank()) return

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85

        val batteryStatus = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val chargingStatus = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = chargingStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                         chargingStatus == android.os.BatteryManager.BATTERY_STATUS_FULL
        val batteryTemp = (batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280) / 10.0f

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeStorage = stat.availableBlocksLong * stat.blockSizeLong
        val totalStorage = stat.blockCountLong * stat.blockSizeLong

        // Real WiFi SSID
        val wifiSsid = try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager?.connectionInfo
            info?.ssid?.replace("\"", "") ?: "Unknown"
        } catch (_: Exception) { "Unknown" }

        // Real Cellular Carrier
        val carrierName = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "No SIM"
        } catch (_: Exception) { "No SIM" }

        // Actively query system LocationManager for real hardware GPS fix if not passed
        var resolvedLoc: Location? = location
        if (resolvedLoc == null) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                if (lm != null) {
                    val gps = try { lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
                    val net = try { lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
                    val pass = try { lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER) } catch (_: Exception) { null }
                    val fused = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            lm.getLastKnownLocation(android.location.LocationManager.FUSED_PROVIDER)
                        } else null
                    } catch (_: Exception) { null }

                    resolvedLoc = listOfNotNull(gps, fused, net, pass).maxByOrNull { it.time }
                }
            } catch (_: Exception) {}
        }

        if (resolvedLoc != null && resolvedLoc.latitude != 0.0) {
            repository.lastLatitude = resolvedLoc.latitude
            repository.lastLongitude = resolvedLoc.longitude
        }

        val lat = resolvedLoc?.latitude ?: repository.lastLatitude.takeIf { it != 0.0 } ?: 0.0
        val lng = resolvedLoc?.longitude ?: repository.lastLongitude.takeIf { it != 0.0 } ?: 0.0
        val gpsAccuracy = resolvedLoc?.accuracy ?: 0.0f

        val payload = DeviceTelemetryPayload(
            deviceId = deviceId,
            serialNumber = @Suppress("DEPRECATION") (Build.SERIAL ?: "UNKNOWN_SERIAL"),
            imei = null,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            batteryLevel = batteryPct,
            isCharging = isCharging,
            batteryTemperature = batteryTemp,
            freeStorageBytes = freeStorage,
            totalStorageBytes = totalStorage,
            freeRamBytes = Runtime.getRuntime().freeMemory(),
            latitude = lat,
            longitude = lng,
            gpsAccuracy = gpsAccuracy,
            wifiSsid = wifiSsid,
            carrierName = carrierName,
            isKnoxAttested = false,
            activePolicyId = repository.getActivePolicy().policyId,
            isGeofenceCompliant = isGeofenceCompliant
        )

        val jsonStr = gson.toJson(payload)
        publish("rrv/devices/$deviceId/telemetry", jsonStr, QOS_TELEMETRY, false)
        publish("rrv/devices/$deviceId/heartbeat", jsonStr, QOS_TELEMETRY, false)

        val realSerial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else @Suppress("DEPRECATION") Build.SERIAL
        } catch (_: Exception) { "" }

        if (realSerial.isNotBlank() && realSerial != "unknown" && realSerial != deviceId) {
            publish("rrv/devices/$realSerial/telemetry", jsonStr, QOS_TELEMETRY, false)
            publish("rrv/devices/$realSerial/heartbeat", jsonStr, QOS_TELEMETRY, false)
        }
        RrvLog.d(TAG, "📡 Outbound telemetry streamed: Bat=$batteryPct%, Charging=$isCharging, WiFi=$wifiSsid, Carrier=$carrierName")
    }


    fun publishAppInventory() {
        val deviceId = getEffectiveDeviceId()
        if (deviceId.isBlank()) return

        try {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appList = installed.map { appInfo ->
                val pkg = appInfo.packageName
                val label = try { pm.getApplicationLabel(appInfo).toString() } catch (_: Exception) { pkg }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isMdmCore = (pkg == context.packageName || pkg.startsWith("com.rrv.mdm"))
                
                var vCode = 0
                var vName = "1.0"
                try {
                    val pInfo = pm.getPackageInfo(pkg, 0)
                    vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo.versionCode
                    }
                    vName = pInfo.versionName ?: "1.0"
                } catch (_: Exception) {}

                val installerPkg = try {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pm.getInstallSourceInfo(pkg).installingPackageName
                    } else {
                        pm.getInstallerPackageName(pkg)
                    }
                } catch (_: Exception) { null }
                val isMdmInstalled = installerPkg?.contains("rrv.mdm") == true

                val classification = when {
                    isMdmCore -> "MDM_CORE_APP"
                    isSystem && !isUpdatedSystem -> "PRE_INSTALLED_SYSTEM_APP"
                    isMdmInstalled -> "MDM_INSTALLED_APP"
                    else -> "USER_INSTALLED_APP"
                }
                val isUninstallable = !isMdmCore && !(isSystem && !isUpdatedSystem)

                mapOf(
                    "packageName" to pkg,
                    "appTitle" to label,
                    "versionCode" to vCode,
                    "versionName" to vName,
                    "installer" to if (isMdmInstalled) "MDM_SILENT_PUSH" else if (isSystem || isUpdatedSystem) "SYSTEM" else "USER",
                    "isSystemApp" to (isSystem || isUpdatedSystem),
                    "classification" to classification,
                    "isUninstallable" to isUninstallable
                )
            }

            val topic = "rrv/devices/$deviceId/app_events"
            val chunks = appList.chunked(8)
            for (chunk in chunks) {
                val payload = mapOf(
                    "event" to "INVENTORY_SYNC",
                    "apps" to chunk,
                    "timestamp" to System.currentTimeMillis()
                )
                publish(topic, gson.toJson(payload), 1, false)
            }
            RrvLog.i(TAG, "📦 Published inventory of ${appList.size} packages (in ${chunks.size} chunks) to MDM backend")
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to publish app inventory: ${e.message}", e)
        }
    }

    fun publishSecurityAlert(alertType: String, message: String) {
        val deviceId = getEffectiveDeviceId()
        val topic = "rrv/devices/$deviceId/events"
        val json = """{"alertType":"$alertType","message":"$message","timestamp":${System.currentTimeMillis()}}"""
        publish(topic, json, 1, false)
        RrvLog.w("SECURITY-ALERT", "🚨 Security alert dispatched [$alertType]: $message")
    }

    fun publishCommandAck(commandId: String, status: String, message: String) {
        val deviceId = getEffectiveDeviceId()
        val topic = "rrv/devices/$deviceId/acks"
        val ack = MqttCommandAck(commandId, deviceId, status, message)
        publish(topic, gson.toJson(ack), 1, false)
        RrvLog.mqtt("✓ Command ACK published for $commandId (Status: $status)")

        // Dual-ACK via REST to guarantee server status sync
        try {
            val serverUrl = (configProvider?.getApiBaseUrl() ?: repository.serverUrl).trimEnd('/')
            if (serverUrl.isNotBlank() && commandId.isNotBlank()) {
                val isSuccess = status == "EXECUTED" || status == "SUCCESS"
                val encodedMsg = java.net.URLEncoder.encode(message, "UTF-8")
                val url = "$serverUrl/api/v1/commands/$commandId/ack?success=$isSuccess&errorMessage=$encodedMsg"
                val jwt = repository.deviceJwt
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .apply { if (jwt.isNotBlank()) header("Authorization", "Bearer $jwt") }
                    .build()
                httpClient.newCall(req).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
                })
            }
        } catch (_: Exception) {}
    }

    /** Publish pre-built heartbeat JSON string */
    fun publishHeartbeat(heartbeatJson: String) {
        val deviceId = getEffectiveDeviceId()
        val topic = "rrv/devices/$deviceId/heartbeat"
        publish(topic, heartbeatJson, QOS_TELEMETRY, false)
    }

    /** Generic raw publish — used by AppEventPublisher and HeartbeatWorker */
    fun publishRaw(topic: String, payload: String, qos: Int = 0, retained: Boolean = false) {
        publish(topic, payload, qos, retained)
    }

    fun isConnected(): Boolean = mqttClient?.isConnected == true

    private fun subscribe(topic: String, qos: Int) {
        try {
            mqttClient?.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    RrvLog.mqtt("✓ Subscribed to topic: $topic (QoS $qos)")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    RrvLog.e(TAG, "✕ Failed to subscribe to topic: $topic", exception)
                }
            })
        } catch (e: Exception) {
            RrvLog.e(TAG, "Error subscribing to topic $topic", e)
        }
    }

    private fun publish(topic: String, payload: String, qos: Int, retained: Boolean) {
        val client = mqttClient ?: return
        try {
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                this.qos = qos
                this.isRetained = retained
            }
            client.publish(topic, message)
        } catch (e: Exception) {
            RrvLog.d(TAG, "Publish deferred for topic $topic (${e.message})")
        }
    }

    private fun getEffectiveDeviceId(): String {
        var id = repository.deviceId
        if (id.isBlank()) {
            val realSerial = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Build.getSerial()
                } else {
                    @Suppress("DEPRECATION")
                    Build.SERIAL
                }
            } catch (_: Exception) { "" }

            val androidId = try {
                android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            } catch (_: Exception) { "" }

            id = if (realSerial.isNotBlank() && realSerial != "unknown") {
                realSerial
            } else if (androidId.isNotBlank()) {
                androidId
            } else {
                "DEV-" + Build.MODEL.replace(" ", "-") + "-" + Build.ID.take(6)
            }
            repository.deviceId = id
        }
        return id
    }
}
