package com.a42r.eucosmandplugin.range.algorithm

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for VoltageCompensator.
 * 
 * Tests voltage sag compensation using power-weighted exponential smoothing.
 */
class VoltageCompensatorTest {
    
    /**
     * Test 1: Initialization - first voltage should be returned as-is
     */
    @Test
    fun testInitialization() {
        val voltage = 84.0
        val initialized = VoltageCompensator.initializeCompensatedVoltage(voltage)
        
        assertEquals("Initialization should return raw voltage", voltage, initialized, 0.001)
    }
    
    /**
     * Test 2: Low power - high trust in voltage reading
     */
    @Test
    fun testLowPower_highTrust() {
        val currentVoltage = 83.0
        val previousCompensated = 84.0
        val lowPower = 300.0  // Below default threshold of 500W
        
        val compensated = VoltageCompensator.calculateCompensatedVoltage(
            currentVoltage, lowPower, previousCompensated
        )
        
        // With high trust (0.8) and alpha (0.3), effectiveAlpha = 0.24
        // Result should be closer to current voltage than with low trust
        val expectedRange = 83.0..84.0
        assertTrue("Low power should produce voltage in range $expectedRange, got $compensated",
            compensated in expectedRange)
    }
    
    /**
     * Test 3: Medium power - medium trust
     */
    @Test
    fun testMediumPower_mediumTrust() {
        val currentVoltage = 80.0
        val previousCompensated = 84.0
        val mediumPower = 1000.0  // Between 500W and 1500W
        
        val compensated = VoltageCompensator.calculateCompensatedVoltage(
            currentVoltage, mediumPower, previousCompensated
        )
        
        // With medium trust (0.5), smoothing is moderate
        assertTrue("Medium power should smooth voltage", compensated > currentVoltage)
        assertTrue("Medium power should trust voltage somewhat", compensated < previousCompensated)
    }
    
    /**
     * Test 4: High power - low trust (significant smoothing)
     */
    @Test
    fun testHighPower_lowTrust() {
        val currentVoltage = 75.0  // Voltage sag during acceleration
        val previousCompensated = 84.0
        val highPower = 3000.0  // Above 1500W threshold
        
        val compensated = VoltageCompensator.calculateCompensatedVoltage(
            currentVoltage, highPower, previousCompensated
        )
        
        // With low trust (0.2) and alpha (0.3), effectiveAlpha = 0.06
        // Result should be very close to previous (heavy smoothing)
        assertTrue("High power should heavily smooth voltage", compensated > 82.0)
        assertTrue("High power should stay close to previous", compensated < 85.0)
    }
    
    /**
     * Test 5: Negative power (regen) - treated as low power
     */
    @Test
    fun testRegenPower_treatedAsLowPower() {
        val currentVoltage = 86.0  // Voltage rises during regen
        val previousCompensated = 84.0
        val regenPower = -500.0  // Negative power
        
        val compensated = VoltageCompensator.calculateCompensatedVoltage(
            currentVoltage, regenPower, previousCompensated
        )
        
        // Negative power is below lowPowerThreshold, gets high trust
        // Should track the voltage increase fairly quickly
        assertTrue("Regen should allow voltage to increase", compensated > previousCompensated)
    }
    
    /**
     * Test 6: Validation - prevents excessive deviation
     */
    @Test
    fun testValidation_preventsExcessiveDeviation() {
        val compensatedVoltage = 90.0
        val rawVoltage = 80.0
        val maxDeviation = 5.0
        
        val validated = VoltageCompensator.validateCompensatedVoltage(
            compensatedVoltage, rawVoltage, maxDeviation
        )
        
        assertEquals("Should clamp to max deviation", 85.0, validated, 0.001)
    }
    
    /**
     * Test 7: Validation - allows reasonable values
     */
    @Test
    fun testValidation_allowsReasonableValues() {
        val compensatedVoltage = 83.0
        val rawVoltage = 80.0
        val maxDeviation = 5.0
        
        val validated = VoltageCompensator.validateCompensatedVoltage(
            compensatedVoltage, rawVoltage, maxDeviation
        )
        
        assertEquals("Should allow reasonable deviation", 83.0, validated, 0.001)
    }
    
    /**
     * Test 8: Validation - handles lower bound
     */
    @Test
    fun testValidation_lowerBound() {
        val compensatedVoltage = 70.0
        val rawVoltage = 80.0
        val maxDeviation = 5.0
        
        val validated = VoltageCompensator.validateCompensatedVoltage(
            compensatedVoltage, rawVoltage, maxDeviation
        )
        
        assertEquals("Should clamp to lower bound", 75.0, validated, 0.001)
    }
    
