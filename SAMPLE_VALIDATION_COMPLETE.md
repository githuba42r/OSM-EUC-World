# Complete Sample Validation and Flagging Logic

**Purpose:** Comprehensive reference for how samples are validated, flagged, and filtered in the range estimation system, with special emphasis on voltage sag handling.

---

## Overview: Two-Stage Filtering

The range estimation system uses **two different filtering criteria** for different calculations:

1. **Energy Calculation Filter** → Determines State of Charge (SOC)
   - Excludes voltage sag samples
   - Excludes charging events
   - Excludes voltage anomalies

2. **Efficiency Calculation Filter** → Determines Wh/km consumption
   - Includes voltage sag samples (real energy consumed)
   - Excludes only data quality issues (gaps, outliers, speed errors)

---

## Complete SampleFlag Enum

```kotlin
enum class SampleFlag {
    // Connection/Data Quality Issues
    TIME_GAP,              // >5s gap between samples
    DISTANCE_ANOMALY,      // Distance jumped without proportional time
    
    // Battery State Issues
    CHARGING_EVENT,        // Battery charging detected
    VOLTAGE_ANOMALY,       // Voltage outside expected range
    VOLTAGE_SAG_SUSPECTED, // ← NEW: Temporary voltage drop under high load
    
    // Measurement Errors
    EFFICIENCY_OUTLIER,    // Calculated efficiency unrealistic
    SPEED_ANOMALY          // Speed sensor error
}
```

---

## Detection Functions

### 1. Time Gap Detection

```kotlin
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
```

### 2. Distance Anomaly Detection

```kotlin
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
```

### 3. Charging Event Detection

```kotlin
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
```

### 4. Voltage Anomaly Detection

```kotlin
/**
 * Detect voltage readings outside physically possible range
 */
fun detectVoltageAnomaly(
    sample: BatterySample,
    cellCount: Int
): Boolean {
    val expectedMaxVoltage = 4.2 * cellCount  // Fully charged
    val expectedMinVoltage = 3.0 * cellCount  // Cutoff voltage
    
    return sample.voltage > expectedMaxVoltage || sample.voltage < expectedMinVoltage
}
```

### 5. Voltage Sag Detection (CRITICAL)

```kotlin
/**
 * Detect temporary voltage sag under high load.
 * 
 * Voltage sag happens when high current draw causes voltage to drop
 * temporarily. This is NOT real discharge - voltage recovers when load decreases.
 * 
 * Detection strategy:
 * 1. High power consumption (>threshold)
 * 2. Voltage drop per cell >0.05V
 * 3. Voltage drop disproportionate to distance traveled
 * 4. OR voltage recovery pattern detected in recent history
 */
fun detectVoltageSag(
    currentSample: BatterySample,
    previousSample: BatterySample,
    recentSamples: List<BatterySample>,
    cellCount: Int = 20,
    highPowerThresholdW: Double = 1500.0
): Boolean {
    // Criterion 1: High power consumption
    if (currentSample.powerWatts < highPowerThresholdW) {
        return false  // Not high enough power to cause sag
    }
    
    // Criterion 2: Significant voltage drop per cell
    val voltageDrop = previousSample.voltage - currentSample.voltage
    val voltageDropPerCell = voltageDrop / cellCount
    
    if (voltageDropPerCell < 0.05) {
        return false  // Voltage drop too small to be sag
    }
    
    // Criterion 3: Voltage drop disproportionate to distance traveled
    val timeDeltaSeconds = (currentSample.timestamp - previousSample.timestamp) / 1000.0
    val distanceDelta = currentSample.tripDistanceKm - previousSample.tripDistanceKm
    
    // If significant voltage drop but minimal distance → likely sag, not discharge
    if (voltageDrop > 1.0 && distanceDelta < 0.05) {
        return true
    }
    
    // Criterion 4: Check for voltage recovery pattern in recent history
    if (recentSamples.size >= 10) {
        val hasRecoveryPattern = detectVoltageRecoveryPattern(recentSamples)
        if (hasRecoveryPattern) {
            return true
        }
    }
    
    return false
}

/**
 * Detect voltage recovery pattern characteristic of sag.
 * Pattern: High power + voltage drop → Low power + voltage increase
 */
fun detectVoltageRecoveryPattern(samples: List<BatterySample>): Boolean {
    if (samples.size < 10) return false
    
    // Look for sag-recovery pairs in sliding window
    for (i in 0 until samples.size - 5) {
        val sagPeriod = samples.subList(i, i + 3)       // 3 samples (~1.5 seconds)
        val recoveryPeriod = samples.subList(i + 3, i + 6)  // Next 3 samples
        
        val avgPowerDuringSag = sagPeriod.map { it.powerWatts }.average()
        val avgPowerDuringRecovery = recoveryPeriod.map { it.powerWatts }.average()
        
        val voltageDuringSag = sagPeriod.last().voltage
        val voltageDuringRecovery = recoveryPeriod.last().voltage
        
        // Characteristic pattern:
        // - High power (>1200W) during sag period
        // - Low power (<800W) during recovery period
        // - Voltage increases during recovery
        if (avgPowerDuringSag > 1200.0 && 
            avgPowerDuringRecovery < 800.0 && 
            voltageDuringRecovery > voltageDuringSag) {
            return true  // Sag-recovery pattern detected
        }
    }
    
    return false
}
```

