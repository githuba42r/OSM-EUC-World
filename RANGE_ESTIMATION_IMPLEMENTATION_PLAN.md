# Range Estimation Feature - Implementation Plan

**Project:** OSM EUC World - Android Companion App  
**Feature:** Battery Range Estimation with Voltage Sag Compensation  
**Date:** February 22, 2026  
**Status:** Planning Complete, Ready to Implement

---

## Overview

This document provides a phased implementation plan for the battery range estimation feature, broken down into 6 phases over approximately 5 weeks.

---

## Implementation Phases

### Phase 1: Core Models & Algorithm (Weeks 1-2)
**Goal:** Get core range estimation logic working (no UI)

#### Files to Create:

1. ✅ **`BatterySample.kt`** - DONE
   - Data class with `voltage`, `compensatedVoltage`, `powerWatts`, etc.
   - Computed properties: `voltageSag`, `hasSignificantSag`
   - Single validation: `isValidForEstimation`

2. ✅ **`VoltageCompensator.kt`** - DONE
   - Power-weighted smoothing algorithm
   - `calculateCompensatedVoltage()` function
   - Wheel-specific presets

3. ✅ **`SampleValidator.kt`** - DONE (needs minor updates)
   - Add `detectConnectionGap()` function
   - Update flag handling for `INTERPOLATED`

4. ⏭️ **`TripSnapshot.kt`** - Model for trip state
   ```kotlin
   data class TripSnapshot(
       val startTime: Long,
       val samples: MutableList<BatterySample>,
       val segments: MutableList<TripSegment>,
       var isCurrentlyCharging: Boolean = false,
       val chargingEvents: MutableList<ChargingEvent> = mutableListOf()
   )
   ```

5. ⏭️ **`TripSegment.kt`** - Model for trip segments
   ```kotlin
   data class TripSegment(
       val type: SegmentType,  // NORMAL_RIDING, CONNECTION_GAP, CHARGING
       val startTimestamp: Long,
       var endTimestamp: Long?,
       val samples: MutableList<BatterySample>,
       val isBaselineSegment: Boolean = false,
       val baselineReason: String? = null
   )
   
   enum class SegmentType {
       NORMAL_RIDING,
       CONNECTION_GAP,
       CHARGING,
       PARKED
   }
   ```

6. ⏭️ **`ChargingEvent.kt`** - Track charging sessions
   ```kotlin
   data class ChargingEvent(
       val startTimestamp: Long,
       val endTimestamp: Long?,
       val voltageBeforeCharging: Double,
       val voltageAfterCharging: Double?,
       val batteryPercentBefore: Double,
       val batteryPercentAfter: Double?,
       val energyAdded: Double?
   )
   ```

7. ⏭️ **`RangeEstimate.kt`** - Result model
   ```kotlin
   data class RangeEstimate(
       val rangeKm: Double?,              // null when insufficient data
       val confidence: Double,
       val status: EstimateStatus,
       val efficiencyWhPerKm: Double?,
       val estimatedTimeMinutes: Double?,
       val dataQuality: DataQuality
   )
   
   enum class EstimateStatus {
       INSUFFICIENT_DATA,
       COLLECTING,
       VALID,
       CHARGING,
       LOW_CONFIDENCE,
       STALE
   }
   ```

8. ⏭️ **`LiIonDischargeCurve.kt`** - Voltage to energy conversion
   ```kotlin
   object LiIonDischargeCurve {
       fun voltageToEnergyPercent(packVoltage: Double, cellCount: Int): Double {
           // Non-linear discharge curve implementation
           // See RANGE_ESTIMATION_IMPLEMENTATION.md Section 6.1
       }
   }
   ```

9. ⏭️ **`RangeEstimator.kt`** - Interface
   ```kotlin
   interface RangeEstimator {
       fun estimate(trip: TripSnapshot): RangeEstimate?
   }
   ```

10. ⏭️ **`SimpleLinearEstimator.kt`** - First estimator
    ```kotlin
    class SimpleLinearEstimator(
        private val batteryCapacityWh: Double,
        private val cellCount: Int
    ) : RangeEstimator {
        override fun estimate(trip: TripSnapshot): RangeEstimate? {
            // Check insufficient data (10 min + 10 km)
            // Calculate energy consumed from compensatedVoltage
            // Calculate efficiency
            // Estimate range
        }
    }
    ```

