package com.a42r.eucosmandplugin.range.model

/**
 * Represents a segment of a trip.
 * 
 * Trips are divided into segments to handle:
 * - Normal riding
 * - Connection gaps (Bluetooth disconnections)
 * - Charging events
 * - Parked periods
 * 
 * Segments allow the range estimator to use only relevant data
 * and create new baselines after charging.
 */
data class TripSegment(
    /** Type of this segment */
    val type: SegmentType,
    
    /** Unix timestamp in milliseconds when segment started */
    val startTimestamp: Long,
    
    /** Unix timestamp in milliseconds when segment ended (null if ongoing) */
    var endTimestamp: Long? = null,
    
    /** Samples collected during this segment */
    val samples: MutableList<BatterySample> = mutableListOf(),
    
    /** 
     * Whether this segment is a baseline for range estimation.
     * Baselines are created:
     * - At trip start
     * - After charging completes
     * - After long connection gaps (optional)
     */
    val isBaselineSegment: Boolean = false,
    
    /** Human-readable reason why this is a baseline (e.g., "Trip start", "Post-charging") */
    val baselineReason: String? = null
) {
    /** Duration of this segment in milliseconds */
    val durationMs: Long
        get() {
            val end = endTimestamp ?: System.currentTimeMillis()
            return end - startTimestamp
        }
    
    /** Whether this segment is currently active (not ended) */
    val isActive: Boolean
        get() = endTimestamp == null
    
    /** Number of samples in this segment */
    val sampleCount: Int
        get() = samples.size
    
    /** Number of valid samples (excludes flagged anomalies) */
    val validSampleCount: Int
        get() = samples.count { it.isValidForEstimation }
    
    /** Distance covered in this segment (km) */
    val distanceKm: Double
        get() {
            if (samples.size < 2) return 0.0
            val first = samples.first().tripDistanceKm
            val last = samples.last().tripDistanceKm
            return last - first
        }
}

/**
 * Types of trip segments.
 */
enum class SegmentType {
    /** Normal riding - samples collected while riding */
    NORMAL_RIDING,
    
    /** 
     * Connection gap - Bluetooth disconnected.
     * Contains interpolated samples to fill the gap.
     */
    CONNECTION_GAP,
    
    /** 
     * Charging - battery is being charged.
     * Range estimation is paused during charging.
     */
    CHARGING,
    
    /** 
     * Parked - wheel stationary, not charging.
     * May be used in future for idle detection.
     */
    PARKED
}
