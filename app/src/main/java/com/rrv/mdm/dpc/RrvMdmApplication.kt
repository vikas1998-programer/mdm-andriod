package com.rrv.mdm.dpc

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rrv.mdm.dpc.data.config.ServerConfigurationProvider
import com.rrv.mdm.dpc.data.database.RrvMdmDatabase
import com.rrv.mdm.dpc.data.repository.MdmRepository
import com.rrv.mdm.dpc.data.repository.MdmRepositoryImpl
import com.rrv.mdm.dpc.domain.usecase.*
import com.rrv.mdm.dpc.mdm.command.CommandProcessor
import com.rrv.mdm.dpc.mdm.device.DeviceManagementManager
import com.rrv.mdm.dpc.network.MdmApiClient
import com.rrv.mdm.dpc.network.MdmMqttManager
import com.rrv.mdm.dpc.policy.DpmPolicyManager
import com.rrv.mdm.dpc.policy.LockTaskController
import com.rrv.mdm.dpc.worker.TelemetrySyncWorker
import java.util.concurrent.TimeUnit

class RrvMdmApplication : Application() {

    companion object {
        private const val TAG = "RrvMdmApplication"
    }

    lateinit var serverConfigProvider: ServerConfigurationProvider
    lateinit var repository: MdmRepository
    lateinit var database: RrvMdmDatabase
    lateinit var repositoryImpl: MdmRepositoryImpl
    lateinit var deviceManager: DeviceManagementManager
    lateinit var commandProcessor: CommandProcessor
    lateinit var policyManager: DpmPolicyManager
    lateinit var lockTaskController: LockTaskController
    lateinit var mqttManager: MdmMqttManager
    lateinit var apiClient: MdmApiClient

    // Use Cases
    lateinit var getManagedAppsUseCase: GetManagedAppsUseCase
    lateinit var getDeviceStatusUseCase: GetDeviceStatusUseCase
    lateinit var getAdminMessagesUseCase: GetAdminMessagesUseCase
    lateinit var getRecentCommandsUseCase: GetRecentCommandsUseCase
    lateinit var processCommandUseCase: ProcessCommandUseCase
    lateinit var launchAppUseCase: LaunchAppUseCase

    @SuppressLint("HardwareIds", "MissingPermission")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 Initializing RRV MDM Enterprise Client Subsystems...")

        // 1. Initialize Authoritative Configuration Provider & Database
        serverConfigProvider = ServerConfigurationProvider(this)
        repository = MdmRepository(this)
        database = RrvMdmDatabase.getInstance(this)
        repositoryImpl = MdmRepositoryImpl(this, database, repository)

        // 2. Initialize Controllers & Business Logic Services
        deviceManager = DeviceManagementManager(this)
        policyManager = DpmPolicyManager(this)
        lockTaskController = LockTaskController(this)
        commandProcessor = CommandProcessor(this)
        mqttManager = MdmMqttManager(this)
        apiClient = MdmApiClient(this)

        // Use Cases
        getManagedAppsUseCase = GetManagedAppsUseCase(repositoryImpl)
        getDeviceStatusUseCase = GetDeviceStatusUseCase(repositoryImpl)
        getAdminMessagesUseCase = GetAdminMessagesUseCase(repositoryImpl)
        getRecentCommandsUseCase = GetRecentCommandsUseCase(repositoryImpl)
        processCommandUseCase = ProcessCommandUseCase(commandProcessor)
        launchAppUseCase = LaunchAppUseCase(this)

        // 3. Ensure Home Launcher binding and baseline security if Device Owner
        if (deviceManager.isDeviceOwner()) {
            deviceManager.setAsDefaultHomeLauncher()
            policyManager.enforceBaselineSecurity()
            deviceManager.applyPolicy(repository.getActivePolicy())
        }

        // 4. Register package install/update default-deny receiver
        try {
            com.rrv.mdm.dpc.receiver.AppEventPublisher.registerDynamically(this)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register AppEventPublisher: ${e.message}")
        }

        // 5. Connect MQTT Command Channel if configured or enrolled
        val currentConfig = serverConfigProvider.getCurrentConfig()
        if (currentConfig != null) {
            repository.serverUrl = currentConfig.apiBaseUrl
            repository.mqttBrokerHost = currentConfig.mqtt.host
            repository.mqttPort = currentConfig.mqtt.port
            mqttManager.connect()
            mqttManager.fetchPendingCommandsFromServer()
        } else if (repository.isEnrolled || repository.serverUrl.isNotBlank()) {
            mqttManager.connect()
            mqttManager.fetchPendingCommandsFromServer()
        } else {
            // Check for cached bootstrap provisioning
            val bootstrapUrl = serverConfigProvider.getBootstrapServerUrl()
            val bootstrapToken = serverConfigProvider.getBootstrapEnrollmentToken()
            if (!bootstrapUrl.isNullOrBlank() && !bootstrapToken.isNullOrBlank()) {
                Log.i(TAG, "Initiating bootstrap enrollment from cached provisioning bundle...")
                apiClient.enrollDevice(bootstrapUrl, bootstrapToken) { success, _ ->
                    if (success) {
                        mqttManager.connect()
                        mqttManager.fetchPendingCommandsFromServer()
                    }
                }
            }
        }

        if (repository.isEnrolled || deviceManager.isDeviceOwner()) {
            try {
                com.rrv.mdm.dpc.service.MdmPersistentService.start(this)
                val locationIntent = android.content.Intent(this, com.rrv.mdm.dpc.geofence.LocationTrackerService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(locationIntent)
                } else {
                    startService(locationIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start persistent background services: ${e.message}")
            }
        }

        // 5. Schedule WorkManager Periodic Telemetry Worker (every 15 mins)
        schedulePeriodicHeartbeat()
    }

    private fun schedulePeriodicHeartbeat() {
        val workRequest = PeriodicWorkRequestBuilder<TelemetrySyncWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "rrv_mdm_telemetry_heartbeat",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