11. ⏭️ **`RangeEstimationManager.kt`** - Core orchestrator (MOST IMPORTANT)
    ```kotlin
    class RangeEstimationManager(
        private val eucDataFlow: StateFlow<EucData>,
        private val settings: RangeEstimationSettings
    ) {
        // State
        private var previousCompensatedVoltage: Double? = null
        private val currentTrip = TripSnapshot(...)
        private val voltageCompensator = VoltageCompensator
        
        // Output
        private val _rangeEstimate = MutableStateFlow<RangeEstimate?>(null)
        val rangeEstimate: StateFlow<RangeEstimate?> = _rangeEstimate.asStateFlow()
        
        // Functions
        private fun onNewEucData(eucData: EucData)
        private fun handleConnectionGap(...)
        private fun interpolateGapSamples(...)
        private fun handleChargingEvent(...)
        private fun handleChargingEnded(...)
        fun resetTrip()
    }
    ```

12. ⏭️ **Unit Tests** - Test all algorithms
    - `VoltageCompensatorTest.kt`
    - `LiIonDischargeCurveTest.kt`
    - `SimpleLinearEstimatorTest.kt`
    - `RangeEstimationManagerTest.kt`

**Deliverable:** Working range estimation engine (no UI)  
**Testing:** Can create samples, calculate estimates, handle gaps/charging  
**Estimated Time:** 2 weeks

---

### Phase 2: Phone App Settings (Week 3)
**Goal:** User can configure battery capacity, cell count, algorithm

#### Files to Create/Update:

13. ⏭️ **`app/src/main/res/xml/preferences.xml`** - Add category:
    ```xml
    <PreferenceCategory
        android:title="@string/settings_category_range_estimation"
        app:iconSpaceReserved="false">
        
        <SwitchPreferenceCompat
            android:key="range_estimation_enabled"
            android:title="@string/settings_range_enabled"
            android:defaultValue="true" />
        
        <EditTextPreference
            android:key="battery_capacity_wh"
            android:title="@string/settings_battery_capacity"
            android:defaultValue="2000"
            android:inputType="number" />
        
        <ListPreference
            android:key="battery_cell_count"
            android:title="@string/settings_cell_count"
            android:defaultValue="20"
            android:entries="@array/cell_count_names"
            android:entryValues="@array/cell_count_values" />
        
        <ListPreference
            android:key="range_algorithm"
            android:title="@string/settings_range_algorithm"
            android:defaultValue="weighted_window"
            android:entries="@array/range_algorithm_names"
            android:entryValues="@array/range_algorithm_values" />
        
        <Preference
            android:key="reset_range_trip"
            android:title="@string/settings_reset_range_trip" />
    </PreferenceCategory>
    ```

14. ⏭️ **`app/src/main/res/values/strings.xml`** - Add strings:
    ```xml
    <!-- Range Estimation -->
    <string name="settings_category_range_estimation">Range Estimation</string>
    <string name="settings_range_enabled">Enable Range Estimation</string>
    <string name="settings_range_enabled_summary">Calculate remaining range based on battery and efficiency</string>
    <string name="settings_battery_capacity">Battery Capacity</string>
    <string name="settings_battery_capacity_summary">Battery capacity in Watt-hours (Wh)</string>
    <string name="settings_cell_count">Cell Count</string>
    <string name="settings_cell_count_summary">Number of cells in series (e.g., 20S)</string>
    <string name="settings_range_algorithm">Estimation Algorithm</string>
    <string name="settings_range_algorithm_summary">Algorithm for calculating range</string>
    <string name="settings_reset_range_trip">Reset Range Calculation</string>
    <string name="settings_reset_range_trip_summary">Clear trip data and start fresh</string>
    ```

