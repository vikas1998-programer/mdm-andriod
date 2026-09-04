package com.rrv.mdm.dpc.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Enterprise Production Logging Subsystem for Android DPC Agent.
 * Mirror logs to Logcat and maintains an in-memory ring buffer for IT diagnostic export.
 */
object RrvLog {

    private const val DEFAULT_TAG = "RRV-MDM-DPC"
    private const val MAX_BUFFER_LINES = 500

    private val logBuffer = ConcurrentLinkedQueue<String>()
    private val rawLogBuffer = ConcurrentLinkedQueue<DeviceLogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    var onLogPublished: ((DeviceLogEntry) -> Unit)? = null

    data class DeviceLogEntry(
        val level: String,
        val tag: String,
        val message: String,
        val timestamp: Long
    )

    fun d(tag: String, message: String) {
        recordLog("DEBUG", tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        recordLog("INFO", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        recordLog("WARN", tag, "$message ${throwable?.message ?: ""}")
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        recordLog("ERROR", tag, "$message ${throwable?.message ?: ""}")
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    // Contextual Tagged Logging Helpers
    fun mqtt(message: String) = i("MQTT-TRANSPORT", "📡 $message")
    fun dpm(message: String) = i("DPM-POLICY", "🛡️ $message")
    fun geo(message: String) = i("SPATIAL-GEO", "📍 $message")
    fun kiosk(message: String) = i("KIOSK-LOCK", "🔒 $message")
    fun boot(message: String) = i("DPC-LIFECYCLE", "⚡ $message")

    private fun recordLog(level: String, tag: String, message: String): String {
        val now = System.currentTimeMillis()
        val timestamp = dateFormat.format(Date(now))
        val entry = "[$timestamp] [$level] [$tag]: $message"

        val logEntry = DeviceLogEntry(level, tag, message, now)
        rawLogBuffer.add(logEntry)
        while (rawLogBuffer.size > MAX_BUFFER_LINES) {
            rawLogBuffer.poll()
        }

        logBuffer.add(entry)
        while (logBuffer.size > MAX_BUFFER_LINES) {
            logBuffer.poll()
        }

        try {
            onLogPublished?.invoke(logEntry)
        } catch (_: Exception) {}

        return entry
    }

    fun getRawLogs(): List<DeviceLogEntry> {
        return rawLogBuffer.toList()
    }

    fun getDiagnosticLogs(): List<String> {
        return logBuffer.toList()
    }

    fun getFormattedLogs(): String {
        return logBuffer.joinToString("\n")
    }
}
