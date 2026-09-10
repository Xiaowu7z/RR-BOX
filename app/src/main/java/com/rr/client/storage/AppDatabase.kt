package com.rr.client.storage

import android.content.Context
import androidx.room.*

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY lastUpdated DESC")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles ORDER BY lastUpdated DESC")
    fun observeProfiles(): kotlinx.coroutines.flow.Flow<List<ProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity)

    @Query("UPDATE profiles SET name = :name WHERE id = :id")
    suspend fun renameProfile(id: String, name: String): Int

    // Node-list cleanup keeps the subscription and its last refresh metadata intact.
    @Query("UPDATE profiles SET nodesJson = :nodesJson WHERE id = :id")
    suspend fun updateProfileNodes(id: String, nodesJson: String): Int

    // A refresh updates network content only. A simultaneous user rename must
    // survive, and a deleted profile must not be recreated from an old snapshot.
    @Query("""UPDATE profiles SET nodesJson = :nodesJson, lastUpdated = :lastUpdated,
        uploadBytes = :uploadBytes, downloadBytes = :downloadBytes,
        totalBytes = :totalBytes, expireTime = :expireTime WHERE id = :id""")
    suspend fun updateSubscriptionContent(
        id: String,
        nodesJson: String,
        lastUpdated: Long,
        uploadBytes: Long,
        downloadBytes: Long,
        totalBytes: Long,
        expireTime: Long
    ): Int

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)
}

@Dao
interface TrafficDao {
    @Insert
    suspend fun insertTraffic(traffic: TrafficHistoryEntity)

    @Query("SELECT * FROM traffic_history ORDER BY timestamp DESC LIMIT 50")
    suspend fun getRecentTraffic(): List<TrafficHistoryEntity>
}

@Database(entities = [ProfileEntity::class, TrafficHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun trafficDao(): TrafficDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rr_client.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
