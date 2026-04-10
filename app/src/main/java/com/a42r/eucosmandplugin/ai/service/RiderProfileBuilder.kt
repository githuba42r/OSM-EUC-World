package com.a42r.eucosmandplugin.ai.service

import android.content.Context
import android.util.Log
import com.a42r.eucosmandplugin.ai.data.RiderProfileDatabase
import com.a42r.eucosmandplugin.ai.model.*
import com.a42r.eucosmandplugin.range.algorithm.LiIonDischargeCurve
import com.a42r.eucosmandplugin.range.database.WheelDatabase
import com.a42r.eucosmandplugin.range.model.BatterySample
import com.a42r.eucosmandplugin.range.model.TripSnapshot
import com.a42r.eucosmandplugin.range.util.TripLogReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Builds and updates rider profiles from completed trips
 */
class RiderProfileBuilder(private val context: Context) {

    private val database = RiderProfileDatabase.getInstance(context)

    companion object {
        private const val TAG = "RiderProfileBuilder"
        private const val MIN_TRIP_DISTANCE_KM = 1.0  // Minimum distance to process trip
        private const val MIN_SAMPLES = 50              // Minimum samples to process

        // Minimum **energy** % used for a trip to contribute to the
        // practical km/% metric. This is energy derived from the compensated
        // voltage via LiIonDischargeCurve, NOT the wheel's reported battery
        // percentage. Short trips that stay in the flat (100-80 %) region of
        // the discharge curve don't span enough of the discharge behaviour to
        // be representative of a full-discharge extrapolation. 30 % energy
        // used is roughly the minimum that guarantees the trip crossed at
        // least one region boundary.
        private const val MIN_ENERGY_USED_PCT = 30.0

        // Per-cell voltage range used to cross-check the discharge curve
        // calculation against degenerate voltages.
        private const val MIN_REASONABLE_PER_CELL_VOLTAGE = 2.8

        // SharedPreferences bookkeeping for log-based replay.
        private const val REPLAY_PREFS_NAME = "rider_profile_replay"
        private const val PREF_PROCESSED_LOGS = "processed_trip_logs"
    }

