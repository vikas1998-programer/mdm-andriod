package com.rrv.mdm.dpc.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<RrvMdmApplication>()

    // Managed Applications State
    val apps: StateFlow<List<ApplicationInfo>> = app.getManagedAppsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Device Status & Compliance State
    val deviceStatus: StateFlow<DeviceStatusInfo> = app.getDeviceStatusUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeviceStatusInfo())

    // Admin Messages State
    val unreadMessageCount: StateFlow<Int> = app.getAdminMessagesUseCase.getUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val latestAdminMessage: StateFlow<AdminMessage?> = app.getAdminMessagesUseCase.getLatestMessage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Real-Time Executing Command Card State
    val executingCommand: StateFlow<MdmCommand?> = app.getRecentCommandsUseCase.getActiveExecutingCommand()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Real-Time Clock & Date State
    private val _currentTime = MutableStateFlow(formatTime())
    val currentTime: StateFlow<String> = _currentTime.asStateFlow()

    private val _currentDate = MutableStateFlow(formatDate())
    val currentDate: StateFlow<String> = _currentDate.asStateFlow()

    init {
        startClockTicker()
        seedDefaultEnterpriseAppsIfEmpty()
    }

    private fun startClockTicker() {
        viewModelScope.launch {
            while (isActive) {
                _currentTime.value = formatTime()
                _currentDate.value = formatDate()
                delay(1000L)
            }
        }
    }

    private fun formatTime(): String {
        return SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
    }

    private fun formatDate(): String {
        return SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date())
    }

    fun launchApp(packageName: String): Boolean {
        return app.launchAppUseCase(packageName)
    }

    fun dismissAdminMessage(messageId: String) {
        viewModelScope.launch {
            app.getAdminMessagesUseCase.markAsRead(messageId)
        }
    }

    fun refreshDeviceStatus() {
        viewModelScope.launch {
            app.getDeviceStatusUseCase.refresh()
        }
    }

    private fun seedDefaultEnterpriseAppsIfEmpty() {
        viewModelScope.launch {
            val pm = app.packageManager
            val activePolicy = app.repository.getActivePolicy()
            val policyApps = activePolicy.applications.filter { it.installType.uppercase() in listOf("SHOW", "VISIBLE", "ALLOWED", "REQUIRED", "MANAGED", "FORCE_INSTALLED", "AVAILABLE", "INSTALL") }

            val appsToSeed = if (policyApps.isNotEmpty()) {
                policyApps.map { appPolicy ->
                    val isInstalled = try { pm.getPackageInfo(appPolicy.packageName, 0); true } catch (_: Exception) { false }
                    val label = if (appPolicy.title.isNotBlank() && appPolicy.title != appPolicy.packageName) {
                        appPolicy.title
                    } else {
                        try {
                            val info = pm.getApplicationInfo(appPolicy.packageName, 0)
                            pm.getApplicationLabel(info).toString()
                        } catch (_: Exception) {
                            if (appPolicy.title.isNotBlank()) appPolicy.title else appPolicy.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                        }
                    }

                    ApplicationInfo(
                        packageName = appPolicy.packageName,
                        appName = label,
                        iconUrl = appPolicy.iconUrl,
                        versionName = "1.0",
                        isLaunchable = true,
                        isEnabled = true,
                        isManaged = true,
                        installStatus = if (isInstalled) InstallStatus.INSTALLED else InstallStatus.AVAILABLE
                    )
                }
            } else {
                // Strict Zero-Trust Default Deny: 0 apps allowed by default
                emptyList()
            }
            app.repositoryImpl.syncAppsFromPolicy(appsToSeed)
        }
    }
}