15. ⏭️ **`app/src/main/res/values/arrays.xml`** - Add arrays:
    ```xml
    <!-- Cell Count -->
    <string-array name="cell_count_names">
        <item>16S (67.2V max)</item>
        <item>20S (84V max)</item>
        <item>24S (100.8V max)</item>
    </string-array>
    <string-array name="cell_count_values">
        <item>16</item>
        <item>20</item>
        <item>24</item>
    </string-array>
    
    <!-- Algorithms -->
    <string-array name="range_algorithm_names">
        <item>Simple Linear</item>
        <item>Weighted Window (Recommended)</item>
        <item>ML-Lite (Advanced)</item>
    </string-array>
    <string-array name="range_algorithm_values">
        <item>simple_linear</item>
        <item>weighted_window</item>
        <item>ml_lite</item>
    </string-array>
    ```

16. ⏭️ **Settings handler** - Handle reset button click
    - Create confirmation dialog
    - Call `RangeEstimationManager.resetTrip()`

**Deliverable:** Phone app settings working  
**Testing:** Can configure battery capacity, cell count, algorithm; reset works  
**Estimated Time:** 2-3 days

---

### Phase 3: Android Auto Settings Screens (Week 3)
**Goal:** Driver can change algorithm and reset trip from Android Auto

#### Files to Create:

17. ⏭️ **`RangeEstimationSettingsScreen.kt`** - Main settings screen
    ```kotlin
    class RangeEstimationSettingsScreen(
        carContext: CarContext,
        private val rangeEstimationManager: RangeEstimationManager
    ) : Screen(carContext) {
        
        override fun onGetTemplate(): Template {
            val listBuilder = ItemList.Builder()
            
            // Current estimate (read-only)
            val currentEstimate = rangeEstimationManager.rangeEstimate.value
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Current Range")
                    .addText("${currentEstimate?.rangeKm?.let { "%.1f km".format(it) } ?: "--"}")
                    .build()
            )
            
            // Algorithm selection
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Algorithm")
                    .addText(getCurrentAlgorithm().displayName)
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(AlgorithmSelectionScreen(carContext))
                    }
                    .build()
            )
            
            // Reset button
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Reset Range Calculation")
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(ResetRangeConfirmScreen(carContext, rangeEstimationManager))
                    }
                    .build()
            )
            
            return ListTemplate.Builder()
                .setTitle("Range Estimation")
                .setHeaderAction(Action.BACK)
                .setSingleList(listBuilder.build())
                .build()
        }
    }
    ```

18. ⏭️ **`AlgorithmSelectionScreen.kt`** - Algorithm picker
    ```kotlin
    class AlgorithmSelectionScreen(carContext: CarContext) : Screen(carContext) {
        override fun onGetTemplate(): Template {
            val listBuilder = ItemList.Builder()
            
            // List of algorithms
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Simple Linear")
                    .addText("Basic linear estimation")
                    .setOnClickListener {
                        setAlgorithm("simple_linear")
                        screenManager.pop()
                    }
                    .build()
            )
            
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Weighted Window")
                    .addText("Adaptive estimation (Recommended)")
                    .setOnClickListener {
                        setAlgorithm("weighted_window")
                        screenManager.pop()
                    }
                    .build()
            )
            
            return ListTemplate.Builder()
                .setTitle("Select Algorithm")
                .setHeaderAction(Action.BACK)
                .setSingleList(listBuilder.build())
                .build()
        }
    }
    ```

19. ⏭️ **`ResetRangeConfirmScreen.kt`** - Reset confirmation
    ```kotlin
    class ResetRangeConfirmScreen(
        carContext: CarContext,
        private val rangeEstimationManager: RangeEstimationManager
    ) : Screen(carContext) {
        
        override fun onGetTemplate(): Template {
            return MessageTemplate.Builder("Reset range calculation? This will clear all trip data.")
                .setTitle("Reset Range")
                .setHeaderAction(Action.BACK)
                .addAction(
                    Action.Builder()
                        .setTitle("Cancel")
                        .setOnClickListener { screenManager.pop() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("Reset")
                        .setOnClickListener {
                            rangeEstimationManager.resetTrip()
                            screenManager.popToRoot()
                        }
                        .build()
                )
                .build()
        }
    }
    ```

