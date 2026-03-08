package com.a42r.eucosmandplugin.ai.service

import android.content.Context
import android.util.Log
import com.a42r.eucosmandplugin.ai.data.RiderProfileDatabase
import com.a42r.eucosmandplugin.ai.model.*
import com.a42r.eucosmandplugin.range.model.BatterySample
import com.a42r.eucosmandplugin.range.model.TripSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Builds and updates rider profiles from completed trips
 */
class RiderProfileBuilder(context: Context) {
    
    private val database = RiderProfileDatabase.getInstance(context)
    
    companion object {
        private const val TAG = "RiderProfileBuilder"
        private const val MIN_TRIP_DISTANCE_KM = 1.0  // Minimum distance to process trip
        private const val MIN_SAMPLES = 50              // Minimum samples to process
    }
    
    /**
     * Process completed trip and update rider profile
     */
    suspend fun processTripForProfile(
        trip: TripSnapshot,
        wheelModel: String,
        batteryCapacityWh: Double
    ) = withContext(Dispatchers.IO) {
        try {
            // Validate trip has enough data
            if (trip.totalDistanceKm < MIN_TRIP_DISTANCE_KM) {
                Log.d(TAG, "Trip too short (${trip.totalDistanceKm} km), skipping profile update")
                return@withContext
            }
            
            val validSamples = trip.samples.filter { it.isValidForEstimation }
            if (validSamples.size < MIN_SAMPLES) {
                Log.d(TAG, "Not enough valid samples (${validSamples.size}), skipping profile update")
                return@withContext
            }
            
            Log.d(TAG, "Processing trip for profile: ${trip.totalDistanceKm} km, ${validSamples.size} samples")
            
            // Load or create profile
            val profile = loadOrCreateProfile(wheelModel, batteryCapacityWh)
            
            // Extract speed profiles from trip
            val newSpeedProfiles = extractSpeedProfiles(profile.profileId, validSamples)
            
            // Extract behavior patterns
            val newBehaviorPattern = analyzeBehaviorPatterns(profile.profileId, validSamples)
            
            // Merge with existing profile
            mergeSpeedProfiles(profile.profileId, newSpeedProfiles)
            mergeBehaviorPattern(profile.profileId, newBehaviorPattern)
            
            // Update profile statistics
            updateProfileStatistics(profile, trip, validSamples)
            
            Log.d(TAG, "Profile updated successfully for $wheelModel")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing trip for profile", e)
        }
    }
    
    /**
     * Load existing profile or create new one
     */
    private suspend fun loadOrCreateProfile(
        wheelModel: String,
        batteryCapacityWh: Double
    ): RiderProfile {
        val existing = database.riderProfileDao().getProfileByWheelModel(wheelModel)
        
        return existing ?: run {
            val newProfile = RiderProfile(
                profileId = "${wheelModel}_${System.currentTimeMillis()}",
                wheelModel = wheelModel,
                batteryCapacityWh = batteryCapacityWh,
                createdAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                totalTrips = 0,
                totalDistanceKm = 0.0,
                totalRidingTimeHours = 0.0,
                avgTripDistanceKm = 0.0,
                overallAvgEfficiency = 0.0,
                overallAvgSpeed = 0.0,
                dataQualityScore = 0.0,
                lastRecalculated = System.currentTimeMillis()
            )
            database.riderProfileDao().insertProfile(newProfile)
            Log.d(TAG, "Created new profile for $wheelModel")
            newProfile
        }
    }
    
    /**
     * Extract speed profiles from trip samples
     */
    private fun extractSpeedProfiles(profileId: String, samples: List<BatterySample>): List<SpeedProfile> {
        // Define speed buckets (km/h ranges)
        val speedBuckets = listOf(
            0 to 10,
            10 to 15,
            15 to 20,
            20 to 25,
            25 to 30,
            30 to 35,
            35 to 40,
            40 to 50
        )
        
        return speedBuckets.mapNotNull { (min, max) ->
            val bucketed = samples.filter { it.speedKmh >= min && it.speedKmh < max }
            
            if (bucketed.isEmpty()) return@mapNotNull null
            
            val efficiencies = bucketed.map { it.instantEfficiencyWhPerKm }
                .filter { !it.isNaN() && !it.isInfinite() }
            
            if (efficiencies.isEmpty()) return@mapNotNull null
            
            val avgEfficiency = efficiencies.average()
            val stdDev = calculateStdDev(efficiencies)
            val totalDistance = bucketed.sumOf { it.distanceSincePreviousSampleKm ?: 0.0 }
            val timePercentage = bucketed.size.toDouble() / samples.size
            
            // Calculate typical duration (how long rider stays in this speed range)
            val typicalDuration = calculateTypicalDuration(bucketed)
            
            SpeedProfile(
                profileId = profileId,
                speedRangeMin = min,
                speedRangeMax = max,
                avgEfficiencyWhPerKm = avgEfficiency,
                stdDeviation = stdDev,
                sampleCount = bucketed.size,
                totalDistanceKm = totalDistance,
                timePercentage = timePercentage,
                typicalDurationSeconds = typicalDuration
            )
        }
    }
    
