package com.a42r.eucosmandplugin.range.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.a42r.eucosmandplugin.BuildConfig
import com.a42r.eucosmandplugin.ai.data.RiderProfileDatabase
import com.a42r.eucosmandplugin.ai.data.TokenManager
import com.a42r.eucosmandplugin.ai.model.AIRangeEstimate
import com.a42r.eucosmandplugin.ai.service.RangeEstimationAIService
import com.a42r.eucosmandplugin.ai.service.RiderProfileBuilder
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.range.algorithm.*
import com.a42r.eucosmandplugin.range.database.WheelDatabase
import com.a42r.eucosmandplugin.range.model.*
import com.a42r.eucosmandplugin.range.util.SampleValidator
import com.a42r.eucosmandplugin.range.util.DataCaptureLogger
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
        private const val CHARGING_BATTERY_INCREASE_THRESHOLD = 5.0 // % - increased from 1.0 to avoid false positives from regen braking
        private const val CHARGING_DISTANCE_CHANGE_THRESHOLD = 0.01 // km
        private const val CHARGING_SPEED_THRESHOLD = 0.5 // km/h - must be nearly stationary (not just < 1.0)
        
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
    private var isWheelConnected: Boolean = false
    
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
    // `lastDetectedWheelModelRaw` holds the raw identifier most recently received
    // from the wheel (used only to short-circuit repeated detection passes).
    // `lastDetectedWheelModel` holds the canonical displayName from WheelDatabase
    // and is the value used when keying rider profile entries so that the writer
    // and the reader (RangeSettingsActivity) look at the same Room row.
    private var lastDetectedWheelModelRaw: String? = null
    private var lastDetectedWheelModel: String? = null

    // Cached practical km-per-% from the rider profile (refreshed when the
    // profile is updated). Zero/negative means no profile data yet → consumers
    // fall back to the algorithmic baseline.
    @Volatile private var cachedPracticalKmPerPct: Double = 0.0
    
    // Historical calibration
    private lateinit var historicalDataManager: HistoricalDataManager
    private val milestones = mutableListOf<BatteryMilestone>()
    private var lastMilestonePercent: Int = 100
    
    // Data capture logger for developer mode
    private lateinit var dataCaptureLogger: DataCaptureLogger
    
    // Estimator instances
    private lateinit var simpleLinearEstimator: SimpleLinearEstimator
    private lateinit var weightedWindowEstimator: WeightedWindowEstimator
    
    // AI service for enhanced estimates
    private lateinit var rangeEstimationAIService: RangeEstimationAIService
    private lateinit var tokenManager: TokenManager
    
    // Output: Range estimate StateFlow (now returns AIRangeEstimate)
    private val _rangeEstimate = MutableStateFlow<AIRangeEstimate?>(null)
    val rangeEstimate: StateFlow<AIRangeEstimate?> = _rangeEstimate.asStateFlow()
    
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
        
        // Initialize data capture logger
        dataCaptureLogger = DataCaptureLogger(context)
        
        // Initialize AI service and token manager
        rangeEstimationAIService = RangeEstimationAIService(context)
        tokenManager = TokenManager(context)
        
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

        // Bootstrap rider profile from any previously-captured trip logs that
        // were never processed (e.g. because profile learning historically only
        // ran on manual trip reset). Idempotent: already-processed files are
        // tracked via shared prefs.
        replayUnprocessedTripLogs()

        // Load practical km/% cache from whatever profile is currently stored
        // (refreshed again after replay completes).
        refreshPracticalKmPerPct()
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
     * Also triggers a new log capture if logging is enabled AND wheel is connected.
     * 
     * BEFORE resetting, processes completed trip for AI rider profile learning.
     */
    fun resetTrip() {
        Log.d(TAG, "Manual trip reset")

        val loggingEnabled = dataCaptureLogger.isLoggingEnabled()

        // Process completed trip for rider profile (AI learning).
        // When logging is enabled the closed log file is authoritative — we let
        // replayUnprocessedTripLogs() pick it up after closeCurrentLog() below,
        // so we avoid processing the same trip from both memory and disk.
        if (!loggingEnabled) {
            processCompletedTripForProfile()
        }

        // Close current log and start new one (if logging enabled AND wheel is connected)
        if (loggingEnabled) {
            dataCaptureLogger.closeCurrentLog()

            // The just-closed log file now represents a completed trip — replay
            // it (and any other unprocessed logs) into the rider profile.
            replayUnprocessedTripLogs()

            if (isWheelConnected) {
                dataCaptureLogger.startNewLog()
                dataCaptureLogger.logEvent("trip_reset", mapOf(
                    "reason" to "manual_reset",
                    "previousSampleCount" to tripSnapshot.samples.size
                ))
                Log.d(TAG, "Started new log capture for trip")
            } else {
                Log.d(TAG, "Wheel not connected - logging will start when wheel connects and data is received")
            }
        }
        
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
     * Get battery capacity from settings (for AI profile builder)
     */
    private fun getBatteryCapacity(): Double {
        return try {
            prefs.getInt(PREF_BATTERY_CAPACITY_WH, -1).takeIf { it != -1 }?.toDouble()
        } catch (e: ClassCastException) {
            prefs.getString(PREF_BATTERY_CAPACITY_WH, null)?.toDoubleOrNull()
        } ?: DEFAULT_BATTERY_CAPACITY
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
        
        // Read developer mode sampling parameters
        val minTimeMinutes = prefs.getFloat("dev_min_time_minutes", 10f).toDouble()
        val minDistanceKm = prefs.getFloat("dev_min_distance_km", 10f).toDouble()
        
        simpleLinearEstimator = SimpleLinearEstimator(
            batteryCapacityWh = batteryCapacity,
            cellCount = cellCount,
            historicalDataManager = historicalDataManager,
            minTimeMinutes = minTimeMinutes,
            minDistanceKm = minDistanceKm
        )
        
        weightedWindowEstimator = WeightedWindowEstimator(
            batteryCapacityWh = batteryCapacity,
            cellCount = cellCount,
            windowMinutes = 30,  // Balanced preset
            weightDecayFactor = 0.5,
            historicalDataManager = historicalDataManager,
            minTimeMinutes = minTimeMinutes,
            minDistanceKm = minDistanceKm
        )
        
        Log.d(TAG, "Estimators initialized: battery=${batteryCapacity}Wh, cells=${cellCount}S, minTime=${minTimeMinutes}min, minDist=${minDistanceKm}km")
    }
    
    /**
     * Update sampling parameters for active estimators.
     * Called when developer changes parameters in Developer Settings.
     */
    fun updateSamplingParameters() {
        val minTimeMinutes = prefs.getFloat("dev_min_time_minutes", 10f).toDouble()
        val minDistanceKm = prefs.getFloat("dev_min_distance_km", 10f).toDouble()
        
        simpleLinearEstimator.minTimeMinutes = minTimeMinutes
        simpleLinearEstimator.minDistanceKm = minDistanceKm
        weightedWindowEstimator.minTimeMinutes = minTimeMinutes
        weightedWindowEstimator.minDistanceKm = minDistanceKm
        
        Log.d(TAG, "Sampling parameters updated: minTime=${minTimeMinutes}min, minDist=${minDistanceKm}km")
        
        // Re-run estimation immediately with new parameters
        scope.launch {
            val estimate = runEstimation()
            if (estimate != null) {
                _rangeEstimate.value = estimate
            }
        }
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
        
        // Track wheel connection state for logging
        isWheelConnected = eucData.isConnected
        
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
                if (currentEstimate?.baselineEstimate != null && 
                    currentEstimate.baselineEstimate.status != EstimateStatus.STALE) {
                    val staleBaseline = currentEstimate.baselineEstimate.copy(status = EstimateStatus.STALE)
                    _rangeEstimate.value = currentEstimate.copy(baselineEstimate = staleBaseline)
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
            
            // Start logging if enabled but not currently active (e.g., after trip reset while disconnected)
            if (dataCaptureLogger.isLoggingEnabled() && !dataCaptureLogger.isCurrentlyLogging()) {
                dataCaptureLogger.startNewLog()
                dataCaptureLogger.logEvent("wheel_reconnected", mapOf(
                    "gapDurationMs" to gapDuration
                ))
                Log.d(TAG, "Started new log capture after wheel reconnection")
            }
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
        
        // Step 9.3: Log sample to data capture (if enabled)
        dataCaptureLogger.logSample(validatedSample)
        
        // Step 9.5: Track battery milestones for historical calibration
        trackBatteryMilestone(validatedSample)
        
        // Step 10: Determine if we should run estimation based on battery level and time since last estimation
        val shouldRunEstimation = shouldRunEstimation(currentTimestamp, sample.batteryPercent)
        
        if (shouldRunEstimation) {
            // Get the algorithm name for logging
            val algorithm = prefs.getString(PREF_ALGORITHM, DEFAULT_ALGORITHM) ?: DEFAULT_ALGORITHM
            val previousStatus = _rangeEstimate.value?.baselineEstimate?.status
            
            // Run estimation algorithm asynchronously (AI call requires suspend)
            scope.launch {
                val estimate = runEstimation()
                
                // Log estimate to data capture with detailed reasoning (if enabled)
                if (estimate != null) {
                    val baselineEstimate = estimate.baselineEstimate
                    
                    // Log detailed estimate with calculation reasoning
                    if (baselineEstimate != null) {
                        val diagnostics = baselineEstimate.diagnostics
                        if (diagnostics != null) {
                            val reasoningMap = mutableMapOf<String, Any?>(
                                "statusReason" to diagnostics.statusReason,
                                "windowSampleCount" to diagnostics.windowSampleCount,
                                "windowMinutes" to diagnostics.windowMinutes,
                                "efficiencyStdDev" to diagnostics.efficiencyStdDev,
                                "compensatedVoltage" to diagnostics.compensatedVoltage,
                                "remainingEnergyWh" to diagnostics.remainingEnergyWh,
                                "currentEnergyPercent" to diagnostics.currentEnergyPercent,
                                "baseRangeKm" to diagnostics.baseRangeKm,
                                "calibrationFactor" to diagnostics.calibrationFactor,
                                "usedHistoricalCalibration" to diagnostics.usedHistoricalCalibration,
                                "currentSpeedKmh" to diagnostics.currentSpeedKmh,
                                "minTimeMinutes" to diagnostics.minTimeMinutes,
                                "minDistanceKm" to diagnostics.minDistanceKm,
                                "hasEverMetRequirements" to diagnostics.hasEverMetRequirements,
                                "notes" to diagnostics.notes
                            )
                            
                            // Add AI info to reasoning if available
                            if (estimate.useAI && estimate.aiEnhancedEstimate != null) {
                                reasoningMap["aiRangeKm"] = estimate.aiEnhancedEstimate.rangeKm
                                reasoningMap["aiConfidence"] = estimate.aiEnhancedEstimate.confidence
                                reasoningMap["aiReasoning"] = estimate.aiEnhancedEstimate.reasoning
                                reasoningMap["aiAssumptions"] = estimate.aiEnhancedEstimate.assumptions
                                reasoningMap["aiRiskFactors"] = estimate.aiEnhancedEstimate.riskFactors
                            } else {
                                reasoningMap["aiReason"] = estimate.reason
                            }
                            
                            dataCaptureLogger.logEstimateWithReasoning(baselineEstimate, algorithm, reasoningMap)
                        } else {
                            // Fallback to simple logging if no diagnostics
                            dataCaptureLogger.logEstimate(baselineEstimate)
                        }
                        
                        // Log status transition if status changed
                        if (previousStatus != null && previousStatus != baselineEstimate.status) {
                            val transitionReason = baselineEstimate.diagnostics?.statusReason ?: "Status changed"
                            val transitionDetails = mapOf(
                                "previousStatus" to previousStatus.name,
                                "newStatus" to baselineEstimate.status.name,
                                "rangeKm" to baselineEstimate.rangeKm,
                                "confidence" to baselineEstimate.confidence,
                                "efficiencyWhPerKm" to baselineEstimate.efficiencyWhPerKm,
                                "travelTimeMinutes" to baselineEstimate.dataQuality.travelTimeMinutes,
                                "travelDistanceKm" to baselineEstimate.dataQuality.travelDistanceKm,
                                "meetsMinimumTime" to baselineEstimate.dataQuality.meetsMinimumTime,
                                "meetsMinimumDistance" to baselineEstimate.dataQuality.meetsMinimumDistance
                            )
                            dataCaptureLogger.logStatusTransition(
                                fromStatus = previousStatus.name,
                                toStatus = baselineEstimate.status.name,
                                reason = transitionReason,
                                details = transitionDetails
                            )
                        }
                    }
                }
                
                // Emit estimate
                _rangeEstimate.value = estimate
            }
            
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
        if (currentEstimate == null || 
            currentEstimate.baselineEstimate?.status == EstimateStatus.INSUFFICIENT_DATA) {
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
        if (wheelModel.isBlank() || wheelModel == lastDetectedWheelModelRaw) {
            return
        }
        lastDetectedWheelModelRaw = wheelModel
        
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

            // Store the CANONICAL wheel key (displayName) so the rider-profile
            // writer and reader agree on the same row in the Room DB. The raw
            // identifier from the wheel BLE data ("EXN", "Master", …) may differ
            // from the displayName ("Begode EX.N", "Begode Master") which is
            // what the UI looks up.
            lastDetectedWheelModel = wheelSpec.displayName

            // Propagate wheel context to the data capture logger so future logs
            // embed the wheel identity in their metadata.
            if (::dataCaptureLogger.isInitialized) {
                dataCaptureLogger.setWheelContext(
                    wheelModel = wheelSpec.displayName,
                    batteryCapacityWh = wheelSpec.batteryConfig.capacityWh
                )
            }

            // Load the practical km/% from the rider profile for the newly
            // detected wheel so the practical-range output is correct from
            // the first estimate on.
            refreshPracticalKmPerPct()
        } else {
            Log.w(TAG, "Wheel model '$wheelModel' not found in database")
            lastDetectedWheelModel = wheelModel
            if (::dataCaptureLogger.isInitialized) {
                dataCaptureLogger.setWheelContext(wheelModel, null)
            }
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
                    latitude = lastSample.latitude + (currentSample.latitude - lastSample.latitude) * progress,
                    longitude = lastSample.longitude + (currentSample.longitude - lastSample.longitude) * progress,
                    gpsSpeedKmh = lastSample.gpsSpeedKmh + (currentSample.gpsSpeedKmh - lastSample.gpsSpeedKmh) * progress,
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
        
        // Check if stationary using multiple signals for better accuracy
        val wheelSpeedStationary = sample.speedKmh < CHARGING_SPEED_THRESHOLD
        val gpsStationary = if (sample.hasGpsLocation && last.hasGpsLocation) {
            // Calculate GPS distance moved (Haversine formula for small distances)
            val latDiff = Math.abs(sample.latitude - last.latitude)
            val lonDiff = Math.abs(sample.longitude - last.longitude)
            val distanceMoved = Math.sqrt(latDiff * latDiff + lonDiff * lonDiff) * 111.32 // Rough km conversion
            val gpsSpeedLow = sample.gpsSpeedKmh < CHARGING_SPEED_THRESHOLD
            distanceMoved < 0.01 && gpsSpeedLow  // Moved less than 10 meters
        } else {
            wheelSpeedStationary  // Fall back to wheel speed if no GPS
        }
        
        // Use GPS stationary check if available, otherwise fall back to wheel speed
        val isStationary = if (sample.hasGpsLocation && last.hasGpsLocation) {
            gpsStationary
        } else {
            wheelSpeedStationary
        }
        
        val chargingIndicators = (voltageIncrease > CHARGING_VOLTAGE_INCREASE_THRESHOLD ||
                                  batteryIncrease > CHARGING_BATTERY_INCREASE_THRESHOLD) &&
                                  distanceChange < CHARGING_DISTANCE_CHANGE_THRESHOLD &&
                                  isStationary
        
        // Check if sample has charging flag
        val eucDataCharging = sample.flags.contains(SampleFlag.CHARGING_DETECTED)
        
        when (chargingState) {
            ChargingState.NOT_CHARGING -> {
                if (chargingIndicators || eucDataCharging) {
                    chargingState = ChargingState.CHARGING_SUSPECTED
                    chargingSuspectedSample = sample
                    Log.d(TAG, "Charging suspected (GPS=${sample.hasGpsLocation})")
                }
            }
            
            ChargingState.CHARGING_SUSPECTED -> {
                if (chargingIndicators || eucDataCharging) {
                    // Confirm charging
                    chargingState = ChargingState.CHARGING_CONFIRMED
                    Log.d(TAG, "Charging confirmed (GPS=${sample.hasGpsLocation})")
                    
                    // Log charging start event
                    dataCaptureLogger.logEvent("charging_start", mapOf(
                        "voltageBeforeCharging" to last.voltage,
                        "batteryPercentBefore" to last.batteryPercent,
                        "hasGpsLocation" to sample.hasGpsLocation
                    ))
                    
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
                        
                        // Log charging end event
                        dataCaptureLogger.logEvent("charging_end", mapOf(
                            "voltageAfterCharging" to sample.voltage,
                            "batteryPercentAfter" to sample.batteryPercent,
                            "voltageGain" to (sample.voltage - lastChargingEvent.voltageBeforeCharging),
                            "batteryPercentGain" to (sample.batteryPercent - lastChargingEvent.batteryPercentBefore),
                            "chargingDurationMs" to (sample.timestamp - lastChargingEvent.startTimestamp)
                        ))
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
     * Run estimation algorithm and return AIRangeEstimate.
     * 
     * This method:
     * 1. Runs baseline algorithm (SimpleLinear or WeightedWindow)
     * 2. Optionally enhances with AI if:
     *    - AI is enabled and configured
     *    - Not currently charging
     *    - Rider profile exists with sufficient quality
     * 3. Returns AIRangeEstimate with both baseline and AI estimates
     */
    private suspend fun runEstimation(): AIRangeEstimate? {
        val algorithm = prefs.getString(PREF_ALGORITHM, DEFAULT_ALGORITHM) ?: DEFAULT_ALGORITHM

        val estimator = when (algorithm) {
            "simple_linear" -> simpleLinearEstimator
            "weighted_window" -> weightedWindowEstimator
            else -> weightedWindowEstimator  // Default to weighted window
        }

        // Get baseline estimate
        val baselineEstimate = estimator.estimate(tripSnapshot) ?: return null

        // Compute deterministic practical range from the rider's historical
        // km-per-energy-% metric and the current voltage-derived energy %.
        //
        // IMPORTANT: practicalKmPerPct is stored as km per 1 % of
        // **voltage-derived energy** (via LiIonDischargeCurve), NOT km per
        // 1 % of wheel-reported battery. We therefore multiply by the
        // current energy % from the compensated voltage to produce a
        // consistent km output. Using the wheel's batteryPercent here
        // instead would reintroduce the non-linearity the profile metric
        // was designed to factor out.
        val latestSample = tripSnapshot.latestSample
        val practicalKmPerEnergyPct = cachedPracticalKmPerPct
        val practicalRangeKm = if (practicalKmPerEnergyPct > 0.0 && latestSample != null) {
            val currentEnergyPct = LiIonDischargeCurve.voltageToEnergyPercent(
                latestSample.compensatedVoltage,
                getCellCount()
            )
            if (currentEnergyPct > 0.0) practicalKmPerEnergyPct * currentEnergyPct else null
        } else null

        // Check if we should use AI enhancement. Gated behind a build-time
        // flag (BuildConfig.AI_RANGE_ESTIMATION_ENABLED) — when false the AI
        // path is completely skipped and the baseline/practical estimates are
        // the only outputs. See build.gradle for rationale.
        val aiBuildEnabled = BuildConfig.AI_RANGE_ESTIMATION_ENABLED
        val shouldUseAI = aiBuildEnabled &&
                          prefs.getBoolean("ai_range_estimation_enabled", false) &&
                          tokenManager.isAiReady() &&
                          !tripSnapshot.isCurrentlyCharging &&
                          baselineEstimate.rangeKm != null &&
                          lastDetectedWheelModel != null

        if (!shouldUseAI) {
            // Return baseline + practical (no AI)
            return AIRangeEstimate(
                baselineEstimate = baselineEstimate,
                aiEnhancedEstimate = null,
                useAI = false,
                reason = when {
                    !aiBuildEnabled -> "AI disabled at build time"
                    !prefs.getBoolean("ai_range_estimation_enabled", false) -> "AI disabled in settings"
                    !tokenManager.isAiReady() -> "AI not configured"
                    tripSnapshot.isCurrentlyCharging -> "Currently charging"
                    baselineEstimate.rangeKm == null -> "Insufficient data for baseline"
                    lastDetectedWheelModel == null -> "Wheel model unknown"
                    else -> "AI not available"
                },
                practicalRangeKm = practicalRangeKm,
                practicalKmPerPct = if (practicalKmPerEnergyPct > 0.0) practicalKmPerEnergyPct else null
            )
        }

        // Enhance with AI (suspend call, runs on IO dispatcher)
        val aiResult = rangeEstimationAIService.enhanceRangeEstimate(
            trip = tripSnapshot,
            baselineEstimate = baselineEstimate,
            wheelModel = lastDetectedWheelModel,
            batteryCapacityWh = getBatteryCapacity()
        )

        val aiEstimate = aiResult.getOrNull() ?: AIRangeEstimate(
            baselineEstimate = baselineEstimate,
            aiEnhancedEstimate = null,
            useAI = false,
            reason = "AI enhancement failed: ${aiResult.exceptionOrNull()?.message}"
        )
        // Ensure the practical range fields are populated regardless of the AI
        // path (so the UI can always show a practical number alongside).
        return aiEstimate.copy(
            practicalRangeKm = practicalRangeKm,
            practicalKmPerPct = if (practicalKmPerEnergyPct > 0.0) practicalKmPerEnergyPct else null
        )
    }

    /**
     * Refresh [cachedPracticalKmPerPct] from the rider profile DB. Called
     * during [start] and after the trip-log replay finishes so the cached
     * value is always up to date with the latest rebuild.
     */
    private fun refreshPracticalKmPerPct() {
        val wheelModel = lastDetectedWheelModel
            ?: prefs.getString("selected_wheel_model", null)
            ?: return
        scope.launch {
            try {
                val profile = RiderProfileDatabase.getInstance(context)
                    .riderProfileDao()
                    .getProfileByWheelModel(wheelModel)
                val km = profile?.practicalKmPerPct ?: 0.0
                if (km > 0.0 && km != cachedPracticalKmPerPct) {
                    cachedPracticalKmPerPct = km
                    Log.d(TAG, "Practical km/% cached for $wheelModel: ${String.format("%.3f", km)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh practical km/%: ${e.message}")
            }
        }
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
            latitude = eucData.latitude,
            longitude = eucData.longitude,
            gpsSpeedKmh = eucData.gpsSpeed,
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
    
    /**
     * Process completed trip for AI rider profile learning.
     *
     * Called before trip reset to extract patterns and update rider profile.
     *
     * NOTE: When developer logging is enabled the log file is the source of
     * truth — profile learning happens in [replayUnprocessedTripLogs] after the
     * log file is closed. In that case we skip the in-memory path to avoid
     * double-counting the same trip.
     */
    private fun processCompletedTripForProfile() {
        // Only process if trip has meaningful data
        if (tripSnapshot.samples.size < 100) {
            Log.d(TAG, "Trip too short for profile learning (${tripSnapshot.samples.size} samples)")
            return
        }

        val distanceKm = tripSnapshot.totalDistanceKm
        if (distanceKm < 1.0) {
            Log.d(TAG, "Trip distance too short for profile learning (${String.format("%.2f", distanceKm)} km)")
            return
        }

        // Get wheel model (canonical displayName — see autoDetectWheelConfiguration)
        val rawWheelModel = lastDetectedWheelModel
        if (rawWheelModel.isNullOrBlank()) {
            Log.d(TAG, "No wheel model detected - skipping profile update")
            return
        }

        // Get battery capacity from settings
        val batteryCapacityWh = getBatteryCapacity()

        // Process trip asynchronously (don't block trip reset)
        val snapshot = tripSnapshot
        scope.launch {
            try {
                val profileBuilder = RiderProfileBuilder(context)
                val canonicalWheel = profileBuilder.canonicalWheelKey(rawWheelModel) ?: rawWheelModel
                Log.d(TAG, "Processing trip for profile learning: $canonicalWheel, " +
                        "${snapshot.samples.size} samples, ${String.format("%.2f", distanceKm)} km")

                profileBuilder.processTripForProfile(
                    trip = snapshot,
                    wheelModel = canonicalWheel,
                    batteryCapacityWh = batteryCapacityWh
                )

                Log.d(TAG, "Profile update completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process trip for profile: ${e.message}", e)
            }
        }
    }

    /**
     * Scan the developer trip-logs directory and feed any unprocessed log files
     * into [RiderProfileBuilder]. Invoked from [start] so that historical logs
     * captured before this fix ran are used to bootstrap the rider profile.
     *
     * Skips the currently-open log file (if any) to avoid replaying a partial
     * capture that is still being written.
     */
    private fun replayUnprocessedTripLogs() {
        scope.launch {
            try {
                val logger = if (::dataCaptureLogger.isInitialized) dataCaptureLogger
                    else DataCaptureLogger(context)
                val logDir = logger.getLogDirectoryFile()
                if (!logDir.exists()) return@launch

                val currentLog = prefs.getString("developer_current_log_file", null)
                val fallbackWheelModel = lastDetectedWheelModel
                    ?: prefs.getString("selected_wheel_model", null)
                val fallbackBatteryCapacityWh = getBatteryCapacity()

                val builder = RiderProfileBuilder(context)
                val result = builder.replayLogDirectory(
                    logDir = logDir,
                    fallbackWheelModel = fallbackWheelModel,
                    fallbackBatteryCapacityWh = fallbackBatteryCapacityWh,
                    onlyUnprocessed = true,
                    skipCurrentFile = currentLog
                )
                if (result.filesProcessed > 0) {
                    Log.i(TAG, "Bootstrapped rider profile from ${result.filesProcessed} " +
                            "trip log(s) (${result.samplesProcessed} samples, " +
                            "${String.format("%.2f", result.totalDistanceKm)} km)")
                    // Profile was just updated — refresh the cached value.
                    refreshPracticalKmPerPct()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to replay trip logs for profile bootstrap", e)
            }
        }
    }
}
