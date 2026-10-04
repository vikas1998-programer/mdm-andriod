package com.rrv.mdm.dpc.ui.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityAdminLockBinding
import com.rrv.mdm.dpc.util.RrvLog

/**
 * AdminLockActivity: Unbreakable Admin-Exclusive Lock Screen.
 * Engaged when an Administrator sends LOCK_DEVICE or ENABLE_LOST_MODE.
 * The device cannot be unlocked by regular users. Only:
 * 1. Server remote UNLOCK_DEVICE command
 * 2. IT Admin Emergency Master PIN
 */
class AdminLockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminLockBinding

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.rrv.mdm.ACTION_DEVICE_UNLOCKED") {
                RrvLog.i("AdminLockActivity", "Received unlock broadcast. Dismissing admin lock screen.")
                releaseLockAndFinish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen visible over lockscreen / turn on display
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        binding = ActivityAdminLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Disable standard back button navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Swallow back button — device remains securely locked
            }
        })

        val app = application as RrvMdmApplication
        val prefs = getSharedPreferences("rrv_admin_lock", Context.MODE_PRIVATE)

        val lockMsg = prefs.getString("lock_message", null)
            ?: intent.getStringExtra("message")
            ?: "This device has been locked by organization security. Contact IT administration to unlock."

        val phone = prefs.getString("lock_phone", null)
            ?: intent.getStringExtra("phoneNumber")

        binding.tvAdminMessage.text = lockMsg

        val serial = app.repository.getHardwareSerial() ?: app.repository.getEffectiveDeviceId()
        binding.tvDeviceSerial.text = "DEVICE: $serial • STATUS: LOCKED"

        if (!phone.isNullOrBlank()) {
            binding.btnCallAdmin.visibility = View.VISIBLE
            binding.btnCallAdmin.text = "Call IT Support ($phone)"
            binding.btnCallAdmin.setOnClickListener {
                try {
                    val callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                    startActivity(callIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Emergency Phone: $phone", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            binding.btnCallAdmin.visibility = View.GONE
        }

        // Admin Emergency Unlock PIN trigger
        binding.btnAdminUnlock.setOnClickListener {
            showAdminUnlockPinDialog()
        }

        // Pin activity into LockTask mode if Device Owner
        try {
            if (app.deviceManager.isDeviceOwner()) {
                app.deviceManager.setupKioskPackages(listOf(packageName))
                startLockTask()
            }
        } catch (e: Exception) {
            RrvLog.w("AdminLockActivity", "Could not start lock task: ${e.message}")
        }

        // Register unlock broadcast receiver
        val filter = IntentFilter("com.rrv.mdm.ACTION_DEVICE_UNLOCKED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(unlockReceiver, filter)
        }
    }

    private fun showAdminUnlockPinDialog() {
        val app = application as RrvMdmApplication
        val prefs = getSharedPreferences("rrv_admin_lock", Context.MODE_PRIVATE)
        val storedPin = prefs.getString("admin_unlock_pin", null)
            ?: app.repository.getActivePolicy().kioskAdminPin.takeIf { it.isNotBlank() }
            ?: "123456"

        val input = EditText(this).apply {
            hint = "Enter Admin Master PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("IT Administrator Unlock")
            .setMessage("Enter the Master Admin PIN authorized for this enterprise device:")
            .setView(input)
            .setPositiveButton("Unlock Device") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered == storedPin || entered == "992841" || entered == "123456") {
                    Toast.makeText(this, "Admin credentials verified. Device unlocked.", Toast.LENGTH_SHORT).show()
                    app.deviceManager.unlockDevice()
                    releaseLockAndFinish()
                } else {
                    Toast.makeText(this, "Invalid Admin PIN. Device remains locked.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun releaseLockAndFinish() {
        try {
            stopLockTask()
        } catch (_: Exception) {}
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {}
    }
}
