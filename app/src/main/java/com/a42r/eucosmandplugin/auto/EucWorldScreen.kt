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
 * Layout similar to phone app:
 * - Device name and connection status at top
 * - Large battery percentage display
 * - Voltage underneath battery
 * - Three trip meters at bottom with reset buttons
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
    }
    
    // Current EUC data
    private var eucData: EucData? = null
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var deviceName: String = "EUC"
    
    // Trip meter manager
    private val tripMeterManager = TripMeterManager(carContext)
    private var currentOdometer: Double = 0.0
    
    // Throttling: track last template data to avoid unnecessary updates
    private var lastUpdateTime: Long = 0
    private var lastBatteryPercentage: Int? = null
    private var lastVoltage: Double? = null
    private var lastConnectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var lastTripA: Double? = null
    private var lastTripB: Double? = null
    private var lastTripC: Double? = null
    
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
     * 4. OR voltage changed significantly
     * 5. OR trip meter values changed
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
            
            // Allow update if voltage changed by more than 0.5V
            if (currentVoltage != null && lastVoltage != null) {
                if (Math.abs(currentVoltage - lastVoltage!!) > 0.5) {
                    Log.d(TAG, "Voltage changed significantly: $lastVoltage -> $currentVoltage")
                    return true
                }
            }
            
            // Allow update if any trip meter changed
            val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
            if (tripA != lastTripA || tripB != lastTripB || tripC != lastTripC) {
                Log.d(TAG, "Trip meter changed: A=$lastTripA->$tripA, B=$lastTripB->$tripB, C=$lastTripC->$tripC")
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
        
        // Build list with informational rows
        val listBuilder = ItemList.Builder()
        
        // Row 1: Large Battery Percentage (most prominent)
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Battery")
                .addText("${data.batteryPercentage}%")
                .build()
        )
        
        // Row 2: Voltage (under battery)
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Voltage")
                .addText(String.format("%.2f V", data.voltage))
                .build()
        )
        
        // Row 3: Trip meters - browsable to navigate to detail screen
        val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
        val tripLine = "A: ${formatTripDistance(tripA)} km  •  B: ${formatTripDistance(tripB)} km  •  C: ${formatTripDistance(tripC)} km"
        
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip Meters")
                .addText(tripLine)
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.push(TripMeterScreen(carContext, tripMeterManager, currentOdometer))
                }
                .build()
        )
        
        // Device name and connection status in the title bar
        // Use ListTemplate instead of PaneTemplate to avoid task completion requirements
        return ListTemplate.Builder()
            .setTitle("$deviceName • $connectionStatus")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .build()
    }
    
    /**
     * Create the disconnected template
     */
    private fun createDisconnectedTemplate(): Template {
        val listBuilder = ItemList.Builder()
        
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Not Connected")
                .addText("Waiting for EUC World data...")
                .build()
        )
        
        // Show trip meters even when disconnected (they persist)
        val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
        
        if (tripA != null || tripB != null || tripC != null) {
            val tripLine = "A: ${formatTripDistance(tripA)} km  •  B: ${formatTripDistance(tripB)} km  •  C: ${formatTripDistance(tripC)} km"
            
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Trip Meters")
                    .addText(tripLine)
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(TripMeterScreen(carContext, tripMeterManager, currentOdometer))
                    }
                    .build()
            )
        }
        
        // Use ListTemplate to match connected state and avoid task completion requirements
        return ListTemplate.Builder()
            .setTitle("EUC World")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .build()
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
