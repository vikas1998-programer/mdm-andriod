package com.rrv.mdm.dpc.data.database

import android.content.Context
import androidx.room.*
import com.rrv.mdm.dpc.data.entity.AdminMessageEntity
import com.rrv.mdm.dpc.data.entity.ApplicationEntity
import com.rrv.mdm.dpc.data.entity.CommandEntity
import com.rrv.mdm.dpc.data.entity.PolicyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandDao {
    @Query("SELECT * FROM commands ORDER BY timestamp DESC")
    fun getAllCommandsFlow(): Flow<List<CommandEntity>>

    @Query("SELECT * FROM commands ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentCommands(limit: Int = 20): Flow<List<CommandEntity>>

    @Query("SELECT * FROM commands WHERE commandId = :commandId LIMIT 1")
    suspend fun getCommandById(commandId: String): CommandEntity?

    @Query("SELECT * FROM commands WHERE status = 'EXECUTING' LIMIT 1")
    fun getActiveExecutingCommandFlow(): Flow<CommandEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(command: CommandEntity)

    @Update
    suspend fun updateCommand(command: CommandEntity)

    @Query("UPDATE commands SET status = :status, resultMessage = :resultMessage, progress = :progress, executedAt = :executedAt WHERE commandId = :commandId")
    suspend fun updateCommandStatus(commandId: String, status: String, resultMessage: String?, progress: Int, executedAt: Long? = System.currentTimeMillis())

    @Query("DELETE FROM commands WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldCommands(cutoffTimestamp: Long)
}

@Dao
interface ApplicationDao {
    @Query("SELECT * FROM managed_apps WHERE isEnabled = 1 ORDER BY appName ASC")
    fun getManagedAppsFlow(): Flow<List<ApplicationEntity>>

    @Query("SELECT * FROM managed_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getAppByPackageName(packageName: String): ApplicationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<ApplicationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: ApplicationEntity)

    @Update
    suspend fun updateApp(app: ApplicationEntity)

    @Query("UPDATE managed_apps SET installStatus = :status, downloadProgress = :progress WHERE packageName = :packageName")
    suspend fun updateInstallProgress(packageName: String, status: String, progress: Int)

    @Query("DELETE FROM managed_apps WHERE packageName = :packageName")
    suspend fun deleteApp(packageName: String)

    @Query("DELETE FROM managed_apps")
    suspend fun clearAllApps()
}

@Dao
interface AdminMessageDao {
    @Query("SELECT * FROM admin_messages ORDER BY timestamp DESC")
    fun getMessagesFlow(): Flow<List<AdminMessageEntity>>

    @Query("SELECT * FROM admin_messages WHERE isRead = 0 ORDER BY timestamp DESC")
    fun getUnreadMessagesFlow(): Flow<List<AdminMessageEntity>>

    @Query("SELECT COUNT(*) FROM admin_messages WHERE isRead = 0")
    fun getUnreadCountFlow(): Flow<Int>

    @Query("SELECT * FROM admin_messages ORDER BY timestamp DESC LIMIT 1")
    fun getLatestMessageFlow(): Flow<AdminMessageEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AdminMessageEntity)

    @Query("UPDATE admin_messages SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: String)

    @Query("DELETE FROM admin_messages WHERE id = :id")
    suspend fun deleteMessage(id: String)
}

@Dao
interface PolicyDao {
    @Query("SELECT * FROM device_policies ORDER BY appliedAt DESC LIMIT 1")
    fun getActivePolicyFlow(): Flow<PolicyEntity?>

    @Query("SELECT * FROM device_policies WHERE policyId = :policyId LIMIT 1")
    suspend fun getPolicyById(policyId: String): PolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: PolicyEntity)
}

@Database(
    entities = [
        CommandEntity::class,
        ApplicationEntity::class,
        AdminMessageEntity::class,
        PolicyEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class RrvMdmDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao
    abstract fun applicationDao(): ApplicationDao
    abstract fun adminMessageDao(): AdminMessageDao
    abstract fun policyDao(): PolicyDao

    companion object {
        @Volatile
        private var instance: RrvMdmDatabase? = null

        fun getInstance(context: Context): RrvMdmDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RrvMdmDatabase::class.java,
                    "rrv_mdm_client.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
            }
        }
    }
}
