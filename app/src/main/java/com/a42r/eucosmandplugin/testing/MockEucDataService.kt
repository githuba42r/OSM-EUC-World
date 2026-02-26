package com.a42r.eucosmandplugin.testing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.range.database.WheelDatabase
import com.a42r.eucosmandplugin.range.manager.RangeEstimationManager
import com.a42r.eucosmandplugin.range.model.RangeEstimate
import com.a42r.eucosmandplugin.service.ConnectionState
import com.a42r.eucosmandplugin.ui.MainActivity

/**
 * Mock version of EucWorldService that generates simulated EUC data
 * based on GPS location from the Android emulator.
 * 
 * This service mimics the behavior of the real EucWorldService but uses
 * MockEucDataGenerator instead of polling the EUC World webservice.
 * 
 * Usage:
 * 1. Enable "Mock Data Mode" in Developer settings
 * 2. Configure wheel model, battery %, rider weight, etc.
 * 3. Service will start automatically when EucWorldService would normally start
 * 4. Load GPX file in emulator Extended Controls > Location
 * 5. Service generates realistic EUC data based on GPS speed/location
 */
class MockEucDataService : LifecycleService(), MockLocationProvider.LocationCallback {
    
    companion object {
        private const val TAG = "MockEucDataService"
        const val NOTIFICATION_CHANNEL_ID = "mock_euc_channel"
        const val NOTIFICATION_ID = 1002
        
        const val ACTION_START = "com.a42r.eucosmandplugin.START_MOCK_SERVICE"
        const val ACTION_STOP = "com.a42r.eucosmandplugin.STOP_MOCK_SERVICE"
        
        private const val UPDATE_INTERVAL_MS = 500L
        
        // Static reference to latest data
        @Volatile
        private var _latestData: EucData? = null
        val latestData: EucData? get() = _latestData
        
        fun start(context: Context) {
            val intent = Intent(context, MockEucDataService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, MockEucDataService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    private val binder = LocalBinder()
    
    private var mockGenerator: MockEucDataGenerator? = null
    private var locationProvider: MockLocationProvider? = null
    private var updateJob: Job? = null
    
    private val _eucDataState = MutableStateFlow<EucData?>(null)
    val eucDataState: StateFlow<EucData?> = _eucDataState.asStateFlow()
    
    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _mockStatus = MutableStateFlow("Initializing...")
    val mockStatus: StateFlow<String> = _mockStatus.asStateFlow()
    
    // Range estimation manager
    private var rangeEstimationManager: RangeEstimationManager? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): MockEucDataService = this@MockEucDataService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MockEucDataService created")
        
        createNotificationChannel()
        initializeMockMode()
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Starting mock EUC service")
                startForeground(NOTIFICATION_ID, createNotification())
                startMockDataGeneration()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping mock EUC service")
                stopMockDataGeneration()
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopMockDataGeneration()
        Log.d(TAG, "MockEucDataService destroyed")
    }
    
    /**
     * Initialize mock mode with configuration from settings.
     */
    private fun initializeMockMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Get wheel model
        val wheelModelName = prefs.getString("mock_wheel_model", "Begode Commander") ?: "Begode Commander"
        val wheelSpec = WheelDatabase.findWheelSpec(wheelModelName)
            ?: WheelDatabase.findWheelSpec("Commander")
            ?: WheelDatabase.getAllWheelSpecs().first()
        
        // Get configuration parameters
        val startBattery = prefs.getString("mock_start_battery", "95")?.toDoubleOrNull() ?: 95.0
        val riderWeight = prefs.getString("mock_rider_weight", "80")?.toDoubleOrNull() ?: 80.0
        val baseEfficiency = prefs.getString("mock_base_efficiency", "18")?.toDoubleOrNull() ?: 18.0
        
        // Create configuration
        val config = MockEucDataGenerator.MockEucConfig(
            wheelSpec = wheelSpec,
            startBatteryPercent = startBattery.coerceIn(20.0, 100.0),
            riderWeightKg = riderWeight.coerceIn(50.0, 150.0),
            baseEfficiencyWhPerKm = baseEfficiency.coerceIn(10.0, 30.0),
            useRealisticVariability = true
        )
        
        // Initialize generator
        mockGenerator = MockEucDataGenerator(config)
        
        // Initialize location provider
        locationProvider = MockLocationProvider(this, this)
        
        // Initialize range estimation if enabled
        val rangeEnabled = prefs.getBoolean("range_estimation_enabled", false)
        if (rangeEnabled) {
            rangeEstimationManager = RangeEstimationManager(this, lifecycleScope)
            rangeEstimationManager?.start(eucDataState)
            Log.d(TAG, "Range estimation enabled for mock mode")
        }
        
        _mockStatus.value = "Initialized: ${wheelSpec.displayName}"
        Log.d(TAG, "Mock mode initialized: ${config.wheelSpec.displayName}, " +
                "battery=${config.startBatteryPercent}%, " +
                "weight=${config.riderWeightKg}kg, " +
                "efficiency=${config.baseEfficiencyWhPerKm}Wh/km")
    }
    
