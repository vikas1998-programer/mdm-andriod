package com.rrv.mdm.dpc.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rrv.mdm.dpc.data.model.GeofenceZone
import com.rrv.mdm.dpc.data.model.PolicyPayload

@SuppressLint("HardwareIds", "MissingPermission")
class MdmRepository(private val context: Context) {

    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "rrv_secure_dpc_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to private prefs if Keystore is corrupted
        context.getSharedPreferences("rrv_dpc_vault_fallback", Context.MODE_PRIVATE)
    }

    fun getHardwareImei(): String? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    tm.imei ?: tm.getImei(0)
                } catch (_: Exception) {
                    try { tm.deviceId } catch (_: Exception) { null }
                }
            } else {
                @Suppress("DEPRECATION")
                try { tm.deviceId } catch (_: Exception) { null }
            }
            imei?.trim()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) && !it.equals("null", ignoreCase = true) && it.matches(Regex("^[0-9A-Fa-f]{14,18}$")) }
        } catch (_: Exception) {
            null
        }
    }

    fun getHardwareSerial(): String? {
        return try {
            val serial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { Build.getSerial() } catch (_: Exception) { @Suppress("DEPRECATION") Build.SERIAL }
            } else {
                @Suppress("DEPRECATION") Build.SERIAL
            }
            serial?.trim()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) && !it.equals("null", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    fun getAndroidId(): String? {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.trim()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) && !it.equals("null", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Primary Device Identifier Hierarchy for all transactions:
     * 1. Hardware IMEI (Primary identifier for telephony devices)
     * 2. Hardware Serial Number (Primary fallback for Wi-Fi / non-telephony devices)
     * 3. Enrolled Backend Device ID (if assigned)
     * 4. Android ID
     * 5. Fallback DEV-{MODEL}-{ID}
     */
    fun getEffectiveDeviceId(): String {
        // Priority 1: Hardware IMEI
        val imei = getHardwareImei()
        if (!imei.isNullOrBlank()) {
            return imei
        }

        // Priority 2: Hardware Serial Number
        val serial = getHardwareSerial()
        if (!serial.isNullOrBlank()) {
            return serial
        }

        // Priority 3: Enrolled Backend Device ID
        val stored = prefs.getString("KEY_DEVICE_ID", "") ?: ""
        if (stored.isNotBlank() && stored != "unknown") {
            return stored
        }

        // Priority 4: Android ID
        val androidId = getAndroidId()
        if (!androidId.isNullOrBlank()) {
            return androidId
        }

        // Priority 5: Fallback Hardware Fingerprint
        val fallback = "DEV-" + Build.MODEL.replace(" ", "-") + "-" + Build.ID.take(6)
        return fallback
    }

    var serverUrl: String
        get() = prefs.getString("KEY_SERVER_URL", "")?.takeIf { it.isNotBlank() } ?: com.rrv.mdm.dpc.data.config.MdmGlobalConfig.SERVER_URL
        set(value) = prefs.edit().putString("KEY_SERVER_URL", value).apply()

    var mqttBrokerHost: String
        get() = prefs.getString("KEY_MQTT_HOST", "")?.takeIf { it.isNotBlank() } ?: com.rrv.mdm.dpc.data.config.MdmGlobalConfig.SERVER_HOST
        set(value) = prefs.edit().putString("KEY_MQTT_HOST", value).apply()

    var mqttPort: Int
        get() = prefs.getInt("KEY_MQTT_PORT", 0).takeIf { it > 0 } ?: com.rrv.mdm.dpc.data.config.MdmGlobalConfig.MQTT_PORT
        set(value) = prefs.edit().putInt("KEY_MQTT_PORT", value).apply()

    var deviceId: String
        get() = getEffectiveDeviceId()
        set(value) = prefs.edit().putString("KEY_DEVICE_ID", value).apply()

    var enrollmentToken: String
        get() = prefs.getString("KEY_ENROLL_TOKEN", "")?.takeIf { it.isNotBlank() } ?: "RRV-DEMO-2026"
        set(value) = prefs.edit().putString("KEY_ENROLL_TOKEN", value).apply()

    var isEnrolled: Boolean
        get() = prefs.getBoolean("KEY_IS_ENROLLED", false) || prefs.getString("KEY_DEVICE_ID", "")?.isNotBlank() == true
        set(value) = prefs.edit().putBoolean("KEY_IS_ENROLLED", value).apply()

    fun saveActivePolicy(policy: PolicyPayload) {
        val json = gson.toJson(policy)
        prefs.edit().putString("KEY_ACTIVE_POLICY", json).apply()
    }

    fun getActivePolicy(): PolicyPayload {
        val json = prefs.getString("KEY_ACTIVE_POLICY", null)
        return PolicyPayload.fromJson(json)
    }

    fun saveGeofences(zones: List<GeofenceZone>) {
        val json = gson.toJson(zones)
        prefs.edit().putString("KEY_CACHED_GEOFENCES", json).apply()
    }

    fun getGeofences(): List<GeofenceZone> {
        val json = prefs.getString("KEY_CACHED_GEOFENCES", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<GeofenceZone>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Device JWT (stored after enrollment for APK download auth) ──────────────
    var deviceJwt: String
        get() = prefs.getString("KEY_DEVICE_JWT", "") ?: ""
        set(value) = prefs.edit().putString("KEY_DEVICE_JWT", value).apply()

    // ── Last known GPS coordinates (updated by LocationService) ─────────────────
    var lastLatitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong("KEY_LAST_LAT", java.lang.Double.doubleToLongBits(0.0)))
        set(value) = prefs.edit().putLong("KEY_LAST_LAT", java.lang.Double.doubleToLongBits(value)).apply()

    var lastLongitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong("KEY_LAST_LNG", java.lang.Double.doubleToLongBits(0.0)))
        set(value) = prefs.edit().putLong("KEY_LAST_LNG", java.lang.Double.doubleToLongBits(value)).apply()

    // ── App Catalog Whitelist (allowed packages from server) ─────────────────────
    fun saveAppCatalog(catalogJson: String) {
        prefs.edit().putString("KEY_APP_CATALOG", catalogJson).apply()
    }

    fun getAppCatalog(): String {
        return prefs.getString("KEY_APP_CATALOG", "[]") ?: "[]"
    }

    // ── Per-package Managed Config (AppConfig JSON pushed by server) ─────────────
    fun saveManagedConfig(packageName: String, configJson: String) {
        prefs.edit().putString("KEY_APP_CONFIG_$packageName", configJson).apply()
    }

    fun getManagedConfig(packageName: String): String {
        return prefs.getString("KEY_APP_CONFIG_$packageName", "{}") ?: "{}"
    }
}