    /**
     * Calculate typical duration rider sustains a speed range
     */
    private fun calculateTypicalDuration(samples: List<BatterySample>): Int {
        if (samples.size < 2) return 0
        
        // Find continuous segments in this speed range
        val segments = mutableListOf<Int>()
        var currentSegmentDuration = 0
        
        samples.zipWithNext().forEach { (curr, next) ->
            val timeDiff = (next.timestamp - curr.timestamp) / 1000 // seconds
            if (timeDiff < 5) { // Continuous if < 5 sec gap
                currentSegmentDuration += timeDiff.toInt()
            } else {
                if (currentSegmentDuration > 0) {
                    segments.add(currentSegmentDuration)
                }
                currentSegmentDuration = 0
            }
        }
        
        if (currentSegmentDuration > 0) {
            segments.add(currentSegmentDuration)
        }
        
        // Return median segment duration
        return if (segments.isNotEmpty()) {
            segments.sorted()[segments.size / 2]
        } else {
            0
        }
    }
    
    /**
     * Analyze riding behavior patterns
     */
    private fun analyzeBehaviorPatterns(profileId: String, samples: List<BatterySample>): BehaviorPattern {
        // Calculate acceleration/deceleration rates
        val accelerations = mutableListOf<Double>()
        val decelerations = mutableListOf<Double>()
        val accelPowers = mutableListOf<Double>()
        val regenSamples = mutableListOf<BatterySample>()
        
        samples.zipWithNext().forEach { (curr, next) ->
            val timeDiff = (next.timestamp - curr.timestamp) / 1000.0 // seconds
            if (timeDiff > 0 && timeDiff < 5) {
                val speedDiff = (next.speedKmh - curr.speedKmh) / 3.6 // m/s
                val accel = speedDiff / timeDiff
                
                if (accel > 0.1) {
                    accelerations.add(accel)
                    accelPowers.add(curr.powerWatts)
                } else if (accel < -0.1) {
                    decelerations.add(abs(accel))
                    if (curr.powerWatts < 0) {
                        regenSamples.add(curr)
                    }
                }
            }
        }
        
        val avgAcceleration = accelerations.takeIf { it.isNotEmpty() }?.average() ?: 0.5
        val avgDeceleration = decelerations.takeIf { it.isNotEmpty() }?.average() ?: 0.5
        val avgAccelPower = accelPowers.takeIf { it.isNotEmpty() }?.average() ?: 500.0
        
        // Calculate aggressiveness score (0.0 = gentle, 1.0 = aggressive)
        // Based on acceleration rate and power usage
        val aggressivenessScore = (avgAcceleration / 2.0).coerceIn(0.0, 1.0)
        
        // Calculate regen efficiency
        val regenEfficiency = if (regenSamples.isNotEmpty()) {
            val avgRegenPower = regenSamples.map { abs(it.powerWatts) }.average()
            (avgRegenPower / 1000.0).coerceIn(0.0, 1.0) // Normalize to 0-1
        } else {
            0.0
        }
        
        // Detect stops (speed drops to < 1 km/h)
        val stops = mutableListOf<Int>()
        var inStop = false
        var stopStartIndex = 0
        
        samples.forEachIndexed { index, sample ->
            if (sample.speedKmh < 1.0) {
                if (!inStop) {
                    inStop = true
                    stopStartIndex = index
                }
            } else {
                if (inStop) {
                    val stopDuration = ((samples[index].timestamp - samples[stopStartIndex].timestamp) / 1000).toInt()
                    if (stopDuration > 3) { // Only count stops > 3 seconds
                        stops.add(stopDuration)
                    }
                    inStop = false
                }
            }
        }
        
        val avgStopsPerKm = stops.size / (samples.lastOrNull()?.tripDistanceKm ?: 1.0).coerceAtLeast(1.0)
        val avgStopDuration = stops.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
        
        return BehaviorPattern(
            profileId = profileId,
            avgAccelerationRate = avgAcceleration,
            aggressivenessScore = aggressivenessScore,
            typicalAccelDuration = 5, // Simplified
            avgPowerDuringAccel = avgAccelPower,
            avgBrakingRate = avgDeceleration,
            regenEfficiency = regenEfficiency,
            typicalBrakeDuration = 3, // Simplified
            avgStopsPerKm = avgStopsPerKm,
            avgStopDurationSeconds = avgStopDuration
        )
    }
    
