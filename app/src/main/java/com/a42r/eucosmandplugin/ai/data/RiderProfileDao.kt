package com.a42r.eucosmandplugin.ai.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.a42r.eucosmandplugin.ai.model.BehaviorPattern
import com.a42r.eucosmandplugin.ai.model.RiderProfile
import com.a42r.eucosmandplugin.ai.model.SpeedProfile
import com.a42r.eucosmandplugin.ai.model.TerrainProfile

/**
 * DAO for RiderProfile operations
 */
@Dao
interface RiderProfileDao {
    
    @Query("SELECT * FROM rider_profiles WHERE profileId = :profileId")
    suspend fun getProfile(profileId: String): RiderProfile?
    
    @Query("SELECT * FROM rider_profiles WHERE wheelModel = :wheelModel LIMIT 1")
    suspend fun getProfileByWheelModel(wheelModel: String): RiderProfile?
    
    @Query("SELECT * FROM rider_profiles")
    suspend fun getAllProfiles(): List<RiderProfile>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: RiderProfile)
    
    @Update
    suspend fun updateProfile(profile: RiderProfile)
    
    @Query("DELETE FROM rider_profiles WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)
    
    @Query("DELETE FROM rider_profiles")
    suspend fun deleteAllProfiles()
}

/**
 * DAO for SpeedProfile operations
 */
@Dao
interface SpeedProfileDao {
    
    @Query("SELECT * FROM speed_profiles WHERE profileId = :profileId")
    suspend fun getSpeedProfiles(profileId: String): List<SpeedProfile>
    
    @Query("SELECT * FROM speed_profiles WHERE profileId IN (SELECT profileId FROM rider_profiles WHERE wheelModel = :wheelModel)")
    suspend fun getProfilesForRider(wheelModel: String): List<SpeedProfile>
    
    @Query("SELECT * FROM speed_profiles WHERE profileId = :profileId AND speedRangeMin = :speedMin")
    suspend fun getSpeedProfile(profileId: String, speedMin: Int): SpeedProfile?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpeedProfile(speedProfile: SpeedProfile)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpeedProfiles(speedProfiles: List<SpeedProfile>)
    
    @Query("DELETE FROM speed_profiles WHERE profileId = :profileId")
    suspend fun deleteSpeedProfiles(profileId: String)
}

/**
 * DAO for TerrainProfile operations
 */
@Dao
interface TerrainProfileDao {
    
    @Query("SELECT * FROM terrain_profiles WHERE profileId = :profileId")
    suspend fun getTerrainProfiles(profileId: String): List<TerrainProfile>
    
    @Query("SELECT * FROM terrain_profiles WHERE profileId = :profileId AND terrainType = :terrainType")
    suspend fun getTerrainProfile(profileId: String, terrainType: String): TerrainProfile?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerrainProfile(terrainProfile: TerrainProfile)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerrainProfiles(terrainProfiles: List<TerrainProfile>)
    
    @Query("DELETE FROM terrain_profiles WHERE profileId = :profileId")
    suspend fun deleteTerrainProfiles(profileId: String)
}

/**
 * DAO for BehaviorPattern operations
 */
@Dao
interface BehaviorPatternDao {
    
    @Query("SELECT * FROM behavior_patterns WHERE profileId = :profileId")
    suspend fun getBehaviorPattern(profileId: String): BehaviorPattern?
    
    @Query("SELECT * FROM behavior_patterns WHERE profileId IN (SELECT profileId FROM rider_profiles WHERE wheelModel = :wheelModel) ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestPattern(wheelModel: String): BehaviorPattern?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBehaviorPattern(behaviorPattern: BehaviorPattern)
    
    @Query("DELETE FROM behavior_patterns WHERE profileId = :profileId")
    suspend fun deleteBehaviorPattern(profileId: String)
}