    /**
     * Test 9: Custom config - different thresholds
     */
    @Test
    fun testCustomConfig_differentThresholds() {
        val config = VoltageCompensator.Config(
            alpha = 0.5,
            lowPowerThresholdW = 1000.0,
            mediumPowerThresholdW = 2000.0,
            lowPowerTrust = 0.9,
            mediumPowerTrust = 0.6,
            highPowerTrust = 0.3
        )
        
        val currentVoltage = 80.0
        val previousCompensated = 84.0
        val power = 1500.0  // Medium with custom config
        
        val compensated = VoltageCompensator.calculateCompensatedVoltage(
            currentVoltage, power, previousCompensated, config
        )
        
        // Custom config should produce different result
        assertTrue("Custom config should affect compensation", compensated > currentVoltage)
    }
    
    /**
     * Test 10: Preset config - small wheel
     */
    @Test
    fun testPresetConfig_smallWheel() {
        val config = VoltageCompensator.getPresetConfig(VoltageCompensator.WheelType.SMALL)
        
        assertEquals("Small wheel should have alpha 0.35", 0.35, config.alpha, 0.001)
        assertEquals("Small wheel low threshold", 400.0, config.lowPowerThresholdW, 0.001)
        assertEquals("Small wheel medium threshold", 1000.0, config.mediumPowerThresholdW, 0.001)
    }
    
    /**
     * Test 11: Preset config - medium wheel (default)
     */
    @Test
    fun testPresetConfig_mediumWheel() {
        val config = VoltageCompensator.getPresetConfig(VoltageCompensator.WheelType.MEDIUM)
        
        assertEquals("Medium wheel should have alpha 0.3", 0.3, config.alpha, 0.001)
        assertEquals("Medium wheel low threshold", 500.0, config.lowPowerThresholdW, 0.001)
        assertEquals("Medium wheel medium threshold", 1500.0, config.mediumPowerThresholdW, 0.001)
    }
    
    /**
     * Test 12: Preset config - large wheel
     */
    @Test
    fun testPresetConfig_largeWheel() {
        val config = VoltageCompensator.getPresetConfig(VoltageCompensator.WheelType.LARGE)
        
        assertEquals("Large wheel should have alpha 0.25", 0.25, config.alpha, 0.001)
        assertEquals("Large wheel low threshold", 600.0, config.lowPowerThresholdW, 0.001)
        assertEquals("Large wheel medium threshold", 2000.0, config.mediumPowerThresholdW, 0.001)
    }
    
    /**
     * Test 13: Preset config - high performance wheel
     */
    @Test
    fun testPresetConfig_highPerformance() {
        val config = VoltageCompensator.getPresetConfig(VoltageCompensator.WheelType.HIGH_PERFORMANCE)
        
        assertEquals("HP wheel should have alpha 0.25", 0.25, config.alpha, 0.001)
        assertEquals("HP wheel low threshold", 800.0, config.lowPowerThresholdW, 0.001)
        assertEquals("HP wheel medium threshold", 2500.0, config.mediumPowerThresholdW, 0.001)
    }
    
    /**
     * Test 14: Smoothing convergence - constant conditions
     */
    @Test
    fun testSmoothing_converges() {
        val currentVoltage = 80.0
        val power = 1000.0
        var compensated = VoltageCompensator.initializeCompensatedVoltage(currentVoltage)
        
        // Run multiple iterations with same input
        for (i in 1..100) {
            val newCompensated = VoltageCompensator.calculateCompensatedVoltage(
                currentVoltage, power, compensated
            )
            
            // Check convergence
            if (abs(newCompensated - compensated) < 0.001) {
                // Converged successfully
                return
            }
            
            compensated = newCompensated
        }
        
        // After 100 iterations with constant input, should be very close to stable
        val finalIteration = VoltageCompensator.calculateCompensatedVoltage(
            currentVoltage, power, compensated
        )
        assertTrue("Should converge to stable value",
            abs(finalIteration - compensated) < 0.01)
    }
    
    /**
     * Test 15: Real-world scenario - acceleration and cruise
     */
    @Test
    fun testRealWorld_accelerationToCruise() {
        // Start at cruise
        var compensated = VoltageCompensator.initializeCompensatedVoltage(83.0)
        
        // Cruise for a bit (low power)
        for (i in 1..5) {
            compensated = VoltageCompensator.calculateCompensatedVoltage(
                83.0, 500.0, compensated
            )
        }
        val afterCruise = compensated
        
        // Sudden acceleration (voltage sags, high power)
        compensated = VoltageCompensator.calculateCompensatedVoltage(
            76.0, 3000.0, compensated
        )
        val duringAccel = compensated
        
        // Back to cruise (voltage recovers)
        for (i in 1..10) {
            compensated = VoltageCompensator.calculateCompensatedVoltage(
                82.5, 600.0, compensated
            )
        }
        val afterRecovery = compensated
        
        // During acceleration, compensated should not drop as much as raw
        assertTrue("Compensated should smooth voltage sag", duringAccel > 76.0)
        
        // After recovery, should be close to original cruise level
        assertTrue("Should recover after acceleration", afterRecovery > 81.0)
    }
}
