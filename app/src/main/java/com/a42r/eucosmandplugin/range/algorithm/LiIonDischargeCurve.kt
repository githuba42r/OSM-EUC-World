package com.a42r.eucosmandplugin.range.algorithm

/**
 * Li-Ion battery discharge curve model.
 * 
 * Converts pack voltage to energy percentage (state of charge) using
 * a non-linear discharge curve that models typical Li-Ion cell behavior.
 * 
 * Li-Ion discharge curve characteristics:
 * - Flat region: 100% - 80% (4.2V - 3.95V per cell)
 * - Gradual region: 80% - 20% (3.95V - 3.50V per cell)
 * - Rapid drop: 20% - 0% (3.50V - 3.00V per cell)
 * 
 * This non-linear curve is critical for accurate range estimation.
 * Using linear voltage-to-energy conversion can cause 10-20% errors.
 */
object LiIonDischargeCurve {
    
    // Cell voltage boundaries (per cell)
    private const val VOLTAGE_MAX = 4.20     // 100% charged
    private const val VOLTAGE_FLAT_END = 3.95 // 80% remaining
    private const val VOLTAGE_GRADUAL_END = 3.50 // 20% remaining
    private const val VOLTAGE_MIN = 3.00     // 0% (empty)
    
    /**
     * Convert pack voltage to energy percentage.
     * 
     * @param packVoltage Total pack voltage in volts (e.g., 75.6V for 20S pack)
     * @param cellCount Number of cells in series (e.g., 20 for 20S pack)
     * @return Energy percentage (0.0 to 100.0)
     */
    fun voltageToEnergyPercent(packVoltage: Double, cellCount: Int): Double {
        val cellVoltage = packVoltage / cellCount
        
        return when {
            // Above maximum (shouldn't happen unless freshly charged)
            cellVoltage >= VOLTAGE_MAX -> 100.0
            
            // Below minimum (battery critically low)
            cellVoltage <= VOLTAGE_MIN -> 0.0
            
            // Flat region: 100% - 80% (4.20V - 3.95V)
            cellVoltage > VOLTAGE_FLAT_END -> {
                val voltageRange = VOLTAGE_MAX - VOLTAGE_FLAT_END
                val voltagePosition = cellVoltage - VOLTAGE_FLAT_END
                val percentage = (voltagePosition / voltageRange) * 20.0 // 20% range
                80.0 + percentage
            }
            
            // Gradual region: 80% - 20% (3.95V - 3.50V)
            cellVoltage > VOLTAGE_GRADUAL_END -> {
                val voltageRange = VOLTAGE_FLAT_END - VOLTAGE_GRADUAL_END
                val voltagePosition = cellVoltage - VOLTAGE_GRADUAL_END
                val percentage = (voltagePosition / voltageRange) * 60.0 // 60% range
                20.0 + percentage
            }
            
            // Rapid drop region: 20% - 0% (3.50V - 3.00V)
            else -> {
                val voltageRange = VOLTAGE_GRADUAL_END - VOLTAGE_MIN
                val voltagePosition = cellVoltage - VOLTAGE_MIN
                val percentage = (voltagePosition / voltageRange) * 20.0 // 20% range
                percentage.coerceAtLeast(0.0)
            }
        }
    }
    
    /**
     * Convert energy percentage to pack voltage (inverse function).
     * 
     * Used for testing and validation.
     * 
     * @param energyPercent Energy percentage (0.0 to 100.0)
     * @param cellCount Number of cells in series
     * @return Pack voltage in volts
     */
    fun energyPercentToVoltage(energyPercent: Double, cellCount: Int): Double {
        val cellVoltage = when {
            energyPercent >= 100.0 -> VOLTAGE_MAX
            energyPercent <= 0.0 -> VOLTAGE_MIN
            
            // Flat region: 100% - 80%
            energyPercent > 80.0 -> {
                val percentage = energyPercent - 80.0 // 0-20
                val ratio = percentage / 20.0
                VOLTAGE_FLAT_END + (ratio * (VOLTAGE_MAX - VOLTAGE_FLAT_END))
            }
            
            // Gradual region: 80% - 20%
            energyPercent > 20.0 -> {
                val percentage = energyPercent - 20.0 // 0-60
                val ratio = percentage / 60.0
                VOLTAGE_GRADUAL_END + (ratio * (VOLTAGE_FLAT_END - VOLTAGE_GRADUAL_END))
            }
            
            // Rapid drop region: 20% - 0%
            else -> {
                val ratio = energyPercent / 20.0
                VOLTAGE_MIN + (ratio * (VOLTAGE_GRADUAL_END - VOLTAGE_MIN))
            }
        }
        
        return cellVoltage * cellCount
    }
    
    /**
     * Get expected voltage range for a given cell count.
     * 
     * @param cellCount Number of cells in series
     * @return Pair of (minimum voltage, maximum voltage) in volts
     */
    fun getVoltageRange(cellCount: Int): Pair<Double, Double> {
        return Pair(
            VOLTAGE_MIN * cellCount,
            VOLTAGE_MAX * cellCount
        )
    }
    
    /**
     * Check if voltage is within expected range for cell count.
     * 
     * @param packVoltage Pack voltage in volts
     * @param cellCount Number of cells in series
     * @return True if voltage is valid
     */
    fun isVoltageValid(packVoltage: Double, cellCount: Int): Boolean {
        val cellVoltage = packVoltage / cellCount
        // Allow slight margin for voltage sag/spike
        return cellVoltage in (VOLTAGE_MIN - 0.2)..(VOLTAGE_MAX + 0.2)
    }
    
    /**
     * Calculate energy consumed between two voltage readings.
     * 
     * @param startVoltage Starting voltage (higher)
     * @param endVoltage Ending voltage (lower)
     * @param cellCount Number of cells in series
     * @return Energy consumed as percentage (0.0 to 100.0)
     */
    fun calculateEnergyConsumed(
        startVoltage: Double,
        endVoltage: Double,
        cellCount: Int
    ): Double {
        val startEnergy = voltageToEnergyPercent(startVoltage, cellCount)
        val endEnergy = voltageToEnergyPercent(endVoltage, cellCount)
        return (startEnergy - endEnergy).coerceAtLeast(0.0)
    }
}
