package com.a42r.eucosmandplugin.range.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.range.algorithm.*
import com.a42r.eucosmandplugin.range.database.WheelDatabase
import com.a42r.eucosmandplugin.range.model.*
import com.a42r.eucosmandplugin.range.util.SampleValidator

/**
 * Core orchestrator for range estimation feature.
 * 
 * Responsibilities:
 * - Observes EucData stream from EucWorldService
 * - Creates BatterySample every 500ms
 * - Applies voltage compensation for sag
 * - Detects and handles connection gaps (linear interpolation)
 * - Detects and handles charging events (new baseline)
 * - Validates samples and flags anomalies
 * - Maintains trip state (samples, segments, charging events)
 * - Calls selected estimation algorithm
 * - Emits RangeEstimate StateFlow for UI
 * 
 * Architecture:
 * - Input: EucData StateFlow from EucWorldService (500ms polling)
 * - Output: RangeEstimate StateFlow for UI consumption
 * - State: TripSnapshot (samples, segments, charging events)
 * 
 * Edge Cases Handled:
 * 1. Connection Gaps: Linear interpolation (never auto-reset)
 * 2. Charging Events: Create new baseline, continue trip
 * 3. Voltage Sag: Power-weighted smoothing compensation
 * 4. Insufficient Data: Return status with progress metrics
 */
class RangeEstimationManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "RangeEstimationManager"
        
        // Sample collection interval (matches EucWorldService polling)
        private const val SAMPLE_INTERVAL_MS = 500L
        
        // Connection gap detection threshold
        private const val CONNECTION_GAP_THRESHOLD_MS = 2000L
        
        // Interpolation interval during gaps
        private const val INTERPOLATION_INTERVAL_MS = 5000L
        
        // Charging detection thresholds
        private const val CHARGING_VOLTAGE_INCREASE_THRESHOLD = 0.5 // V
        private const val CHARGING_BATTERY_INCREASE_THRESHOLD = 1.0 // %
        private const val CHARGING_DISTANCE_CHANGE_THRESHOLD = 0.01 // km
        
        // Settings keys
        private const val PREF_RANGE_ENABLED = "range_estimation_enabled"
        private const val PREF_WHEEL_CONFIG_MODE = "wheel_config_mode"
        private const val PREF_BATTERY_CAPACITY_WH = "battery_capacity_wh"
        private const val PREF_CELL_COUNT = "battery_cell_count"
        private const val PREF_ALGORITHM = "range_algorithm"
        
        // Default values
        private const val DEFAULT_BATTERY_CAPACITY = 2000.0 // Wh
        private const val DEFAULT_CELL_COUNT = 20 // 20S pack (84V nominal)
        private const val DEFAULT_ALGORITHM = "weighted_window"
    }
    
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    // Trip state
    private var tripSnapshot = TripSnapshot.createInitial()
    private var lastSample: BatterySample? = null
    private var previousCompensatedVoltage: Double? = null
    
    // Charging detection state machine
    private enum class ChargingState {
        NOT_CHARGING,
        CHARGING_SUSPECTED,  // First sample showing increase
        CHARGING_CONFIRMED   // Multiple samples confirming
    }
    private var chargingState = ChargingState.NOT_CHARGING
    private var chargingSuspectedSample: BatterySample? = null
    
    // Auto-detection state
    private var lastDetectedWheelModel: String? = null
    
    // Estimator instances
    private lateinit var simpleLinearEstimator: SimpleLinearEstimator
    private lateinit var weightedWindowEstimator: WeightedWindowEstimator
    
    // Output: Range estimate StateFlow
    private val _rangeEstimate = MutableStateFlow<RangeEstimate?>(null)
    val rangeEstimate: StateFlow<RangeEstimate?> = _rangeEstimate.asStateFlow()
    
    // Collection job
    private var collectionJob: Job? = null
    
    /**
     * Start observing EucData stream and producing range estimates.
     * 
     * @param eucDataFlow StateFlow of EucData from EucWorldService
     */
    fun start(eucDataFlow: StateFlow<EucData?>) {
        Log.d(TAG, "Starting RangeEstimationManager")
        
        // Initialize estimators with current settings
        initializeEstimators()
        
        // Stop any existing collection
        stop()
        
        // Start collecting EucData and producing estimates
        collectionJob = scope.launch {
            eucDataFlow
                .filterNotNull()
                .collect { eucData ->
                    if (isEnabled()) {
                        processSample(eucData)
                    }
                }
        }
    }
    
    /**
     * Stop range estimation.
     */
    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        _rangeEstimate.value = null
    }
    
    /**
     * Reset trip (manual reset from user).
     * Creates new trip with fresh baseline.
     */
    fun resetTrip() {
        Log.d(TAG, "Manual trip reset")
        tripSnapshot = TripSnapshot.createInitial()
        lastSample = null
        previousCompensatedVoltage = null
        chargingState = ChargingState.NOT_CHARGING
        chargingSuspectedSample = null
        _rangeEstimate.value = null
    }
    
    /**
     * Check if range estimation is enabled in settings.
     */
    private fun isEnabled(): Boolean {
        return prefs.getBoolean(PREF_RANGE_ENABLED, true)
    }
    
    /**
     * Initialize estimators with current settings.
     */
    private fun initializeEstimators() {
        // Read battery capacity (can be stored as String or Int in preferences)
        val batteryCapacity = prefs.getString(PREF_BATTERY_CAPACITY_WH, null)?.toDoubleOrNull()
            ?: prefs.getInt(PREF_BATTERY_CAPACITY_WH, DEFAULT_BATTERY_CAPACITY.toInt()).toDouble()
        
        // Read cell count (can be stored as String or Int in preferences)
        val cellCount = prefs.getString(PREF_CELL_COUNT, null)?.toIntOrNull()
            ?: prefs.getInt(PREF_CELL_COUNT, DEFAULT_CELL_COUNT)
        
        simpleLinearEstimator = SimpleLinearEstimator(
            batteryCapacityWh = batteryCapacity,
            cellCount = cellCount
        )
        
        weightedWindowEstimator = WeightedWindowEstimator(
            batteryCapacityWh = batteryCapacity,
            cellCount = cellCount,
            windowMinutes = 30,  // Balanced preset
            weightDecayFactor = 0.5
        )
        
        Log.d(TAG, "Estimators initialized: battery=${batteryCapacity}Wh, cells=${cellCount}S")
    }
    
    /**
     * Process a single EucData sample from the stream.
     * 
     * Steps:
     * 1. Auto-detect wheel configuration if in 'auto' mode
     * 2. Create BatterySample from EucData
     * 3. Detect and handle connection gaps
     * 4. Apply voltage compensation
     * 5. Detect and handle charging events
     * 6. Validate and flag sample
     * 7. Add to trip state
     * 8. Run estimation algorithm
     * 9. Emit RangeEstimate
     */
    private fun processSample(eucData: EucData) {
        val currentTimestamp = System.currentTimeMillis()
        
        // Step 1: Auto-detect wheel configuration if in 'auto' mode
        autoDetectWheelConfiguration(eucData)
        
        // Step 2: Detect connection gap
        if (lastSample != null) {
            val timeDelta = currentTimestamp - lastSample!!.timestamp
            if (timeDelta > CONNECTION_GAP_THRESHOLD_MS) {
                handleConnectionGap(lastSample!!, eucData, timeDelta)
            }
        }
        
        // Step 3: Create BatterySample from EucData
        val rawSample = createSampleFromEucData(eucData, currentTimestamp)
        
        // Step 4: Apply voltage compensation
        val compensatedVoltage = if (previousCompensatedVoltage == null) {
            // First sample: initialize compensated voltage
            VoltageCompensator.initializeCompensatedVoltage(rawSample.voltage)
        } else {
            // Apply power-weighted smoothing
            VoltageCompensator.calculateCompensatedVoltage(
                currentVoltage = rawSample.voltage,
                currentPower = rawSample.powerWatts,
                previousCompensatedVoltage = previousCompensatedVoltage!!,
                config = VoltageCompensator.Config(alpha = 0.3)
            )
        }
        previousCompensatedVoltage = compensatedVoltage
        
        val sample = rawSample.copy(compensatedVoltage = compensatedVoltage)
        
        // Step 5: Detect and handle charging
        handleChargingDetection(sample)
        
        // Step 6: Validate and flag sample
        val validatedSample = validateSample(sample)
        
        // Step 7: Add to trip state
        addSampleToTrip(validatedSample)
        
        // Step 8: Run estimation algorithm
        val estimate = runEstimation()
        
        // Step 9: Emit estimate
        _rangeEstimate.value = estimate
        
        // Update state for next iteration
        lastSample = validatedSample
    }
    
    /**
     * Auto-detect wheel configuration from EucData when in 'auto' mode.
     * 
     * This method:
     * 1. Checks if wheel_config_mode is 'auto'
     * 2. Checks if wheel model has changed
     * 3. Looks up wheel in database
     * 4. Saves battery capacity and cell count to SharedPreferences
     * 5. Reinitializes estimators with new configuration
     */
    private fun autoDetectWheelConfiguration(eucData: EucData) {
        val configMode = prefs.getString(PREF_WHEEL_CONFIG_MODE, "auto") ?: "auto"
        
        // Only auto-detect if in 'auto' mode
        if (configMode != "auto") {
            return
        }
        
        // Check if wheel model is available and has changed
        val wheelModel = eucData.wheelModel
        if (wheelModel.isBlank() || wheelModel == lastDetectedWheelModel) {
            return
        }
        
        // Try to find wheel in database
        val wheelSpec = WheelDatabase.findWheelSpec(wheelModel)
        
        if (wheelSpec != null) {
            Log.d(TAG, "Auto-detected wheel: ${wheelSpec.displayName} - ${wheelSpec.batteryConfig.capacityWh}Wh, ${wheelSpec.batteryConfig.cellCount}S")
            
            // Save configuration to SharedPreferences
            prefs.edit().apply {
                putString("selected_wheel_model", wheelSpec.displayName)
                putInt(PREF_BATTERY_CAPACITY_WH, wheelSpec.batteryConfig.capacityWh.toInt())
                putInt(PREF_CELL_COUNT, wheelSpec.batteryConfig.cellCount)
                apply()
            }
            
            // Reinitialize estimators with new configuration
            initializeEstimators()
            
            // Update last detected model
            lastDetectedWheelModel = wheelModel
        } else {
            Log.w(TAG, "Wheel model '$wheelModel' not found in database")
            lastDetectedWheelModel = wheelModel
        }
    }
    
    /**
     * Handle connection gap by creating interpolated samples.
     * 
     * Strategy: Linear interpolation between last known sample and current sample.
     * Creates samples every 5 seconds during gap.
     * Flags with INTERPOLATED but includes in estimation.
     */
    private fun handleConnectionGap(
        lastSample: BatterySample,
        currentEucData: EucData,
        gapDurationMs: Long
    ) {
        Log.d(TAG, "Connection gap detected: ${gapDurationMs}ms")
        
        // Create CONNECTION_GAP segment
        val gapSegment = TripSegment(
            type = SegmentType.CONNECTION_GAP,
            startTimestamp = lastSample.timestamp,
            samples = mutableListOf()
        )
        
        // Calculate number of interpolated samples (every 5 seconds)
        val numInterpolatedSamples = (gapDurationMs / INTERPOLATION_INTERVAL_MS).toInt()
        
        // Only interpolate if gap is reasonable (< 1 hour)
        if (numInterpolatedSamples > 0 && numInterpolatedSamples < 720) {
            val currentSample = createSampleFromEucData(currentEucData, System.currentTimeMillis())
            
            for (i in 1..numInterpolatedSamples) {
                val progress = i.toDouble() / (numInterpolatedSamples + 1)
                val interpolatedTimestamp = lastSample.timestamp + (gapDurationMs * progress).toLong()
                
                // Linear interpolation of values
                val interpolatedSample = BatterySample(
                    timestamp = interpolatedTimestamp,
                    voltage = lastSample.voltage + (currentSample.voltage - lastSample.voltage) * progress,
                    compensatedVoltage = lastSample.compensatedVoltage + (currentSample.compensatedVoltage - lastSample.compensatedVoltage) * progress,
                    batteryPercent = lastSample.batteryPercent + (currentSample.batteryPercent - lastSample.batteryPercent) * progress,
                    tripDistanceKm = lastSample.tripDistanceKm + (currentSample.tripDistanceKm - lastSample.tripDistanceKm) * progress,
                    speedKmh = lastSample.speedKmh + (currentSample.speedKmh - lastSample.speedKmh) * progress,
                    powerWatts = lastSample.powerWatts + (currentSample.powerWatts - lastSample.powerWatts) * progress,
                    currentAmps = lastSample.currentAmps + (currentSample.currentAmps - lastSample.currentAmps) * progress,
                    flags = setOf(SampleFlag.INTERPOLATED)
                )
                
                gapSegment.samples.add(interpolatedSample)
            }
            
            Log.d(TAG, "Created ${gapSegment.samples.size} interpolated samples")
        }
        
        // Add gap segment to trip
        tripSnapshot = tripSnapshot.copy(
            segments = tripSnapshot.segments + gapSegment
        )
    }
    
    /**
     * Detect charging events using state machine.
     * 
     * State machine:
     * 1. NOT_CHARGING: Normal riding
     * 2. CHARGING_SUSPECTED: First sample with voltage/battery increase + no distance change
     * 3. CHARGING_CONFIRMED: Multiple consecutive samples confirming charging
     * 
     * When charging confirmed:
     * - Create ChargingEvent
     * - Create CHARGING segment
     * - When charging ends: create new NORMAL_RIDING baseline segment
     */
    private fun handleChargingDetection(sample: BatterySample) {
        val last = lastSample ?: return
        
        // Check for charging indicators
        val voltageIncrease = sample.voltage - last.voltage
        val batteryIncrease = sample.batteryPercent - last.batteryPercent
        val distanceChange = sample.tripDistanceKm - last.tripDistanceKm
        
        val chargingIndicators = (voltageIncrease > CHARGING_VOLTAGE_INCREASE_THRESHOLD ||
                                  batteryIncrease > CHARGING_BATTERY_INCREASE_THRESHOLD) &&
                                  distanceChange < CHARGING_DISTANCE_CHANGE_THRESHOLD
        
        // Check if sample has charging flag
        val eucDataCharging = sample.flags.contains(SampleFlag.CHARGING_DETECTED)
        
        when (chargingState) {
            ChargingState.NOT_CHARGING -> {
                if (chargingIndicators || eucDataCharging) {
                    chargingState = ChargingState.CHARGING_SUSPECTED
                    chargingSuspectedSample = sample
                    Log.d(TAG, "Charging suspected")
                }
            }
            
            ChargingState.CHARGING_SUSPECTED -> {
                if (chargingIndicators || eucDataCharging) {
                    // Confirm charging
                    chargingState = ChargingState.CHARGING_CONFIRMED
                    Log.d(TAG, "Charging confirmed")
                    
                    // Create charging event
                    val chargingEvent = ChargingEvent(
                        startTimestamp = chargingSuspectedSample!!.timestamp,
                        voltageBeforeCharging = last.voltage,
                        batteryPercentBefore = last.batteryPercent
                    )
                    
                    // Create CHARGING segment
                    val chargingSegment = TripSegment(
                        type = SegmentType.CHARGING,
                        startTimestamp = chargingSuspectedSample!!.timestamp,
                        samples = mutableListOf(chargingSuspectedSample!!, sample)
                    )
                    
                    tripSnapshot = tripSnapshot.copy(
                        segments = tripSnapshot.segments + chargingSegment,
                        chargingEvents = tripSnapshot.chargingEvents + chargingEvent,
                        isCurrentlyCharging = true
                    )
                } else {
                    // False alarm
                    chargingState = ChargingState.NOT_CHARGING
                    chargingSuspectedSample = null
                }
            }
            
            ChargingState.CHARGING_CONFIRMED -> {
                if (!chargingIndicators && !eucDataCharging) {
                    // Charging ended
                    Log.d(TAG, "Charging ended - creating new baseline")
                    
                    // Update last charging event
                    val lastChargingEvent = tripSnapshot.chargingEvents.lastOrNull()
                    if (lastChargingEvent != null) {
                        val updatedEvent = lastChargingEvent.copy(
                            endTimestamp = sample.timestamp,
                            voltageAfterCharging = sample.voltage,
                            batteryPercentAfter = sample.batteryPercent
                        )
                        tripSnapshot = tripSnapshot.copy(
                            chargingEvents = tripSnapshot.chargingEvents.dropLast(1) + updatedEvent
                        )
                    }
                    
                    // Create new NORMAL_RIDING baseline segment
                    val newBaselineSegment = TripSegment(
                        type = SegmentType.NORMAL_RIDING,
                        startTimestamp = sample.timestamp,
                        samples = mutableListOf(),
                        isBaselineSegment = true,
                        baselineReason = "Post-charging (${sample.timestamp})"
                    )
                    
                    tripSnapshot = tripSnapshot.copy(
                        segments = tripSnapshot.segments + newBaselineSegment,
                        isCurrentlyCharging = false
                    )
                    
                    // Reset voltage compensation baseline
                    previousCompensatedVoltage = null
                    
                    chargingState = ChargingState.NOT_CHARGING
                    chargingSuspectedSample = null
                }
            }
        }
    }
    
    /**
     * Validate sample and apply flags.
     */
    private fun validateSample(sample: BatterySample): BatterySample {
        val flags = mutableSetOf<SampleFlag>()
        
        // Add existing flags
        flags.addAll(sample.flags)
        
        // Validate with SampleValidator
        val last = lastSample
        if (last != null) {
            // Check for voltage anomaly
            if (SampleValidator.detectVoltageAnomaly(sample, getCellCount())) {
                flags.add(SampleFlag.VOLTAGE_ANOMALY)
            }
            
            // Check for distance anomaly
            if (SampleValidator.detectDistanceAnomaly(sample, last)) {
                flags.add(SampleFlag.DISTANCE_ANOMALY)
            }
            
            // Check for speed anomaly
            if (SampleValidator.detectSpeedAnomaly(sample, last)) {
                flags.add(SampleFlag.SPEED_ANOMALY)
            }
        }
        
        return sample.copy(flags = flags)
    }
    
    /**
     * Add sample to trip state.
     */
    private fun addSampleToTrip(sample: BatterySample) {
        // Get current active segment
        val activeSegment = tripSnapshot.segments.lastOrNull()
        
        if (activeSegment == null) {
            // First sample - create initial baseline segment
            val initialSegment = TripSegment(
                type = SegmentType.NORMAL_RIDING,
                startTimestamp = sample.timestamp,
                samples = mutableListOf(sample),
                isBaselineSegment = true,
                baselineReason = "Trip start (${sample.timestamp})"
            )
            tripSnapshot = tripSnapshot.copy(segments = listOf(initialSegment))
        } else {
            // Add to active segment
            activeSegment.samples.add(sample)
        }
        
        // Update trip samples list
        tripSnapshot = tripSnapshot.copy(
            samples = tripSnapshot.samples + sample
        )
    }
    
    /**
     * Run estimation algorithm and return RangeEstimate.
     */
    private fun runEstimation(): RangeEstimate? {
        val algorithm = prefs.getString(PREF_ALGORITHM, DEFAULT_ALGORITHM) ?: DEFAULT_ALGORITHM
        
        val estimator = when (algorithm) {
            "simple_linear" -> simpleLinearEstimator
            "weighted_window" -> weightedWindowEstimator
            else -> weightedWindowEstimator  // Default to weighted window
        }
        
        return estimator.estimate(tripSnapshot)
    }
    
    /**
     * Create BatterySample from EucData.
     */
    private fun createSampleFromEucData(eucData: EucData, timestamp: Long): BatterySample {
        val flags = mutableSetOf<SampleFlag>()
        
        // Add charging flag if EucData indicates charging
        if (eucData.isCharging) {
            flags.add(SampleFlag.CHARGING_DETECTED)
        }
        
        return BatterySample(
            timestamp = timestamp,
            voltage = eucData.voltage,
            compensatedVoltage = eucData.voltage,  // Will be updated by compensation
            batteryPercent = eucData.batteryPercentage.toDouble(),
            tripDistanceKm = eucData.totalDistance,
            speedKmh = eucData.speed,
            powerWatts = eucData.power,
            currentAmps = eucData.current,
            temperatureCelsius = eucData.temperature,
            flags = flags
        )
    }
    
    /**
     * Get cell count from settings.
     */
    private fun getCellCount(): Int {
        // Can be stored as String or Int in preferences
        return prefs.getString(PREF_CELL_COUNT, null)?.toIntOrNull()
            ?: prefs.getInt(PREF_CELL_COUNT, DEFAULT_CELL_COUNT)
    }
}
