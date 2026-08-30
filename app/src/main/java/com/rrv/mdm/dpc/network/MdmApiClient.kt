package com.rrv.mdm.dpc.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.rrv.mdm.dpc.RrvMdmApplication
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * RESTful API Client for initial device enrollment and binary APK downloads.
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

        if (imei.isNullOrBlank()) {
            imei = try {
                android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            } catch (_: Exception) {
                serial
            }
        }

        val payload = mapOf(
            "enrollmentToken" to token,
            "hardware" to mapOf(
                "serialNumber" to serial,
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
}
