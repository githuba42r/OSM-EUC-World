package com.a42r.eucosmandplugin.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.service.AutoProxyReceiver
import com.a42r.eucosmandplugin.service.ConnectionState
import com.a42r.eucosmandplugin.util.TripMeterManager

/**
 * Android Auto screen showing EUC telemetry with detailed layout.
 * 
 * Layout:
 * - Device name and connection status at top
 * - Battery percentage and voltage on same row (% left, voltage right)
 * - Range estimate (if enabled)
 * - Three trip meters with reset buttons
 */
class EucWorldScreen(carContext: CarContext) : Screen(carContext) {
    
    companion object {
        private const val TAG = "EucWorldScreen"
        private const val ACTION_RESET_TRIP_A = "reset_trip_a"
        private const val ACTION_RESET_TRIP_B = "reset_trip_b"
        private const val ACTION_RESET_TRIP_C = "reset_trip_c"
        private const val ACTION_CLEAR_ALL_TRIPS = "clear_all_trips"
        
        // Default throttling settings
        private const val DEFAULT_UPDATE_INTERVAL_SECONDS = 15
        private const val MIN_UPDATE_INTERVAL_SECONDS = 5
        private const val MAX_UPDATE_INTERVAL_SECONDS = 300 // 5 minutes
        
        // Thresholds for change detection (prevent excessive invalidation)
        private const val VOLTAGE_CHANGE_THRESHOLD = 0.5 // Volts
        private const val TRIP_CHANGE_THRESHOLD = 0.1 // km
        private const val RANGE_CHANGE_THRESHOLD = 1.0 // km
    }
    
    // Current EUC data
    private var eucData: EucData? = null
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var deviceName: String = "EUC"
    
    // Trip meter manager
    private val tripMeterManager = TripMeterManager(carContext)
    private var currentOdometer: Double = 0.0
    
    // Range estimation data
    private var rangeEstimateKm: Double? = null
    private var rangeConfidence: Double? = null
    
    // Throttling: track last template data to avoid unnecessary updates
    private var lastUpdateTime: Long = 0
    private var lastBatteryPercentage: Int? = null
    private var lastVoltage: Double? = null
    private var lastConnectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var lastTripA: Double? = null
    private var lastTripB: Double? = null
    private var lastTripC: Double? = null
    private var lastRangeEstimateKm: Double? = null
    
