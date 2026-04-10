package com.a42r.eucosmandplugin.ai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.a42r.eucosmandplugin.ai.model.BehaviorPattern
import com.a42r.eucosmandplugin.ai.model.RiderProfile
import com.a42r.eucosmandplugin.ai.model.SpeedProfile
import com.a42r.eucosmandplugin.ai.model.TerrainProfile

/**
 * Room database for rider profile data
 */
@Database(
    entities = [
        RiderProfile::class,
        SpeedProfile::class,
        TerrainProfile::class,
        BehaviorPattern::class
    ],
    // v2: added RiderProfile.practicalKmPerPct.
    // v3: added RiderProfile.totalAscentMeters / totalDescentMeters, populated
    //     TerrainProfile for the first time, and switched overallAvgSpeed to a
    //     time-weighted computation. Migration is destructive — trip logs are
    //     replayed (Developer Settings → Rebuild Profile from Trip Logs) to
    //     repopulate the profile, and existing v2 rows are wiped on first
    //     launch by fallbackToDestructiveMigration().
    version = 3,
    exportSchema = false
)
abstract class RiderProfileDatabase : RoomDatabase() {
    
    abstract fun riderProfileDao(): RiderProfileDao
    abstract fun speedProfileDao(): SpeedProfileDao
    abstract fun terrainProfileDao(): TerrainProfileDao
    abstract fun behaviorPatternDao(): BehaviorPatternDao
    
    companion object {
        @Volatile
        private var INSTANCE: RiderProfileDatabase? = null
        
        fun getInstance(context: Context): RiderProfileDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RiderProfileDatabase::class.java,
                    "rider_profile_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