### 6. Efficiency Outlier Detection

```kotlin
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
```

### 7. Speed Anomaly Detection

```kotlin
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
        if (Math.abs(accelerationKmhPerSec) > 20) return true
    }
    
    return false
}
```

---

## Comprehensive Sample Validation

```kotlin
/**
 * Validate and flag a sample based on all detection criteria.
 * Called by RangeEstimationManager when new sample is received.
 */
fun validateAndFlagSample(
    currentSample: BatterySample,
    previousSample: BatterySample?,
    recentSamples: List<BatterySample>,
    cellCount: Int = 20,
    voltageSagThresholdW: Double = 1500.0
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
            flags.add(SampleFlag.CHARGING_EVENT)
        }
        
        // VOLTAGE SAG CHECK (KEY FOR ACCURACY)
        if (detectVoltageSag(
                currentSample, 
                previousSample, 
                recentSamples, 
                cellCount,
                voltageSagThresholdW
            )) {
            flags.add(SampleFlag.VOLTAGE_SAG_SUSPECTED)
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
    return currentSample.copy(flags = flags)
}
```

---

## BatterySample Validation Properties

```kotlin
data class BatterySample(
    val timestamp: Long,
    val voltage: Double,
    val batteryPercent: Double,
    val tripDistanceKm: Double,
    val speedKmh: Double,
    val powerWatts: Double,
    val currentAmps: Double,
    val temperatureCelsius: Double = -1.0,
    val flags: Set<SampleFlag> = emptySet()
) {
    /** Time since previous sample (set by RangeEstimationManager) */
    var timeSincePreviousSampleMs: Long? = null
    
    /** Distance since previous sample (set by RangeEstimationManager) */
    var distanceSincePreviousSampleKm: Double? = null
    
    /** Calculated instantaneous energy efficiency (Wh/km) */
    val instantEfficiencyWhPerKm: Double
        get() = if (speedKmh > 1.0 && tripDistanceKm > 0.01) {
            powerWatts / speedKmh  // Power (W) / Speed (km/h) = Wh/km
        } else {
            Double.NaN
        }
    
    /**
     * Valid for STATE OF CHARGE (energy %) calculations.
     * 
     * EXCLUDES:
     * - Voltage sag samples (temporary voltage drop, not real discharge)
     * - Voltage anomalies (sensor errors)
     * - Charging events (battery gaining energy)
     * 
     * Use this filter when calculating remaining energy percentage.
     */
    val isValidForEnergyCalculation: Boolean
        get() = flags.none { it in setOf(
            SampleFlag.VOLTAGE_SAG_SUSPECTED,  // ← KEY: Exclude sag!
            SampleFlag.VOLTAGE_ANOMALY,
            SampleFlag.CHARGING_EVENT
        )} && 
        voltage > 0 && 
        batteryPercent in 0.0..100.0
    
    /**
     * Valid for EFFICIENCY (Wh/km) calculations.
     * 
     * INCLUDES voltage sag samples because power consumption during sag is REAL.
     * 
     * EXCLUDES:
     * - Time gaps (unreliable power data)
     * - Distance anomalies (distance measurement error)
     * - Efficiency outliers (calculation errors)
     * - Speed anomalies (speed measurement error)
     * 
     * Use this filter when calculating trip efficiency.
     */
    val isValidForEfficiencyCalculation: Boolean
        get() = flags.none { it in setOf(
            SampleFlag.TIME_GAP,
            SampleFlag.DISTANCE_ANOMALY,
            SampleFlag.EFFICIENCY_OUTLIER,
            SampleFlag.SPEED_ANOMALY
            // NOTE: VOLTAGE_SAG_SUSPECTED is NOT excluded
        )} && 
        speedKmh > 1.0 &&
        tripDistanceKm > 0.01 &&
        !instantEfficiencyWhPerKm.isNaN() &&
        !instantEfficiencyWhPerKm.isInfinite()
    
    /**
     * General validity check for any estimation use.
     * Sample has no critical flags and basic sanity checks pass.
     */
    val isValidForEstimation: Boolean
        get() = flags.isEmpty() && 
                voltage > 0 && 
                batteryPercent in 0.0..100.0 &&
                speedKmh >= 0 &&
                !instantEfficiencyWhPerKm.isNaN() &&
                !instantEfficiencyWhPerKm.isInfinite()
}
```

