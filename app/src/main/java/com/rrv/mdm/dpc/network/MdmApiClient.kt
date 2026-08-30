package com.rrv.mdm.dpc.network

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.data.entity.QueuedDeviceEventEntity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * RESTful API Client for initial device enrollment, batch event uploads, and binary APK downloads.
 */
class MdmApiClient(private val context: Context) {

    companion object {
        private const val TAG = "MdmApiClient"
    }

    private val httpClient = OkHttpClient.Builder()
        .build()

    private val gson = Gson()
    private val repository get() = (context.applicationContext as RrvMdmApplication).repository

    fun enrollDevice(serverUrl: String, token: String, callback: (Boolean, String) -> Unit) {
        val cleanServerUrl = serverUrl.trimEnd('/')
        val endpoint = "$cleanServerUrl/api/v1/android/enroll"

        val serial = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.os.Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                android.os.Build.SERIAL
            }
        } catch (_: Exception) {
            "DEV-" + android.os.Build.MODEL.replace(" ", "-") + "-" + android.os.Build.ID.take(6)
        }

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        var imei: String? = null
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                imei = tm?.imei
            } else {
                @Suppress("DEPRECATION")
                imei = tm?.deviceId
            }
        } catch (_: Exception) {}

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

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Enrollment network error", e)
                callback(false, e.message ?: "Connection failed to server $cleanServerUrl")
            }

            override fun onResponse(call: Call, response: Response) {
                val respStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    try {
                        val respMap = gson.fromJson(respStr, Map::class.java)
                        val devId = respMap["deviceId"]?.toString() ?: serial
                        val jwt = respMap["jwtSessionToken"]?.toString() ?: ""
                        val status = respMap["enrollmentStatus"]?.toString() ?: "ACTIVE"
                        val message = respMap["approvalStatusMessage"]?.toString() ?: "Device enrolled successfully!"

                        if (status == "PENDING") {
                            repository.deviceId = devId
                            repository.serverUrl = cleanServerUrl
                            repository.enrollmentToken = token
                            callback(false, "⏳ Registration Pending: $message")
                            return
                        }

                        repository.isEnrolled = true
                        repository.serverUrl = cleanServerUrl
                        repository.enrollmentToken = token
                        repository.deviceId = devId
                        repository.deviceJwt = jwt

                        // Extract host from serverUrl for MQTT
                        try {
                            val uri = java.net.URI(cleanServerUrl)
                            val host = uri.host ?: "127.0.0.1"
                            repository.mqttBrokerHost = host
                            repository.mqttPort = 1883
                        } catch (_: Exception) {
                            repository.mqttBrokerHost = "127.0.0.1"
                            repository.mqttPort = 1883
                        }

                        callback(true, "Device enrolled successfully!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing enrollment response", e)
                        callback(true, "Enrolled with warning: ${e.message}")
                    }
                } else {
                    callback(false, "Server rejected enrollment: HTTP ${response.code} ($respStr)")
                }
            }
        })
    }

    fun uploadEvents(events: List<QueuedDeviceEventEntity>, callback: (Boolean, Int) -> Unit) {
        val serverUrl = repository.serverUrl ?: return callback(false, 0)
        val devId = repository.deviceId ?: return callback(false, 0)
        val jwt = repository.deviceJwt ?: ""

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

        httpClient.newCall(reqBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to upload queued events", e)
                callback(false, 0)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    callback(true, events.size)
                } else {
                    callback(false, 0)
                }
            }
        })
    }

    fun downloadApk(downloadUrl: String, destinationFile: File, callback: (Boolean, File?) -> Unit) {
        val request = Request.Builder().url(downloadUrl).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to download APK", e)
                callback(false, null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    callback(false, null)
                    return
                }

                try {
                    val input = response.body?.byteStream()
                    val output = FileOutputStream(destinationFile)
                    input?.copyTo(output)
                    output.flush()
                    output.close()
                    callback(true, destinationFile)
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving APK binary", e)
                    callback(false, null)
                }
            }
        })
    }

    fun sendHeartbeat(deviceId: String, batteryLevel: Int, isCharging: Boolean) {
        if (deviceId.isBlank()) return
        val serverUrl = repository.serverUrl.trimEnd('/')
        val jwt = repository.deviceJwt
        val endpoint = "$serverUrl/api/v1/devices/$deviceId/heartbeat"

        val payload = mapOf(
            "batteryLevel" to batteryLevel,
            "isCharging" to isCharging,
            "networkType" to "WIFI",
            "lastKnownIp" to "127.0.0.1",
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
                Log.w(TAG, "Heartbeat REST fallback failed: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
