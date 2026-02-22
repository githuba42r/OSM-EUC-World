package com.a42r.eucosmandplugin.range.model

/**
 * Represents the result of a range estimation calculation.
 * 
 * Contains the estimated remaining range, confidence level,
 * and data quality metrics.
 */
data class RangeEstimate(
    /** 
     * Estimated remaining range in kilometers.
     * Null when insufficient data or currently charging.
     */
    val rangeKm: Double?,
    
    /** 
     * Confidence level (0.0 to 1.0).
     * 0.0 = insufficient data
     * 0.5 = low confidence
     * 0.7+ = good confidence
     * 1.0 = maximum confidence
     */
    val confidence: Double,
    
    /** Status of this estimate */
    val status: EstimateStatus,
    
    /** 
     * Current energy efficiency in Wh/km.
     * Null when insufficient data.
     */
    val efficiencyWhPerKm: Double?,
    
    /** 
     * Estimated time remaining in minutes at current speed.
     * Null when insufficient data or speed is zero.
     */
    val estimatedTimeMinutes: Double? = null,
    
    /** Data quality metrics for this estimate */
    val dataQuality: DataQuality
) {
    /** Whether this estimate is valid and should be displayed */
    val isValid: Boolean
        get() = status == EstimateStatus.VALID && rangeKm != null
    
    /** 
     * Whether this estimate has low confidence.
     * Low confidence estimates should show a warning to the user.
     */
    val isLowConfidence: Boolean
        get() = status == EstimateStatus.LOW_CONFIDENCE || confidence < 0.5
}

/**
 * Status of a range estimate.
 */
enum class EstimateStatus {
    /** Not enough data to calculate estimate (need 10 min + 10 km) */
    INSUFFICIENT_DATA,
    
    /** Collecting data, some requirements met but not all */
    COLLECTING,
    
    /** Valid estimate with good confidence */
    VALID,
    
    /** Currently charging - estimate paused */
    CHARGING,
    
    /** Estimate available but confidence is low */
    LOW_CONFIDENCE,
    
    /** No new data received recently (connection may be lost) */
    STALE
}

/**
 * Data quality metrics for a range estimate.
 * 
 * Provides transparency about the data used to calculate the estimate.
 */
data class DataQuality(
    /** Total number of samples in the trip */
    val totalSamples: Int,
    
    /** Number of valid samples (excludes flagged anomalies) */
    val validSamples: Int,
    
    /** Number of interpolated samples (during connection gaps) */
    val interpolatedSamples: Int,
    
    /** Number of charging events during this trip */
    val chargingEvents: Int,
    
    /** Reason for current baseline (e.g., "Trip start", "Post-charging") */
    val baselineReason: String?,
    
    /** Travel time in minutes since baseline */
    val travelTimeMinutes: Double,
    
    /** Travel distance in kilometers since baseline */
    val travelDistanceKm: Double,
    
    /** Whether minimum time requirement is met (10 minutes) */
    val meetsMinimumTime: Boolean,
    
    /** Whether minimum distance requirement is met (10 km) */
    val meetsMinimumDistance: Boolean
) {
    /** Whether both minimum requirements are met */
    val meetsBothRequirements: Boolean
        get() = meetsMinimumTime && meetsMinimumDistance
    
    /** Percentage of samples that are interpolated */
    val interpolatedPercentage: Double
        get() = if (totalSamples > 0) {
            (interpolatedSamples.toDouble() / totalSamples) * 100.0
        } else {
            0.0
        }
    
    /** Percentage of samples that are valid */
    val validPercentage: Double
        get() = if (totalSamples > 0) {
            (validSamples.toDouble() / totalSamples) * 100.0
        } else {
            0.0
        }
    
    /**
     * Progress toward minimum time requirement (0.0 to 1.0).
     * 1.0 = requirement met
     */
    val timeProgress: Double
        get() = (travelTimeMinutes / 10.0).coerceAtMost(1.0)
    
    /**
     * Progress toward minimum distance requirement (0.0 to 1.0).
     * 1.0 = requirement met
     */
    val distanceProgress: Double
        get() = (travelDistanceKm / 10.0).coerceAtMost(1.0)
}