---

## Usage in Estimators

### Energy Calculation (State of Charge)

```kotlin
// In SimpleLinearEstimator, WeightedWindowEstimator, etc.

fun calculateRemainingEnergy(trip: TripSnapshot): Double {
    // Use only non-sag samples for voltage reading
    val energyCalculationSamples = trip.samples.filter { 
        it.isValidForEnergyCalculation  // Excludes voltage sag!
    }
    
    // Get most recent valid voltage (not affected by sag)
    val currentVoltage = energyCalculationSamples.lastOrNull()?.voltage
        ?: trip.latestSample?.voltage
        ?: return 0.0
    
    // Convert voltage to energy percentage using discharge curve
    val currentEnergyPercent = dischargeCurve.voltageToEnergyPercent(
        currentVoltage,
        cellCount
    )
    
    // Calculate remaining energy in Wh
    return currentEnergyPercent * batteryCapacityWh / 100.0
}
```

### Efficiency Calculation (Wh/km)

```kotlin
// In WeightedWindowEstimator

fun calculateWeightedAverageEfficiency(trip: TripSnapshot): Double {
    // Use all valid samples, INCLUDING those with voltage sag
    val efficiencySamples = trip.samples.filter { 
        it.isValidForEfficiencyCalculation  // Includes sag samples!
    }
    
    if (efficiencySamples.isEmpty()) return Double.NaN
    
    val latestTimestamp = efficiencySamples.last().timestamp
    var weightedSum = 0.0
    var weightSum = 0.0
    
    efficiencySamples.forEach { sample ->
        val efficiency = sample.instantEfficiencyWhPerKm
        
        if (!efficiency.isNaN() && efficiency > 0 && efficiency < 200) {
            // Exponential decay weight based on age
            val ageSeconds = (latestTimestamp - sample.timestamp) / 1000.0
            val weight = exp(-weightDecayFactor * ageSeconds / 60.0)
            
            weightedSum += efficiency * weight
            weightSum += weight
        }
    }
    
    return if (weightSum > 0) weightedSum / weightSum else Double.NaN
}
```

---

## Integration in RangeEstimationManager

