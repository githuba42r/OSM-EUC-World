package com.a42r.eucosmandplugin.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.a42r.eucosmandplugin.api.EucData

/**
 * Proxy service for Android Auto communication.
 * 
 * Android Auto runs in a sandboxed environment on the car's head unit and cannot
 * directly access localhost (127.0.0.1) on the phone. This proxy handles IPC
 * between the Car App (running on the head unit) and the phone-side service
 * that fetches data from the EUC World API.
 * 
 * Architecture:
 * 
 * [Car Head Unit]                    [Phone]
 * ┌─────────────────┐               ┌──────────────────────────┐
 * │  EucWorldScreen │               │  EucWorldService         │
 * │  (Car App)      │◄──Broadcast───│  (Foreground Service)    │
 * │                 │               │         │                │
 * │                 │───Request────►│  AutoProxyReceiver       │
 * │                 │               │         │                │
 * └─────────────────┘               │         ▼                │
 *                                   │  EucWorldApiClient       │
 *                                   │         │                │
 *                                   │         ▼                │
 *                                   │  localhost:8080          │
 *                                   │  (EUC World App)         │
 *                                   └──────────────────────────┘
 * 
 * Communication Flow:
 * 1. Car App sends ACTION_REQUEST_DATA broadcast to phone
 * 2. AutoProxyReceiver receives request on phone
 * 3. Phone service fetches latest data from EUC World API
 * 4. Phone broadcasts ACTION_EUC_DATA_UPDATE with data bundle
 * 5. Car App's receiver gets the data and updates UI
 */
class AutoProxyReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "AutoProxyReceiver"
        
        // Actions for Car App -> Phone communication
        const val ACTION_REQUEST_DATA = "com.a42r.eucosmandplugin.auto.REQUEST_DATA"
        const val ACTION_REQUEST_SUBSCRIBE = "com.a42r.eucosmandplugin.auto.SUBSCRIBE"
        const val ACTION_REQUEST_UNSUBSCRIBE = "com.a42r.eucosmandplugin.auto.UNSUBSCRIBE"
        
        // Actions for Phone -> Car App communication
        const val ACTION_EUC_DATA_UPDATE = "com.a42r.eucosmandplugin.auto.DATA_UPDATE"
        const val ACTION_CONNECTION_STATE = "com.a42r.eucosmandplugin.auto.CONNECTION_STATE"
        
        // Bundle keys for data transfer
        const val EXTRA_BATTERY_PERCENT = "battery_percent"
        const val EXTRA_VOLTAGE = "voltage"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_POWER = "power"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_TOP_SPEED = "top_speed"
        const val EXTRA_TEMPERATURE = "temperature"
        const val EXTRA_TRIP_DISTANCE = "trip_distance"
        const val EXTRA_TOTAL_DISTANCE = "total_distance"
        const val EXTRA_PWM = "pwm"
        const val EXTRA_WHEEL_MODEL = "wheel_model"
        const val EXTRA_WHEEL_BRAND = "wheel_brand"
        const val EXTRA_IS_CONNECTED = "is_connected"
        const val EXTRA_IS_CHARGING = "is_charging"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_CONNECTION_STATE = "connection_state"
        
        /**
         * Create an intent filter for the Car App to receive data updates
         */
        fun createDataUpdateFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(ACTION_EUC_DATA_UPDATE)
                addAction(ACTION_CONNECTION_STATE)
            }
        }
        
        /**
         * Create a bundle from EucData for broadcast
         */
        fun createDataBundle(data: EucData): Bundle {
            return Bundle().apply {
                putInt(EXTRA_BATTERY_PERCENT, data.batteryPercentage)
                putDouble(EXTRA_VOLTAGE, data.voltage)
                putDouble(EXTRA_CURRENT, data.current)
                putDouble(EXTRA_POWER, data.power)
                putDouble(EXTRA_SPEED, data.speed)
                putDouble(EXTRA_TOP_SPEED, data.topSpeed)
                putDouble(EXTRA_TEMPERATURE, data.temperature)
                putDouble(EXTRA_TRIP_DISTANCE, data.wheelTrip)
                putDouble(EXTRA_TOTAL_DISTANCE, data.totalDistance)
                putDouble(EXTRA_PWM, data.pwm)
                putString(EXTRA_WHEEL_MODEL, data.wheelModel)
                putString(EXTRA_WHEEL_BRAND, data.wheelBrand)
                putBoolean(EXTRA_IS_CONNECTED, data.isConnected)
                putBoolean(EXTRA_IS_CHARGING, data.isCharging)
                putLong(EXTRA_TIMESTAMP, data.timestamp)
            }
        }
        
        /**
         * Parse EucData from a received bundle
         */
        fun parseDataBundle(bundle: Bundle): EucData {
            return EucData(
                batteryPercentage = bundle.getInt(EXTRA_BATTERY_PERCENT, 0),
                voltage = bundle.getDouble(EXTRA_VOLTAGE, 0.0),
                current = bundle.getDouble(EXTRA_CURRENT, 0.0),
                power = bundle.getDouble(EXTRA_POWER, 0.0),
                speed = bundle.getDouble(EXTRA_SPEED, 0.0),
                topSpeed = bundle.getDouble(EXTRA_TOP_SPEED, 0.0),
                temperature = bundle.getDouble(EXTRA_TEMPERATURE, 0.0),
                wheelTrip = bundle.getDouble(EXTRA_TRIP_DISTANCE, 0.0),
                totalDistance = bundle.getDouble(EXTRA_TOTAL_DISTANCE, 0.0),
                pwm = bundle.getDouble(EXTRA_PWM, 0.0),
                wheelModel = bundle.getString(EXTRA_WHEEL_MODEL, ""),
                wheelBrand = bundle.getString(EXTRA_WHEEL_BRAND, ""),
                isConnected = bundle.getBoolean(EXTRA_IS_CONNECTED, false),
                isCharging = bundle.getBoolean(EXTRA_IS_CHARGING, false),
                timestamp = bundle.getLong(EXTRA_TIMESTAMP, System.currentTimeMillis())
            )
        }
        
        /**
         * Send a data request from Car App to Phone
         */
        fun requestData(context: Context) {
            val intent = Intent(ACTION_REQUEST_DATA).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
        
        /**
         * Subscribe to continuous updates from Phone
         */
        fun subscribe(context: Context) {
            val intent = Intent(ACTION_REQUEST_SUBSCRIBE).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
        
        /**
         * Unsubscribe from updates
         */
        fun unsubscribe(context: Context) {
            val intent = Intent(ACTION_REQUEST_UNSUBSCRIBE).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")
        
        when (intent.action) {
            ACTION_REQUEST_DATA -> {
                // Car App is requesting current data
                handleDataRequest(context)
            }
            ACTION_REQUEST_SUBSCRIBE -> {
                // Car App wants to subscribe to updates
                handleSubscribe(context)
            }
            ACTION_REQUEST_UNSUBSCRIBE -> {
                // Car App wants to unsubscribe
                handleUnsubscribe(context)
            }
        }
    }
    
    private fun handleDataRequest(context: Context) {
        // Get latest data from service and broadcast it
        val latestData = EucWorldService.latestData
        if (latestData != null) {
            broadcastData(context, latestData)
        } else {
            // No data available, send disconnected state
            broadcastConnectionState(context, ConnectionState.DISCONNECTED)
        }
    }
    
    private fun handleSubscribe(context: Context) {
        // Ensure the service is running
        EucWorldService.start(context)
        
        // Mark that Auto is subscribed (service will broadcast updates)
        context.getSharedPreferences("auto_proxy", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_subscribed", true)
            .apply()
    }
    
    private fun handleUnsubscribe(context: Context) {
        context.getSharedPreferences("auto_proxy", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_subscribed", false)
            .apply()
    }
    
    /**
     * Broadcast EUC data to the Car App
     */
    fun broadcastData(context: Context, data: EucData) {
        val intent = Intent(ACTION_EUC_DATA_UPDATE).apply {
            setPackage(context.packageName)
            putExtras(createDataBundle(data))
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast data: ${data.batteryPercentage}% ${data.voltage}V")
    }
    
    /**
     * Broadcast connection state to the Car App
     */
    fun broadcastConnectionState(context: Context, state: ConnectionState) {
        val intent = Intent(ACTION_CONNECTION_STATE).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CONNECTION_STATE, state.name)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Broadcast connection state: $state")
    }
}

/**
 * Helper class for the Car App side to receive data from the phone
 */
class AutoDataReceiver(
    private val onDataReceived: (EucData) -> Unit,
    private val onConnectionStateChanged: (ConnectionState) -> Unit
) : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "AutoDataReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Car App received: ${intent.action}")
        
        when (intent.action) {
            AutoProxyReceiver.ACTION_EUC_DATA_UPDATE -> {
                intent.extras?.let { bundle ->
                    val data = AutoProxyReceiver.parseDataBundle(bundle)
                    onDataReceived(data)
                }
            }
            AutoProxyReceiver.ACTION_CONNECTION_STATE -> {
                val stateName = intent.getStringExtra(AutoProxyReceiver.EXTRA_CONNECTION_STATE)
                val state = try {
                    ConnectionState.valueOf(stateName ?: "DISCONNECTED")
                } catch (e: Exception) {
                    ConnectionState.DISCONNECTED
                }
                onConnectionStateChanged(state)
            }
        }
    }
    
    /**
     * Register this receiver with the appropriate context
     */
    fun register(context: Context) {
        val filter = AutoProxyReceiver.createDataUpdateFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(this, filter)
        }
    }
    
    /**
     * Unregister this receiver
     */
    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receiver: ${e.message}")
        }
    }
}
