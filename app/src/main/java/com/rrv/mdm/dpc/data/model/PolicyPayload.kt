package com.rrv.mdm.dpc.data.model

import com.google.gson.annotations.SerializedName

data class PolicyPayload(
    @SerializedName("policyId") val policyId: String = "",
    @SerializedName("name") val name: String = "Corporate Android Enterprise Standard",
    @SerializedName("version") val version: Int = 1,

    // Hardware Peripheral Restrictions
    @SerializedName("cameraDisabled") val cameraDisabled: Boolean = false,
    @SerializedName("screenCaptureDisabled") val screenCaptureDisabled: Boolean = false,
    @SerializedName("usbDataTransferDisabled") val usbDataTransferDisabled: Boolean = false,
    @SerializedName("bluetoothDisabled") val bluetoothDisabled: Boolean = false,
    @SerializedName("sdCardDisabled") val sdCardDisabled: Boolean = false,
    @SerializedName("microphoneDisabled") val microphoneDisabled: Boolean = false,
    @SerializedName("factoryResetDisabled") val factoryResetDisabled: Boolean = false,
    @SerializedName("safeBootDisabled") val safeBootDisabled: Boolean = false,
    @SerializedName("developerOptionsDisabled") val developerOptionsDisabled: Boolean = false,
    @SerializedName("statusBarDisabled") val statusBarDisabled: Boolean = false,

    // Network Restrictions
    @SerializedName("tetheringDisabled") val tetheringDisabled: Boolean = false,
    @SerializedName("wifiConfigLock") val wifiConfigLock: Boolean = false,
    @SerializedName("dataRoamingDisabled") val dataRoamingDisabled: Boolean = false,
    @SerializedName("airplaneModeDisabled") val airplaneModeDisabled: Boolean = false,

    // Data Loss Prevention (DLP) & App Governance
    @SerializedName("clipboardDlpDisabled") val clipboardDlpDisabled: Boolean = false,
    @SerializedName("appUninstallDisabled") val appUninstallDisabled: Boolean = false,
    @SerializedName("unknownSourcesDisabled") val unknownSourcesDisabled: Boolean = false,
    @SerializedName("applications") val applications: List<ApplicationPolicy> = emptyList(),

    // Kiosk Mode Settings
    @SerializedName("kioskModeEnabled") val kioskModeEnabled: Boolean = false,
    @SerializedName("kioskAdminPin") val kioskAdminPin: String = "123456",
    @SerializedName("allowedKioskPackages") val allowedKioskPackages: List<String> = emptyList(),

    // Password & Lockout Governance
    @SerializedName("minPasswordLength") val minPasswordLength: Int = 6,
    @SerializedName("maxFailedAttempts") val maxFailedAttempts: Int = 5,
    @SerializedName("lockoutDurationMinutes") val lockoutDurationMinutes: Int = 15,

    // Display & Screen Brightness Governance
    @SerializedName("screenBrightnessPercent") val screenBrightnessPercent: Int? = null,
    @SerializedName("autoBrightnessEnabled") val autoBrightnessEnabled: Boolean = true,
    @SerializedName("screenTimeoutSeconds") val screenTimeoutSeconds: Int = 300,

    // Audio & Volume Level Governance
    @SerializedName("masterVolumeMuted") val masterVolumeMuted: Boolean = false,
    @SerializedName("volumeAdjustDisabled") val volumeAdjustDisabled: Boolean = false,
    @SerializedName("mediaVolumePercent") val mediaVolumePercent: Int? = null,
    @SerializedName("alarmVolumePercent") val alarmVolumePercent: Int? = null,
    @SerializedName("ringVolumePercent") val ringVolumePercent: Int? = null,

    // Mobile UI & Launcher Branding Design Control
    @SerializedName("launcherDesign") val launcherDesign: LauncherDesignPolicy = LauncherDesignPolicy()
) {
    companion object {
        fun fromJson(json: String?): PolicyPayload {
            if (json.isNullOrBlank()) return PolicyPayload()
            val gson = com.google.gson.Gson()
            return try {
                val base = gson.fromJson(json, PolicyPayload::class.java) ?: PolicyPayload()
                val root = gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: return base

                var cameraDisabled = base.cameraDisabled
                var screenCaptureDisabled = base.screenCaptureDisabled
                var usbDataDisabled = base.usbDataTransferDisabled
                var bluetoothDisabled = base.bluetoothDisabled
                var sdCardDisabled = base.sdCardDisabled
                var microphoneDisabled = base.microphoneDisabled

                var factoryResetDisabled = base.factoryResetDisabled
                var safeBootDisabled = base.safeBootDisabled
                var developerOptionsDisabled = base.developerOptionsDisabled

                var tetheringDisabled = base.tetheringDisabled
                var wifiConfigLock = base.wifiConfigLock
                var dataRoamingDisabled = base.dataRoamingDisabled
                var airplaneModeDisabled = base.airplaneModeDisabled

                var clipboardDlpDisabled = base.clipboardDlpDisabled
                var appUninstallDisabled = base.appUninstallDisabled
                var unknownSourcesDisabled = base.unknownSourcesDisabled

                // Check nested hardware block
                val hw = root["hardware"] as? Map<*, *>
                if (hw != null) {
                    (hw["cameraDisabled"] as? Boolean ?: hw["camera_disabled"] as? Boolean)?.let { cameraDisabled = it }
                    (hw["screenCaptureDisabled"] as? Boolean ?: hw["screen_capture_disabled"] as? Boolean)?.let { screenCaptureDisabled = it }
                    (hw["usbDataDisabled"] as? Boolean ?: hw["usb_data_disabled"] as? Boolean)?.let { usbDataDisabled = it }
                    (hw["bluetoothDisabled"] as? Boolean ?: hw["bluetooth_disabled"] as? Boolean)?.let { bluetoothDisabled = it }
                    (hw["sdCardDisabled"] as? Boolean ?: hw["external_media_disabled"] as? Boolean)?.let { sdCardDisabled = it }
                    (hw["microphoneDisabled"] as? Boolean ?: hw["microphone_disabled"] as? Boolean)?.let { microphoneDisabled = it }
                }

                // Check nested network block
                val net = root["network"] as? Map<*, *>
                if (net != null) {
                    (net["tetheringDisabled"] as? Boolean ?: net["tethering_disabled"] as? Boolean)?.let { tetheringDisabled = it }
                    (net["wifiConfigLock"] as? Boolean ?: net["wifi_config_lock"] as? Boolean)?.let { wifiConfigLock = it }
                    (net["dataRoamingDisabled"] as? Boolean ?: net["data_roaming_disabled"] as? Boolean)?.let { dataRoamingDisabled = it }
                    (net["airplaneModeDisabled"] as? Boolean ?: net["airplane_mode_disabled"] as? Boolean)?.let { airplaneModeDisabled = it }
                }

                // Check nested system block
                val sys = root["system"] as? Map<*, *>
                if (sys != null) {
                    (sys["factoryResetDisabled"] as? Boolean ?: sys["factory_reset_disabled"] as? Boolean)?.let { factoryResetDisabled = it }
                    (sys["safeBootDisabled"] as? Boolean ?: sys["safe_boot_disabled"] as? Boolean)?.let { safeBootDisabled = it }
                    (sys["developerOptionsDisabled"] as? Boolean ?: sys["developer_options_disabled"] as? Boolean)?.let { developerOptionsDisabled = it }
                }

                // Check nested dlp block
                val dlp = root["dlp"] as? Map<*, *>
                if (dlp != null) {
                    (dlp["crossProfileCopyPasteDisabled"] as? Boolean ?: dlp["clipboardDlpDisabled"] as? Boolean)?.let { clipboardDlpDisabled = it }
                    (dlp["usbMassStorageDisabled"] as? Boolean ?: dlp["external_media_disabled"] as? Boolean)?.let { sdCardDisabled = it }
                }

                // Check nested passcode block
                val pass = root["passcode"] as? Map<*, *>
                var minPassLen = base.minPasswordLength
                var maxAttempts = base.maxFailedAttempts
                if (pass != null) {
                    (pass["minPasswordLength"] as? Number ?: pass["min_length"] as? Number)?.let { minPassLen = it.toInt() }
                    (pass["maxFailedAttemptsForWipe"] as? Number ?: pass["max_failed_attempts_wipe"] as? Number)?.let { maxAttempts = it.toInt() }
                }

                // Check nested kiosk block
                val kiosk = root["kiosk"] as? Map<*, *>
                var kioskMode = base.kioskModeEnabled
                var allowedKiosk = base.allowedKioskPackages
                if (kiosk != null) {
                    (kiosk["kioskModeEnabled"] as? Boolean ?: kiosk["isKioskEnabled"] as? Boolean ?: kiosk["enabled"] as? Boolean)?.let { kioskMode = it }
                    (kiosk["allowedKioskPackages"] as? List<*>)?.let { list ->
                        allowedKiosk = list.filterIsInstance<String>()
                    }
                }

                // Check applications array
                val apps = root["applications"] as? List<*>
                val appList = mutableListOf<ApplicationPolicy>()
                if (apps != null) {
                    for (item in apps) {
                        if (item is Map<*, *>) {
                            val pkg = item["packageName"]?.toString() ?: item["package_name"]?.toString() ?: ""
                            val title = item["title"]?.toString() ?: pkg
                            val installType = item["installType"]?.toString() ?: item["install_type"]?.toString() ?: "BLOCKED"
                            val iconUrl = item["iconUrl"]?.toString() ?: item["icon_url"]?.toString()
                            val config = item["managedConfigJson"]?.toString() ?: item["managed_config_json"]?.toString()
                            if (pkg.isNotBlank()) {
                                appList.add(ApplicationPolicy(pkg, title, iconUrl, installType.uppercase(), config))
                            }
                        }
                    }
                    val visiblePkgs = appList.filter {
                        it.installType.uppercase() in listOf("SHOW", "VISIBLE", "INSTALL", "FORCE_INSTALLED", "AVAILABLE", "ALLOWED", "REQUIRED", "MANAGED", "MANDATORY", "MANDATORY_SILENT", "MANAGED_SILENT", "SILENT", "OPTIONAL", "AUTO_INSTALL")
                    }.map { it.packageName }
                    if (visiblePkgs.isNotEmpty()) {
                        allowedKiosk = (allowedKiosk + visiblePkgs).distinct()
                    }
                }

                // Check nested display & audio
                val disp = root["display"] as? Map<*, *>
                var brightness = base.screenBrightnessPercent
                var autoBright = base.autoBrightnessEnabled
                var timeout = base.screenTimeoutSeconds
                if (disp != null) {
                    (disp["screenBrightnessPercent"] as? Number ?: disp["brightness"] as? Number ?: disp["screen_brightness_percent"] as? Number)?.let { brightness = it.toInt() }
                    (disp["autoBrightnessEnabled"] as? Boolean ?: disp["auto_brightness_enabled"] as? Boolean)?.let { autoBright = it }
                    (disp["screenTimeoutSeconds"] as? Number ?: disp["timeout"] as? Number ?: disp["screen_timeout_seconds"] as? Number)?.let { timeout = it.toInt() }
                }
                (root["screenBrightnessPercent"] as? Number ?: root["brightness"] as? Number ?: root["screen_brightness_percent"] as? Number)?.let { brightness = it.toInt() }
                (root["autoBrightnessEnabled"] as? Boolean ?: root["auto_brightness_enabled"] as? Boolean)?.let { autoBright = it }
                (root["screenTimeoutSeconds"] as? Number ?: root["timeout"] as? Number ?: root["screen_timeout_seconds"] as? Number)?.let { timeout = it.toInt() }

                val aud = root["audio"] as? Map<*, *>
                var masterMute = base.masterVolumeMuted
                var volLock = base.volumeAdjustDisabled
                var mediaVol = base.mediaVolumePercent
                var alarmVol = base.alarmVolumePercent
                var ringVol = base.ringVolumePercent
                if (aud != null) {
                    (aud["masterVolumeMuted"] as? Boolean ?: aud["master_volume_muted"] as? Boolean)?.let { masterMute = it }
                    (aud["volumeAdjustDisabled"] as? Boolean ?: aud["volume_adjust_disabled"] as? Boolean)?.let { volLock = it }
                    (aud["mediaVolumePercent"] as? Number ?: aud["media_volume_percent"] as? Number ?: aud["mediaVolume"] as? Number)?.let { mediaVol = it.toInt() }
                    (aud["alarmVolumePercent"] as? Number ?: aud["alarm_volume_percent"] as? Number ?: aud["alarmVolume"] as? Number)?.let { alarmVol = it.toInt() }
                    (aud["ringVolumePercent"] as? Number ?: aud["ring_volume_percent"] as? Number ?: aud["ringVolume"] as? Number)?.let { ringVol = it.toInt() }
                }
                (root["masterVolumeMuted"] as? Boolean ?: root["master_volume_muted"] as? Boolean)?.let { masterMute = it }
                (root["volumeAdjustDisabled"] as? Boolean ?: root["volume_adjust_disabled"] as? Boolean)?.let { volLock = it }
                (root["mediaVolumePercent"] as? Number ?: root["media_volume_percent"] as? Number ?: root["mediaVolume"] as? Number)?.let { mediaVol = it.toInt() }
                (root["alarmVolumePercent"] as? Number ?: root["alarm_volume_percent"] as? Number ?: root["alarmVolume"] as? Number)?.let { alarmVol = it.toInt() }
                (root["ringVolumePercent"] as? Number ?: root["ring_volume_percent"] as? Number ?: root["ringVolume"] as? Number)?.let { ringVol = it.toInt() }

                base.copy(
                    cameraDisabled = cameraDisabled,
                    screenCaptureDisabled = screenCaptureDisabled,
                    usbDataTransferDisabled = usbDataDisabled,
                    bluetoothDisabled = bluetoothDisabled,
                    sdCardDisabled = sdCardDisabled,
                    microphoneDisabled = microphoneDisabled,
                    factoryResetDisabled = factoryResetDisabled,
                    safeBootDisabled = safeBootDisabled,
                    developerOptionsDisabled = developerOptionsDisabled,
                    tetheringDisabled = tetheringDisabled,
                    wifiConfigLock = wifiConfigLock,
                    dataRoamingDisabled = dataRoamingDisabled,
                    airplaneModeDisabled = airplaneModeDisabled,
                    clipboardDlpDisabled = clipboardDlpDisabled,
                    appUninstallDisabled = appUninstallDisabled,
                    unknownSourcesDisabled = unknownSourcesDisabled,
                    applications = if (apps != null || root.containsKey("applications")) appList else base.applications,
                    minPasswordLength = minPassLen,
                    maxFailedAttempts = maxAttempts,
                    kioskModeEnabled = kioskMode,
                    allowedKioskPackages = allowedKiosk,
                    screenBrightnessPercent = brightness,
                    autoBrightnessEnabled = autoBright,
                    screenTimeoutSeconds = timeout,
                    masterVolumeMuted = masterMute,
                    volumeAdjustDisabled = volLock,
                    mediaVolumePercent = mediaVol,
                    alarmVolumePercent = alarmVol,
                    ringVolumePercent = ringVol
                )
            } catch (_: Exception) {
                PolicyPayload()
            }
        }
    }
}

