package com.a42r.eucosmandplugin.range.algorithm

import com.a42r.eucosmandplugin.range.model.*
import com.a42r.eucosmandplugin.range.manager.HistoricalDataManager
import kotlin.math.*

/**
 * Weighted window range estimator with exponential decay.
 * 
 * This is the recommended default algorithm. It adapts to riding style changes
 * by weighting recent samples more heavily using exponential decay.
 * 
 * Algorithm:
 * 1. Filter samples within time window (default: 30 minutes)
 * 2. Apply exponential decay weighting (recent samples weighted more)
 * 3. Calculate weighted average efficiency
 * 4. Calculate remaining energy using compensated voltage
 * 5. Estimate range = remaining energy / weighted efficiency
 * 6. Calculate confidence based on variance and sample count
 * 
 * Pros:
 * - Adapts to riding style changes (e.g., highway → city)
 * - More responsive than simple linear
 * - Balances stability and adaptiveness
 * - Works well for varied terrain/conditions
 * 
 * Cons:
 * - More complex than simple linear
 * - Requires parameter tuning for optimal performance
 * - Can be jumpy with aggressive weight decay settings
 * 
 * Configuration Presets:
 * - Conservative: 45 min window, slow adaptation (weight decay 0.3)
 * - Balanced: 30 min window, medium adaptation (weight decay 0.5) [DEFAULT]
 * - Responsive: 15 min window, fast adaptation (weight decay 0.7)
 */
