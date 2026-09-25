package com.rrv.mdm.dpc.network

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.data.entity.QueuedDeviceEventEntity
import com.rrv.mdm.dpc.util.RrvLog
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * RESTful API Client for initial device enrollment, batch event uploads, and binary APK downloads.
 */
@SuppressLint("HardwareIds", "MissingPermission")
class MdmApiClient(private val context: Context) {

    companion object {
        private const val TAG = "MdmApiClient"
    }

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("ngrok-skip-browser-warning", "true")
                .build()
            chain.proceed(req)
        }
        .build()

    private val gson = Gson()
    private val app get() = context.applicationContext as RrvMdmApplication
    private val repository get() = app.repository
    private val configProvider get() = app.serverConfigProvider

    fun enrollDevice(serverUrl: String, token: String, callback: (Boolean, String) -> Unit) {
        val cleanServerUrl = serverUrl.trimEnd('/')
        val endpoint = "$cleanServerUrl/api/v1/android/enroll"

        val serial = repository.getHardwareSerial() ?: ("DEV-" + android.os.Build.MODEL.replace(" ", "-") + "-" + android.os.Build.ID.take(6))
        val androidId = repository.getAndroidId()
        val imei = repository.getHardwareImei()
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        val payload = mapOf(
            "enrollmentToken" to token,
            "hardware" to mapOf(
                "serialNumber" to serial,
                "androidId" to androidId,
                "deviceName" to deviceName,
                "manufacturer" to android.os.Build.MANUFACTURER,
                "model" to android.os.Build.MODEL,
                "imeiPrimary" to imei,
                "imeiSecondary" to null,
                "macAddress" to null
            ),
            "os" to mapOf(
                "osVersion" to android.os.Build.VERSION.RELEASE,
                "apiLevel" to android.os.Build.VERSION.SDK_INT,
                "buildFingerprint" to android.os.Build.FINGERPRINT,
                "securityPatch" to null
            ),
            "dpc" to mapOf(
                "version" to "1.0.0-PROD",
                "managementMode" to (if ((context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager).isDeviceOwnerApp(context.packageName)) "DEVICE_OWNER" else "PROFILE_OWNER"),
                "isStrongBoxBacked" to false
            ),
            "csrPem" to null
        )

        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        RrvLog.net("Dispatching device enrollment to $endpoint")

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                RrvLog.e(TAG, "Enrollment connection failed to $cleanServerUrl: ${e.message}", e)
                callback(false, e.message ?: "Connection failed to server $cleanServerUrl")
            }

            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: ""
                RrvLog.net("Enrollment HTTP ${response.code} received from $cleanServerUrl")

                if (response.isSuccessful) {
                    try {
                        val respMap = gson.fromJson(respStr, Map::class.java)
                        val devId = respMap["deviceId"]?.toString() ?: serial
                        val jwt = respMap["jwtSessionToken"]?.toString() ?: ""
                        val status = respMap["enrollmentStatus"]?.toString() ?: "ACTIVE"
                        val message = respMap["approvalStatusMessage"]?.toString() ?: "Device enrolled successfully"

                        RrvLog.i(TAG, "Enrollment parsed: deviceId=$devId, status=$status, hasJwt=${jwt.isNotBlank()}")

                        if (status == "PENDING") {
                            repository.deviceId = devId
                            repository.serverUrl = cleanServerUrl
                            repository.enrollmentToken = token
                            callback(false, "Registration Pending: $message")
                            return
                        }

                        repository.isEnrolled = true
                        repository.serverUrl = cleanServerUrl
                        repository.enrollmentToken = token
                        repository.deviceId = devId
                        repository.deviceJwt = jwt

                        // Parse Dynamic Server Configuration returned by Server
                        val serverConfigMap = respMap["serverConfig"] as? Map<*, *>
                        if (serverConfigMap != null) {
                            try {
                                val configJson = gson.toJson(serverConfigMap)
                                var serverConfig = gson.fromJson(configJson, com.rrv.mdm.dpc.data.config.ServerConfiguration::class.java)
                                // If backend returned localhost/127.0.0.1 but device connected via remote/ngrok cleanServerUrl,
                                // retain cleanServerUrl so device doesn't lose connectivity
                                if ((serverConfig.apiBaseUrl.contains("localhost") || serverConfig.apiBaseUrl.contains("127.0.0.1")) &&
                                    !cleanServerUrl.contains("localhost") && !cleanServerUrl.contains("127.0.0.1")) {
                                    serverConfig = serverConfig.copy(apiBaseUrl = cleanServerUrl)
                                }
                                configProvider.applyServerConfiguration(serverConfig, testConnectivity = false)
                                repository.serverUrl = serverConfig.apiBaseUrl
                                repository.mqttBrokerHost = serverConfig.mqtt.host
                                repository.mqttPort = serverConfig.mqtt.port
                                RrvLog.i(TAG, "Dynamic server configuration applied: baseUrl=${serverConfig.apiBaseUrl}, broker=${serverConfig.mqtt.serverUri}")
                            } catch (ce: Exception) {
                                RrvLog.w(TAG, "Could not deserialize serverConfig directly: ${ce.message}")
                            }
                        } else {
                            // Synthesize dynamic server config from enrollment URL
                            try {
                                val uri = java.net.URI(cleanServerUrl)
                                val host = uri.host ?: "127.0.0.1"
                                val isHttps = uri.scheme?.equals("https", ignoreCase = true) == true
                                val mqttPort = if (isHttps) 8883 else 1883
                                val dynamicConfig = com.rrv.mdm.dpc.data.config.ServerConfiguration(
                                    apiBaseUrl = cleanServerUrl,
                                    mqtt = com.rrv.mdm.dpc.data.config.MqttConfiguration(
                                        host = host,
                                        port = mqttPort,
                                        tls = isHttps
                                    ),
                                    environment = if (isHttps) "PRODUCTION" else "DEVELOPMENT",
                                    configurationVersion = 1
                                )
                                configProvider.applyServerConfiguration(dynamicConfig, testConnectivity = false)
                                repository.serverUrl = cleanServerUrl
                                repository.mqttBrokerHost = host
                                repository.mqttPort = mqttPort
                                RrvLog.i(TAG, "Synthesized dynamic server configuration: baseUrl=$cleanServerUrl, broker=$host:$mqttPort")
                            } catch (ue: Exception) {
                                RrvLog.e(TAG, "Error synthesizing fallback server config", ue)
                            }
                        }

                        // 1. Automatically fetch full canonical policy profile via dedicated endpoint
                        val policyHash = respMap["policyHash"]?.toString()
                        RrvLog.i(TAG, "Fetching canonical policy profile for device: deviceId=$devId")
                        fetchAndApplyPolicy(devId, currentHash = null) { policySuccess ->
                            RrvLog.i(TAG, "Initial enrollment policy fetch result: success=$policySuccess")
                        }

                        callback(true, "Device enrolled successfully")
                    } catch (e: Exception) {
                        RrvLog.e(TAG, "Error parsing enrollment response", e)
                        callback(true, "Enrolled with warning: ${e.message}")
                    }
                } else {
                    RrvLog.e(TAG, "Server rejected enrollment: status=${response.code}, response=$respStr")
                    callback(false, "Server rejected enrollment: HTTP ${response.code} ($respStr)")
                }
            }
        })
    }

    fun uploadEvents(events: List<QueuedDeviceEventEntity>, callback: (Boolean, Int) -> Unit) {
        val serverUrl = configProvider.getApiBaseUrl() ?: repository.serverUrl.takeIf { it.isNotBlank() } ?: return callback(false, 0)
        val devId = repository.deviceId.takeIf { it.isNotBlank() } ?: return callback(false, 0)
        val jwt = repository.deviceJwt

        val endpoint = "${serverUrl.trimEnd('/')}/api/v1/devices/$devId/events"
        val payloadEvents = events.map {
            mapOf(
                "eventType" to it.eventType,
                "severity" to it.severity,
                "tag" to it.tag,
                "source" to it.source,
                "correlationId" to it.correlationId,
                "message" to it.message,
                "metadataJson" to it.metadataJson,
                "timestamp" to java.time.Instant.ofEpochMilli(it.timestamp).toString()
            )
        }

        val body = gson.toJson(mapOf("events" to payloadEvents)).toRequestBody("application/json".toMediaType())
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(body)

        if (jwt.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $jwt")
        }

        RrvLog.d(TAG, "Uploading queued events: count=${events.size}, endpoint=$endpoint")

        httpClient.newCall(reqBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                RrvLog.e(TAG, "Failed to upload queued events: endpoint=$endpoint, error=${e.message}", e)
                callback(false, 0)
            }

            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    RrvLog.d(TAG, "Events uploaded successfully: status=${response.code}, count=${events.size}")
                    callback(true, events.size)
                } else {
                    RrvLog.w(TAG, "Server rejected events upload: status=${response.code}, response=$respStr")
                    callback(false, 0)
                }
            }
        })
    }

    fun downloadApk(downloadUrl: String, destinationFile: File, callback: (Boolean, File?) -> Unit) {
        val fullUrl = if (downloadUrl.startsWith("http://", ignoreCase = true) || downloadUrl.startsWith("https://", ignoreCase = true)) {
            downloadUrl
        } else {
            val base = (configProvider.getApiBaseUrl() ?: repository.serverUrl).trimEnd('/')
            if (downloadUrl.startsWith("/")) "$base$downloadUrl" else "$base/$downloadUrl"
        }

        val request = Request.Builder().url(fullUrl).build()
        RrvLog.net("Downloading APK from $fullUrl")

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                RrvLog.e(TAG, "Failed to download APK from $fullUrl: ${e.message}", e)
                callback(false, null)
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val contentLength = response.body?.contentLength() ?: -1L
                val contentType = response.header("Content-Type") ?: "unknown"
                RrvLog.net("APK download response: status=$code, contentType=$contentType, size=$contentLength bytes")

                if (!response.isSuccessful) {
                    RrvLog.e(TAG, "Server returned HTTP $code for APK download at $fullUrl")
                    callback(false, null)
                    return
                }

                try {
                    response.body?.byteStream()?.use { input ->
                        destinationFile.outputStream().use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    RrvLog.i(TAG, "APK saved successfully: path=${destinationFile.absolutePath}, bytes=${destinationFile.length()}")
                    callback(true, destinationFile)
                } catch (e: Exception) {
                    RrvLog.e(TAG, "Error saving APK binary from server", e)
                    callback(false, null)
                } finally {
                    response.close()
                }
            }
        })
    }

    fun sendHeartbeat(deviceId: String, batteryLevel: Int, isCharging: Boolean) {
        if (deviceId.isBlank()) return
        val serverUrl = (configProvider.getApiBaseUrl() ?: repository.serverUrl).trimEnd('/')
        if (serverUrl.isBlank()) return
        val jwt = repository.deviceJwt
        val endpoint = "$serverUrl/api/v1/devices/$deviceId/heartbeat"

        val payload = mapOf(
            "batteryLevel" to batteryLevel,
            "isCharging" to isCharging,
            "networkType" to "WIFI",
            "deviceTimestamp" to System.currentTimeMillis()
        )
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .apply { if (jwt.isNotBlank()) header("Authorization", "Bearer $jwt") }
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                RrvLog.w(TAG, "Heartbeat REST fallback failed to $endpoint: ${e.message}")
                if (e is java.net.UnknownHostException && !serverUrl.contains("127.0.0.1") && !serverUrl.contains("localhost")) {
                    RrvLog.i(TAG, "Attempting ADB reverse loopback heartbeat fallback to http://127.0.0.1:8080")
                    val fallbackEndpoint = "http://127.0.0.1:8080/api/v1/devices/$deviceId/heartbeat"
                    val fallbackReq = request.newBuilder().url(fallbackEndpoint).build()
                    httpClient.newCall(fallbackReq).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {}
                        override fun onResponse(call: Call, response: Response) {
                            val resp = response.body?.string() ?: ""
                            RrvLog.d(TAG, "Heartbeat loopback response status: ${response.code}")
                            response.close()
                        }
                    })
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: ""
                RrvLog.d(TAG, "Heartbeat response received: status=${response.code}")
                response.close()
            }
        })
    }

    /**
     * Canonical Policy Fetch & Application Pipeline by Policy Profile ID.
     * Hits GET /api/v1/policies/{policyId} with ETag If-None-Match support.
     */
    fun fetchAndApplyPolicyById(
        policyId: String,
        currentHash: String? = null,
        callback: ((Boolean) -> Unit)? = null
    ) {
        if (policyId.isBlank()) {
            callback?.invoke(false)
            return
        }
        val serverUrl = (configProvider.getApiBaseUrl() ?: repository.serverUrl).trimEnd('/')
        if (serverUrl.isBlank()) {
            callback?.invoke(false)
            return
        }
        val jwt = repository.deviceJwt
        val endpoint = "$serverUrl/api/v1/policies/$policyId"

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .get()

        if (jwt.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $jwt")
        }
        if (!currentHash.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", "\"$currentHash\"")
        }

        val request = reqBuilder.build()
        RrvLog.net("Requesting policy profile by ID: policyId=$policyId, endpoint=$endpoint")

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                RrvLog.w(TAG, "Failed to fetch policy by ID: policyId=$policyId, error=${e.message}")
                val devId = app.mqttManager.getEffectiveDeviceId().ifBlank { repository.deviceId }
                if (devId.isNotBlank()) {
                    fetchAndApplyPolicy(devId, currentHash, callback)
                } else {
                    callback?.invoke(false)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    RrvLog.w(TAG, "Policy fetch by ID returned status: HTTP ${response.code}. Falling back to device policy endpoint")
                    response.close()
                    val devId = app.mqttManager.getEffectiveDeviceId().ifBlank { repository.deviceId }
                    if (devId.isNotBlank()) {
                        fetchAndApplyPolicy(devId, currentHash, callback)
                    } else {
                        callback?.invoke(false)
                    }
                    return
                }
                handlePolicyResponse(response, endpoint, callback)
            }
        })
    }

    /**
     * Canonical Policy Fetch & Application Pipeline by Device ID.
     * Hits dedicated GET /api/v1/policies/device/{deviceId} endpoint with ETag If-None-Match support.
     * Used on enrollment, boot, reconnect, and OTA sync.
     */
    fun fetchAndApplyPolicy(
        deviceId: String,
        currentHash: String? = null,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val targetDeviceId = app.mqttManager.getEffectiveDeviceId().ifBlank { deviceId.ifBlank { repository.deviceId } }
        if (targetDeviceId.isBlank()) {
            callback?.invoke(false)
            return
        }
        val serverUrl = (configProvider.getApiBaseUrl() ?: repository.serverUrl).trimEnd('/')
        if (serverUrl.isBlank()) {
            callback?.invoke(false)
            return
        }
        val jwt = repository.deviceJwt
        val endpoint = "$serverUrl/api/v1/policies/device/$targetDeviceId"

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .get()

        if (jwt.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $jwt")
        }
        if (!currentHash.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", "\"$currentHash\"")
        }

        val request = reqBuilder.build()
        RrvLog.net("Requesting policy for device: deviceId=$deviceId, endpoint=$endpoint")

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                RrvLog.w(TAG, "Failed to fetch policy from $endpoint: ${e.message}")
                if (e is java.net.UnknownHostException && !serverUrl.contains("127.0.0.1") && !serverUrl.contains("localhost")) {
                    RrvLog.i(TAG, "Attempting ADB reverse loopback policy fetch from http://127.0.0.1:8080")
                    val fallbackReq = request.newBuilder().url("http://127.0.0.1:8080/api/v1/policies/device/$deviceId").build()
                    httpClient.newCall(fallbackReq).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            callback?.invoke(false)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            handlePolicyResponse(response, "http://127.0.0.1:8080/api/v1/policies/device/$deviceId", callback)
                        }
                    })
                    return
                }
                callback?.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                handlePolicyResponse(response, endpoint, callback)
            }
        })
    }

    private fun handlePolicyResponse(response: Response, sourceEndpoint: String, callback: ((Boolean) -> Unit)?) {
        try {
            val code = response.code
            val etag = response.header("ETag")
            if (code == 304) {
                RrvLog.net("Policy not modified (304): sourceEndpoint=$sourceEndpoint, etag=$etag")
                callback?.invoke(true)
                return
            }
            val body = response.body?.string() ?: ""
            RrvLog.json("REST-NETWORK", "Policy Response status=$code, etag=$etag", body)

            if (!response.isSuccessful) {
                RrvLog.w(TAG, "Server returned non-success status $code for policy fetch")
                callback?.invoke(false)
                return
            }

            val policyDto = try { gson.fromJson(body, Map::class.java) } catch (_: Exception) { null }
            val rawPayload = policyDto?.get("payloadJson") ?: policyDto?.get("payload")
            val jsonToParse = when (rawPayload) {
                is String -> if (rawPayload.isNotBlank() && rawPayload != "{}") rawPayload else body
                is Map<*, *> -> gson.toJson(rawPayload)
                else -> body
            }

            val parsedPolicy = com.rrv.mdm.dpc.data.model.PolicyPayload.fromJson(jsonToParse)
            repository.saveActivePolicy(parsedPolicy)
            val app = context.applicationContext as? com.rrv.mdm.dpc.RrvMdmApplication
            app?.deviceManager?.applyPolicy(parsedPolicy, force = true)
            com.rrv.mdm.dpc.util.NotificationHelper.showPolicyNotification(context, parsedPolicy)
            context.sendBroadcast(android.content.Intent("com.rrv.mdm.ACTION_POLICY_UPDATED").setPackage(context.packageName))
            RrvLog.i(TAG, "Policy applied: policyName='${parsedPolicy.name}', version=${parsedPolicy.version}, allowedApps=${parsedPolicy.allowedKioskPackages.size}, kioskEnabled=${parsedPolicy.kioskModeEnabled}")
            callback?.invoke(true)
        } catch (e: Exception) {
            RrvLog.e(TAG, "Error parsing and applying fetched policy data: ${e.message}", e)
            callback?.invoke(false)
        } finally {
            response.close()
        }
    }
}