```kotlin
class RangeEstimationManager(
    private val eucDataFlow: StateFlow<EucData>,
    private val settings: RangeEstimationSettings
) {
    private val currentTrip = TripSnapshot(...)
    private val recentSamplesWindow = mutableListOf<BatterySample>()
    
    init {
        // Observe EUC data stream
        eucDataFlow.collect { eucData ->
            onNewEucData(eucData)
        }
    }
    
    private fun onNewEucData(eucData: EucData) {
        // 1. Create raw sample from EucData
        val rawSample = BatterySample(
            timestamp = System.currentTimeMillis(),
            voltage = eucData.voltage,
            batteryPercent = eucData.batteryLevel,
            tripDistanceKm = eucData.tripDistance,
            speedKmh = eucData.speed,
            powerWatts = eucData.power,
            currentAmps = eucData.current,
            temperatureCelsius = eucData.temperature
        )
        
        // 2. Validate and flag sample
        val previousSample = currentTrip.latestSample
        val validatedSample = validateAndFlagSample(
            currentSample = rawSample,
            previousSample = previousSample,
            recentSamples = recentSamplesWindow,
            cellCount = settings.cellCount,
            voltageSagThresholdW = settings.voltageSagThresholdW
        )
        
        // 3. Add to trip
        currentTrip.samples.add(validatedSample)
        
        // 4. Maintain recent samples window (last 10 seconds for sag detection)
        recentSamplesWindow.add(validatedSample)
        val cutoffTime = System.currentTimeMillis() - 10000
        recentSamplesWindow.removeAll { it.timestamp < cutoffTime }
        
        // 5. Detect trip state changes
        updateTripState(validatedSample)
        
        // 6. Calculate range estimate
        if (currentTrip.state == TripState.RIDING) {
            val estimate = selectedEstimator.estimate(currentTrip)
            _rangeEstimate.value = estimate
        }
        
        // 7. Persist trip state periodically
        if (validatedSample.timestamp % 5000 < 500) {  // Every ~5 seconds
            tripDataStore.saveCurrentTrip(currentTrip)
        }
    }
}
```

---

## Testing Sample Validation

### Test Case 1: Voltage Sag Flagging

```kotlin
@Test
fun testVoltageSagFlagging() {
    val baseSample = BatterySample(
        timestamp = 1000,
        voltage = 75.0,
        batteryPercent = 70.0,
        tripDistanceKm = 5.0,
        speedKmh = 20.0,
        powerWatts = 600.0,
        currentAmps = 8.0
    )
    
    val sagSample = BatterySample(
        timestamp = 1500,
        voltage = 72.0,  // 3V drop
        batteryPercent = 70.0,
        tripDistanceKm = 5.01,  // Minimal distance
        speedKmh = 22.0,
        powerWatts = 2500.0,  // High power!
        currentAmps = 35.0
    )
    
    val flaggedSample = validateAndFlagSample(
        currentSample = sagSample,
        previousSample = baseSample,
        recentSamples = listOf(baseSample),
        cellCount = 20,
        voltageSagThresholdW = 1500.0
    )
    
    // Should be flagged as sag
    assertTrue(flaggedSample.flags.contains(SampleFlag.VOLTAGE_SAG_SUSPECTED))
    
    // Should be EXCLUDED from energy calculation
    assertFalse(flaggedSample.isValidForEnergyCalculation)
    
    // Should be INCLUDED in efficiency calculation
    assertTrue(flaggedSample.isValidForEfficiencyCalculation)
}
```

### Test Case 2: Normal Discharge Not Flagged

```kotlin
@Test
fun testNormalDischargeNotFlaggedAsSag() {
    val baseSample = BatterySample(
        timestamp = 1000,
        voltage = 75.0,
        batteryPercent = 70.0,
        tripDistanceKm = 5.0,
        speedKmh = 20.0,
        powerWatts = 800.0,
        currentAmps = 11.0
    )
    
    val dischargeSample = BatterySample(
        timestamp = 6000,  // 5 seconds later
        voltage = 74.5,  // 0.5V drop over distance
        batteryPercent = 69.0,
        tripDistanceKm = 5.5,  // 500m traveled
        speedKmh = 20.0,
        powerWatts = 850.0,  // Normal power
        currentAmps = 11.5
    )
    
    val flaggedSample = validateAndFlagSample(
        currentSample = dischargeSample,
        previousSample = baseSample,
        recentSamples = listOf(baseSample),
        cellCount = 20,
        voltageSagThresholdW = 1500.0
    )
    
    // Should NOT be flagged as sag (normal discharge)
    assertFalse(flaggedSample.flags.contains(SampleFlag.VOLTAGE_SAG_SUSPECTED))
    
    // Should be valid for both calculations
    assertTrue(flaggedSample.isValidForEnergyCalculation)
    assertTrue(flaggedSample.isValidForEfficiencyCalculation)
}
```