data class ApplicationPolicy(
    @SerializedName("packageName") val packageName: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("iconUrl") val iconUrl: String? = null,
    @SerializedName("installType") val installType: String = "VISIBLE", // VISIBLE, HIDDEN, BLOCKED, FORCE_INSTALLED, AVAILABLE
    @SerializedName("managedConfigJson") val managedConfigJson: String? = null
)

data class LauncherDesignPolicy(
    @SerializedName("wallpaperUrl") val wallpaperUrl: String? = null,
    @SerializedName("backgroundColor") val backgroundColor: String? = null,
    @SerializedName("backgroundTheme") val backgroundTheme: String = "CYBER_GLASS", // CYBER_GLASS, CLEAN_SLATE, MIDNIGHT_BLUE, CARBON_RUGGED, CUSTOM
    @SerializedName("screenOrientation") val screenOrientation: String = "PORTRAIT", // PORTRAIT, LANDSCAPE, AUTO_ROTATE
    @SerializedName("gridColumns") val gridColumns: Int = 4,
    @SerializedName("showClockWidget") val showClockWidget: Boolean = true,
    @SerializedName("showTelemetryPill") val showTelemetryPill: Boolean = true,
    @SerializedName("showBottomDock") val showBottomDock: Boolean = true,
    @SerializedName("companyTitle") val companyTitle: String = "RRV Enterprise Global",
    @SerializedName("companySubtitle") val companySubtitle: String = "Zero-Trust Secured Workstation"
)