class WeightedWindowEstimator(
    /** Battery capacity in Watt-hours (e.g., 2000 Wh) */
    private val batteryCapacityWh: Double,
    
    /** Number of cells in series (e.g., 20 for 20S pack) */
    private val cellCount: Int,
    
    /** Time window in minutes for considering samples (default: 30 min = Balanced preset) */
    private val windowMinutes: Int = 30,
    
    /** 
     * Weight decay factor for exponential decay.
     * Higher = more weight on recent samples
     * 0.3 = Conservative, 0.5 = Balanced, 0.7 = Responsive
     */
    private val weightDecayFactor: Double = 0.5,
    
    /** Historical data manager for calibration (optional) */
    private val historicalDataManager: HistoricalDataManager? = null,
    
    /** Minimum time in minutes for initial baseline (configurable in developer mode) */
    var minTimeMinutes: Double = 10.0,
    
    /** Minimum distance in km for initial baseline (configurable in developer mode) */
    var minDistanceKm: Double = 10.0
) : RangeEstimator {
    
    companion object {
        // Minimum samples in window to produce estimate
        private const val MIN_WINDOW_SAMPLES = 5
        
        // Efficiency sanity bounds (Wh/km)
        private const val MIN_EFFICIENCY = 5.0  // Below this is unrealistic
        private const val MAX_EFFICIENCY = 200.0  // Above this is unrealistic
        
        // Confidence calculation weights
        private const val VARIANCE_WEIGHT = 0.5
        private const val SAMPLE_WEIGHT = 0.5
    }
    
    override fun estimate(trip: TripSnapshot): RangeEstimate? {
        // Handle charging state
        if (trip.isCurrentlyCharging) {
            return createChargingEstimate(trip)
        }
        
        // Get baseline segment
        val baselineSegment = trip.currentBaselineSegment
            ?: return null
        
        // Get valid samples since baseline
        val validSamples = trip.getValidSamplesSinceBaseline()
        if (validSamples.isEmpty()) {
            return null
        }
        
        val currentSample = validSamples.last()
        
        // Calculate travel metrics for current baseline
        val travelTimeMs = trip.getRidingTimeMsSinceBaseline()
        val travelTimeMinutes = travelTimeMs / 60000.0
        val travelDistanceKm = trip.getDistanceKmSinceBaseline()
        
        // Check minimum requirements for current baseline
        val meetsMinimumTime = travelTimeMinutes >= minTimeMinutes
        val meetsMinimumDistance = travelDistanceKm >= minDistanceKm
        
        // Check if trip has EVER met the initial requirements (across all baselines)
        // This prevents resetting to "Collecting data" after charging or long stops
        val hasEverMetRequirements = trip.chargingEvents.isNotEmpty() || 
                                     (meetsMinimumTime && meetsMinimumDistance)
        
        val dataQuality = DataQuality(
            totalSamples = trip.samples.size,
            validSamples = validSamples.size,
            interpolatedSamples = trip.interpolatedSampleCount,
            chargingEvents = trip.chargingEvents.size,
            baselineReason = baselineSegment.baselineReason,
            travelTimeMinutes = travelTimeMinutes,
            travelDistanceKm = travelDistanceKm,
            meetsMinimumTime = meetsMinimumTime,
            meetsMinimumDistance = meetsMinimumDistance
        )
        
        // Return insufficient data ONLY if trip has never met requirements
        // Once initial stage is complete, we should continue using available data
        if (!hasEverMetRequirements && !meetsMinimumTime && !meetsMinimumDistance) {
            val diagnostics = EstimateDiagnostics(
                statusReason = "Insufficient data: Need ${minTimeMinutes}min and ${minDistanceKm}km. " +
                        "Current: ${String.format("%.1f", travelTimeMinutes)}min, ${String.format("%.2f", travelDistanceKm)}km",
                minTimeMinutes = minTimeMinutes,
                minDistanceKm = minDistanceKm,
                hasEverMetRequirements = hasEverMetRequirements,
                notes = listOf(
                    "Collecting initial data",
                    "Time progress: ${String.format("%.0f", dataQuality.timeProgress * 100)}%",
                    "Distance progress: ${String.format("%.0f", dataQuality.distanceProgress * 100)}%"
                )
            )
            return RangeEstimate(
                rangeKm = null,
                confidence = 0.0,
                status = EstimateStatus.INSUFFICIENT_DATA,
                efficiencyWhPerKm = null,
                estimatedTimeMinutes = null,
                dataQuality = dataQuality,
                diagnostics = diagnostics
            )
        }
        
        // Use sample-based window instead of time-based window
        // This ensures stops don't affect the window - only actual riding samples count
        // Target: equivalent to ~10 minutes of riding at 2 samples/sec = 1200 samples
        val targetWindowSamples = 1200
        val windowSamples = validSamples.takeLast(targetWindowSamples)
        
        // Use all valid samples if window is too small
        val samplesForEstimation = if (windowSamples.size >= MIN_WINDOW_SAMPLES) {
            windowSamples
        } else {
            validSamples
        }
        
        // Calculate recent window efficiency (detects current conditions like hills, traffic)
        val recentEfficiency = calculateWeightedAverageEfficiency(
            samples = samplesForEstimation,
            latestTimestamp = currentSample.timestamp
        )
        
        // Calculate overall trip efficiency (represents typical riding style)
        val overallEfficiency = calculateWeightedAverageEfficiency(
            samples = validSamples,
            latestTimestamp = currentSample.timestamp
        )
        
        // Blend recent and overall efficiency
        // The blend factor depends on how much data we have:
        // - More recent samples = trust recent more (temporary conditions may be real)
        // - Fewer recent samples = trust overall more (recent may be anomaly)
        val recentWeight = calculateRecentWeight(samplesForEstimation.size, validSamples.size)
        val overallWeight = 1.0 - recentWeight
        
        val weightedEfficiency = if (!recentEfficiency.isNaN() && !overallEfficiency.isNaN()) {
            recentEfficiency * recentWeight + overallEfficiency * overallWeight
        } else if (!recentEfficiency.isNaN()) {
            recentEfficiency
        } else if (!overallEfficiency.isNaN()) {
            overallEfficiency
        } else {
            Double.NaN
        }
        
        // Check if efficiency is valid
        if (weightedEfficiency.isNaN() || 
            weightedEfficiency <= MIN_EFFICIENCY || 
            weightedEfficiency >= MAX_EFFICIENCY) {
            val reason = when {
                weightedEfficiency.isNaN() -> "Efficiency calculation returned NaN (no valid samples with distance delta)"
                weightedEfficiency <= MIN_EFFICIENCY -> "Efficiency too low: ${String.format("%.1f", weightedEfficiency)} Wh/km (min: $MIN_EFFICIENCY)"
                else -> "Efficiency too high: ${String.format("%.1f", weightedEfficiency)} Wh/km (max: $MAX_EFFICIENCY)"
            }
            val diagnostics = EstimateDiagnostics(
                statusReason = reason,
                windowSampleCount = samplesForEstimation.size,
                windowMinutes = windowMinutes,
                minTimeMinutes = minTimeMinutes,
                minDistanceKm = minDistanceKm,
                hasEverMetRequirements = hasEverMetRequirements,
                notes = listOf(
                    "Valid samples in window: ${samplesForEstimation.size}",
                    "Efficiency value: ${if (weightedEfficiency.isNaN()) "NaN" else String.format("%.2f", weightedEfficiency)} Wh/km"
                )
            )
            return RangeEstimate(
                rangeKm = null,
                confidence = 0.0,
                status = EstimateStatus.INSUFFICIENT_DATA,
                efficiencyWhPerKm = if (weightedEfficiency.isNaN()) null else weightedEfficiency,
                estimatedTimeMinutes = null,
                dataQuality = dataQuality,
                diagnostics = diagnostics
            )
        }
        
        // Calculate efficiency variance for confidence
        val efficiencyStdDev = calculateEfficiencyStdDev(
            samples = samplesForEstimation,
            mean = weightedEfficiency
        )
        
        // Calculate remaining energy (using compensated voltage!)
        val currentEnergy = LiIonDischargeCurve.voltageToEnergyPercent(
            currentSample.compensatedVoltage,
            cellCount
        )
        
        val remainingEnergyWh = currentEnergy * batteryCapacityWh / 100.0
        
        // Calculate base estimated range
        val baseEstimatedRangeKm = remainingEnergyWh / weightedEfficiency
        
        // Apply historical calibration if available
        val estimatedRangeKm = if (historicalDataManager != null) {
            val calibrationFactor = historicalDataManager.getCalibrationFactor(
                currentPercent = currentSample.batteryPercent.toInt(),
                predictedEfficiency = weightedEfficiency,
                wheelModel = null // Could pass wheel model from trip metadata
            )
            baseEstimatedRangeKm * calibrationFactor
        } else {
            baseEstimatedRangeKm
        }
        
        // Calculate confidence
        val confidence = calculateConfidence(
            sampleCount = samplesForEstimation.size,
            stdDev = efficiencyStdDev,
            mean = weightedEfficiency,
            travelDistanceKm = travelDistanceKm,
            travelTimeMinutes = travelTimeMinutes
        )
        
        // Determine status based on confidence and data requirements
        val status = when {
            // If we've ever met requirements (trip completed initial stage), don't show COLLECTING
            // even if current baseline is rebuilding data after charging
            !hasEverMetRequirements && (!meetsMinimumTime || !meetsMinimumDistance) -> EstimateStatus.COLLECTING
            confidence < 0.5 -> EstimateStatus.LOW_CONFIDENCE
            else -> EstimateStatus.VALID
        }
        
        // Calculate estimated time (using recent speed average for smoothing)
        val (estimatedTimeMinutes, avgSpeed) = if (samplesForEstimation.isNotEmpty()) {
            // Use average speed from last 2 minutes (or up to 120 samples)
            val recentSamples = samplesForEstimation.takeLast(120)
            val recentSpeed = recentSamples
                .map { it.speedKmh }
                .filter { it > 1.0 }  // Filter out stopped/slow speeds
                .average()
            
            if (recentSpeed > 1.0) {
                Pair((estimatedRangeKm / recentSpeed) * 60.0, recentSpeed)
            } else {
                Pair(null, 0.0)
            }
        } else {
            Pair(null, 0.0)
        }
        
        // Build diagnostics for logging
        val calibrationUsed = historicalDataManager != null
        val calibrationFactor = if (calibrationUsed) estimatedRangeKm / baseEstimatedRangeKm else 1.0
        
        // Calculate blend info for diagnostics
        val recentWeightForDiagnostics = calculateRecentWeight(samplesForEstimation.size, validSamples.size)
        val overallWeightForDiagnostics = 1.0 - recentWeightForDiagnostics
        
        val statusReason = when (status) {
            EstimateStatus.COLLECTING -> "Collecting data: Need ${minTimeMinutes}min and ${minDistanceKm}km. " +
                    "Current: ${String.format("%.1f", travelTimeMinutes)}min, ${String.format("%.2f", travelDistanceKm)}km"
            EstimateStatus.LOW_CONFIDENCE -> "Low confidence: confidence=${String.format("%.2f", confidence)} (threshold: 0.5), " +
                    "stdDev=${String.format("%.2f", efficiencyStdDev)} Wh/km"
            EstimateStatus.VALID -> "Valid estimate: confidence=${String.format("%.2f", confidence)}, " +
                    "efficiency=${String.format("%.2f", weightedEfficiency)} Wh/km"
            else -> "Unknown status: $status"
        }
        
        val diagnostics = EstimateDiagnostics(
            statusReason = statusReason,
            windowSampleCount = samplesForEstimation.size,
            windowMinutes = windowMinutes,
            efficiencyStdDev = efficiencyStdDev,
            compensatedVoltage = currentSample.compensatedVoltage,
            remainingEnergyWh = remainingEnergyWh,
            currentEnergyPercent = currentEnergy,
            baseRangeKm = baseEstimatedRangeKm,
            calibrationFactor = calibrationFactor,
            usedHistoricalCalibration = calibrationUsed,
            currentSpeedKmh = avgSpeed,
            minTimeMinutes = minTimeMinutes,
            minDistanceKm = minDistanceKm,
            hasEverMetRequirements = hasEverMetRequirements,
            notes = buildList {
                add("Algorithm: Weighted Window (window=1200 samples, decay=${weightDecayFactor})")
                add("Samples: ${samplesForEstimation.size} recent (total: ${validSamples.size})")
                add("Energy: ${String.format("%.1f", currentEnergy)}% = ${String.format("%.1f", remainingEnergyWh)} Wh")
                
                // Show efficiency blend details
                if (!recentEfficiency.isNaN() && !overallEfficiency.isNaN()) {
                    add("Efficiency blend:")
                    add("  Recent (${String.format("%.0f", recentWeightForDiagnostics * 100)}%): ${String.format("%.2f", recentEfficiency)} Wh/km")
                    add("  Overall (${String.format("%.0f", overallWeightForDiagnostics * 100)}%): ${String.format("%.2f", overallEfficiency)} Wh/km")
                    add("  Blended: ${String.format("%.2f", weightedEfficiency)} Wh/km ± ${String.format("%.2f", efficiencyStdDev)} Wh/km")
                } else {
                    add("Efficiency: ${String.format("%.2f", weightedEfficiency)} Wh/km ± ${String.format("%.2f", efficiencyStdDev)} Wh/km")
                }
                
                add("Base range: ${String.format("%.2f", baseEstimatedRangeKm)} km")
                if (calibrationUsed) {
                    add("Calibration: ${String.format("%.3f", calibrationFactor)}x = ${String.format("%.2f", estimatedRangeKm)} km")
                }
                if (avgSpeed > 1.0) {
                    add("Average speed: ${String.format("%.1f", avgSpeed)} km/h")
                }
            }
        )
        
        return RangeEstimate(
            rangeKm = estimatedRangeKm,
            confidence = confidence,
            status = status,
            efficiencyWhPerKm = weightedEfficiency,
            estimatedTimeMinutes = estimatedTimeMinutes,
            dataQuality = dataQuality,
            diagnostics = diagnostics
        )
    }
    
    /**
     * Calculate weighted average efficiency using exponential decay.
     * 
     * Recent samples are weighted more heavily. Weight decreases exponentially
     * based on sample age.
     * 
     * Weight formula: w = exp(-decay * age_minutes)
     * 
     * @param samples Samples to calculate efficiency from
     * @param latestTimestamp Timestamp of the most recent sample
     * @return Weighted average efficiency in Wh/km
     */
    private fun calculateWeightedAverageEfficiency(
        samples: List<BatterySample>,
        latestTimestamp: Long
    ): Double {
        if (samples.isEmpty()) return Double.NaN

        // Require minimum sample count (at least 25 seconds of riding at 2 Hz)
        if (samples.size < 50) return Double.NaN

        // Require minimum distance traveled in window (at least 500m)
        val distanceTraveled = (samples.maxOfOrNull { it.tripDistanceKm } ?: 0.0) -
                              (samples.minOfOrNull { it.tripDistanceKm } ?: 0.0)
        if (distanceTraveled < 0.5) return Double.NaN

        // IMPORTANT — physically correct efficiency aggregation.
        //
        // Previous implementation: mean(powerWatts_i / speedKmh_i) with IQR
        // outlier filtering on that ratio. The arithmetic mean of per-sample
        // instant-efficiency values systematically *under-estimates* true Wh/km
        // when speed varies across the window, because at fixed time intervals
        // a high-speed sample covers more distance per interval and should
        // contribute proportionally more to the denominator. The IQR filter
        // then compounded the bias by rejecting hard-acceleration samples
        // (high instant Wh/km) whose power contribution is real physics. On
        // real Sherman S trip logs this produced initial range estimates of
        // ~155-170 km vs an actual expected ~100 km.
        //
        // Correct aggregation: efficiency = total_energy / total_distance
        //   = Σ(P_i · dt) / Σ(S_i · dt)
        //   = Σ(P_i · w_i) / Σ(S_i · w_i)   (uniform dt, exponential decay w_i)
        //
        // With this aggregation, outliers cannot bias the result because each
        // sample contributes proportionally to both numerator and denominator.
        // The only filter we keep is the ±MAX_EFFICIENCY sanity bound to
        // reject sensor glitches (e.g. a 500 Wh/km spike from a bad read).
        // Stopped samples (speed <= 1.0) have NaN instant efficiency and are
        // naturally excluded. Regen samples contribute negative power and
        // positive speed — the correct physical accounting for recovered
        // energy.

        var weightedSumPower = 0.0
        var weightedSumSpeed = 0.0
        var weightSum = 0.0
        var contributingSamples = 0

        samples.forEach { sample ->
            val efficiency = sample.instantEfficiencyWhPerKm

            // Skip stopped samples (NaN) and sensor-glitch outliers whose
            // instant Wh/km is outside the realistic range.
            if (!efficiency.isNaN() &&
                efficiency > -MAX_EFFICIENCY &&
                efficiency < MAX_EFFICIENCY) {

                // Exponential decay weight: more recent = higher weight.
                val ageMinutes = (latestTimestamp - sample.timestamp) / 60000.0
                val weight = exp(-weightDecayFactor * ageMinutes / windowMinutes)

                // Accumulate power and speed separately. The ratio of these
                // weighted sums is the distance-weighted (true) efficiency.
                weightedSumPower += sample.powerWatts * weight
                weightedSumSpeed += sample.speedKmh * weight
                weightSum += weight
                contributingSamples++
            }
        }

        if (contributingSamples < 50) return Double.NaN

        return if (weightSum > 0 && weightedSumSpeed > 0) {
            weightedSumPower / weightedSumSpeed
        } else {
            Double.NaN
        }
    }
    
    /**
     * Calculate standard deviation of efficiency samples.
     * 
     * Used for confidence calculation - higher variance = lower confidence.
     * 
     * @param samples Samples to calculate variance from
     * @param mean Mean efficiency (for variance calculation)
     * @return Standard deviation in Wh/km
     */
    private fun calculateEfficiencyStdDev(
        samples: List<BatterySample>,
        mean: Double
    ): Double {
        val validEfficiencies = samples
            .map { it.instantEfficiencyWhPerKm }
            .filter { !it.isNaN() && it > -MAX_EFFICIENCY && it < MAX_EFFICIENCY }
        
        if (validEfficiencies.size < 2) return 0.0
        
        val variance = validEfficiencies
            .map { (it - mean).pow(2) }
            .average()
        
        return sqrt(variance)
    }
    
    /**
     * Calculate confidence based on:
     * - Efficiency variance (coefficient of variation)
     * - Sample count
     * - Travel distance and time
     * 
     * @return Confidence level (0.0 to 1.0)
     */
    private fun calculateConfidence(
        sampleCount: Int,
        stdDev: Double,
        mean: Double,
        travelDistanceKm: Double,
        travelTimeMinutes: Double
    ): Double {
        // Coefficient of variation (CV): stdDev / mean
        // Lower CV = more consistent efficiency = higher confidence
        val coefficientOfVariation = if (mean > 0) stdDev / mean else 1.0
        
        // Variance confidence: 1.0 at CV=0, 0.0 at CV=0.5 or higher
        val varianceConfidence = max(0.0, 1.0 - (coefficientOfVariation * 2.0))
        
        // Sample confidence: 1.0 at 100+ samples, linear below
        val sampleConfidence = min(1.0, sampleCount / 100.0)
        
        // Distance confidence: 1.0 at 20+ km, linear below 20 km
        val distanceConfidence = min(1.0, travelDistanceKm / 20.0)
        
        // Time confidence: 1.0 at 20+ minutes, linear below 20 minutes
        val timeConfidence = min(1.0, travelTimeMinutes / 20.0)
        
        // Weighted average
        return (varianceConfidence * VARIANCE_WEIGHT) +
               (min(sampleConfidence, min(distanceConfidence, timeConfidence)) * SAMPLE_WEIGHT)
    }
    
    /**
     * Calculate weight for recent efficiency vs overall trip efficiency.
     * 
     * This balances between:
     * - Recent window (detects temporary conditions: hills, traffic, terrain changes)
     * - Overall trip (represents typical long-term riding style)
     * 
     * Strategy:
     * - Early in trip: Use mostly overall (not enough data to distinguish temporary vs typical)
     * - Mid trip: Balanced blend (50/50) - recent conditions matter but overall is baseline
     * - Long trip with full window: Still favor overall (60/40) to avoid overreacting to hills
     * 
     * @param recentSampleCount Number of samples in recent window
     * @param totalSampleCount Total samples in trip
     * @return Weight for recent efficiency (0.0 to 0.5), overall gets remainder
     */
    private fun calculateRecentWeight(recentSampleCount: Int, totalSampleCount: Int): Double {
        // If we don't have a full window yet, use mostly overall
        if (recentSampleCount < 1200) {
            return 0.2  // 20% recent, 80% overall
        }
        
        // If trip is very short (< 2x window), use mostly overall
        if (totalSampleCount < 2400) {
            return 0.3  // 30% recent, 70% overall
        }
        
        // For normal trips with full window: favor overall to avoid overreacting to temporary conditions
        // This means hills, traffic bursts, etc. influence estimate but don't dominate it
        return 0.4  // 40% recent, 60% overall
    }
    
    /**
     * Create an estimate for charging state.
     */
    private fun createChargingEstimate(trip: TripSnapshot): RangeEstimate {
        val baseline = trip.currentBaselineSegment
        val travelTimeMinutes = trip.getRidingTimeMsSinceBaseline() / 60000.0
        val travelDistanceKm = trip.getDistanceKmSinceBaseline()
        
        return RangeEstimate(
            rangeKm = null,
            confidence = 0.0,
            status = EstimateStatus.CHARGING,
            efficiencyWhPerKm = null,
            estimatedTimeMinutes = null,
            dataQuality = DataQuality(
                totalSamples = trip.samples.size,
                validSamples = trip.validSampleCount,
                interpolatedSamples = trip.interpolatedSampleCount,
                chargingEvents = trip.chargingEvents.size,
                baselineReason = baseline?.baselineReason,
                travelTimeMinutes = travelTimeMinutes,
                travelDistanceKm = travelDistanceKm,
                meetsMinimumTime = false,
                meetsMinimumDistance = false
            )
        )
    }
    
    override fun getName(): String = "Weighted Window"
    
    override fun getDescription(): String = 
        "Adaptive algorithm that responds to riding style changes (${windowMinutes}min window)"
}
