package com.a42r.eucosmandplugin.range.util

import com.a42r.eucosmandplugin.range.model.BatterySample
import com.a42r.eucosmandplugin.range.model.SampleFlag

/**
 * Validation logic for battery samples.
 * Detects and flags anomalous data for filtering.
 */
object SampleValidator {
    
    /**
     * Validate and flag a sample based on all detection criteria.
     * Called by RangeEstimationManager when new sample is received.
     */
    fun validateAndFlagSample(
        currentSample: BatterySample,
        previousSample: BatterySample?,
        cellCount: Int = 20
    ): BatterySample {
        val flags = mutableSetOf<SampleFlag>()
        
        if (previousSample != null) {
            // Connection/continuity checks
            if (detectTimeGap(currentSample, previousSample)) {
                flags.add(SampleFlag.TIME_GAP)
            }
            
            if (detectDistanceAnomaly(currentSample, previousSample)) {
                flags.add(SampleFlag.DISTANCE_ANOMALY)
            }
            
            // Battery state checks
            if (detectChargingEvent(currentSample, previousSample)) {
                flags.add(SampleFlag.CHARGING_DETECTED)
            }
            
            // Speed sensor checks
            if (detectSpeedAnomaly(currentSample, previousSample)) {
                flags.add(SampleFlag.SPEED_ANOMALY)
            }
        }
        
        // Voltage range check (can be done without previous sample)
        if (detectVoltageAnomaly(currentSample, cellCount)) {
            flags.add(SampleFlag.VOLTAGE_ANOMALY)
        }
        
        // Efficiency check (can be done without previous sample)
        if (detectEfficiencyOutlier(currentSample)) {
            flags.add(SampleFlag.EFFICIENCY_OUTLIER)
        }
        
        // Return sample with flags applied
        return if (flags.isEmpty()) {
            currentSample
        } else {
            currentSample.copy(flags = flags)
        }
    }
    
    /**
     * Detect connection gaps (Bluetooth disconnect, app pause, etc.)
     */
    fun detectTimeGap(
        currentSample: BatterySample,
        previousSample: BatterySample
    ): Boolean {
        val timeDeltaMs = currentSample.timestamp - previousSample.timestamp
        return timeDeltaMs > 5000  // >5 seconds = gap
    }
    
    /**
     * Detect unrealistic distance jumps (GPS error, trip meter reset)
     */
    fun detectDistanceAnomaly(
        currentSample: BatterySample,
        previousSample: BatterySample
    ): Boolean {
        val timeDeltaMs = currentSample.timestamp - previousSample.timestamp
        val distanceDeltaKm = currentSample.tripDistanceKm - previousSample.tripDistanceKm
        
        // Distance jumped >500m in <10 seconds = anomaly
        return distanceDeltaKm > 0.5 && timeDeltaMs < 10000
    }
    
    /**
     * Detect battery charging mid-trip
     */
    fun detectChargingEvent(
        currentSample: BatterySample,
        previousSample: BatterySample
    ): Boolean {
        val batteryIncrease = currentSample.batteryPercent - previousSample.batteryPercent
        val voltageIncrease = currentSample.voltage - previousSample.voltage
        val isStationary = currentSample.speedKmh < 1.0
        
        // Battery/voltage increased while stationary = charging
        return (batteryIncrease >= 3.0 || voltageIncrease >= 1.0) && isStationary
    }
    
    /**
     * Detect voltage readings outside physically possible range
     */
    fun detectVoltageAnomaly(
        sample: BatterySample,
        cellCount: Int
    ): Boolean {
        val expectedMaxVoltage = 4.2 * cellCount  // Fully charged
        val expectedMinVoltage = 3.0 * cellCount  // Cutoff voltage
        
        // Check both raw and compensated voltage
        return sample.voltage > expectedMaxVoltage || 
               sample.voltage < expectedMinVoltage ||
               sample.compensatedVoltage > expectedMaxVoltage ||
               sample.compensatedVoltage < expectedMinVoltage
    }
    
    /**
     * Detect unrealistic efficiency values
     */
    fun detectEfficiencyOutlier(sample: BatterySample): Boolean {
        val efficiency = sample.instantEfficiencyWhPerKm
        
        if (efficiency.isNaN() || efficiency.isInfinite()) {
            return true
        }
        
        // Realistic range for EUC: 10-200 Wh/km
        // - Minimum: gentle cruising downhill with regen (~10 Wh/km)
        // - Maximum: extreme climbing or very heavy rider (~200 Wh/km)
        return efficiency < 0 || efficiency > 200
    }
    
    /**
     * Detect speed sensor errors
     */
    fun detectSpeedAnomaly(
        currentSample: BatterySample,
        previousSample: BatterySample
    ): Boolean {
        // Negative speed = sensor error
        if (currentSample.speedKmh < 0) return true
        
        // Unrealistic maximum speed (>80 km/h for most EUCs)
        if (currentSample.speedKmh > 80) return true
        
        // Unrealistic acceleration (0 → 60 km/h in 0.5 seconds)
        val timeDeltaSeconds = (currentSample.timestamp - previousSample.timestamp) / 1000.0
        val speedDelta = currentSample.speedKmh - previousSample.speedKmh
        
        if (timeDeltaSeconds > 0) {
            val accelerationKmhPerSec = speedDelta / timeDeltaSeconds
            // Max realistic acceleration: ~20 km/h per second
            if (kotlin.math.abs(accelerationKmhPerSec) > 20) return true
        }
        
        return false
    }
}
