package com.rrv.mdm.dpc.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rrv.mdm.dpc.data.model.PolicyPayload

object NotificationHelper {

    private const val CHANNEL_ID = "rrv_mdm_policy_channel"
    private const val CHANNEL_NAME = "Enterprise Policy & Security Updates"

    fun showPolicyNotification(context: Context, policy: PolicyPayload) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifies when IT administrator pushes new enterprise security policies."
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val details = buildString {
                append("Profile: ${policy.name}\n")
                append("- Camera: ${if (policy.cameraDisabled) "Disabled" else "Enabled"}\n")
                append("- Bluetooth: ${if (policy.bluetoothDisabled) "Disabled" else "Enabled"}\n")
                append("- Screenshots: ${if (policy.screenCaptureDisabled) "Disabled" else "Allowed"}\n")
                append("- Allowed Apps: ${policy.allowedKioskPackages.size} packages")
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Policy Enforced: ${policy.name}")
                .setContentText("Camera: ${if (policy.cameraDisabled) "Disabled" else "Enabled"} | Bluetooth: ${if (policy.bluetoothDisabled) "Disabled" else "Enabled"}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(1001, notification)
        } catch (e: Exception) {
            RrvLog.e("NotificationHelper", "Failed to display policy notification", e)
        }
    }

    fun showCommandNotification(context: Context, title: String, message: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifies when remote IT commands are executed."
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = android.content.Intent(context, com.rrv.mdm.dpc.ui.messages.AdminMessagesActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getActivity(context, 0, intent, pendingFlags)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
        } catch (e: Exception) {
            RrvLog.e("NotificationHelper", "Failed to display command notification", e)
        }
    }
}
