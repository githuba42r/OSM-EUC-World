package com.a42r.eucosmandplugin.ai.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Complete rider profile containing historical consumption patterns
 */
@Entity(tableName = "rider_profiles")
data class RiderProfile(
    @PrimaryKey
    val profileId: String,
    val wheelModel: String,
    val batteryCapacityWh: Double,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    
    // Statistical summary
    val totalTrips: Int,
    val totalDistanceKm: Double,
    val totalRidingTimeHours: Double,
    val avgTripDistanceKm: Double,
    val overallAvgEfficiency: Double,
    val overallAvgSpeed: Double,
    val dataQualityScore: Double,
    val lastRecalculated: Long
) {
    // Convenience properties for AI service
    val avgEfficiencyWhPerKm: Double get() = overallAvgEfficiency
    val avgSpeedKmh: Double get() = overallAvgSpeed
    val avgRangePerChargeKm: Double get() = if (overallAvgEfficiency > 0) batteryCapacityWh / overallAvgEfficiency else 0.0
}

/**
 * Speed-based consumption profile
 */
@Entity(
    tableName = "speed_profiles",
    primaryKeys = ["profileId", "speedRangeMin"]
)
data class SpeedProfile(
    val profileId: String,
    val speedRangeMin: Int,        // e.g., 20 km/h
    val speedRangeMax: Int,        // e.g., 25 km/h
    val avgEfficiencyWhPerKm: Double,
    val stdDeviation: Double,
    val sampleCount: Int,
    val totalDistanceKm: Double,
    val timePercentage: Double,
    
    // Typical duration rider sustains this speed (seconds)
    val typicalDurationSeconds: Int
) {
    // Convenience properties for AI service
    val speedRangeLow: Int get() = speedRangeMin
    val speedRangeHigh: Int get() = speedRangeMax
    val distancePercentage: Double get() = timePercentage
}

/**
 * Terrain-based consumption profile
 */
@Entity(
    tableName = "terrain_profiles",
    primaryKeys = ["profileId", "terrainType"]
)
data class TerrainProfile(
    val profileId: String,
    val terrainType: String,       // FLAT, GENTLE_UPHILL, STEEP_UPHILL, etc.
    val gradeMin: Double,           // Minimum grade percentage
    val gradeMax: Double,           // Maximum grade percentage
    val avgEfficiencyWhPerKm: Double,
    val stdDeviation: Double,
    val sampleCount: Int,
    val avgSpeedKmh: Double
)

enum class TerrainType {
    FLAT,           // < 2% grade
    GENTLE_UPHILL,  // 2-5% grade
    STEEP_UPHILL,   // > 5% grade
    GENTLE_DOWNHILL,// -2 to -5% grade
    STEEP_DOWNHILL, // < -5% grade
    MIXED           // Frequent grade changes
}

/**
 * Riding behavior patterns
 */
@Entity(
    tableName = "behavior_patterns",
    primaryKeys = ["profileId"]
)
data class BehaviorPattern(
    val profileId: String,
    val createdAt: Long = System.currentTimeMillis(),
    
    // Acceleration profile
    val avgAccelerationRate: Double,     // m/s²
    val aggressivenessScore: Double,     // 0.0 (gentle) to 1.0 (aggressive)
    val typicalAccelDuration: Int,       // seconds
    val avgPowerDuringAccel: Double,     // Watts
    
    // Braking profile
    val avgBrakingRate: Double,          // m/s²
    val regenEfficiency: Double,         // How much energy recovered (0.0-1.0)
    val typicalBrakeDuration: Int,       // seconds
    
    // Stop patterns
    val avgStopsPerKm: Double,
    val avgStopDurationSeconds: Int
) {
    // Convenience properties for AI service
    val accelerationStyle: String get() = when {
        aggressivenessScore > 0.7 -> "Aggressive"
        aggressivenessScore > 0.4 -> "Moderate"
        else -> "Gentle"
    }
    val avgAccelerationMps2: Double get() = avgAccelerationRate
    val brakingStyle: String get() = when {
        avgBrakingRate < -2.0 -> "Aggressive"
        avgBrakingRate < -1.0 -> "Moderate"
        else -> "Gentle"
    }
    val avgBrakingMps2: Double get() = avgBrakingRate
    val stopsPerKm: Double get() = avgStopsPerKm
}

/**
 * Current trip statistics (not stored in DB, calculated on-the-fly)
 */
data class CurrentTripStats(
    val distanceKm: Double,
    val ridingTimeMinutes: Double,
    val avgSpeedKmh: Double,
    val recentEfficiencyWhPerKm: Double,
    val overallEfficiencyWhPerKm: Double,
    val currentAggressiveness: Double
)

/**
 * Matching historical pattern
 */
data class MatchingPattern(
    val type: String,                    // "Speed Match", "Behavior Match", etc.
    val description: String,
    val expectedEfficiencyWhPerKm: Double,
    val confidence: Double               // 0.0-1.0
)
