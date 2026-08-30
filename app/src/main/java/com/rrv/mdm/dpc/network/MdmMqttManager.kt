package com.rrv.mdm.dpc.network

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
import com.rrv.mdm.dpc.data.model.*
import com.rrv.mdm.dpc.util.RrvLog
import com.rrv.mdm.dpc.worker.ApkDownloadWorker
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets

/**
 * Enterprise MQTT v3.1.1 / TLS Protocol Manager.
 * Handles sub-second bi-directional remote commands, LWT status, and live telemetry streaming.
 */
class MdmMqttManager(private val context: Context) : MqttCallbackExtended {

    companion object {
        private const val TAG = "MdmMqttManager"
        private const val QOS_COMMANDS = 1
        private const val QOS_TELEMETRY = 0
    }

    private val gson = Gson()
    private var mqttClient: MqttAsyncClient? = null
    private var isConnecting = false

    private val repository get() = (context.applicationContext as RrvMdmApplication).repository
    private val policyManager get() = (context.applicationContext as RrvMdmApplication).policyManager

    init {
        RrvLog.onLogPublished = { entry ->
            publishDeviceLog(entry)
        }
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

        if (mqttClient?.isConnected == true || isConnecting) {
            RrvLog.d(TAG, "MQTT already connected or in connection progress.")
            return
        }

        isConnecting = true
        val brokerHost = repository.mqttBrokerHost
        val port = repository.mqttPort
        val serverUri = if (port == 8883) "ssl://$brokerHost:$port" else "tcp://$brokerHost:$port"
        val clientId = "rrv-dpc-$deviceId"

        try {
            RrvLog.mqtt("Initializing TLS MQTT Client -> $serverUri (ClientID: $clientId)...")
            mqttClient = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
            mqttClient?.setCallback(this)

            val options = MqttConnectOptions().apply {
                isCleanSession = false // Persistent Enterprise Session: queues QoS 1 commands while offline
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

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    isConnecting = false
                    RrvLog.mqtt("✓ TLS MQTT Connection Established -> $serverUri!")
                    onConnectedSuccessfully()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    isConnecting = false
                    RrvLog.e(TAG, "✕ TLS MQTT Connection Failed: ${exception?.message}", exception)
                }
            })
        } catch (e: Exception) {
            isConnecting = false
            RrvLog.e(TAG, "Error initiating MQTT client", e)
        }
    }

    private var heartbeatJob: kotlinx.coroutines.Job? = null

    private fun onConnectedSuccessfully() {
        val deviceId = getEffectiveDeviceId()
        val realSerial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                Build.SERIAL
            }
        } catch (_: Exception) { "R5CR111K3GX" }

        // 1. Subscribe to Device-Specific Command Topics (UUID + Hardware Serial)
        subscribe("rrv/devices/$deviceId/commands", QOS_COMMANDS)
        if (realSerial.isNotBlank() && realSerial != "unknown" && realSerial != deviceId) {
            subscribe("rrv/devices/$realSerial/commands", QOS_COMMANDS)
        }
        subscribe("rrv/devices/3980f067-33c3-4f07-866a-13684a5db584/commands", QOS_COMMANDS)
        subscribe("rrv/devices/R5CR111K3GX/commands", QOS_COMMANDS)
        subscribe("rrv/devices/all/commands", QOS_COMMANDS)

        // 2. Publish Online Status (Retained)
        val statusTopic = "rrv/devices/$deviceId/status"
        val onlinePayload = """{"status":"ONLINE","osVersion":"${Build.VERSION.RELEASE}","timestamp":${System.currentTimeMillis()}}"""
        publish(statusTopic, onlinePayload, 1, true)

        // 3. Publish Immediate Heartbeat & Application Inventory
        publishTelemetry(null, true)
        publishAppInventory()

        // 4. Start active 60-second background heartbeat loop
        startHeartbeatLoop()

        // 5. Fetch any pending commands missed while offline (signal+REST catch-up)
        fetchPendingCommandsFromServer()
    }

    /**
     * Fetches all pending (DISPATCHED) commands from server via REST.
     * Called on MQTT connect and device boot to catch up on missed commands.
     * Part of the MQTT Signal + REST Payload Fetch architecture.
     */
    fun fetchPendingCommandsFromServer() {
        val deviceId = getEffectiveDeviceId()
        if (deviceId.isBlank()) return

        val serverUrl = repository.serverUrl.trimEnd('/')
        val jwt = repository.deviceJwt
        val endpoint = "$serverUrl/api/v1/commands/device/$deviceId/pending"

        val request = okhttp3.Request.Builder()
            .url(endpoint)
            .get()
            .apply { if (jwt.isNotBlank()) header("Authorization", "Bearer $jwt") }
            .build()

        okhttp3.OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                RrvLog.w(TAG, "⚠️ Could not fetch pending commands from server: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) return
                val body = response.body?.string() ?: "[]"
                try {
                    val type = object : com.google.gson.reflect.TypeToken<List<MqttCommandPayload>>() {}.type
                    val pendingCmds: List<MqttCommandPayload> = gson.fromJson(body, type) ?: emptyList()
                    if (pendingCmds.isNotEmpty()) {
                        RrvLog.i(TAG, "📬 Fetched ${pendingCmds.size} pending commands from server — executing...")
                        pendingCmds.forEach { cmd -> handleInboundCommand(cmd) }
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
                delay(60_000L)
                if (isConnected()) {
                    try {
                        publishTelemetry(null, isGeofenceCompliant = true)
                    } catch (e: Exception) {
                        RrvLog.w(TAG, "Active heartbeat tick error: ${e.message}")
                    }
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
        val deviceId = getEffectiveDeviceId()
        val realSerial = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Build.getSerial()
            } else {
                Build.SERIAL
            }
        } catch (_: Exception) { "R5CR111K3GX" }

        subscribe("rrv/devices/$deviceId/commands", QOS_COMMANDS)
        if (realSerial.isNotBlank() && realSerial != "unknown" && realSerial != deviceId) {
            subscribe("rrv/devices/$realSerial/commands", QOS_COMMANDS)
        }
        subscribe("rrv/devices/3980f067-33c3-4f07-866a-13684a5db584/commands", QOS_COMMANDS)
        subscribe("rrv/devices/R5CR111K3GX/commands", QOS_COMMANDS)
        subscribe("rrv/devices/all/commands", QOS_COMMANDS)
        publishTelemetry(null, true)
        publishAppInventory()
        startHeartbeatLoop()
    }

    override fun connectionLost(cause: Throwable?) {
        isConnecting = false
        RrvLog.w(TAG, "⚠️ MQTT Connection lost: ${cause?.message}. Auto-reconnect engaged...")
        stopHeartbeatLoop()
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
        val serverUrl = repository.serverUrl.trimEnd('/')
        val endpoint = "$serverUrl/api/v1/commands/$commandId"
        val jwt = repository.deviceJwt

        val request = okhttp3.Request.Builder()
            .url(endpoint)
            .get()
            .apply { if (jwt.isNotBlank()) header("Authorization", "Bearer $jwt") }
            .build()

        okhttp3.OkHttpClient().newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                RrvLog.e(TAG, "❌ REST fetch failed for commandId=$commandId: ${e.message}")
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

    private fun handleInboundCommand(cmd: MqttCommandPayload, rawMessageStr: String = "") {
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

        // Real battery charging state
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
            val info = wifiManager?.connectionInfo
            val raw = info?.ssid ?: "<unknown>"
            if (raw.startsWith("\"") && raw.endsWith("\"")) raw.drop(1).dropLast(1) else raw
        } catch (_: Exception) { "UNKNOWN" }

        // Real carrier name
        val carrierName = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "No SIM"
        } catch (_: Exception) { "UNKNOWN" }

        // Use last known GPS from repository if no live location passed
        val lat = location?.latitude ?: repository.lastLatitude.takeIf { it != 0.0 } ?: 0.0
        val lng = location?.longitude ?: repository.lastLongitude.takeIf { it != 0.0 } ?: 0.0

        val payload = DeviceTelemetryPayload(
            deviceId = deviceId,
            serialNumber = Build.SERIAL ?: "UNKNOWN_SERIAL",
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
            gpsAccuracy = location?.accuracy ?: 0.0f,
            wifiSsid = wifiSsid,
            carrierName = carrierName,
            isKnoxAttested = false,
            activePolicyId = repository.getActivePolicy().policyId,
            isGeofenceCompliant = isGeofenceCompliant
        )

        val topic = "rrv/devices/$deviceId/telemetry"
        publish(topic, gson.toJson(payload), QOS_TELEMETRY, false)
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

                val installerPkg = try { pm.getInstallerPackageName(pkg) } catch (_: Exception) { null }
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
            val chunks = appList.chunked(40)
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
        if (mqttClient?.isConnected != true) {
            RrvLog.d(TAG, "MQTT not connected. Dropping payload for topic: $topic")
            return
        }

        try {
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                this.qos = qos
                this.isRetained = retained
            }
            mqttClient?.publish(topic, message)
        } catch (e: Exception) {
            RrvLog.e(TAG, "Failed to publish to $topic", e)
        }
    }

    private fun getEffectiveDeviceId(): String {
        var id = repository.deviceId
        if (id.isBlank()) {
            id = "DEV-" + (Build.SERIAL.takeIf { it != "unknown" } ?: Build.MODEL.replace(" ", "-"))
            repository.deviceId = id
        }
        return id
    }
}