    /**
     * Returns the canonical wheel key (displayName) for a raw wheel identifier,
     * falling back to the raw value if no match is found. The rider profile DB is
     * keyed on this canonical form so writers and readers cannot drift apart.
     */
    fun canonicalWheelKey(rawWheelModel: String?): String? {
        if (rawWheelModel.isNullOrBlank()) return null
        return WheelDatabase.findWheelSpec(rawWheelModel)?.displayName ?: rawWheelModel
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

            // Extract terrain profiles (grade-segmented efficiency) from trip.
            // Populated whenever altitude is available on the samples. Collected
            // now but not yet consumed by the live range estimator (see
            // schema-v3 plan — "collect now, use later").
            val newTerrainProfiles = extractTerrainProfiles(profile.profileId, validSamples)

            // Extract behavior patterns
            val newBehaviorPattern = analyzeBehaviorPatterns(profile.profileId, validSamples)

            // Merge with existing profile
            mergeSpeedProfiles(profile.profileId, newSpeedProfiles)
            mergeTerrainProfiles(profile.profileId, newTerrainProfiles)
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
     * Extract speed profiles from trip samples.
     *
     * `timePercentage` is now genuinely **time-weighted**: each sample
     * contributes the Δt since the previous sample (capped at 5 s to drop
     * connection gaps) rather than a uniform weight of 1. At clean 500 ms
     * sampling this is identical to the old sample-count ratio, but it
     * behaves correctly when the log contains gaps.
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

        // Precompute per-sample Δt (seconds) for time-weighting. The first
        // sample has no predecessor so we assign it zero weight.
        val dtSec = DoubleArray(samples.size)
        for (i in 1 until samples.size) {
            val dt = (samples[i].timestamp - samples[i - 1].timestamp) / 1000.0
            dtSec[i] = if (dt in 0.0..5.0) dt else 0.0
        }
        val totalDt = dtSec.sum().takeIf { it > 0.0 } ?: 1.0

        return speedBuckets.mapNotNull { (min, max) ->
            val bucketedIdx = samples.indices.filter {
                samples[it].speedKmh >= min && samples[it].speedKmh < max
            }
            if (bucketedIdx.isEmpty()) return@mapNotNull null

            val bucketed = bucketedIdx.map { samples[it] }
            val bucketDt = bucketedIdx.sumOf { dtSec[it] }

            val efficiencies = bucketed.map { it.instantEfficiencyWhPerKm }
                .filter { !it.isNaN() && !it.isInfinite() }
            if (efficiencies.isEmpty()) return@mapNotNull null

            val avgEfficiency = efficiencies.average()
            val stdDev = calculateStdDev(efficiencies)
            val totalDistance = bucketed.sumOf { it.distanceSincePreviousSampleKm ?: 0.0 }
            val timePercentage = bucketDt / totalDt

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
     * Extract terrain (grade-segmented) efficiency profiles from trip samples.
     *
     * Approach: walk the samples in order and emit a "grade window" every
     * time the cumulative distance since the last window crosses a 20 m
     * threshold. For each window compute:
     *   - Δaltitude / Δdistance (as grade %)
     *   - distance-weighted Wh/km for the samples in the window
     *
     * Windows where altitude is NaN or Δdistance < 5 m are dropped
     * (sub-5 m windows are below the effective GPS altitude resolution).
     *
     * Windows are then bucketed into the five `TerrainType` ranges:
     *   FLAT           |grade| < 2
     *   GENTLE_UPHILL  2 ≤ grade < 5
     *   STEEP_UPHILL   grade ≥ 5
     *   GENTLE_DOWNHILL -5 < grade ≤ -2
     *   STEEP_DOWNHILL grade ≤ -5
     *
     * If the trip has no altitude data at all, an empty list is returned
     * and no TerrainProfile rows are written.
     */
    private fun extractTerrainProfiles(profileId: String, samples: List<BatterySample>): List<TerrainProfile> {
        // If no sample has altitude, there's nothing to compute.
        if (samples.none { it.hasAltitude }) return emptyList()

        val WINDOW_MIN_DISTANCE_KM = 0.020  // 20 m grade window
        val MIN_WINDOW_DISTANCE_KM = 0.005  // 5 m hard floor

        data class Window(
            val gradePct: Double,
            val distanceKm: Double,
            val whPerKm: Double,
            val speedKmh: Double
        )
        val windows = mutableListOf<Window>()

        var windowStart = 0
        var windowDistanceKm = 0.0
        for (i in 1 until samples.size) {
            windowDistanceKm += samples[i].distanceSincePreviousSampleKm ?: 0.0

            if (windowDistanceKm < WINDOW_MIN_DISTANCE_KM) continue

            val a = samples[windowStart]
            val b = samples[i]

            // Skip windows missing altitude at either endpoint.
            if (!a.hasAltitude || !b.hasAltitude) {
                windowStart = i
                windowDistanceKm = 0.0
                continue
            }
            if (windowDistanceKm < MIN_WINDOW_DISTANCE_KM) {
                windowStart = i
                windowDistanceKm = 0.0
                continue
            }

            val deltaAltM = b.altitudeMeters - a.altitudeMeters
            val gradePct = 100.0 * deltaAltM / (windowDistanceKm * 1000.0)

            // Distance-weighted Wh/km across the window:
            //   Σ(power · dt) / Σ(speed · dt)  (integrated energy / distance)
            // which is equivalent to energy / distance for the window.
            var energyWh = 0.0
            for (k in (windowStart + 1)..i) {
                val dtH = (samples[k].timestamp - samples[k - 1].timestamp) / 3_600_000.0
                if (dtH in 0.0..(5.0 / 3600.0)) {
                    energyWh += samples[k].powerWatts * dtH
                }
            }
            val whPerKm = if (windowDistanceKm > 0.0) energyWh / windowDistanceKm else 0.0

            // Window-average speed (simple mean over the window's samples).
            val speeds = (windowStart..i).map { samples[it].speedKmh }
            val avgSpeed = if (speeds.isNotEmpty()) speeds.average() else 0.0

            if (!whPerKm.isNaN() && !whPerKm.isInfinite()) {
                windows.add(Window(gradePct, windowDistanceKm, whPerKm, avgSpeed))
            }

            windowStart = i
            windowDistanceKm = 0.0
        }

        if (windows.isEmpty()) return emptyList()

        // Bucket windows by terrain type.
        data class BucketAccum(
            val terrainType: TerrainType,
            val gradeMin: Double,
            val gradeMax: Double,
            val whPerKm: MutableList<Double> = mutableListOf(),
            val speeds: MutableList<Double> = mutableListOf(),
            var totalDistanceKm: Double = 0.0
        )
        val buckets = listOf(
            BucketAccum(TerrainType.STEEP_DOWNHILL,  Double.NEGATIVE_INFINITY, -5.0),
            BucketAccum(TerrainType.GENTLE_DOWNHILL, -5.0,                     -2.0),
            BucketAccum(TerrainType.FLAT,            -2.0,                      2.0),
            BucketAccum(TerrainType.GENTLE_UPHILL,    2.0,                      5.0),
            BucketAccum(TerrainType.STEEP_UPHILL,     5.0,                      Double.POSITIVE_INFINITY)
        )

        for (w in windows) {
            val bucket = buckets.firstOrNull { b ->
                w.gradePct >= b.gradeMin && w.gradePct < b.gradeMax
            } ?: buckets.last()  // Treat exact +inf as STEEP_UPHILL
            bucket.whPerKm.add(w.whPerKm)
            bucket.speeds.add(w.speedKmh)
            bucket.totalDistanceKm += w.distanceKm
        }

        return buckets.mapNotNull { b ->
            if (b.whPerKm.isEmpty()) return@mapNotNull null
            TerrainProfile(
                profileId = profileId,
                terrainType = b.terrainType.name,
                gradeMin = b.gradeMin,
                gradeMax = b.gradeMax,
                avgEfficiencyWhPerKm = b.whPerKm.average(),
                stdDeviation = calculateStdDev(b.whPerKm),
                sampleCount = b.whPerKm.size,
                avgSpeedKmh = if (b.speeds.isNotEmpty()) b.speeds.average() else 0.0
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
     * Cumulative ascent and descent (metres) across a sample list, with a
     * 0.5 m deadband. The deadband reference only advances when the delta
     * to the previous reference exceeds the deadband, so small oscillations
     * around a plateau (GPS altitude jitter) don't silently accumulate.
     *
     * Samples without altitude (`hasAltitude == false`) are skipped entirely.
     * Returns (0.0, 0.0) for trips with no altitude data.
     */
    private fun computeAscentDescent(samples: List<BatterySample>): Pair<Double, Double> {
        val deadband = 0.5  // metres
        var ascent = 0.0
        var descent = 0.0
        var ref: Double? = null
        for (s in samples) {
            if (!s.hasAltitude) continue
            val a = s.altitudeMeters
            val prev = ref
            if (prev == null) {
                ref = a
                continue
            }
            val delta = a - prev
            when {
                delta > deadband  -> { ascent += delta;  ref = a }
                delta < -deadband -> { descent += -delta; ref = a }
                // else: within deadband → hold reference
            }
        }
        return ascent to descent
    }

    /**
     * Time-weighted average speed Σ(speed·Δt) / Σ Δt over a sample list.
     * Drops gaps > 5 s to avoid biasing the mean on connection dropouts.
     * Returns 0.0 for single-sample lists.
     */
    private fun computeTimeWeightedAvgSpeed(samples: List<BatterySample>): Double {
        if (samples.size < 2) return samples.firstOrNull()?.speedKmh ?: 0.0
        var num = 0.0
        var den = 0.0
        for (i in 1 until samples.size) {
            val dt = (samples[i].timestamp - samples[i - 1].timestamp) / 1000.0
            if (dt in 0.0..5.0 && samples[i].speedKmh >= 0.0) {
                num += samples[i].speedKmh * dt
                den += dt
            }
        }
        return if (den > 0.0) num / den else 0.0
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
     * Merge new terrain profiles with existing ones using a sample-count
     * weighted mean — same approach as [mergeSpeedProfiles].
     */
    private suspend fun mergeTerrainProfiles(profileId: String, newProfiles: List<TerrainProfile>) {
        if (newProfiles.isEmpty()) return
        for (new in newProfiles) {
            val existing = database.terrainProfileDao().getTerrainProfile(profileId, new.terrainType)
            val merged = if (existing != null) {
                val totalSamples = existing.sampleCount + new.sampleCount
                val existingWeight = existing.sampleCount.toDouble() / totalSamples
                val newWeight = new.sampleCount.toDouble() / totalSamples
                existing.copy(
                    avgEfficiencyWhPerKm = existing.avgEfficiencyWhPerKm * existingWeight +
                                          new.avgEfficiencyWhPerKm * newWeight,
                    avgSpeedKmh = existing.avgSpeedKmh * existingWeight + new.avgSpeedKmh * newWeight,
                    sampleCount = totalSamples
                )
            } else {
                new
            }
            database.terrainProfileDao().insertTerrainProfile(merged)
        }
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

        // Time-weighted average speed: Σ(speed · Δt) / Σ Δt. Connection
        // gaps (Δt > 5 s) are dropped so a long dropout doesn't deflate
        // the mean. At clean 500 ms sampling this is identical to an
        // arithmetic mean over the samples.
        val avgSpeed = computeTimeWeightedAvgSpeed(validSamples)

        // Cumulative ascent / descent for this trip, with a 0.5 m
        // deadband to kill GPS altitude jitter. Returns (0, 0) if the
        // trip has no altitude data.
        val (tripAscent, tripDescent) = computeAscentDescent(trip.samples)

        val newTotalTrips = profile.totalTrips + 1
        val newTotalDistance = profile.totalDistanceKm + trip.totalDistanceKm
        val newTotalTime = profile.totalRidingTimeHours + ridingTimeHours
        val newTotalAscent = profile.totalAscentMeters + tripAscent
        val newTotalDescent = profile.totalDescentMeters + tripDescent

        // Practical km-per-% from this trip. We use **voltage-derived energy
        // percent** (via LiIonDischargeCurve), NOT the wheel's reported
        // batteryPercent, because the Li-Ion discharge curve is non-linear
        // and the wheel's SoC reporting is typically closer to linear with
        // voltage than with actual energy. This gives a more physically
        // accurate km-per-energy-% that we can then project onto the full
        // discharge curve.
        //
        // The value we store, practicalKmPerPct, is "km per 1 % of total
        // battery ENERGY consumed". Multiplying by 100 gives the practical
        // full-discharge range.
        //
        // Short trips that stay in the flat (100–80 %) region of the curve
        // are filtered out because they don't span enough of the discharge
        // to be representative: the flat region's flat voltage hides the
        // real per-km consumption until the rider enters the gradual
        // discharge region. Minimum 30 % energy used.
        //
        // Long trips dominate the running weighted mean because each trip
        // contributes weight proportional to the energy % it used.
        val firstSample = trip.samples.firstOrNull()
        val lastSample = trip.samples.lastOrNull()

        // Resolve cell count from the wheel model so we can apply the
        // discharge curve. Fall back to 24 for a typical high-voltage pack.
        val cellCount = WheelDatabase.findWheelSpec(profile.wheelModel)?.batteryConfig?.cellCount ?: 24

        val startEnergyPct = firstSample?.compensatedVoltage
            ?.takeIf { it / cellCount > MIN_REASONABLE_PER_CELL_VOLTAGE }
            ?.let { LiIonDischargeCurve.voltageToEnergyPercent(it, cellCount) }
            ?: 0.0
        val endEnergyPct = lastSample?.compensatedVoltage
            ?.takeIf { it / cellCount > MIN_REASONABLE_PER_CELL_VOLTAGE }
            ?.let { LiIonDischargeCurve.voltageToEnergyPercent(it, cellCount) }
            ?: 0.0
        val energyUsedPct = startEnergyPct - endEnergyPct

        val tripKmPerEnergyPct = if (
            trip.totalDistanceKm >= MIN_TRIP_DISTANCE_KM &&
            energyUsedPct >= MIN_ENERGY_USED_PCT
        ) {
            trip.totalDistanceKm / energyUsedPct
        } else null

        val newPracticalKmPerPct = when {
            tripKmPerEnergyPct == null -> profile.practicalKmPerPct
            profile.practicalKmPerPct <= 0.0 -> tripKmPerEnergyPct
            else -> {
                // Weighted running mean. We approximate the prior total
                // weight from the stored trip count × a typical trip's
                // energy used (45 %). Longer trips dominate because each
                // new trip contributes its own energy-% used as weight.
                val priorWeight = profile.totalTrips * 45.0
                val newWeight = energyUsedPct
                (profile.practicalKmPerPct * priorWeight + tripKmPerEnergyPct * newWeight) /
                    (priorWeight + newWeight)
            }
        }

        val updated = profile.copy(
            lastUpdatedAt = System.currentTimeMillis(),
            totalTrips = newTotalTrips,
            totalDistanceKm = newTotalDistance,
            totalRidingTimeHours = newTotalTime,
            avgTripDistanceKm = newTotalDistance / newTotalTrips,
            overallAvgEfficiency = (profile.overallAvgEfficiency * profile.totalTrips + avgEfficiency) / newTotalTrips,
            overallAvgSpeed = (profile.overallAvgSpeed * profile.totalTrips + avgSpeed) / newTotalTrips,
            dataQualityScore = calculateDataQuality(newTotalTrips, newTotalDistance),
            lastRecalculated = System.currentTimeMillis(),
            practicalKmPerPct = newPracticalKmPerPct,
            totalAscentMeters = newTotalAscent,
            totalDescentMeters = newTotalDescent
        )

        database.riderProfileDao().updateProfile(updated)

        Log.d(TAG, "Trip elevation: ascent=${"%.1f".format(tripAscent)}m " +
                "descent=${"%.1f".format(tripDescent)}m, avgSpd=${"%.1f".format(avgSpeed)}km/h " +
                "→ profile totalAscent=${"%.1f".format(newTotalAscent)}m " +
                "totalDescent=${"%.1f".format(newTotalDescent)}m")

        if (tripKmPerEnergyPct != null) {
            Log.d(TAG, "Trip km/energy%: ${String.format("%.3f", tripKmPerEnergyPct)} " +
                    "(energy ${String.format("%.1f", startEnergyPct)}% → " +
                    "${String.format("%.1f", endEnergyPct)}%, " +
                    "wheel batt ${String.format("%.0f", firstSample?.batteryPercent ?: 0.0)}% → " +
                    "${String.format("%.0f", lastSample?.batteryPercent ?: 0.0)}%, " +
                    "${String.format("%.2f", trip.totalDistanceKm)} km) " +
                    "→ profile practicalKmPerPct = ${String.format("%.3f", newPracticalKmPerPct)}")
        } else if (firstSample != null && lastSample != null) {
            Log.d(TAG, "Trip skipped for practical km/% (energy used: " +
                    "${String.format("%.1f", energyUsedPct)}% < min ${MIN_ENERGY_USED_PCT}%): " +
                    "${String.format("%.2f", trip.totalDistanceKm)} km")
        }
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

    // =========================================================================
    // Log-file based replay (rebuild rider profile from JSONL trip logs).
    // =========================================================================

    /**
     * Result of a bulk log replay operation.
     */
    data class ReplayResult(
        val filesScanned: Int,
        val filesProcessed: Int,
        val filesSkipped: Int,
        val filesFailed: Int,
        val samplesProcessed: Int,
        val totalDistanceKm: Double
    )

    /**
     * Delete every rider profile, speed/terrain/behavior row, and clear the
     * "processed logs" bookkeeping so a subsequent replay starts from scratch.
     */
    suspend fun resetAllProfiles() = withContext(Dispatchers.IO) {
        try {
            database.clearAllTables()
            context.getSharedPreferences(REPLAY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_PROCESSED_LOGS)
                .apply()
            Log.i(TAG, "Rider profile database cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear rider profile database", e)
            throw e
        }
    }

    /**
     * Process a single trip log file and update the rider profile.
     *
     * @param file JSONL trip log file produced by [com.a42r.eucosmandplugin.range.util.DataCaptureLogger].
     * @param fallbackWheelModel Wheel model to use when the log does not embed one
     *        (older logs captured before metadata was extended).
     * @param fallbackBatteryCapacityWh Battery capacity to use when the log does
     *        not embed one.
     * @param markProcessed If true, records the file in shared-preferences so it
     *        will be skipped on subsequent replay runs.
     * @return true on success, false if the file was skipped or failed.
     */
    suspend fun processTripLogFile(
        file: File,
        fallbackWheelModel: String?,
        fallbackBatteryCapacityWh: Double,
        markProcessed: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val parsed = TripLogReader().read(file) ?: run {
                Log.d(TAG, "Log file ${file.name} yielded no parseable trip")
                return@withContext false
            }

            val rawWheelModel = parsed.wheelModel ?: fallbackWheelModel
            val canonicalWheelModel = canonicalWheelKey(rawWheelModel)
            if (canonicalWheelModel.isNullOrBlank()) {
                Log.w(TAG, "Cannot process ${file.name}: no wheel model available")
                return@withContext false
            }

            val batteryCapacity = parsed.batteryCapacityWh
                ?: WheelDatabase.findWheelSpec(rawWheelModel ?: "")?.batteryConfig?.capacityWh
                ?: fallbackBatteryCapacityWh

            processTripForProfile(
                trip = parsed.tripSnapshot,
                wheelModel = canonicalWheelModel,
                batteryCapacityWh = batteryCapacity
            )

            if (markProcessed) {
                recordProcessedLog(file.name)
            }
            Log.i(TAG, "Replayed ${file.name}: ${parsed.sampleCount} samples, " +
                    "${String.format("%.2f", parsed.tripSnapshot.totalDistanceKm)} km, " +
                    "wheel=$canonicalWheelModel")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to replay log file ${file.name}", e)
            false
        }
    }

    /**
     * Scan a log directory and process every unprocessed JSONL trip log.
     *
     * @param logDir Directory containing `trip_*.jsonl` files.
     * @param fallbackWheelModel Wheel model to use when a log lacks embedded metadata.
     * @param fallbackBatteryCapacityWh Battery capacity to use when a log lacks embedded metadata.
     * @param onlyUnprocessed If true, skip log files that were processed in a prior run.
     *        Set to false for a forced rebuild.
     * @param skipCurrentFile Optional absolute path of the currently-open log file
     *        to avoid replaying a log that is still being written.
     */
    suspend fun replayLogDirectory(
        logDir: File,
        fallbackWheelModel: String?,
        fallbackBatteryCapacityWh: Double,
        onlyUnprocessed: Boolean = true,
        skipCurrentFile: String? = null
    ): ReplayResult = withContext(Dispatchers.IO) {
        if (!logDir.exists() || !logDir.isDirectory) {
            Log.d(TAG, "Log directory does not exist: ${logDir.absolutePath}")
            return@withContext ReplayResult(0, 0, 0, 0, 0, 0.0)
        }

        val files = logDir.listFiles { f -> f.extension == "jsonl" && f.length() > 0 }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

        val processed = if (onlyUnprocessed) loadProcessedLogs() else emptySet()

        var scanned = 0
        var succeeded = 0
        var skipped = 0
        var failed = 0
        var samplesTotal = 0
        var distanceTotal = 0.0

        for (file in files) {
            scanned++

            if (skipCurrentFile != null && file.absolutePath == skipCurrentFile) {
                skipped++
                continue
            }
            if (onlyUnprocessed && file.name in processed) {
                skipped++
                continue
            }

            // Read first for accurate stats even if processing is suppressed downstream.
            val parsed = TripLogReader().read(file)
            if (parsed == null) {
                failed++
                continue
            }

            val ok = processTripLogFile(
                file = file,
                fallbackWheelModel = parsed.wheelModel ?: fallbackWheelModel,
                fallbackBatteryCapacityWh = parsed.batteryCapacityWh ?: fallbackBatteryCapacityWh,
                markProcessed = true
            )
            if (ok) {
                succeeded++
                samplesTotal += parsed.sampleCount
                distanceTotal += parsed.tripSnapshot.totalDistanceKm
            } else {
                failed++
            }
        }

        Log.i(TAG, "Replay summary: scanned=$scanned processed=$succeeded " +
                "skipped=$skipped failed=$failed samples=$samplesTotal " +
                "distance=${String.format("%.2f", distanceTotal)}km")

        ReplayResult(
            filesScanned = scanned,
            filesProcessed = succeeded,
            filesSkipped = skipped,
            filesFailed = failed,
            samplesProcessed = samplesTotal,
            totalDistanceKm = distanceTotal
        )
    }

    private fun loadProcessedLogs(): Set<String> {
        return context.getSharedPreferences(REPLAY_PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(PREF_PROCESSED_LOGS, emptySet())
            ?: emptySet()
    }

    private fun recordProcessedLog(fileName: String) {
        val prefs = context.getSharedPreferences(REPLAY_PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(PREF_PROCESSED_LOGS, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        if (current.add(fileName)) {
            prefs.edit().putStringSet(PREF_PROCESSED_LOGS, current).apply()
        }
    }
}
