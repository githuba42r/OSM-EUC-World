package com.a42r.eucosmandplugin.range.algorithm

import com.a42r.eucosmandplugin.range.model.*
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
    private val weightDecayFactor: Double = 0.5
) : RangeEstimator {
    
    companion object {
        // Minimum requirements for estimation
        private const val MIN_TIME_MINUTES = 10.0
        private const val MIN_DISTANCE_KM = 10.0
        
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
        
        val startSample = baselineSegment.samples.firstOrNull()
            ?: return null
        val currentSample = validSamples.last()
        
        // Calculate travel metrics
        val travelTimeMs = trip.getRidingTimeMsSinceBaseline()
        val travelTimeMinutes = travelTimeMs / 60000.0
        val travelDistanceKm = trip.getDistanceKmSinceBaseline()
        
        // Check minimum requirements
        val meetsMinimumTime = travelTimeMinutes >= MIN_TIME_MINUTES
        val meetsMinimumDistance = travelDistanceKm >= MIN_DISTANCE_KM
        
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
        
        // Return insufficient data if requirements not met
        if (!meetsMinimumTime || !meetsMinimumDistance) {
            return RangeEstimate(
                rangeKm = null,
                confidence = 0.0,
                status = EstimateStatus.INSUFFICIENT_DATA,
                efficiencyWhPerKm = null,
                estimatedTimeMinutes = null,
                dataQuality = dataQuality
            )
        }
        
        // Filter samples within time window
        val windowMs = windowMinutes * 60 * 1000L
        val windowStartTime = currentSample.timestamp - windowMs
        val windowSamples = validSamples.filter { it.timestamp >= windowStartTime }
        
        // Use all valid samples if window is too small
        val samplesForEstimation = if (windowSamples.size >= MIN_WINDOW_SAMPLES) {
            windowSamples
        } else {
            validSamples
        }
        
        // Calculate weighted average efficiency
        val weightedEfficiency = calculateWeightedAverageEfficiency(
            samples = samplesForEstimation,
            latestTimestamp = currentSample.timestamp
        )
        
        // Check if efficiency is valid
        if (weightedEfficiency.isNaN() || 
            weightedEfficiency <= MIN_EFFICIENCY || 
            weightedEfficiency >= MAX_EFFICIENCY) {
            return RangeEstimate(
                rangeKm = null,
                confidence = 0.0,
                status = EstimateStatus.INSUFFICIENT_DATA,
                efficiencyWhPerKm = if (weightedEfficiency.isNaN()) null else weightedEfficiency,
                estimatedTimeMinutes = null,
                dataQuality = dataQuality
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
        
        // Calculate estimated range
        val estimatedRangeKm = remainingEnergyWh / weightedEfficiency
        
        // Calculate confidence
        val confidence = calculateConfidence(
            sampleCount = samplesForEstimation.size,
            stdDev = efficiencyStdDev,
            mean = weightedEfficiency,
            travelDistanceKm = travelDistanceKm,
            travelTimeMinutes = travelTimeMinutes
        )
        
        // Determine status
        val status = when {
            confidence < 0.5 -> EstimateStatus.LOW_CONFIDENCE
            else -> EstimateStatus.VALID
        }
        
        // Calculate estimated time (using recent speed average for smoothing)
        val estimatedTimeMinutes = if (samplesForEstimation.isNotEmpty()) {
            // Use average speed from last 2 minutes (or up to 120 samples)
            val recentSamples = samplesForEstimation.takeLast(120)
            val recentSpeed = recentSamples
                .map { it.speedKmh }
                .filter { it > 1.0 }  // Filter out stopped/slow speeds
                .average()
            
            if (recentSpeed > 1.0) {
                (estimatedRangeKm / recentSpeed) * 60.0
            } else {
                null
            }
        } else {
            null
        }
        
        return RangeEstimate(
            rangeKm = estimatedRangeKm,
            confidence = confidence,
            status = status,
            efficiencyWhPerKm = weightedEfficiency,
            estimatedTimeMinutes = estimatedTimeMinutes,
            dataQuality = dataQuality
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
        
        var weightedSum = 0.0
        var weightSum = 0.0
        
        samples.forEach { sample ->
            val efficiency = sample.instantEfficiencyWhPerKm
            
            // Only use valid efficiency values
            if (!efficiency.isNaN() && 
                efficiency > MIN_EFFICIENCY && 
                efficiency < MAX_EFFICIENCY) {
                
                // Calculate age in minutes
                val ageSeconds = (latestTimestamp - sample.timestamp) / 1000.0
                val ageMinutes = ageSeconds / 60.0
                
                // Exponential decay weight: more recent = higher weight
                val weight = exp(-weightDecayFactor * ageMinutes / windowMinutes)
                
                weightedSum += efficiency * weight
                weightSum += weight
            }
        }
        
        return if (weightSum > 0) weightedSum / weightSum else Double.NaN
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
            .filter { !it.isNaN() && it > MIN_EFFICIENCY && it < MAX_EFFICIENCY }
        
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
