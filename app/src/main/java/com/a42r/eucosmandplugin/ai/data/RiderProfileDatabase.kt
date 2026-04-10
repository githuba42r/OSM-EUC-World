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
    // v2: added RiderProfile.practicalKmPerPct. Migration is destructive —
    // rebuild-from-logs (Developer Settings → Rebuild Profile from Trip Logs)
    // repopulates the profile on first run.
    version = 2,
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
