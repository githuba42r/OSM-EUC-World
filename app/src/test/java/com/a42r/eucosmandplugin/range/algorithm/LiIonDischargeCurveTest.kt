package com.a42r.eucosmandplugin.range.algorithm

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for LiIonDischargeCurve.
 * 
 * Tests Li-Ion battery discharge curve voltage-to-energy conversion.
 */
class LiIonDischargeCurveTest {
    
    private val cellCount20S = 20
    private val cellCount24S = 24
    
    /**
     * Test 1: Full charge (4.2V per cell) = 100%
     */
    @Test
    fun testFullCharge_100Percent() {
        val packVoltage = 4.2 * cellCount20S
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount20S)
        
        assertEquals("Full charge should be 100%", 100.0, energyPercent, 0.1)
    }
    
    /**
     * Test 2: Empty (3.0V per cell) = 0%
     */
    @Test
    fun testEmpty_0Percent() {
        val packVoltage = 3.0 * cellCount20S
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount20S)
        
        assertEquals("Empty should be 0%", 0.0, energyPercent, 0.1)
    }
    
    /**
     * Test 3: Flat region boundary (3.95V per cell) = 80%
     */
    @Test
    fun testFlatRegionEnd_80Percent() {
        val packVoltage = 3.95 * cellCount20S
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount20S)
        
        assertEquals("Flat region end should be 80%", 80.0, energyPercent, 0.5)
    }
    
    /**
     * Test 4: Gradual region boundary (3.50V per cell) = 20%
     */
    @Test
    fun testGradualRegionEnd_20Percent() {
        val packVoltage = 3.5 * cellCount20S
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount20S)
        
        assertEquals("Gradual region end should be 20%", 20.0, energyPercent, 0.5)
    }
    
    /**
     * Test 5: Mid-point in flat region (~4.075V per cell) = ~90%
     */
    @Test
    fun testFlatRegion_midPoint() {
        val packVoltage = 4.075 * cellCount20S
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount20S)
        
        assertTrue("Mid-flat region should be around 90%", energyPercent in 85.0..95.0)
    }
    
    /**
     * Test 6: Mid-point in gradual region (3.725V per cell) = ~50%
     */
    @Test
    fun testGradualRegion_midPoint() {
        val packVoltage = 3.725 * cellCount20S
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount20S)
        
        assertTrue("Mid-gradual region should be around 50%", energyPercent in 45.0..55.0)
    }
    
    /**
     * Test 7: Mid-point in rapid drop region (3.25V per cell) = ~10%
     */
    @Test
    fun testRapidDropRegion_midPoint() {
        val packVoltage = 3.25 * cellCount20S
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount20S)
        
        assertTrue("Mid-rapid drop region should be around 10%", energyPercent in 8.0..12.0)
    }
    
    /**
     * Test 8: Inverse function - 100% to voltage and back
     */
    @Test
    fun testInverseFunction_fullCharge() {
        val energyPercent = 100.0
        val voltage = LiIonDischargeCurve.energyPercentToVoltage(energyPercent, cellCount20S)
        val backToPercent = LiIonDischargeCurve.voltageToEnergyPercent(voltage, cellCount20S)
        
        assertEquals("Inverse should return original value", energyPercent, backToPercent, 0.1)
    }
    
    /**
     * Test 9: Inverse function - 50% roundtrip
     */
    @Test
    fun testInverseFunction_50Percent() {
        val energyPercent = 50.0
        val voltage = LiIonDischargeCurve.energyPercentToVoltage(energyPercent, cellCount20S)
        val backToPercent = LiIonDischargeCurve.voltageToEnergyPercent(voltage, cellCount20S)
        
        assertEquals("Inverse should return original value", energyPercent, backToPercent, 0.5)
    }
    
    /**
     * Test 10: Inverse function - 0% to voltage and back
     */
    @Test
    fun testInverseFunction_empty() {
        val energyPercent = 0.0
        val voltage = LiIonDischargeCurve.energyPercentToVoltage(energyPercent, cellCount20S)
        val backToPercent = LiIonDischargeCurve.voltageToEnergyPercent(voltage, cellCount20S)
        
        assertEquals("Inverse should return original value", energyPercent, backToPercent, 0.1)
    }
    
    /**
     * Test 11: Different cell count - 24S pack at full charge
     */
    @Test
    fun testDifferentCellCount_24S_fullCharge() {
        val packVoltage = 4.2 * cellCount24S
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount24S)
        
        assertEquals("24S full charge should be 100%", 100.0, energyPercent, 0.1)
    }
    
    /**
     * Test 12: Different cell count - 24S pack at 50%
     */
    @Test
    fun testDifferentCellCount_24S_50Percent() {
        // 50% is in gradual region, around 3.725V per cell
        val packVoltage = 3.725 * cellCount24S
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount24S)
        
        assertTrue("24S at mid-voltage should be around 50%", energyPercent in 45.0..55.0)
    }
    
    /**
     * Test 13: Voltage range for 20S pack
     */
    @Test
    fun testVoltageRange_20S() {
        val (minVoltage, maxVoltage) = LiIonDischargeCurve.getVoltageRange(cellCount20S)
        
        assertEquals("20S min voltage", 60.0, minVoltage, 0.1)
        assertEquals("20S max voltage", 84.0, maxVoltage, 0.1)
    }
    
    /**
     * Test 14: Voltage range for 24S pack
     */
    @Test
    fun testVoltageRange_24S() {
        val (minVoltage, maxVoltage) = LiIonDischargeCurve.getVoltageRange(cellCount24S)
        
        assertEquals("24S min voltage", 72.0, minVoltage, 0.1)
        assertEquals("24S max voltage", 100.8, maxVoltage, 0.1)
    }
    
    /**
     * Test 15: Voltage validation - valid voltage
     */
    @Test
    fun testVoltageValidation_valid() {
        val packVoltage = 75.0  // 3.75V per cell in 20S
        val isValid = LiIonDischargeCurve.isVoltageValid(packVoltage, cellCount20S)
        
        assertTrue("75V should be valid for 20S pack", isValid)
    }
    
    /**
     * Test 16: Voltage validation - too low
     */
    @Test
    fun testVoltageValidation_tooLow() {
        val packVoltage = 50.0  // 2.5V per cell - too low
        val isValid = LiIonDischargeCurve.isVoltageValid(packVoltage, cellCount20S)
        
        assertFalse("50V should be invalid for 20S pack", isValid)
    }
    
    /**
     * Test 17: Voltage validation - too high
     */
    @Test
    fun testVoltageValidation_tooHigh() {
        val packVoltage = 95.0  // 4.75V per cell - too high
        val isValid = LiIonDischargeCurve.isVoltageValid(packVoltage, cellCount20S)
        
        assertFalse("95V should be invalid for 20S pack", isValid)
    }
    
    /**
     * Test 18: Voltage validation - boundary case (slightly above max)
     */
    @Test
    fun testVoltageValidation_slightlyAboveMax() {
        val packVoltage = 84.3  // 4.215V per cell - just above max (within margin)
        val isValid = LiIonDischargeCurve.isVoltageValid(packVoltage, cellCount20S)
        
        assertTrue("84.3V should be valid (within margin) for 20S pack", isValid)
    }
    
    /**
     * Test 19: Energy consumed calculation
     */
    @Test
    fun testEnergyConsumed() {
        val startVoltage = 84.0  // 100%
        val endVoltage = 60.0    // 0%
        
        val consumed = LiIonDischargeCurve.calculateEnergyConsumed(
            startVoltage, endVoltage, cellCount20S
        )
        
        assertTrue("Full discharge should consume close to 100%", consumed > 99.0)
        assertTrue("Full discharge should not exceed 100%", consumed <= 100.0)
    }
    
    /**
     * Test 20: Energy consumed - partial discharge
     */
    @Test
    fun testEnergyConsumed_partial() {
        // Start at 80% (3.95V per cell) and go to 20% (3.50V per cell)
        val startVoltage = 3.95 * cellCount20S
        val endVoltage = 3.50 * cellCount20S
        
        val consumed = LiIonDischargeCurve.calculateEnergyConsumed(
            startVoltage, endVoltage, cellCount20S
        )
        
        // Should consume approximately 60% (80% - 20%)
        assertTrue("Partial discharge should consume around 60%", consumed in 55.0..65.0)
    }
    
    /**
     * Test 21: Energy consumed - no discharge (same voltage)
     */
    @Test
    fun testEnergyConsumed_none() {
        val voltage = 75.0
        
        val consumed = LiIonDischargeCurve.calculateEnergyConsumed(
            voltage, voltage, cellCount20S
        )
        
        assertEquals("No discharge should consume 0%", 0.0, consumed, 0.1)
    }
    
    /**
     * Test 22: Energy consumed - prevents negative (voltage increased)
     */
    @Test
    fun testEnergyConsumed_preventsNegative() {
        val startVoltage = 75.0
        val endVoltage = 80.0  // Voltage increased (charging or measurement error)
        
        val consumed = LiIonDischargeCurve.calculateEnergyConsumed(
            startVoltage, endVoltage, cellCount20S
        )
        
        assertEquals("Should not return negative energy consumed", 0.0, consumed, 0.1)
    }
    
    /**
     * Test 23: Above maximum voltage handling
     */
    @Test
    fun testAboveMaximum_clampTo100() {
        val packVoltage = 4.3 * cellCount20S  // Above 4.2V per cell
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount20S)
        
        assertEquals("Above maximum should clamp to 100%", 100.0, energyPercent, 0.1)
    }
    
    /**
     * Test 24: Below minimum voltage handling
     */
    @Test
    fun testBelowMinimum_clampTo0() {
        val packVoltage = 2.8 * cellCount20S  // Below 3.0V per cell
        val energyPercent = LiIonDischargeCurve.voltageToEnergyPercent(packVoltage, cellCount20S)
        
        assertEquals("Below minimum should clamp to 0%", 0.0, energyPercent, 0.1)
    }
    
    /**
     * Test 25: Non-linearity verification - flat vs gradual regions
     */
    @Test
    fun testNonLinearity_flatVsGradualRegions() {
        // In flat region: 4.2V to 3.95V = 0.25V drop = 20% energy
        // In gradual region: 3.95V to 3.70V = 0.25V drop = 33% energy
        
        val flatStart = 4.2 * cellCount20S
        val flatEnd = 3.95 * cellCount20S
        val gradualStart = 3.95 * cellCount20S
        val gradualEnd = 3.70 * cellCount20S
        
        val flatConsumed = LiIonDischargeCurve.calculateEnergyConsumed(
            flatStart, flatEnd, cellCount20S
        )
        val gradualConsumed = LiIonDischargeCurve.calculateEnergyConsumed(
            gradualStart, gradualEnd, cellCount20S
        )
        
        // Same voltage drop should consume more energy in gradual region
        assertTrue("Gradual region should consume more energy per volt than flat region",
            gradualConsumed > flatConsumed)
    }
}
