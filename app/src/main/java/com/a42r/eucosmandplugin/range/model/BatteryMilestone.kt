package com.a42r.eucosmandplugin.range.model

/**
 * Represents a battery percentage milestone reached during a trip.
 * 
 * Used for historical calibration of range estimation. By tracking
 * actual distance traveled at each battery level, we can build a
 * real-world efficiency profile that accounts for:
 * - Li-Ion discharge characteristics
 * - Rider behavior patterns
 * - Terrain and conditions
 * - Wheel-specific efficiency
 * 
 * Example: At 80% battery, rider has traveled 15 km from full charge.
 * This gives us real data: (100% - 80%) = 20% consumed for 15 km traveled.
 * Actual efficiency: (20% of capacity) / 15 km
 */
data class BatteryMilestone(
    /** Battery percentage at this milestone (e.g., 90, 80, 70, ...) */
    val batteryPercent: Int,
    
    /** Compensated voltage at this milestone */
    val voltage: Double,
    
    /** Distance traveled from trip start (km) */
    val distanceKm: Double,
    
    /** Time elapsed from trip start (ms) */
    val timeMs: Long,
    
    /** Unix timestamp when milestone was reached */
    val timestamp: Long,
    
    /** Average efficiency up to this point (Wh/km) */
    val averageEfficiencyWhPerKm: Double
) {
    companion object {
        /** Standard milestone percentages to track */
        val STANDARD_MILESTONES = listOf(95, 90, 85, 80, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15, 10, 5)
        
        /**
         * Determine if a battery percent crossing should trigger a milestone.
         * Returns the milestone percentage if crossed, null otherwise.
         */
        fun getMilestoneCrossed(previousPercent: Double, currentPercent: Double): Int? {
            val prevInt = previousPercent.toInt()
            val currInt = currentPercent.toInt()
            
            // Check if we crossed any standard milestone
            return STANDARD_MILESTONES.firstOrNull { milestone ->
                prevInt > milestone && currInt <= milestone
            }
        }
    }
}

/**
 * Historical calibration data point from a completed trip segment.
 * 
 * A trip segment is from one battery level to another (e.g., 100% to 50%).
 * Multiple segments from different trips are aggregated to build a
 * real-world efficiency profile.
 */
data class HistoricalSegment(
    /** Starting battery percentage */
    val startPercent: Int,
    
    /** Ending battery percentage */
    val endPercent: Int,
    
    /** Starting voltage (compensated) */
    val startVoltage: Double,
    
    /** Ending voltage (compensated) */
    val endVoltage: Double,
    
    /** Distance traveled in this segment (km) */
    val distanceKm: Double,
    
    /** Time duration of segment (ms) */
    val durationMs: Long,
    
    /** Average efficiency for this segment (Wh/km) */
    val efficiencyWhPerKm: Double,
    
    /** Unix timestamp when segment was recorded */
    val timestamp: Long,
    
    /** Wheel model (for wheel-specific calibration) */
    val wheelModel: String,
    
    /** Battery capacity used for this calculation (Wh) */
    val batteryCapacityWh: Double
) {
    /**
     * Energy consumed in this segment as percentage of total capacity.
     */
    val energyConsumedPercent: Double
        get() = (startPercent - endPercent).toDouble()
    
    /**
     * Energy consumed in Wh.
     */
    val energyConsumedWh: Double
        get() = (energyConsumedPercent / 100.0) * batteryCapacityWh
}
