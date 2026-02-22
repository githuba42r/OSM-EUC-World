# Voltage Sag Compensation - Implementation Summary

**Date:** February 22, 2026  
**Status:** Documented in main implementation spec  
**Related Document:** `RANGE_ESTIMATION_IMPLEMENTATION.md`

---

## Problem Statement

Li-Ion batteries exhibit **voltage sag** - a temporary voltage drop under high current draw that recovers when load decreases. This occurs during:

- **Hard acceleration** (high current draw)
- **Steep climbs** (sustained high power)
- **High-speed riding** (continuous high power)

**Without compensation**, voltage sag causes the range estimation algorithm to:
- Underestimate remaining range by 10-20%
- Show wildly fluctuating estimates during varied riding
- Lose user confidence

---

## Solution Overview

### Key Insight

**Voltage sag is temporary and load-dependent**, not a true reflection of battery state of charge.

**Example:**
```
Battery at rest: 75.0V (true SOC)
  ↓
Hard acceleration → voltage drops to 72.0V (SAG - not real discharge)
  ↓
Return to cruising → voltage recovers to 74.8V (true SOC)

✓ Use 74.8V for energy calculations, NOT 72.0V
```

### Implementation Strategy

**Separate treatment for different calculations:**

1. **State of Charge (Energy %) Calculation:**
   - **EXCLUDE** voltage sag samples
   - Use voltage from recent low-load samples
   - Prevents underestimating remaining energy

2. **Efficiency (Wh/km) Calculation:**
   - **INCLUDE** voltage sag samples
   - Power consumption during sag is real energy usage
   - Accurately reflects trip energy consumption

---

## Detection Algorithm

### Voltage Sag Indicators

Detect sag by correlating voltage drops with power consumption:

```kotlin
fun detectVoltageSag(
    currentSample: BatterySample,
    previousSample: BatterySample,
    recentSamples: List<BatterySample>,
    cellCount: Int = 20,
    highPowerThresholdW: Double = 1500.0
): Boolean {
    // 1. Check for high power consumption
    if (currentSample.powerWatts < highPowerThresholdW) return false
    
    // 2. Calculate voltage drop per cell
    val voltageDrop = previousSample.voltage - currentSample.voltage
    val voltageDropPerCell = voltageDrop / cellCount
    
    // 3. Typical sag threshold: >0.05V per cell under load
    if (voltageDropPerCell < 0.05) return false
    
    // 4. Voltage drop disproportionate to distance?
    val distanceDelta = currentSample.tripDistanceKm - previousSample.tripDistanceKm
    if (voltageDrop > 1.0 && distanceDelta < 0.05) {
        return true // Significant drop, minimal distance = SAG
    }
    
    // 5. Check for voltage recovery pattern
    if (recentSamples.size >= 10) {
        return detectVoltageRecoveryPattern(recentSamples)
    }
    
    return false
}
```

### Voltage Recovery Pattern Detection

Looks for characteristic sag-recovery behavior:

```kotlin
fun detectVoltageRecoveryPattern(samples: List<BatterySample>): Boolean {
    // Look for pattern: high power + voltage drop → low power + voltage increase
    
    for (i in 0 until samples.size - 5) {
        val sagPeriod = samples.subList(i, i + 3)
        val recoveryPeriod = samples.subList(i + 3, i + 6)
        
        val avgPowerDuringSag = sagPeriod.map { it.powerWatts }.average()
        val avgPowerDuringRecovery = recoveryPeriod.map { it.powerWatts }.average()
        
        val voltageDuringSag = sagPeriod.last().voltage
        val voltageDuringRecovery = recoveryPeriod.last().voltage
        
        // Pattern: high power (>1200W) → low power (<800W), voltage recovers
        if (avgPowerDuringSag > 1200 && 
            avgPowerDuringRecovery < 800 && 
            voltageDuringRecovery > voltageDuringSag) {
            return true // Sag-recovery pattern detected
        }
    }
    
    return false
}
```

---

## Modified Data Model

### BatterySample with Sag Flag

