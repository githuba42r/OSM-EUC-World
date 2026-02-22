package com.a42r.eucosmandplugin.range.algorithm

import kotlin.math.exp

/**
 * Voltage compensation for battery voltage sag under load.
 * 
 * Problem: Li-Ion batteries exhibit temporary voltage drop under high current draw
 * (acceleration, climbs) that recovers when load decreases. This is NOT real discharge.
 * 
 * Solution: Power-weighted exponential smoothing that automatically adjusts trust in
 * voltage reading based on current power consumption.
 * 
 * Benefits:
 * - Smooth, stable estimates even during aggressive riding
 * - No lag - responds immediately to real discharge
 * - Self-tuning - works across different riding styles
 * - No sample exclusion needed
 */
object VoltageCompensator {
    
    /**
     * Configuration for voltage compensation.
     * Can be customized per wheel type or user preference.
     */
    data class Config(
        /** Base smoothing factor (0.2-0.4 recommended) */
        val alpha: Double = 0.3,
        
        /** Power threshold for low power (high trust in voltage) */
        val lowPowerThresholdW: Double = 500.0,
        
        /** Power threshold for medium power */
        val mediumPowerThresholdW: Double = 1500.0,
        
        /** Trust factor for low power readings (0.0-1.0) */
        val lowPowerTrust: Double = 0.8,
        
        /** Trust factor for medium power readings (0.0-1.0) */
        val mediumPowerTrust: Double = 0.5,
        
        /** Trust factor for high power readings (0.0-1.0) */
        val highPowerTrust: Double = 0.2
    )
    
    /**
     * Calculate load-compensated voltage using power-weighted exponential smoothing.
     * 
     * Formula: compensated = α × trust × raw + (1 - α × trust) × previous
     * 
     * Where trust is determined by current power consumption:
     * - Low power (<500W): High trust (0.8) - voltage is accurate
     * - Medium power (500-1500W): Medium trust (0.5) - some sag possible
     * - High power (>1500W): Low trust (0.2) - significant sag likely
     * 
     * @param currentVoltage Raw voltage reading from current sample
     * @param currentPower Current power consumption in watts
     * @param previousCompensatedVoltage Previously calculated compensated voltage
     * @param config Compensation configuration (use default for most cases)
     * @return Compensated voltage that removes sag while preserving true discharge
     */
    fun calculateCompensatedVoltage(
        currentVoltage: Double,
        currentPower: Double,
        previousCompensatedVoltage: Double,
        config: Config = Config()
    ): Double {
        // Determine trust factor based on power consumption
        val powerTrust = when {
            currentPower < config.lowPowerThresholdW -> config.lowPowerTrust
            currentPower < config.mediumPowerThresholdW -> config.mediumPowerTrust
            else -> config.highPowerTrust
        }
        
        // Calculate effective smoothing factor
        val effectiveAlpha = config.alpha * powerTrust
        
        // Apply exponential smoothing with power weighting
        // High power → low effectiveAlpha → more smoothing (less sag influence)
        // Low power → high effectiveAlpha → less smoothing (trust voltage)
        return effectiveAlpha * currentVoltage + (1 - effectiveAlpha) * previousCompensatedVoltage
    }
    
    /**
     * Calculate initial compensated voltage for trip start.
     * Uses higher trust since first samples are typically at low/zero power.
     */
    fun initializeCompensatedVoltage(initialVoltage: Double): Double {
        // For first sample, just use raw voltage
        return initialVoltage
    }
    
    /**
     * Validate compensated voltage is within reasonable bounds.
     * Prevents drift due to sensor errors or edge cases.
     * 
     * @param compensatedVoltage Calculated compensated voltage
     * @param rawVoltage Current raw voltage reading
     * @param maxDeviation Maximum allowed deviation from raw voltage (in volts)
     * @return Validated compensated voltage, clamped to reasonable range
     */
    fun validateCompensatedVoltage(
        compensatedVoltage: Double,
        rawVoltage: Double,
        maxDeviation: Double = 5.0
    ): Double {
        // Compensated voltage shouldn't deviate too far from raw
        // (prevents drift from accumulating errors)
        val minAllowed = rawVoltage - maxDeviation
        val maxAllowed = rawVoltage + maxDeviation
        
        return compensatedVoltage.coerceIn(minAllowed, maxAllowed)
    }
    
    /**
     * Get configuration preset based on wheel type.
     */
    fun getPresetConfig(wheelType: WheelType): Config {
        return when (wheelType) {
            WheelType.SMALL -> Config(
                alpha = 0.35,  // More responsive
                lowPowerThresholdW = 400.0,
                mediumPowerThresholdW = 1000.0
            )
            WheelType.MEDIUM -> Config(
                alpha = 0.3,  // Balanced (default)
                lowPowerThresholdW = 500.0,
                mediumPowerThresholdW = 1500.0
            )
            WheelType.LARGE -> Config(
                alpha = 0.25,  // More smoothing
                lowPowerThresholdW = 600.0,
                mediumPowerThresholdW = 2000.0
            )
            WheelType.HIGH_PERFORMANCE -> Config(
                alpha = 0.25,
                lowPowerThresholdW = 800.0,
                mediumPowerThresholdW = 2500.0
            )
        }
    }
    
    enum class WheelType {
        SMALL,              // <16" wheels
        MEDIUM,             // 16"-18" wheels
        LARGE,              // 20"+ wheels
        HIGH_PERFORMANCE    // Racing/extreme wheels
    }
}
