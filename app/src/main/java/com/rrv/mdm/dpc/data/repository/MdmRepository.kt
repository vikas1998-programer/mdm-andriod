package com.rrv.mdm.dpc.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rrv.mdm.dpc.data.model.GeofenceZone
import com.rrv.mdm.dpc.data.model.PolicyPayload

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

    var serverUrl: String
        get() = prefs.getString("KEY_SERVER_URL", "")?.takeIf { it.isNotBlank() } ?: "https://yang-neighbors-affair-disks.trycloudflare.com"
        set(value) = prefs.edit().putString("KEY_SERVER_URL", value).apply()

    var mqttBrokerHost: String
        get() = prefs.getString("KEY_MQTT_HOST", "")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        set(value) = prefs.edit().putString("KEY_MQTT_HOST", value).apply()

    var mqttPort: Int
        get() = prefs.getInt("KEY_MQTT_PORT", 0).takeIf { it > 0 } ?: 1883
        set(value) = prefs.edit().putInt("KEY_MQTT_PORT", value).apply()

    var deviceId: String
        get() = prefs.getString("KEY_DEVICE_ID", "") ?: ""
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