```kotlin
data class BatterySample(
    val timestamp: Long,
    val voltage: Double,
    val batteryPercent: Double,
    val tripDistanceKm: Double,
    val speedKmh: Double,
    val powerWatts: Double,        // ← Key for sag detection
    val currentAmps: Double,
    val temperatureCelsius: Double = -1.0,
    val flags: Set<SampleFlag> = emptySet()
) {
    /** 
     * Valid for STATE OF CHARGE (energy %) calculations.
     * EXCLUDES voltage sag samples.
     */
    val isValidForEnergyCalculation: Boolean
        get() = flags.none { it in setOf(
            SampleFlag.VOLTAGE_SAG_SUSPECTED,  // ← NEW
            SampleFlag.VOLTAGE_ANOMALY,
            SampleFlag.CHARGING_EVENT
        )} && voltage > 0 && batteryPercent in 0.0..100.0
    
    /**
     * Valid for EFFICIENCY (Wh/km) calculations.
     * INCLUDES voltage sag samples (real energy consumed).
     */
    val isValidForEfficiencyCalculation: Boolean
        get() = flags.none { it in setOf(
            SampleFlag.TIME_GAP,
            SampleFlag.DISTANCE_ANOMALY,
            SampleFlag.EFFICIENCY_OUTLIER,
            SampleFlag.SPEED_ANOMALY
            // NOTE: VOLTAGE_SAG_SUSPECTED NOT excluded here
        )} && 
        speedKmh > 1.0 &&
        !instantEfficiencyWhPerKm.isNaN()
}

enum class SampleFlag {
    TIME_GAP,
    DISTANCE_ANOMALY,
    CHARGING_EVENT,
    VOLTAGE_ANOMALY,
    EFFICIENCY_OUTLIER,
    SPEED_ANOMALY,
    VOLTAGE_SAG_SUSPECTED  // ← NEW
}
```

---

## Integration into Estimators

### Modified Energy Calculation

**OLD (incorrect):**
```kotlin
// Directly uses latest voltage (may be sagging)
val currentEnergyPercent = dischargeCurve.voltageToEnergyPercent(
    latestSample.voltage, 
    cellCount
)
```

**NEW (correct):**
```kotlin
// Uses voltage from most recent LOW-LOAD sample
val energyCalculationSamples = trip.samples.filter { 
    it.isValidForEnergyCalculation 
}

val currentEnergyVoltage = energyCalculationSamples.lastOrNull()?.voltage 
    ?: latestSample.voltage  // Fallback if no low-load samples

val currentEnergyPercent = dischargeCurve.voltageToEnergyPercent(
    currentEnergyVoltage,  // ← Uses compensated voltage
    cellCount
)
```

### Efficiency Calculation (Unchanged)

```kotlin
// Efficiency calculation INCLUDES sag samples
val validEfficiencySamples = trip.samples.filter { 
    it.isValidForEfficiencyCalculation  // Sag samples included
}

val weightedEfficiency = calculateWeightedAverageEfficiency(validEfficiencySamples)
```

---

## Sample Processing Flow

### In RangeEstimationManager

```kotlin
fun onNewEucData(eucData: EucData) {
    // 1. Create sample from EucData
    val newSample = BatterySample(
        timestamp = System.currentTimeMillis(),
        voltage = eucData.voltage,
        batteryPercent = eucData.batteryLevel,
        tripDistanceKm = eucData.tripDistance,
        speedKmh = eucData.speed,
        powerWatts = eucData.power,  // ← Critical for sag detection
        currentAmps = eucData.current
    )
    
    // 2. Detect and flag voltage sag
    val previousSample = currentTrip.latestSample
    val recentSamples = currentTrip.samples.takeLast(20)  // Last 10 seconds
    
    val flaggedSample = if (previousSample != null) {
        val isSag = detectVoltageSag(newSample, previousSample, recentSamples)
        if (isSag) {
            newSample.copy(flags = newSample.flags + SampleFlag.VOLTAGE_SAG_SUSPECTED)
        } else {
            newSample
        }
    } else {
        newSample
    }
    
    // 3. Add to trip
    currentTrip.samples.add(flaggedSample)
    
    // 4. Estimate range (algorithm will use filtered samples)
    val estimate = selectedEstimator.estimate(currentTrip)
    rangeEstimate.emit(estimate)
}
```

