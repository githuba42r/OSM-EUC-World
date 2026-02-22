package com.a42r.eucosmandplugin.range.model

/**
 * Immutable snapshot of battery and trip state at a specific moment.
 * Collected every 500ms during active riding.
 * 
 * Includes compensated voltage to handle voltage sag under load.
 */
data class BatterySample(
    /** Unix timestamp in milliseconds */
    val timestamp: Long,
    
    /** Raw battery voltage in volts (e.g., 75.6V for 20S pack) */
    val voltage: Double,
    
    /** 
     * Compensated battery voltage in volts.
     * Uses power-weighted exponential smoothing to remove voltage sag.
     * This is the voltage that should be used for state-of-charge calculations.
     */
    val compensatedVoltage: Double,
    
    /** Battery percentage (0.0 to 100.0) - may be inaccurate, use voltage as primary */
    val batteryPercent: Double,
    
    /** Trip distance in kilometers since trip start */
    val tripDistanceKm: Double,
    
    /** Current speed in km/h */
    val speedKmh: Double,
    
    /** Current power consumption in watts (positive = consuming, negative = regenerating) */
    val powerWatts: Double,
    
    /** Current draw in amperes */
    val currentAmps: Double,
    
    /** Wheel temperature in Celsius (optional, -1 if not available) */
    val temperatureCelsius: Double = -1.0,
    
    /** Sample quality flags (for anomaly detection) */
    val flags: Set<SampleFlag> = emptySet()
) {
    /** Time since previous sample (if known), used for gap detection */
    var timeSincePreviousSampleMs: Long? = null
    
    /** Distance since previous sample (if known), used for anomaly detection */
    var distanceSincePreviousSampleKm: Double? = null
    
    /** 
     * Voltage sag amount (difference between compensated and raw voltage).
     * Positive value indicates sag is present.
     */
    val voltageSag: Double
        get() = compensatedVoltage - voltage
    
    /** 
     * Whether significant voltage sag is detected.
     * True if sag > 1.0V (indicates high power consumption).
     */
    val hasSignificantSag: Boolean
        get() = voltageSag > 1.0
    
    /** Calculated instantaneous energy efficiency (Wh/km) */
    val instantEfficiencyWhPerKm: Double
        get() = if (speedKmh > 1.0 && tripDistanceKm > 0.01) {
            // Energy used = Power * Time / Distance
            // Simplified: Power (W) / Speed (km/h) = Wh/km
            powerWatts / speedKmh
        } else {
            Double.NaN
        }
    
    /** Whether this sample is valid for efficiency calculations */
    val isValidForEstimation: Boolean
        get() = flags.isEmpty() && 
                voltage > 0 && 
                compensatedVoltage > 0 &&
                batteryPercent in 0.0..100.0 &&
                speedKmh >= 0 &&
                !instantEfficiencyWhPerKm.isNaN() &&
                !instantEfficiencyWhPerKm.isInfinite()
}

enum class SampleFlag {
    /** Time gap >5 seconds since previous sample */
    TIME_GAP,
    
    /** Distance jump >500m without proportional time increase */
    DISTANCE_ANOMALY,
    
    /** Battery increased (charging event detected) */
    CHARGING_DETECTED,
    
    /** Voltage reading outside expected range (e.g., <60V or >84V for 20S) */
    VOLTAGE_ANOMALY,
    
    /** Extreme efficiency outlier (e.g., >200 Wh/km or <0 Wh/km) */
    EFFICIENCY_OUTLIER,
    
    /** Speed sensor error (e.g., sudden spike to unrealistic speed) */
    SPEED_ANOMALY,
    
    /** Interpolated sample (created during connection gap) */
    INTERPOLATED
}
