package com.rrv.mdm.dpc.worker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.util.Log
import androidx.work.*
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.util.RrvLog
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * WorkManager background worker that:
 *  1. Downloads an APK from the MDM server (authenticated with device JWT)
 *  2. Verifies SHA-256 checksum against the server-provided hash
 *  3. Silently installs via PackageInstaller.Session (Device Owner privilege)
 *  4. Reports result to MdmMqttManager as an APP_INSTALLED / APP_INSTALL_FAILED event
 *
 * Input Data keys:
 *   commandId     — MQTT command UUID (for ACK)
 *   appId         — catalog app UUID
 *   packageName   — e.g. "com.example.warehouse"
 *   appTitle      — display name
 *   downloadUrl   — https://server/api/v1/apps/{appId}/download
 *   sha256        — expected SHA-256 hex string
 *   versionCode   — integer version code
 *   versionName   — string version name
 *   appConfigJson — managed config JSON (stored post-install)
 */
class ApkDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ApkDownloadWorker"

        fun enqueue(
            context: Context,
            commandId: String,
            appId: String,
            packageName: String,
            appTitle: String,
            downloadUrl: String,
            sha256: String,
            versionCode: Int,
            versionName: String,
            appConfigJson: String
        ): Operation {
            val data = workDataOf(
                "commandId" to commandId,
                "appId" to appId,
                "packageName" to packageName,
                "appTitle" to appTitle,
                "downloadUrl" to downloadUrl,
                "sha256" to sha256,
                "versionCode" to versionCode,
                "versionName" to versionName,
                "appConfigJson" to appConfigJson
            )
            val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("apk-install-$packageName")
                .build()

            return WorkManager.getInstance(context).enqueueUniqueWork(
                "apk-install-$packageName",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val commandId  = inputData.getString("commandId") ?: ""
        val packageName = inputData.getString("packageName") ?: return Result.failure()
        val downloadUrl = inputData.getString("downloadUrl") ?: return Result.failure()
        val expectedSha = inputData.getString("sha256") ?: ""
        val versionCode = inputData.getInt("versionCode", 0)
        val versionName = inputData.getString("versionName") ?: "1.0"
        val appConfigJson = inputData.getString("appConfigJson") ?: "{}"

        val app = context.applicationContext as RrvMdmApplication
        val mqttManager = app.mqttManager
        val repository  = app.repository

        RrvLog.i(TAG, "⬇️  Starting APK download: pkg=$packageName v$versionName url=$downloadUrl")

        // ── Step 1: Download APK ─────────────────────────────────────────
        val apkFile = File(context.cacheDir, "$packageName-$versionCode.apk")
        try {
            downloadApk(downloadUrl, apkFile, repository.deviceJwt)
        } catch (e: IOException) {
            RrvLog.e(TAG, "Download failed for $packageName: ${e.message}", e)
            mqttManager.publishCommandAck(commandId, "FAILED", "APK download error: ${e.message}")
            return Result.retry()
        }

        // ── Step 2: SHA-256 Integrity Check ──────────────────────────────
        if (expectedSha.isNotBlank()) {
            val actualSha = sha256(apkFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                RrvLog.e(TAG, "❌ SHA-256 mismatch for $packageName! expected=$expectedSha got=$actualSha")
                apkFile.delete()
                mqttManager.publishCommandAck(commandId, "FAILED", "SHA-256 integrity check failed")
                return Result.failure()
            }
            RrvLog.i(TAG, "✓ SHA-256 verified for $packageName")
        }

        // ── Step 3: Silent Install via PackageInstaller ───────────────────
        try {
            silentInstall(apkFile, packageName)
            RrvLog.i(TAG, "✅ APK install session committed: $packageName v$versionName")
            mqttManager.publishCommandAck(commandId, "EXECUTED", "APK installed silently: $packageName v$versionName")

            // Persist managed config values for this package
            if (appConfigJson.isNotBlank() && appConfigJson != "{}") {
                repository.saveManagedConfig(packageName, appConfigJson)
            }
        } catch (e: Exception) {
            RrvLog.e(TAG, "Silent install failed for $packageName: ${e.message}", e)
            apkFile.delete()
            mqttManager.publishCommandAck(commandId, "FAILED", "Silent install error: ${e.message}")
            return Result.retry()
        }

        apkFile.delete()
        return Result.success()
    }

    // ── Download ──────────────────────────────────────────────────────────────

    @Throws(IOException::class)
    private fun downloadApk(url: String, dest: File, jwt: String?) {
        val app = context.applicationContext as RrvMdmApplication
        val fullUrl = if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            url
        } else {
            val base = (app.serverConfigProvider.getApiBaseUrl() ?: app.repository.serverUrl).trimEnd('/')
            val path = if (url.startsWith("/")) url else "/$url"
            "$base$path"
        }

        val connection = URL(fullUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        if (!jwt.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $jwt")
        }
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Server returned HTTP ${connection.responseCode} for $fullUrl")
        }

        connection.inputStream.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
        RrvLog.i(TAG, "✓ Downloaded ${dest.length() / 1024}KB → ${dest.absolutePath}")
    }

    // ── SHA-256 ───────────────────────────────────────────────────────────────

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Silent Install (Device Owner PackageInstaller) ────────────────────────

    @Throws(Exception::class)
    private fun silentInstall(apkFile: File, packageName: String) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
        }
        val sessionId = packageInstaller.createSession(params)
        val session   = packageInstaller.openSession(sessionId)

        try {
            session.openWrite("$packageName.apk", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    input.copyTo(out, bufferSize = 65536)
                    session.fsync(out)
                }
            }

            // Silent commit intent — routed to SilentInstallReceiver
            val intent = Intent("com.rrv.mdm.dpc.SILENT_INSTALL_RESULT")
            intent.setPackage(context.packageName)
            val pi = android.app.PendingIntent.getBroadcast(
                context, sessionId, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pi.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}