20. ⏭️ **Update `EucWorldScreen.kt`** - Add action to action strip:
    ```kotlin
    // In createConnectedTemplate():
    val actionStrip = ActionStrip.Builder()
        .addAction(
            Action.Builder()
                .setTitle("Trip Meters")
                .setOnClickListener {
                    screenManager.push(TripMeterScreen(carContext, tripMeterManager, currentOdometer))
                }
                .build()
        )
        .addAction(
            Action.Builder()
                .setTitle("Range Settings")
                .setOnClickListener {
                    screenManager.push(RangeEstimationSettingsScreen(carContext, rangeEstimationManager))
                }
                .build()
        )
        .build()
    ```

**Deliverable:** Android Auto settings screens working  
**Testing:** Can change algorithm and reset trip from Android Auto  
**Estimated Time:** 2-3 days

---

### Phase 4: Phone App UI Display (Week 4)
**Goal:** Show range estimate on phone main screen

#### Files to Create:

21. ⏭️ **`RangeEstimateCard.kt`** - Composable card
    ```kotlin
    @Composable
    fun RangeEstimateCard(estimate: RangeEstimate?) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Title
                Text("Range Estimate", style = MaterialTheme.typography.h6)
                
                // Main display
                when {
                    estimate == null || estimate.status == EstimateStatus.INSUFFICIENT_DATA -> {
                        Text("--", style = MaterialTheme.typography.h3)
                        estimate?.dataQuality?.let { quality ->
                            Text(
                                text = "Collecting data: ${quality.travelTimeMinutes} / 10 min, ${quality.travelDistanceKm} / 10 km",
                                style = MaterialTheme.typography.body2
                            )
                        }
                    }
                    
                    estimate.status == EstimateStatus.CHARGING -> {
                        Text("--", style = MaterialTheme.typography.h3)
                        Text("⚡ Charging in progress", style = MaterialTheme.typography.body2)
                    }
                    
                    else -> {
                        Text("%.1f km".format(estimate.rangeKm), style = MaterialTheme.typography.h3)
                        ConfidenceBar(confidence = estimate.confidence)
                    }
                }
                
                // Efficiency display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Efficiency", style = MaterialTheme.typography.caption)
                        Text(
                            text = estimate?.efficiencyWhPerKm?.let { "%.1f Wh/km".format(it) } ?: "--",
                            style = MaterialTheme.typography.body1
                        )
                    }
                    
                    Column {
                        Text("Confidence", style = MaterialTheme.typography.caption)
                        Text(
                            text = estimate?.confidence?.let { "${(it * 100).toInt()}%" } ?: "--",
                            style = MaterialTheme.typography.body1
                        )
                    }
                }
            }
        }
    }
    ```

22. ⏭️ **Update `MainActivity.kt`** - Add card to main screen
    - Collect `rangeEstimate` StateFlow
    - Pass to `RangeEstimateCard`
    - Position below battery display

**Deliverable:** Phone app displays range estimate  
**Testing:** See range estimate update in real-time; see `--` when insufficient data  
**Estimated Time:** 2 days

---

### Phase 5: Android Auto Display Integration (Week 4)
**Goal:** Show range estimate in Android Auto main screen

#### Files to Update:

23. ⏭️ **Update `EucWorldScreen.kt`** - Add range row:
    ```kotlin
    // In createConnectedTemplate():
    
    // After voltage row, add range row
    val rangeEstimate = rangeEstimationManager.rangeEstimate.value
    paneBuilder.addRow(
        Row.Builder()
            .setTitle("Range")
            .addText(
                rangeEstimate?.rangeKm?.let { "%.1f km".format(it) } ?: "--"
            )
            .build()
    )
    ```

24. ⏭️ **Update data broadcasting** (if needed)
    - Check if `AutoProxyReceiver` needs range estimate
    - Add range to bundle if necessary

**Deliverable:** Android Auto displays range estimate  
**Testing:** See range in Android Auto main screen  
**Estimated Time:** 1 day

---

### Phase 6: Integration & Testing (Week 5)
**Goal:** Real-world validation and bug fixes

#### Tasks:

25. ⏭️ **Integrate with `EucWorldService.kt`**
    - Initialize `RangeEstimationManager` in `onCreate()`
    - Pass `eucData` StateFlow to manager
    - Load settings from SharedPreferences

26. ⏭️ **Connection Gap Testing**
    - Simulate Bluetooth disconnect (turn off wheel)
    - Verify interpolation creates samples
    - Verify estimate remains stable