### Test Case 3: Multiple Flags

```kotlin
@Test
fun testMultipleFlags() {
    val sample = BatterySample(
        timestamp = 10000,
        voltage = 120.0,  // Voltage anomaly!
        batteryPercent = 70.0,
        tripDistanceKm = 5.0,
        speedKmh = 100.0,  // Speed anomaly!
        powerWatts = 3000.0,
        currentAmps = 25.0
    )
    
    val previousSample = BatterySample(
        timestamp = 1000,  // 9 second gap!
        voltage = 75.0,
        batteryPercent = 70.0,
        tripDistanceKm = 4.0,
        speedKmh = 20.0,
        powerWatts = 800.0,
        currentAmps = 11.0
    )
    
    val flaggedSample = validateAndFlagSample(
        currentSample = sample,
        previousSample = previousSample,
        recentSamples = listOf(previousSample),
        cellCount = 20
    )
    
    // Should have multiple flags
    assertTrue(flaggedSample.flags.contains(SampleFlag.TIME_GAP))
    assertTrue(flaggedSample.flags.contains(SampleFlag.VOLTAGE_ANOMALY))
    assertTrue(flaggedSample.flags.contains(SampleFlag.SPEED_ANOMALY))
    
    // Should be invalid for all calculations
    assertFalse(flaggedSample.isValidForEnergyCalculation)
    assertFalse(flaggedSample.isValidForEfficiencyCalculation)
    assertFalse(flaggedSample.isValidForEstimation)
}
```

---

## Summary: Voltage Sag Handling Flow

```
┌─────────────────────────────────────────────────────────────┐
│ 1. New EucData received (every 500ms)                       │
│    - Voltage: 72V                                           │
│    - Power: 2500W                                           │
│    - Speed: 25 km/h                                         │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Create BatterySample from EucData                        │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Validate and flag sample                                 │
│    - Check power: 2500W > 1500W threshold ✓                 │
│    - Check voltage drop: 75V → 72V = 3V drop ✓             │
│    - Check distance: only 0.01km traveled ✓                 │
│    → FLAG: VOLTAGE_SAG_SUSPECTED                            │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. Add flagged sample to trip                               │
│    - Sample added to trip.samples list                      │
│    - Flag preserved in sample                               │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ 5. Calculate range estimate                                 │
│                                                              │
│   A. Energy Calculation:                                    │
│      - Filter: isValidForEnergyCalculation                  │
│      - Excludes this sample (has VOLTAGE_SAG_SUSPECTED)     │
│      - Uses previous sample: 75V ✓                          │
│      - Energy: 65% (accurate, not affected by sag)          │
│                                                              │
│   B. Efficiency Calculation:                                │
│      - Filter: isValidForEfficiencyCalculation              │
│      - Includes this sample (sag samples included)          │
│      - Power 2500W / Speed 25 km/h = 100 Wh/km ✓            │
│      - Efficiency: accurate (real energy consumed)          │
│                                                              │
│   C. Range Estimate:                                        │
│      - Remaining energy: 65% × 2000Wh = 1300Wh              │
│      - Efficiency: 100 Wh/km                                │
│      - Range: 1300Wh / 100 Wh/km = 13km ✓                   │
└─────────────────────────────────────────────────────────────┘
```

---

## References

- Main implementation: `RANGE_ESTIMATION_IMPLEMENTATION.md`
- Summary: `VOLTAGE_SAG_COMPENSATION_SUMMARY.md`
- Visual guide: `VOLTAGE_SAG_VISUAL_REFERENCE.md`

---

**Document Version:** 1.0  
**Last Updated:** February 22, 2026
