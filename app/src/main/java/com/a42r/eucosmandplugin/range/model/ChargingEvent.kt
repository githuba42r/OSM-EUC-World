package com.a42r.eucosmandplugin.range.model

/**
 * Represents a charging event during a trip.
 * 
 * Tracks battery state before and after charging,
 * allowing analysis of energy added and charging efficiency.
 */
data class ChargingEvent(
    /** Unix timestamp in milliseconds when charging started */
    val startTimestamp: Long,
    
    /** Unix timestamp in milliseconds when charging ended (null if still charging) */
    var endTimestamp: Long? = null,
    
    /** Compensated voltage before charging started */
    val voltageBeforeCharging: Double,
    
    /** Compensated voltage after charging completed (null if still charging) */
    var voltageAfterCharging: Double? = null,
    
    /** Battery percentage before charging started */
    val batteryPercentBefore: Double,
    
    /** Battery percentage after charging completed (null if still charging) */
    var batteryPercentAfter: Double? = null,
    
    /** 
     * Energy added during charging in Watt-hours.
     * Calculated when charging ends.
     * Null if still charging.
     */
    var energyAdded: Double? = null
) {
    /** Whether this charging event is still ongoing */
    val isCharging: Boolean
        get() = endTimestamp == null
    
    /** Duration of charging in milliseconds (0 if still charging) */
    val chargingDurationMs: Long
        get() {
            val end = endTimestamp ?: return 0L
            return end - startTimestamp
        }
    
    /** Duration of charging in minutes (0 if still charging) */
    val chargingDurationMinutes: Double
        get() = chargingDurationMs / 60000.0
    
    /** 
     * Voltage increase during charging.
     * Returns 0.0 if still charging.
     */
    val voltageIncrease: Double
        get() {
            val after = voltageAfterCharging ?: return 0.0
            return after - voltageBeforeCharging
        }
    
    /** 
     * Battery percentage increase during charging.
     * Returns 0.0 if still charging.
     */
    val batteryPercentIncrease: Double
        get() {
            val after = batteryPercentAfter ?: return 0.0
            return after - batteryPercentBefore
        }
    
    /**
     * Average charging power in watts.
     * Returns null if still charging or duration is zero.
     */
    val averageChargingPowerW: Double?
        get() {
            val energy = energyAdded ?: return null
            val durationHours = chargingDurationMs / 3600000.0
            if (durationHours <= 0) return null
            return energy / durationHours
        }
}