27. ⏭️ **Charging Detection Testing**
    - Plug in charger mid-ride
    - Verify charging detected
    - Verify new baseline created after unplug
    - Verify estimate resets (10 min + 10 km requirement)

28. ⏭️ **Insufficient Data Testing**
    - Start fresh trip
    - Verify `--` displayed
    - Verify progress shown (X / 10 min, Y / 10 km)
    - Ride until requirements met
    - Verify estimate appears

29. ⏭️ **Voltage Sag Testing**
    - Hard acceleration
    - Verify raw voltage drops
    - Verify compensated voltage smooth
    - Verify estimate doesn't drop

30. ⏭️ **Real-World Ride Testing**
    - Different riding styles (gentle, aggressive, mixed)
    - Long rides (>50 km)
    - Multiple charging stops
    - Different EUC models (if available)

**Deliverable:** Feature ready for beta release  
**Testing:** All edge cases handled correctly  
**Estimated Time:** 5-7 days

---

## File Structure Summary

```
app/src/main/java/com/a42r/eucosmandplugin/
├── range/
│   ├── model/
│   │   ├── BatterySample.kt             ✅ DONE
│   │   ├── TripSnapshot.kt              ⏭️ TODO
│   │   ├── TripSegment.kt               ⏭️ TODO
│   │   ├── ChargingEvent.kt             ⏭️ TODO
│   │   └── RangeEstimate.kt             ⏭️ TODO
│   │
│   ├── algorithm/
│   │   ├── VoltageCompensator.kt        ✅ DONE
│   │   ├── LiIonDischargeCurve.kt       ⏭️ TODO
│   │   ├── RangeEstimator.kt            ⏭️ TODO
│   │   ├── SimpleLinearEstimator.kt     ⏭️ TODO
│   │   └── WeightedWindowEstimator.kt   ⏭️ TODO
│   │
│   ├── util/
│   │   └── SampleValidator.kt           ✅ DONE (needs update)
│   │
│   ├── manager/
│   │   └── RangeEstimationManager.kt    ⏭️ TODO (PRIORITY)
│   │
│   └── ui/
│       └── RangeEstimateCard.kt         ⏭️ TODO
│
├── auto/
│   ├── EucWorldScreen.kt                 ⏭️ UPDATE
│   ├── RangeEstimationSettingsScreen.kt  ⏭️ TODO
│   ├── AlgorithmSelectionScreen.kt       ⏭️ TODO
│   └── ResetRangeConfirmScreen.kt        ⏭️ TODO
│
└── service/
    └── EucWorldService.kt                ⏭️ UPDATE

app/src/main/res/
├── xml/
│   └── preferences.xml                   ⏭️ UPDATE
├── values/
│   ├── strings.xml                       ⏭️ UPDATE
│   └── arrays.xml                        ⏭️ CREATE
```

---

## Timeline Summary

| Phase | Duration | Deliverable |
|-------|----------|-------------|
| **Phase 1** | 2 weeks | Core algorithm working (no UI) |
| **Phase 2** | 2-3 days | Phone app settings working |
| **Phase 3** | 2-3 days | Android Auto settings working |
| **Phase 4** | 2 days | Phone app UI displaying estimate |
| **Phase 5** | 1 day | Android Auto displaying estimate |
| **Phase 6** | 5-7 days | Real-world tested, ready for beta |
| **TOTAL** | ~5 weeks | Feature complete |

---

## Settings Summary

### Android Auto Settings (Driver-Safe):
- ✅ Current range estimate (read-only)
- ✅ Algorithm selection (Simple/Weighted/ML-Lite)
- ✅ Reset trip (with confirmation)

### Phone App Settings (Complete):
- ✅ Enable/disable feature
- ✅ Battery capacity (Wh)
- ✅ Cell count (16S/20S/24S)
- ✅ Algorithm selection
- ✅ Manual trip reset

### Shared/Synced Settings:
- Algorithm selection synced between Phone and Android Auto
- Trip reset works from both interfaces

---

## Next Step

**START WITH:** Phase 1, Step 4 - Create `TripSnapshot.kt`

Then proceed through the models, algorithms, and finally the core `RangeEstimationManager.kt`.

---

**Implementation plan complete and ready to execute!** ✅
