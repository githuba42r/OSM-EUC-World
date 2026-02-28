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
import com.google.gson.Gson

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
        
        // Estimation update intervals based on battery level
        private const val ESTIMATION_UPDATE_INTERVAL_HIGH_BATTERY_MS = 5 * 60 * 1000L // 5 minutes for top 50% battery
        private const val ESTIMATION_UPDATE_INTERVAL_LOW_BATTERY_MS = 1 * 60 * 1000L // 1 minute for bottom 50% battery
        private const val BATTERY_THRESHOLD_PERCENT = 50.0 // Threshold for switching update intervals
        
        // Settings keys
        private const val PREF_RANGE_ENABLED = "range_estimation_enabled"
        private const val PREF_WHEEL_CONFIG_MODE = "wheel_config_mode"
        private const val PREF_BATTERY_CAPACITY_WH = "battery_capacity_wh"
        private const val PREF_CELL_COUNT = "battery_cell_count"
        private const val PREF_ALGORITHM = "range_algorithm"
        
        // Trip state persistence keys
        private const val PREF_LAST_SAMPLE_TIMESTAMP = "range_last_sample_timestamp"
        private const val PREF_LAST_SAMPLE_VOLTAGE = "range_last_sample_voltage"
        private const val PREF_LAST_SAMPLE_COMPENSATED_VOLTAGE = "range_last_sample_compensated_voltage"
        private const val PREF_LAST_SAMPLE_BATTERY_PERCENT = "range_last_sample_battery_percent"
        private const val PREF_LAST_SAMPLE_DISTANCE = "range_last_sample_distance"
        private const val PREF_LAST_SAMPLE_SPEED = "range_last_sample_speed"
        private const val PREF_LAST_SAMPLE_POWER = "range_last_sample_power"
        private const val PREF_LAST_SAMPLE_CURRENT = "range_last_sample_current"
        private const val PREF_LAST_CONNECTION_STATE = "range_last_connection_state"
        private const val PREF_DISCONNECTION_START = "range_disconnection_start"
        
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
    
    // Estimation update throttling
    private var lastEstimationTimestamp: Long = 0L
    
    // Connection state tracking for gap detection
    private var lastConnectionState: Boolean = false
    private var lastDataTimestamp: Long = 0L
    private var disconnectionStartTimestamp: Long? = null
    
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
    
    // Historical calibration
    private lateinit var historicalDataManager: HistoricalDataManager
    private val milestones = mutableListOf<BatteryMilestone>()
    private var lastMilestonePercent: Int = 100
    
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
        
        // Initialize historical data manager
        historicalDataManager = HistoricalDataManager(context)
        
        // Initialize estimators with current settings
        initializeEstimators()
        
        // Restore last known state from persistence (for service restarts)
        restoreLastKnownState()
        
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
        lastEstimationTimestamp = 0L
        chargingState = ChargingState.NOT_CHARGING
        chargingSuspectedSample = null
        lastConnectionState = false
        lastDataTimestamp = 0L
        disconnectionStartTimestamp = null
        milestones.clear()
        lastMilestonePercent = 100
        _rangeEstimate.value = null
        
        // Clear persisted state
        prefs.edit().apply {
            remove(PREF_LAST_SAMPLE_TIMESTAMP)
            remove(PREF_LAST_SAMPLE_VOLTAGE)
            remove(PREF_LAST_SAMPLE_COMPENSATED_VOLTAGE)
            remove(PREF_LAST_SAMPLE_BATTERY_PERCENT)
            remove(PREF_LAST_SAMPLE_DISTANCE)
            remove(PREF_LAST_SAMPLE_SPEED)
            remove(PREF_LAST_SAMPLE_POWER)
            remove(PREF_LAST_SAMPLE_CURRENT)
            remove(PREF_LAST_CONNECTION_STATE)
            remove(PREF_DISCONNECTION_START)
            apply()
        }
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
        // Try Int first, then String (to avoid ClassCastException)
        val batteryCapacity = try {
            prefs.getInt(PREF_BATTERY_CAPACITY_WH, -1).takeIf { it != -1 }?.toDouble()
        } catch (e: ClassCastException) {
            prefs.getString(PREF_BATTERY_CAPACITY_WH, null)?.toDoubleOrNull()
        } ?: DEFAULT_BATTERY_CAPACITY
        
        // Read cell count (can be stored as String or Int in preferences)
        // Try Int first, then String (to avoid ClassCastException)
        val cellCount = try {
            prefs.getInt(PREF_CELL_COUNT, -1).takeIf { it != -1 }
        } catch (e: ClassCastException) {
            prefs.getString(PREF_CELL_COUNT, null)?.toIntOrNull()
        } ?: DEFAULT_CELL_COUNT
        
        simpleLinearEstimator = SimpleLinearEstimator(
            batteryCapacityWh = batteryCapacity,
            cellCount = cellCount,
            historicalDataManager = historicalDataManager
        )
        
        weightedWindowEstimator = WeightedWindowEstimator(
            batteryCapacityWh = batteryCapacity,
            cellCount = cellCount,
            windowMinutes = 30,  // Balanced preset
            weightDecayFactor = 0.5,
            historicalDataManager = historicalDataManager
        )
        
        Log.d(TAG, "Estimators initialized: battery=${batteryCapacity}Wh, cells=${cellCount}S")
    }
    
    /**
     * Process a single EucData sample from the stream.
     * 
     * Steps:
     * 1. Auto-detect wheel configuration if in 'auto' mode
     * 2. Detect wheel disconnection state
     * 3. Create BatterySample from EucData
     * 4. Detect and handle connection gaps
     * 5. Apply voltage compensation
     * 6. Detect and handle charging events
     * 7. Validate and flag sample
     * 8. Add to trip state
     * 9. Run estimation algorithm (throttled based on battery level)
     * 10. Emit RangeEstimate
     */
    private fun processSample(eucData: EucData) {
        val currentTimestamp = System.currentTimeMillis()
        
        // Step 1: Auto-detect wheel configuration if in 'auto' mode
        autoDetectWheelConfiguration(eucData)
        
        // Step 2: Check if wheel is disconnected (EUC.World active but wheel not connected)
        // In this case, we should not process samples but also not lose existing trip state
        if (!eucData.isConnected) {
            Log.d(TAG, "Wheel disconnected - pausing sample collection")
            
            // Track disconnection start time for interpolation when reconnected
            if (lastConnectionState) {
                // Just became disconnected
                disconnectionStartTimestamp = lastSample?.timestamp ?: currentTimestamp
                Log.d(TAG, "Wheel disconnection started at $disconnectionStartTimestamp")
            }
            
            lastConnectionState = false
            lastDataTimestamp = currentTimestamp
            
            // Emit current estimate with stale warning if disconnected for too long
            if (disconnectionStartTimestamp != null && 
                currentTimestamp - disconnectionStartTimestamp!! > 60000L) { // 1 minute
                val currentEstimate = _rangeEstimate.value
                if (currentEstimate != null && currentEstimate.status != EstimateStatus.STALE) {
                    _rangeEstimate.value = currentEstimate.copy(status = EstimateStatus.STALE)
                }
            }
            return
        }
        
        // Step 3: Wheel just reconnected - handle reconnection gap
        if (!lastConnectionState && lastSample != null && disconnectionStartTimestamp != null) {
            val gapDuration = currentTimestamp - disconnectionStartTimestamp!!
            Log.d(TAG, "Wheel reconnected after ${gapDuration}ms disconnection")
            handleConnectionGap(lastSample!!, eucData, gapDuration)
            disconnectionStartTimestamp = null
        }
        
        lastConnectionState = true
        lastDataTimestamp = currentTimestamp
        
        // Step 4: Detect connection gap (time-based, even when connected)
        if (lastSample != null) {
            val timeDelta = currentTimestamp - lastSample!!.timestamp
            if (timeDelta > CONNECTION_GAP_THRESHOLD_MS) {
                Log.d(TAG, "Time gap detected: ${timeDelta}ms between samples")
                handleConnectionGap(lastSample!!, eucData, timeDelta)
            }
        }
        
        // Step 5: Create BatterySample from EucData
        val rawSample = createSampleFromEucData(eucData, currentTimestamp)
        
        // Step 6: Apply voltage compensation
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
        
        // Step 7: Detect and handle charging
        handleChargingDetection(sample)
        
        // Step 8: Validate and flag sample
        val validatedSample = validateSample(sample)
        
        // Step 9: Add to trip state
        addSampleToTrip(validatedSample)
        
        // Step 9.5: Track battery milestones for historical calibration
        trackBatteryMilestone(validatedSample)
        
        // Step 10: Determine if we should run estimation based on battery level and time since last estimation
        val shouldRunEstimation = shouldRunEstimation(currentTimestamp, sample.batteryPercent)
        
        if (shouldRunEstimation) {
            // Run estimation algorithm
            val estimate = runEstimation()
            
            // Emit estimate
            _rangeEstimate.value = estimate
            
            // Update last estimation timestamp
            lastEstimationTimestamp = currentTimestamp
        }
        
        // Update state for next iteration
        lastSample = validatedSample
        
        // Save state for recovery after service restart
        saveLastKnownState()
    }
    
    /**
     * Determine if we should run estimation based on battery level and time since last estimation.
     * 
     * Rules:
     * - Top 50% battery: Update every 5 minutes
     * - Bottom 50% battery: Update every 1 minute
     * - Always run on first sample or when no estimate exists yet
     * - Always run when collecting initial data (before minimum requirements met)
     */
    private fun shouldRunEstimation(currentTimestamp: Long, batteryPercent: Double): Boolean {
        // Always run on first sample
        if (lastEstimationTimestamp == 0L) {
            return true
        }
        
        // Check if we have an estimate yet - if not, run frequently to provide progress updates
        val currentEstimate = _rangeEstimate.value
        if (currentEstimate == null || currentEstimate.status == EstimateStatus.INSUFFICIENT_DATA) {
            // Run every 10 seconds while collecting initial data to show progress
            val timeSinceLastEstimation = currentTimestamp - lastEstimationTimestamp
            return timeSinceLastEstimation >= 10000L
        }
        
        // Determine update interval based on battery level
        val updateInterval = if (batteryPercent >= BATTERY_THRESHOLD_PERCENT) {
            ESTIMATION_UPDATE_INTERVAL_HIGH_BATTERY_MS
        } else {
            ESTIMATION_UPDATE_INTERVAL_LOW_BATTERY_MS
        }
        
        // Check if enough time has passed since last estimation
        val timeSinceLastEstimation = currentTimestamp - lastEstimationTimestamp
        return timeSinceLastEstimation >= updateInterval
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
        // Try Int first, then String (to avoid ClassCastException)
        return try {
            prefs.getInt(PREF_CELL_COUNT, -1).takeIf { it != -1 }
        } catch (e: ClassCastException) {
            prefs.getString(PREF_CELL_COUNT, null)?.toIntOrNull()
        } ?: DEFAULT_CELL_COUNT
    }
    
    /**
     * Track battery milestones for historical calibration.
     * 
     * Records when battery crosses standard percentage thresholds (95%, 90%, 85%, ...)
     * with distance and efficiency data.
     */
    private fun trackBatteryMilestone(sample: BatterySample) {
        val currentPercent = sample.batteryPercent
        
        // Check if we crossed a milestone
        val milestoneCrossed = BatteryMilestone.getMilestoneCrossed(
            previousPercent = lastMilestonePercent.toDouble(),
            currentPercent = currentPercent
        )
        
        if (milestoneCrossed != null) {
            // Calculate stats for this milestone
            val baseline = tripSnapshot.currentBaselineSegment?.samples?.firstOrNull()
            if (baseline != null && sample.tripDistanceKm > baseline.tripDistanceKm) {
                val distanceFromStart = sample.tripDistanceKm - baseline.tripDistanceKm
                val timeFromStart = sample.timestamp - baseline.timestamp
                
                // Calculate average efficiency so far
                val energyConsumed = LiIonDischargeCurve.calculateEnergyConsumed(
                    baseline.compensatedVoltage,
                    sample.compensatedVoltage,
                    getCellCount()
                )
                
                val batteryCapacity = try {
                    prefs.getInt(PREF_BATTERY_CAPACITY_WH, -1).takeIf { it != -1 }?.toDouble()
                } catch (e: ClassCastException) {
                    prefs.getString(PREF_BATTERY_CAPACITY_WH, null)?.toDoubleOrNull()
                } ?: DEFAULT_BATTERY_CAPACITY
                
                val energyConsumedWh = (energyConsumed / 100.0) * batteryCapacity
                val avgEfficiency = if (distanceFromStart > 0) {
                    energyConsumedWh / distanceFromStart
                } else {
                    Double.NaN
                }
                
                if (!avgEfficiency.isNaN() && avgEfficiency > 0) {
                    val milestone = BatteryMilestone(
                        batteryPercent = milestoneCrossed,
                        voltage = sample.compensatedVoltage,
                        distanceKm = distanceFromStart,
                        timeMs = timeFromStart,
                        timestamp = sample.timestamp,
                        averageEfficiencyWhPerKm = avgEfficiency
                    )
                    
                    milestones.add(milestone)
                    lastMilestonePercent = milestoneCrossed
                    
                    Log.d(TAG, "Milestone: $milestoneCrossed% reached, distance=$distanceFromStart km, efficiency=$avgEfficiency Wh/km")
                    
                    // If we have a previous milestone, create a historical segment
                    if (milestones.size >= 2) {
                        val previousMilestone = milestones[milestones.size - 2]
                        createHistoricalSegment(previousMilestone, milestone, batteryCapacity)
                    }
                }
            }
        } else {
            // Update last milestone percent even if no crossing (for first sample)
            lastMilestonePercent = currentPercent.toInt()
        }
    }
    
    /**
     * Create and save a historical segment from two milestones.
     */
    private fun createHistoricalSegment(
        startMilestone: BatteryMilestone,
        endMilestone: BatteryMilestone,
        batteryCapacity: Double
    ) {
        val distanceKm = endMilestone.distanceKm - startMilestone.distanceKm
        val durationMs = endMilestone.timeMs - startMilestone.timeMs
        
        val energyConsumed = LiIonDischargeCurve.calculateEnergyConsumed(
            startMilestone.voltage,
            endMilestone.voltage,
            getCellCount()
        )
        
        val energyConsumedWh = (energyConsumed / 100.0) * batteryCapacity
        val efficiency = if (distanceKm > 0) {
            energyConsumedWh / distanceKm
        } else {
            return
        }
        
        val segment = HistoricalSegment(
            startPercent = startMilestone.batteryPercent,
            endPercent = endMilestone.batteryPercent,
            startVoltage = startMilestone.voltage,
            endVoltage = endMilestone.voltage,
            distanceKm = distanceKm,
            durationMs = durationMs,
            efficiencyWhPerKm = efficiency,
            timestamp = endMilestone.timestamp,
            wheelModel = lastDetectedWheelModel ?: "Unknown",
            batteryCapacityWh = batteryCapacity
        )
        
        historicalDataManager.addSegment(segment)
    }
    
    /**
     * Save critical trip state to SharedPreferences to survive service restarts.
     * Called after each sample is processed.
     */
    private fun saveLastKnownState() {
        val last = lastSample ?: return
        
        prefs.edit().apply {
            putLong(PREF_LAST_SAMPLE_TIMESTAMP, last.timestamp)
            putFloat(PREF_LAST_SAMPLE_VOLTAGE, last.voltage.toFloat())
            putFloat(PREF_LAST_SAMPLE_COMPENSATED_VOLTAGE, last.compensatedVoltage.toFloat())
            putFloat(PREF_LAST_SAMPLE_BATTERY_PERCENT, last.batteryPercent.toFloat())
            putFloat(PREF_LAST_SAMPLE_DISTANCE, last.tripDistanceKm.toFloat())
            putFloat(PREF_LAST_SAMPLE_SPEED, last.speedKmh.toFloat())
            putFloat(PREF_LAST_SAMPLE_POWER, last.powerWatts.toFloat())
            putFloat(PREF_LAST_SAMPLE_CURRENT, last.currentAmps.toFloat())
            putBoolean(PREF_LAST_CONNECTION_STATE, lastConnectionState)
            disconnectionStartTimestamp?.let { 
                putLong(PREF_DISCONNECTION_START, it)
            } ?: remove(PREF_DISCONNECTION_START)
            apply()
        }
        
        Log.v(TAG, "Saved last known state: timestamp=${last.timestamp}, voltage=${last.voltage}V, distance=${last.tripDistanceKm}km")
    }
    
    /**
     * Restore critical trip state from SharedPreferences after service restart.
     * Only restores if the last sample was recent (< 5 minutes).
     */
    private fun restoreLastKnownState() {
        val lastTimestamp = prefs.getLong(PREF_LAST_SAMPLE_TIMESTAMP, 0L)
        
        // Only restore if we have a recent sample (< 5 minutes old)
        val currentTime = System.currentTimeMillis()
        val timeSinceLastSample = currentTime - lastTimestamp
        
        if (lastTimestamp == 0L || timeSinceLastSample > 5 * 60 * 1000L) {
            Log.d(TAG, "No recent state to restore (age=${timeSinceLastSample}ms)")
            return
        }
        
        try {
            // Restore last sample
            lastSample = BatterySample(
                timestamp = lastTimestamp,
                voltage = prefs.getFloat(PREF_LAST_SAMPLE_VOLTAGE, 0f).toDouble(),
                compensatedVoltage = prefs.getFloat(PREF_LAST_SAMPLE_COMPENSATED_VOLTAGE, 0f).toDouble(),
                batteryPercent = prefs.getFloat(PREF_LAST_SAMPLE_BATTERY_PERCENT, 0f).toDouble(),
                tripDistanceKm = prefs.getFloat(PREF_LAST_SAMPLE_DISTANCE, 0f).toDouble(),
                speedKmh = prefs.getFloat(PREF_LAST_SAMPLE_SPEED, 0f).toDouble(),
                powerWatts = prefs.getFloat(PREF_LAST_SAMPLE_POWER, 0f).toDouble(),
                currentAmps = prefs.getFloat(PREF_LAST_SAMPLE_CURRENT, 0f).toDouble()
            )
            
            previousCompensatedVoltage = lastSample?.compensatedVoltage
            lastConnectionState = prefs.getBoolean(PREF_LAST_CONNECTION_STATE, false)
            
            val disconnectStart = prefs.getLong(PREF_DISCONNECTION_START, -1L)
            if (disconnectStart > 0L) {
                disconnectionStartTimestamp = disconnectStart
            }
            
            lastDataTimestamp = currentTime
            
            Log.d(TAG, "Restored last known state: timestamp=$lastTimestamp (${timeSinceLastSample}ms ago), voltage=${lastSample?.voltage}V, connected=$lastConnectionState")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore state: ${e.message}")
            lastSample = null
            previousCompensatedVoltage = null
        }
    }
}
