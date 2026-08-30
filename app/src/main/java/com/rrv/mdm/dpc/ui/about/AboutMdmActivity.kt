package com.rrv.mdm.dpc.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityAboutMdmBinding

class AboutMdmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutMdmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutMdmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val app = application as RrvMdmApplication
        binding.tvAboutServer.text = "MDM Server: ${app.repository.serverUrl}"
        binding.tvAboutDeviceId.text = "Device ID: ${app.repository.deviceId}"
        val isDO = app.deviceManager.isDeviceOwner()
        binding.tvAboutEnrollment.text = "Enrollment State: ${if (app.repository.isEnrolled) "ENROLLED" else "UNENROLLED"} (${if (isDO) "Device Owner" else "Profile"})"
    }
}