    /**
     * Get the user-configured update interval from preferences
     */
    private fun getUpdateIntervalMs(): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(carContext)
        val seconds = prefs.getInt("android_auto_update_interval", DEFAULT_UPDATE_INTERVAL_SECONDS)
        return (seconds * 1000L).coerceIn(
            MIN_UPDATE_INTERVAL_SECONDS * 1000L,
            MAX_UPDATE_INTERVAL_SECONDS * 1000L
        )
    }
    
    // Receiver for data from phone
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AutoProxyReceiver.ACTION_EUC_DATA_UPDATE -> {
                    intent.extras?.let { bundle ->
                        eucData = AutoProxyReceiver.parseDataBundle(bundle)
                        eucData?.let { data ->
                            // Extract device name
                            if (data.wheelModel.isNotEmpty()) {
                                deviceName = data.wheelModel
                            }
                            // Store odometer for trip calculations
                            currentOdometer = data.totalDistance
                            
                            // Extract range estimation data if available
                            rangeEstimateKm = if (bundle.containsKey("range_estimate_km")) {
                                bundle.getDouble("range_estimate_km")
                            } else null
                            
                            rangeConfidence = if (bundle.containsKey("range_confidence")) {
                                bundle.getDouble("range_confidence")
                            } else null
                            
                            // Update connection state based on actual wheel connection
                            connectionState = if (data.isConnected) {
                                ConnectionState.CONNECTED
                            } else {
                                ConnectionState.WHEEL_DISCONNECTED
                            }
                        }
                        
                        // Only invalidate if enough time has passed or data significantly changed
                        if (shouldInvalidate()) {
                            lastUpdateTime = System.currentTimeMillis()
                            lastBatteryPercentage = eucData?.batteryPercentage
                            lastVoltage = eucData?.voltage
                            lastConnectionState = connectionState
                            lastRangeEstimateKm = rangeEstimateKm
                            // Store current trip values
                            val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
                            lastTripA = tripA
                            lastTripB = tripB
                            lastTripC = tripC
                            Log.d(TAG, "Invalidating template (data changed)")
                            invalidate()
                        }
                    }
                }
                AutoProxyReceiver.ACTION_CONNECTION_STATE -> {
                    val stateName = intent.getStringExtra(AutoProxyReceiver.EXTRA_CONNECTION_STATE)
                    connectionState = try {
                        ConnectionState.valueOf(stateName ?: "DISCONNECTED")
                    } catch (e: Exception) {
                        ConnectionState.DISCONNECTED
                    }
                    if (connectionState != ConnectionState.CONNECTED) {
                        eucData = null
                    }
                    
                    // Always update on connection state change
                    if (connectionState != lastConnectionState) {
                        lastConnectionState = connectionState
                        Log.d(TAG, "Invalidating template (connection state changed to $connectionState)")
                        invalidate()
                    }
                }
            }
        }
    }
    
    /**
     * Check if we should invalidate the template.
     * Only invalidate if:
     * 1. Enough time has passed since last update (throttling)
     * 2. OR connection state changed
     * 3. OR battery percentage changed by 1% or more
     * 4. OR voltage changed significantly (>= threshold)
     * 5. OR trip meter values changed significantly (>= threshold)
     * 6. OR range estimate changed significantly (>= threshold)
     */
    private fun shouldInvalidate(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastUpdate = now - lastUpdateTime
        val minInterval = getUpdateIntervalMs()
        
        // If minimum interval hasn't passed, check if critical data changed
        if (timeSinceLastUpdate < minInterval) {
            val currentBattery = eucData?.batteryPercentage
            val currentVoltage = eucData?.voltage
            
            // Allow update if battery changed
            if (currentBattery != lastBatteryPercentage) {
                Log.d(TAG, "Battery changed: $lastBatteryPercentage -> $currentBattery")
                return true
            }
            
            // Allow update if voltage changed by more than threshold
            if (currentVoltage != null && lastVoltage != null) {
                if (Math.abs(currentVoltage - lastVoltage!!) >= VOLTAGE_CHANGE_THRESHOLD) {
                    Log.d(TAG, "Voltage changed significantly: $lastVoltage -> $currentVoltage")
                    return true
                }
            }
            
            // Allow update if any trip meter changed by more than threshold
            val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
            val tripAChanged = tripA != null && lastTripA != null && Math.abs(tripA - lastTripA!!) >= TRIP_CHANGE_THRESHOLD
            val tripBChanged = tripB != null && lastTripB != null && Math.abs(tripB - lastTripB!!) >= TRIP_CHANGE_THRESHOLD
            val tripCChanged = tripC != null && lastTripC != null && Math.abs(tripC - lastTripC!!) >= TRIP_CHANGE_THRESHOLD
            val tripNewOrRemoved = (tripA == null) != (lastTripA == null) || 
                                   (tripB == null) != (lastTripB == null) || 
                                   (tripC == null) != (lastTripC == null)
            
            if (tripAChanged || tripBChanged || tripCChanged || tripNewOrRemoved) {
                Log.d(TAG, "Trip meter changed significantly: A=$lastTripA->$tripA, B=$lastTripB->$tripB, C=$lastTripC->$tripC")
                return true
            }
            
            // Allow update if range estimate changed by more than threshold
            if (rangeEstimateKm != null && lastRangeEstimateKm != null) {
                if (Math.abs(rangeEstimateKm!! - lastRangeEstimateKm!!) >= RANGE_CHANGE_THRESHOLD) {
                    Log.d(TAG, "Range estimate changed significantly: $lastRangeEstimateKm -> $rangeEstimateKm")
                    return true
                }
            } else if ((rangeEstimateKm == null) != (lastRangeEstimateKm == null)) {
                // Range estimate appeared or disappeared
                Log.d(TAG, "Range estimate availability changed: $lastRangeEstimateKm -> $rangeEstimateKm")
                return true
            }
            
            // Connection state is handled separately
            
            Log.d(TAG, "Throttling update (${timeSinceLastUpdate}ms < ${minInterval}ms)")
            return false
        }
        
        // Enough time has passed
        Log.d(TAG, "Update interval passed (${timeSinceLastUpdate}ms >= ${minInterval}ms)")
        return true
    }
    
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            registerDataReceiver()
            AutoProxyReceiver.subscribe(carContext)
            Log.d(TAG, "Screen started, subscribed to updates")
        }
        
        override fun onStop(owner: LifecycleOwner) {
            unregisterDataReceiver()
            AutoProxyReceiver.unsubscribe(carContext)
            Log.d(TAG, "Screen stopped, unsubscribed")
        }
    }
    
    init {
        lifecycle.addObserver(lifecycleObserver)
    }
    
    private fun registerDataReceiver() {
        val filter = AutoProxyReceiver.createDataUpdateFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            carContext.registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            carContext.registerReceiver(dataReceiver, filter)
        }
    }
    
    private fun unregisterDataReceiver() {
        try {
            carContext.unregisterReceiver(dataReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver: ${e.message}")
        }
    }
    
    override fun onGetTemplate(): Template {
        Log.d(TAG, "onGetTemplate called, connectionState=$connectionState, eucData=$eucData")
        
        val data = eucData
        
        return if (data != null && connectionState == ConnectionState.CONNECTED) {
            createConnectedTemplate(data)
        } else {
            createDisconnectedTemplate()
        }
    }
    
    /**
     * Create the connected template with full layout  
     */
    private fun createConnectedTemplate(data: EucData): Template {
        // Connection status string
        val connectionStatus = when (connectionState) {
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.WHEEL_DISCONNECTED -> "Wheel Disconnected"
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.ERROR -> "Error"
        }
        
        // Build pane with informational rows
        val paneBuilder = Pane.Builder()
        
        // Row 1: Battery Percentage and Voltage on same line
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("${data.batteryPercentage}%")
                .addText(String.format("%.2f V", data.voltage))
                .build()
        )
        
        // Row 2: Range Estimate (if available)
        val prefs = PreferenceManager.getDefaultSharedPreferences(carContext)
        val rangeEnabled = prefs.getBoolean("range_estimation_enabled", false)
        
        if (rangeEnabled && rangeEstimateKm != null && rangeConfidence != null) {
            val useMetric = prefs.getBoolean("use_metric", true)
            val rangeValue = if (useMetric) rangeEstimateKm!! else rangeEstimateKm!! * 0.621371
            val unit = if (useMetric) "km" else "mi"
            
            val confidenceText = when {
                rangeConfidence!! >= 0.8 -> "High confidence"
                rangeConfidence!! >= 0.5 -> "Medium confidence"
                else -> "Low confidence"
            }
            
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Range Estimate")
                    .addText(String.format("%.1f %s (%s)", rangeValue, unit, confidenceText))
                    .build()
            )
        }
        
        // Row 3: Trip meters display
        val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
        val tripLine = "A: ${formatTripDistance(tripA)} km  •  B: ${formatTripDistance(tripB)} km  •  C: ${formatTripDistance(tripC)} km"
        
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Trip Meters")
                .addText(tripLine)
                .build()
        )
        
        // Action strip with Trip Meters button
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Trip Meters")
                    .setOnClickListener {
                        screenManager.push(TripMeterScreen(carContext, tripMeterManager, currentOdometer))
                    }
                    .build()
            )
            .build()
        
        // Device name and connection status in the title bar
        // Use PaneTemplate with ActionStrip for POI category compatibility
        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("$deviceName • $connectionStatus")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
            .build()
    }
    
    /**
     * Create the disconnected template
     */
    private fun createDisconnectedTemplate(): Template {
        val paneBuilder = Pane.Builder()
        
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Not Connected")
                .addText("Waiting for EUC World data...")
                .build()
        )
        
        // Show trip meters even when disconnected (they persist)
        val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
        
        if (tripA != null || tripB != null || tripC != null) {
            val tripLine = "A: ${formatTripDistance(tripA)} km  •  B: ${formatTripDistance(tripB)} km  •  C: ${formatTripDistance(tripC)} km"
            
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Trip Meters")
                    .addText(tripLine)
                    .build()
            )
        }
        
        // Action strip with Trip Meters button (only show if trips exist)
        val actionStrip = if (tripA != null || tripB != null || tripC != null) {
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("Trip Meters")
                        .setOnClickListener {
                            screenManager.push(TripMeterScreen(carContext, tripMeterManager, currentOdometer))
                        }
                        .build()
                )
                .build()
        } else {
            null
        }
        
        // Use PaneTemplate to match connected state
        val builder = PaneTemplate.Builder(paneBuilder.build())
            .setTitle("EUC World")
            .setHeaderAction(Action.APP_ICON)
        
        if (actionStrip != null) {
            builder.setActionStrip(actionStrip)
        }
        
        return builder.build()
    }
    
    /**
     * Format trip distance for display
     */
    private fun formatTripDistance(distance: Double?): String {
        return if (distance != null) {
            String.format("%.1f", distance)
        } else {
            "--"
        }
    }
}
