package com.rr.client.storage

import android.content.Context
import androidx.room.*

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY lastUpdated DESC")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity)

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
