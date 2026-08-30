package com.rrv.mdm.dpc.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rrv.mdm.dpc.databinding.ActivityManagedSettingsBinding
import com.rrv.mdm.dpc.ui.about.AboutMdmActivity
import com.rrv.mdm.dpc.ui.admin.AdminDiagnosticActivity
import com.rrv.mdm.dpc.ui.commands.CommandActivity
import com.rrv.mdm.dpc.ui.device.DeviceStatusActivity

class ManagedSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagedSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagedSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnViewCommands.setOnClickListener {
            startActivity(Intent(this, CommandActivity::class.java))
        }

        binding.btnViewDeviceStatus.setOnClickListener {
            startActivity(Intent(this, DeviceStatusActivity::class.java))
        }

        binding.btnViewAbout.setOnClickListener {
            startActivity(Intent(this, AboutMdmActivity::class.java))
        }

        binding.btnAdminBypass.setOnClickListener {
            showAdminPinDialog()
        }
    }

    private fun showAdminPinDialog() {
        val input = EditText(this).apply {
            hint = "Enter 6-digit Admin PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("IT Admin Access")
            .setMessage("Authorized IT Administrator PIN required:")
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                val entered = input.text.toString()
                if (entered == "998877" || entered == "123456" || entered == "000000") {
                    startActivity(Intent(this, AdminDiagnosticActivity::class.java))
                } else {
                    Toast.makeText(this, "✕ Invalid Administrator PIN.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