    /**
     * Calculate standard deviation
     */
    private fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }
    
    /**
     * Merge new speed profiles with existing ones (incremental learning)
     */
    private suspend fun mergeSpeedProfiles(profileId: String, newProfiles: List<SpeedProfile>) {
        val existingProfiles = database.speedProfileDao().getSpeedProfiles(profileId)
        
        val merged = newProfiles.map { new ->
            val existing = existingProfiles.find { 
                it.speedRangeMin == new.speedRangeMin 
            }
            
            if (existing != null) {
                // Weighted merge: give 30% weight to new data, 70% to existing
                val totalSamples = existing.sampleCount + new.sampleCount
                val existingWeight = existing.sampleCount.toDouble() / totalSamples
                val newWeight = new.sampleCount.toDouble() / totalSamples
                
                existing.copy(
                    avgEfficiencyWhPerKm = existing.avgEfficiencyWhPerKm * existingWeight + 
                                          new.avgEfficiencyWhPerKm * newWeight,
                    sampleCount = totalSamples,
                    totalDistanceKm = existing.totalDistanceKm + new.totalDistanceKm,
                    timePercentage = (existing.timePercentage * existingWeight + 
                                    new.timePercentage * newWeight)
                )
            } else {
                new
            }
        }
        
        database.speedProfileDao().insertSpeedProfiles(merged)
    }
    
    /**
     * Merge behavior pattern
     */
    private suspend fun mergeBehaviorPattern(profileId: String, newPattern: BehaviorPattern) {
        val existing = database.behaviorPatternDao().getBehaviorPattern(profileId)
        
        val merged = if (existing != null) {
            // Weighted merge: 30% new, 70% existing
            existing.copy(
                avgAccelerationRate = existing.avgAccelerationRate * 0.7 + newPattern.avgAccelerationRate * 0.3,
                aggressivenessScore = existing.aggressivenessScore * 0.7 + newPattern.aggressivenessScore * 0.3,
                avgBrakingRate = existing.avgBrakingRate * 0.7 + newPattern.avgBrakingRate * 0.3,
                regenEfficiency = existing.regenEfficiency * 0.7 + newPattern.regenEfficiency * 0.3,
                avgStopsPerKm = existing.avgStopsPerKm * 0.7 + newPattern.avgStopsPerKm * 0.3
            )
        } else {
            newPattern
        }
        
        database.behaviorPatternDao().insertBehaviorPattern(merged)
    }
    
    /**
     * Update profile statistics
     */
    private suspend fun updateProfileStatistics(
        profile: RiderProfile,
        trip: TripSnapshot,
        validSamples: List<BatterySample>
    ) {
        val ridingTimeHours = trip.getRidingTimeMsSinceBaseline() / 3_600_000.0
        
        val efficiencies = validSamples.map { it.instantEfficiencyWhPerKm }
            .filter { !it.isNaN() && !it.isInfinite() }
        val avgEfficiency = efficiencies.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        
        val speeds = validSamples.map { it.speedKmh }
        val avgSpeed = speeds.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        
        val newTotalTrips = profile.totalTrips + 1
        val newTotalDistance = profile.totalDistanceKm + trip.totalDistanceKm
        val newTotalTime = profile.totalRidingTimeHours + ridingTimeHours
        
        val updated = profile.copy(
            lastUpdatedAt = System.currentTimeMillis(),
            totalTrips = newTotalTrips,
            totalDistanceKm = newTotalDistance,
            totalRidingTimeHours = newTotalTime,
            avgTripDistanceKm = newTotalDistance / newTotalTrips,
            overallAvgEfficiency = (profile.overallAvgEfficiency * profile.totalTrips + avgEfficiency) / newTotalTrips,
            overallAvgSpeed = (profile.overallAvgSpeed * profile.totalTrips + avgSpeed) / newTotalTrips,
            dataQualityScore = calculateDataQuality(newTotalTrips, newTotalDistance),
            lastRecalculated = System.currentTimeMillis()
        )
        
        database.riderProfileDao().updateProfile(updated)
    }
    
    /**
     * Calculate data quality score based on trip count and distance
     */
    private fun calculateDataQuality(tripCount: Int, totalDistanceKm: Double): Double {
        // Quality improves with more trips and distance
        // Reaches 1.0 at 20 trips and 200km
        val tripScore = (tripCount / 20.0).coerceIn(0.0, 1.0)
        val distanceScore = (totalDistanceKm / 200.0).coerceIn(0.0, 1.0)
        return (tripScore + distanceScore) / 2.0
    }
}
