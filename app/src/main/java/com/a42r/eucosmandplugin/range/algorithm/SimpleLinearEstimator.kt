package com.a42r.eucosmandplugin.range.algorithm

import com.a42r.eucosmandplugin.range.model.*
import com.a42r.eucosmandplugin.range.manager.HistoricalDataManager
import kotlin.math.min

/**
 * Simple linear range estimator.
 * 
 * Algorithm:
 * 1. Calculate energy consumed since baseline (using compensated voltage)
 * 2. Calculate distance traveled since baseline
 * 3. Calculate efficiency (Wh/km)
 * 4. Calculate remaining energy
 * 5. Estimate range = remaining energy / efficiency
 * 
 * This is the simplest algorithm and works well for steady riding.
 * However, it doesn't adapt to changing riding styles.
 * 
 * Pros:
 * - Simple and predictable
 * - Easy to understand
 * - Low computational cost
 * 
 * Cons:
 * - Doesn't adapt to riding style changes
 * - Can be inaccurate if riding becomes more/less aggressive
 */
class SimpleLinearEstimator(
    /** Battery capacity in Watt-hours (e.g., 2000 Wh) */
    private val batteryCapacityWh: Double,
    
    /** Number of cells in series (e.g., 20 for 20S pack) */
    private val cellCount: Int,
    
    /** Historical data manager for calibration (optional) */
    private val historicalDataManager: HistoricalDataManager? = null,
    
    /** Minimum time in minutes for initial baseline (configurable in developer mode) */
    var minTimeMinutes: Double = 10.0,
    
    /** Minimum distance in km for initial baseline (configurable in developer mode) */
    var minDistanceKm: Double = 10.0
) : RangeEstimator {
    
    companion object {
        // Confidence calculation weights
        private const val SAMPLE_WEIGHT = 0.3
        private const val DISTANCE_WEIGHT = 0.4
        private const val TIME_WEIGHT = 0.3
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
        val meetsMinimumTime = travelTimeMinutes >= minTimeMinutes
        val meetsMinimumDistance = travelDistanceKm >= minDistanceKm
        
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
        
        // Return insufficient data if neither requirement met
        if (!meetsMinimumTime && !meetsMinimumDistance) {
            val diagnostics = EstimateDiagnostics(
                statusReason = "Insufficient data: Need ${minTimeMinutes}min and ${minDistanceKm}km. " +
                        "Current: ${String.format("%.1f", travelTimeMinutes)}min, ${String.format("%.2f", travelDistanceKm)}km",
                minTimeMinutes = minTimeMinutes,
                minDistanceKm = minDistanceKm,
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
        
        // Calculate energy consumed (using compensated voltage!)
        val startEnergy = LiIonDischargeCurve.voltageToEnergyPercent(
            startSample.compensatedVoltage,
            cellCount
        )
        val currentEnergy = LiIonDischargeCurve.voltageToEnergyPercent(
            currentSample.compensatedVoltage,
            cellCount
        )
        
        val energyConsumedPercent = startEnergy - currentEnergy
        
        // Check if energy consumed is valid
        if (energyConsumedPercent <= 0) {
            // Battery increased or no change (shouldn't happen unless charging)
            val diagnostics = EstimateDiagnostics(
                statusReason = "Energy consumed is <= 0: Battery increased or no change (start: ${String.format("%.1f", startEnergy)}%, current: ${String.format("%.1f", currentEnergy)}%)",
                compensatedVoltage = currentSample.compensatedVoltage,
                currentEnergyPercent = currentEnergy,
                notes = listOf(
                    "Start voltage: ${String.format("%.2f", startSample.compensatedVoltage)}V = ${String.format("%.1f", startEnergy)}%",
                    "Current voltage: ${String.format("%.2f", currentSample.compensatedVoltage)}V = ${String.format("%.1f", currentEnergy)}%",
                    "Energy consumed: ${String.format("%.2f", energyConsumedPercent)}%"
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
        
        val energyConsumedWh = energyConsumedPercent * batteryCapacityWh / 100.0
        
        // Calculate efficiency
        val efficiencyWhPerKm = energyConsumedWh / travelDistanceKm
        
        // Calculate remaining energy
        val remainingEnergyWh = currentEnergy * batteryCapacityWh / 100.0
        
        // Calculate base estimated range
        val baseEstimatedRangeKm = remainingEnergyWh / efficiencyWhPerKm
        
        // Apply historical calibration if available
        val estimatedRangeKm = if (historicalDataManager != null) {
            val calibrationFactor = historicalDataManager.getCalibrationFactor(
                currentPercent = currentSample.batteryPercent.toInt(),
                predictedEfficiency = efficiencyWhPerKm,
                wheelModel = null // Could pass wheel model from trip metadata
            )
            baseEstimatedRangeKm * calibrationFactor
        } else {
            baseEstimatedRangeKm
        }
        
        // Calculate confidence
        val confidence = calculateConfidence(
            sampleCount = validSamples.size,
            distanceKm = travelDistanceKm,
            timeMinutes = travelTimeMinutes
        )
        
        // Determine status based on confidence and data requirements
        val status = when {
            !meetsMinimumTime || !meetsMinimumDistance -> EstimateStatus.COLLECTING  // One requirement met, generating estimates
            confidence < 0.5 -> EstimateStatus.LOW_CONFIDENCE
            else -> EstimateStatus.VALID
        }
        
        // Calculate estimated time (if speed available)
        val estimatedTimeMinutes = if (currentSample.speedKmh > 1.0) {
            (estimatedRangeKm / currentSample.speedKmh) * 60.0
        } else {
            null
        }
        
        // Build diagnostics for logging
        val calibrationUsed = historicalDataManager != null
        val calibrationFactor = if (calibrationUsed) estimatedRangeKm / baseEstimatedRangeKm else 1.0
        
        val statusReason = when (status) {
            EstimateStatus.COLLECTING -> "Collecting data: Need ${minTimeMinutes}min and ${minDistanceKm}km. " +
                    "Current: ${String.format("%.1f", travelTimeMinutes)}min, ${String.format("%.2f", travelDistanceKm)}km"
            EstimateStatus.LOW_CONFIDENCE -> "Low confidence: confidence=${String.format("%.2f", confidence)} (threshold: 0.5)"
            EstimateStatus.VALID -> "Valid estimate: confidence=${String.format("%.2f", confidence)}, " +
                    "efficiency=${String.format("%.2f", efficiencyWhPerKm)} Wh/km"
            else -> "Unknown status: $status"
        }
        
        val diagnostics = EstimateDiagnostics(
            statusReason = statusReason,
            compensatedVoltage = currentSample.compensatedVoltage,
            remainingEnergyWh = remainingEnergyWh,
            currentEnergyPercent = currentEnergy,
            baseRangeKm = baseEstimatedRangeKm,
            calibrationFactor = calibrationFactor,
            usedHistoricalCalibration = calibrationUsed,
            currentSpeedKmh = currentSample.speedKmh,
            minTimeMinutes = minTimeMinutes,
            minDistanceKm = minDistanceKm,
            notes = buildList {
                add("Algorithm: Simple Linear")
                add("Valid samples: ${validSamples.size}")
                add("Start energy: ${String.format("%.1f", startEnergy)}% (${String.format("%.2f", startSample.compensatedVoltage)}V)")
                add("Current energy: ${String.format("%.1f", currentEnergy)}% (${String.format("%.2f", currentSample.compensatedVoltage)}V)")
                add("Energy consumed: ${String.format("%.2f", energyConsumedPercent)}% = ${String.format("%.1f", energyConsumedWh)} Wh")
                add("Distance traveled: ${String.format("%.2f", travelDistanceKm)} km")
                add("Efficiency: ${String.format("%.2f", efficiencyWhPerKm)} Wh/km")
                add("Remaining energy: ${String.format("%.1f", remainingEnergyWh)} Wh")
                add("Base range: ${String.format("%.2f", baseEstimatedRangeKm)} km")
                if (calibrationUsed) {
                    add("Calibration: ${String.format("%.3f", calibrationFactor)}x = ${String.format("%.2f", estimatedRangeKm)} km")
                }
                if (currentSample.speedKmh > 1.0) {
                    add("Current speed: ${String.format("%.1f", currentSample.speedKmh)} km/h")
                }
            }
        )
        
        return RangeEstimate(
            rangeKm = estimatedRangeKm,
            confidence = confidence,
            status = status,
            efficiencyWhPerKm = efficiencyWhPerKm,
            estimatedTimeMinutes = estimatedTimeMinutes,
            dataQuality = dataQuality,
            diagnostics = diagnostics
        )
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
    
    /**
     * Calculate confidence based on data quantity and quality.
     * 
     * Confidence factors:
     * - Sample count (more samples = higher confidence)
     * - Distance traveled (more distance = higher confidence)
     * - Time traveled (more time = higher confidence)
     * 
     * @return Confidence level (0.0 to 1.0)
     */
    private fun calculateConfidence(
        sampleCount: Int,
        distanceKm: Double,
        timeMinutes: Double
    ): Double {
        // Sample score: max at 100 samples
        val sampleScore = min(sampleCount / 100.0, 1.0)
        
        // Distance score: max at 20 km
        val distanceScore = min(distanceKm / 20.0, 1.0)
        
        // Time score: max at 20 minutes
        val timeScore = min(timeMinutes / 20.0, 1.0)
        
        // Weighted average
        return (sampleScore * SAMPLE_WEIGHT) +
               (distanceScore * DISTANCE_WEIGHT) +
               (timeScore * TIME_WEIGHT)
    }
    
    override fun getName(): String = "Simple Linear"
    
    override fun getDescription(): String = "Basic linear estimation - simple and predictable"
}