---

## Configuration & Tuning

### Configurable Thresholds (Advanced Settings)

```xml
<!-- In preferences.xml -->
<SwitchPreferenceCompat
    android:key="range_voltage_sag_compensation_enabled"
    android:title="Enable Voltage Sag Compensation"
    android:summary="Detect and compensate for temporary voltage drops under load"
    android:defaultValue="true" />

<EditTextPreference
    android:key="range_voltage_sag_power_threshold"
    android:title="Voltage Sag Power Threshold (W)"
    android:summary="Power level above which voltage sag is suspected"
    android:defaultValue="1500"
    android:inputType="number" />
```

### Wheel-Specific Tuning

Different EUC models have different power characteristics:

| Wheel Type | Recommended Threshold |
|------------|----------------------|
| Small wheels (<16") | 1000W |
| Medium wheels (16"-18") | 1500W (default) |
| Large wheels (20"+) | 2000W |
| High-performance wheels | 2500W |

---

## Testing Strategy

### Unit Tests

```kotlin
@Test
fun testVoltageSagDetection() {
    val baseSample = createSample(voltage = 75.0, power = 500.0)
    val sagSample = createSample(voltage = 72.0, power = 2000.0)
    
    val isSag = detectVoltageSag(sagSample, baseSample, emptyList())
    assertTrue(isSag)
}

@Test
fun testSagExcludedFromEnergyCalculation() {
    val sagSample = createSample(
        voltage = 72.0, 
        power = 2000.0,
        flags = setOf(SampleFlag.VOLTAGE_SAG_SUSPECTED)
    )
    
    // Should be EXCLUDED from energy calculation
    assertFalse(sagSample.isValidForEnergyCalculation)
    
    // Should be INCLUDED in efficiency calculation
    assertTrue(sagSample.isValidForEfficiencyCalculation)
}

@Test
fun testRangeEstimateStabilityDuringAcceleration() {
    val trip = simulateAccelerationTrip(
        startVoltage = 80.0,
        endVoltage = 78.0,
        peakPower = 3000.0,
        minSagVoltage = 75.0  // Temporary sag during acceleration
    )
    
    val estimate = WeightedWindowEstimator().estimate(trip)
    
    // Estimate should use recovered voltage (~78V), not sag voltage (75V)
    val expectedEnergy = LiIonDischargeCurve.voltageToEnergyPercent(78.0, 20)
    val actualEnergy = estimate.remainingEnergyWh / 2000.0 * 100.0
    
    assertEquals(expectedEnergy, actualEnergy, 2.0)  // Within 2% tolerance
}
```

### Real-World Testing

**Test Scenario 1: Aggressive Acceleration**
1. Start from standstill
2. Hard acceleration to 30 km/h
3. Observe voltage drop and recovery
4. Verify estimate doesn't drop significantly during sag

**Test Scenario 2: Steep Hill Climb**
1. Approach steep hill at moderate speed
2. Climb with sustained high power (1500-2500W)
3. Voltage should sag throughout climb
4. At top of hill, coast/descend
5. Verify estimate recovers to realistic value

**Test Scenario 3: Mixed Riding**
1. Ride with alternating aggressive/gentle riding
2. Monitor voltage fluctuations
3. Verify estimate remains stable despite voltage variations

---

## Expected Accuracy Improvement

### Without Voltage Sag Compensation

| Riding Style | Range Error | Estimate Stability |
|--------------|-------------|-------------------|
| Gentle cruising | ±5% | Stable |
| Mixed riding | ±15% | Moderate fluctuation |
| Aggressive | -20% to -30% | High fluctuation |

### With Voltage Sag Compensation

| Riding Style | Range Error | Estimate Stability |
|--------------|-------------|-------------------|
| Gentle cruising | ±5% | Stable |
| Mixed riding | ±8% | Stable |
| Aggressive | ±10% | Stable |

**Overall MAPE improvement:** 5-10% for typical riding, up to 20% for aggressive riding

---

## Implementation Checklist

### Phase 1 - Core Implementation (MVP)
- [x] Document voltage sag problem in implementation spec
- [ ] Add `VOLTAGE_SAG_SUSPECTED` to `SampleFlag` enum
- [ ] Implement `detectVoltageSag()` function
- [ ] Implement `detectVoltageRecoveryPattern()` helper
- [ ] Add `isValidForEnergyCalculation` property to `BatterySample`
- [ ] Add `isValidForEfficiencyCalculation` property to `BatterySample`
- [ ] Integrate sag detection into `RangeEstimationManager.onNewEucData()`
- [ ] Modify all estimators to use filtered samples for energy calculations
- [ ] Write unit tests for sag detection
- [ ] Write integration tests for estimate stability

### Phase 2 - Configuration (Settings)
- [ ] Add voltage sag compensation enable/disable setting
- [ ] Add power threshold configuration (advanced)
- [ ] Add per-wheel threshold presets (optional)

### Phase 3 - Validation (Real-World Testing)
- [ ] Test on 3+ different EUC models
- [ ] Collect logs with sag events flagged
- [ ] Measure accuracy improvement vs baseline
- [ ] Tune default thresholds based on data

---

## Alternative Approaches Considered

### ❌ Voltage Smoothing (Rejected)

**Approach:** Apply exponential moving average to voltage readings

**Pros:** Simple, smooth estimates

**Cons:** 
- Introduces lag (delayed response to real discharge)
- Requires tuning smoothing factor
- Doesn't distinguish sag from real discharge

### ❌ Use Battery Percentage Instead (Rejected)

**Approach:** Use BMS-reported battery % instead of voltage

**Cons:**
- BMS % is often inaccurate (linear approximation)
- Also affected by voltage sag
- Doesn't account for discharge curve

### ✅ Flag and Filter (Selected)

**Approach:** Detect sag, flag samples, filter for energy calculations

**Pros:**
- Explicit and transparent
- Easy to debug (flagged samples visible)
- Conservative (doesn't overestimate)
- Maintains efficiency accuracy

---

## References

1. **Battery Voltage Sag Under Load**
   - [Battery University: BU-501a Discharge Characteristics](https://batteryuniversity.com/article/bu-501a-discharge-characteristics-of-li-ion)
   - "Effects of High Rate Discharge on Li-Ion Battery Performance" - Journal of Power Sources

2. **Internal Resistance and Sag**
   - [Understanding Battery Internal Resistance](https://www.analog.com/en/technical-articles/understanding-battery-internal-resistance.html)
   - V_sag = V_rest - (I × R_internal)

3. **EV Range Estimation**
   - "State of Charge Estimation for Electric Vehicles Using Kalman Filter" - IEEE Transactions
   - Tesla/Nissan Leaf range estimation patents (use load-compensated voltage)

---

## Document Updates

The main implementation document (`RANGE_ESTIMATION_IMPLEMENTATION.md`) has been updated with:

1. **New section 1b: Voltage Sag Compensation** (comprehensive algorithm details)
2. **Updated Problem Statement** (mentions voltage sag)
3. **Updated FR-1.6** (voltage sag compensation requirement)
4. **Updated BatterySample** (dual validation properties)
5. **Updated SampleFlag enum** (VOLTAGE_SAG_SUSPECTED flag)
6. **Updated Edge Cases section** (new section 4 on voltage sag)
7. **Updated Sprint 1.1** (voltage sag detection tasks)
8. **Updated Sprint 1.2** (sag compensation in estimators)

---

**Next Steps:**

1. Review this voltage sag compensation design
2. Confirm approach and thresholds
3. Begin implementation in Phase 1, Sprint 1.1
4. Validate with real EUC data during testing phase

---

**Document Revision:** 1.0  
**Last Updated:** February 22, 2026