    /**
     * Start generating mock data based on GPS location.
     */
    private fun startMockDataGeneration() {
        if (updateJob?.isActive == true) return
        
        // Generate initial idle data (standing still at 0 speed)
        generateIdleData()
        
        // Start location provider
        locationProvider?.start()
        _mockStatus.value = "Waiting for GPS..."
        
        // Start update loop (generates idle data while waiting for GPS)
        updateJob = lifecycleScope.launch {
            while (isActive) {
                // If we haven't received GPS data in the last 2 seconds, generate idle data
                val timeSinceLastUpdate = System.currentTimeMillis() - (_latestData?.timestamp ?: 0)
                if (timeSinceLastUpdate > 2000) {
                    generateIdleData()
                }
                
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Stop generating mock data.
     */
    private fun stopMockDataGeneration() {
        updateJob?.cancel()
        updateJob = null
        locationProvider?.stop()
        _eucDataState.value = null
        _latestData = null
        _mockStatus.value = "Stopped"
    }
    
    /**
     * Generate idle EUC data (standing still at 0 speed).
     * Used while waiting for GPS location.
     */
    private fun generateIdleData() {
        val generator = mockGenerator ?: return
        
        // Create fake location data for idle state
        val idleLocationData = MockLocationProvider.MockLocationData(
            speedKmh = 0.0,
            altitude = 0.0,
            latitude = 0.0,
            longitude = 0.0,
            timestamp = System.currentTimeMillis(),
            hasSpeed = true,
            hasAltitude = true
        )
        
        // Generate EUC data for idle state
        val eucData = generator.generateEucData(idleLocationData)
        
        // Update state
        _eucDataState.value = eucData
        _latestData = eucData
        
        // Update notification
        updateNotification(eucData)
        
        _mockStatus.value = "Idle: Waiting for GPS (${eucData.batteryPercentage}%)"
    }
    
    /**
     * Location callback: Called when GPS location updates.
     */
    override fun onLocationUpdate(data: MockLocationProvider.MockLocationData) {
        val generator = mockGenerator ?: return
        
        // Generate EUC data based on location
        val eucData = generator.generateEucData(data)
        
        // Update state
        _eucDataState.value = eucData
        _latestData = eucData
        
        // Update notification
        updateNotification(eucData)
        
        // Update status
        _mockStatus.value = "Active: ${String.format("%.1f", data.speedKmh)} km/h, " +
                "${eucData.batteryPercentage}%"
        
        // Log periodically (every 10 seconds)
        if (System.currentTimeMillis() % 10000 < UPDATE_INTERVAL_MS) {
            Log.d(TAG, "Mock data: ${generator.getBatteryStats()}, ${generator.getTripStats()}")
        }
    }
    
    /**
     * Location callback: Called on location errors.
     */
    override fun onLocationError(error: String) {
        Log.e(TAG, "Location error: $error")
        _mockStatus.value = "Error: $error"
    }
    
    /**
     * Create notification channel.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mock EUC Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows mock EUC data generation status"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create notification for foreground service.
     */
    private fun createNotification(eucData: EucData? = null): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val contentTitle = "Mock EUC Mode"
        val contentText = if (eucData != null) {
            "${eucData.batteryPercentage}% • ${String.format("%.1fV", eucData.voltage)} • " +
                    "${String.format("%.1f", eucData.speed)} km/h"
        } else {
            "Waiting for GPS location..."
        }
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * Update notification with current data.
     */
    private fun updateNotification(eucData: EucData) {
        val notification = createNotification(eucData)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Get current range estimate (for UI).
     */
    fun getCurrentRangeEstimate(): Double? {
        return rangeEstimationManager?.rangeEstimate?.value?.rangeKm
    }
    
    /**
     * Get range estimation confidence (for UI).
     */
    fun getRangeEstimationConfidence(): Double {
        return rangeEstimationManager?.rangeEstimate?.value?.confidence ?: 0.0
    }
    
    /**
     * Get full range estimate with all details.
     */
    fun getFullRangeEstimate(): RangeEstimate? {
        return rangeEstimationManager?.rangeEstimate?.value
    }
    
    /**
     * Reset trip data (for testing).
     */
    fun resetTrip() {
        mockGenerator?.resetTrip()
        rangeEstimationManager?.resetTrip()
        Log.d(TAG, "Trip reset")
    }
    
    /**
     * Reset battery to starting state (for testing).
     */
    fun resetBattery() {
        mockGenerator?.resetBattery()
        Log.d(TAG, "Battery reset")
    }
    
    /**
     * Trigger immediate broadcast (compatibility with EucWorldService).
     * In mock mode, we don't broadcast to Android Auto since this is emulator-only.
     */
    fun triggerImmediateBroadcast() {
        Log.d(TAG, "triggerImmediateBroadcast called (no-op in mock mode)")
        // No-op: Mock mode doesn't support Android Auto broadcasting
    }
}
