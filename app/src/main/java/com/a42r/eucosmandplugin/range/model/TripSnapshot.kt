package com.a42r.eucosmandplugin.range.model

/**
 * Represents a complete trip with all samples and metadata.
 * 
 * A trip spans from user-initiated start (or app start) until user-initiated reset.
 * Trips can include:
 * - Multiple riding segments
 * - Connection gaps (Bluetooth disconnections)
 * - Charging events
 * 
 * The trip continues seamlessly through all events - it is NEVER auto-reset.
 */
data class TripSnapshot(
    /** Unix timestamp in milliseconds when trip started */
    val startTime: Long,
    
    /** All samples collected during this trip (includes interpolated samples) */
    val samples: List<BatterySample> = emptyList(),
    
    /** Trip segments (normal riding, gaps, charging) */
    val segments: List<TripSegment> = emptyList(),
    
    /** Whether currently charging (battery increasing) */
    val isCurrentlyCharging: Boolean = false,
    
    /** All charging events that occurred during this trip */
    val chargingEvents: List<ChargingEvent> = emptyList()
) {
    companion object {
        /**
         * Create initial trip snapshot at trip start.
         */
        fun createInitial(): TripSnapshot {
            return TripSnapshot(
                startTime = System.currentTimeMillis(),
                samples = emptyList(),
                segments = emptyList(),
                isCurrentlyCharging = false,
                chargingEvents = emptyList()
            )
        }
    }
    
    /** Latest sample in the trip (most recent) */
    val latestSample: BatterySample?
        get() = samples.lastOrNull()
    
    /** First sample in the trip */
    val startSample: BatterySample?
        get() = samples.firstOrNull()
    
    /** Total distance traveled in kilometers (from start to current) */
    val totalDistanceKm: Double
        get() = latestSample?.tripDistanceKm?.minus(startSample?.tripDistanceKm ?: 0.0) ?: 0.0
    
    /** Total number of valid samples (excludes flagged anomalies) */
    val validSampleCount: Int
        get() = samples.count { it.isValidForEstimation }
    
    /** Total number of interpolated samples (during connection gaps) */
    val interpolatedSampleCount: Int
        get() = samples.count { SampleFlag.INTERPOLATED in it.flags }
    
    /** Total trip duration in milliseconds */
    val tripDurationMs: Long
        get() {
            val start = startSample?.timestamp ?: return 0L
            val end = latestSample?.timestamp ?: return 0L
            return end - start
        }
    
    /** 
     * Current baseline segment - the segment used as starting point for estimation.
     * After charging, a new baseline is created.
     */
    val currentBaselineSegment: TripSegment?
        get() = segments
            .filter { it.isBaselineSegment }
            .lastOrNull()
            ?: segments.firstOrNull { it.type == SegmentType.NORMAL_RIDING }
    
    /**
     * Get all riding segments since the current baseline.
     * Used for range estimation calculations.
     */
    fun getSegmentsSinceBaseline(): List<TripSegment> {
        val baseline = currentBaselineSegment ?: return emptyList()
        return segments.filter { 
            it.startTimestamp >= baseline.startTimestamp &&
            it.type == SegmentType.NORMAL_RIDING
        }
    }
    
    /**
     * Get all valid samples since the current baseline.
     * Excludes anomalies but includes interpolated samples.
     */
    fun getValidSamplesSinceBaseline(): List<BatterySample> {
        return getSegmentsSinceBaseline()
            .flatMap { it.samples }
            .filter { it.isValidForEstimation }
    }
    
    /**
     * Calculate total riding time (excluding gaps and charging) since baseline.
     * Returns time in milliseconds.
     */
    fun getRidingTimeMsSinceBaseline(): Long {
        val ridingSegments = getSegmentsSinceBaseline()
        if (ridingSegments.isEmpty()) return 0L
        
        return ridingSegments.sumOf { segment ->
            val segmentSamples = segment.samples.filter { it.isValidForEstimation }
            if (segmentSamples.size < 2) {
                0L
            } else {
                segmentSamples.last().timestamp - segmentSamples.first().timestamp
            }
        }
    }
    
    /**
     * Calculate distance traveled since baseline.
     * Returns distance in kilometers.
     */
    fun getDistanceKmSinceBaseline(): Double {
        val baseline = currentBaselineSegment ?: return 0.0
        val baselineStart = baseline.samples.firstOrNull() ?: return 0.0
        val current = latestSample ?: return 0.0
        
        return current.tripDistanceKm - baselineStart.tripDistanceKm
    }
}
