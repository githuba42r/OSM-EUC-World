package com.a42r.eucosmandplugin.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.service.AutoDataReceiver
import com.a42r.eucosmandplugin.service.AutoProxyReceiver
import com.a42r.eucosmandplugin.service.ConnectionState

/**
 * Main screen for the EUC World Android Auto app.
 * 
 * Displays EUC battery percentage and voltage in a large, clear format
 * similar to a Motoeye E6 HUD display. The screen receives data from
 * the phone via broadcast IPC since the Car App cannot directly access
 * localhost on the phone.
 * 
 * Display Layout (similar to Motoeye E6 HUD):
 * ┌─────────────────────────────────┐
 * │           EUC World             │
 * │                                 │
 * │  Begode Master          SN123   │  <- Wheel name/model + serial ID
 * │  85%                    84.2V   │  <- Battery % (left) + Voltage (right)
 * │                                 │
 * │    Speed: 25 km/h   Temp: 32°C  │  <- Additional metrics
 * └─────────────────────────────────┘
 */
class EucWorldScreen(carContext: CarContext) : Screen(carContext) {
    
    companion object {
        private const val TAG = "EucWorldScreen"
        private const val REFRESH_INTERVAL_MS = 500L
    }
    
    // Current EUC data
    private var eucData: EucData? = null
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    
    // Receiver for data from phone
    private val dataReceiver = AutoDataReceiver(
        onDataReceived = { data ->
            eucData = data
            invalidate() // Trigger screen refresh
        },
        onConnectionStateChanged = { state ->
            connectionState = state
            invalidate()
        }
    )
    
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // Register receiver and subscribe to updates
            registerDataReceiver()
            AutoProxyReceiver.subscribe(carContext)
            Log.d(TAG, "EucWorldScreen started, subscribed to updates")
        }
        
        override fun onStop(owner: LifecycleOwner) {
            // Unregister receiver and unsubscribe
            unregisterDataReceiver()
            AutoProxyReceiver.unsubscribe(carContext)
            Log.d(TAG, "EucWorldScreen stopped, unsubscribed from updates")
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
        return when {
            connectionState == ConnectionState.ERROR || 
            connectionState == ConnectionState.DISCONNECTED -> {
                createDisconnectedTemplate()
            }
            eucData == null -> {
                createLoadingTemplate()
            }
            else -> {
                createDataTemplate(eucData!!)
            }
        }
    }
    
    /**
     * Create the main data display template showing battery and voltage
     * in a HUD-style layout
     */
    private fun createDataTemplate(data: EucData): Template {
        val paneBuilder = Pane.Builder()
        
        // Line 1: Wheel name/model and serial ID
        val wheelName = if (data.wheelModel.isNotEmpty()) {
            "${data.wheelBrand} ${data.wheelModel}"
        } else {
            "Unknown Wheel"
        }
        val serialText = if (data.serialNumber.isNotEmpty()) data.serialNumber else ""
        
        val wheelRow = Row.Builder()
            .setTitle(wheelName)
            .apply {
                if (serialText.isNotEmpty()) {
                    addText(serialText)
                }
            }
            .build()
        paneBuilder.addRow(wheelRow)
        
        // Line 2: Battery percentage (left) and voltage (right)
        val batteryRow = Row.Builder()
            .setTitle(createBatteryTitle(data))
            .addText(createVoltageText(data))
            .build()
        paneBuilder.addRow(batteryRow)
        
        // Speed and temperature row
        val metricsRow = Row.Builder()
            .setTitle("Speed: ${String.format("%.0f", data.speed)} km/h")
            .addText("Temp: ${String.format("%.0f", data.temperature)}°C")
            .build()
        paneBuilder.addRow(metricsRow)
        
        // Power and PWM row
        val powerRow = Row.Builder()
            .setTitle("Power: ${String.format("%.0f", data.power)}W")
            .addText("Load: ${String.format("%.0f", data.pwm)}%")
            .build()
        paneBuilder.addRow(powerRow)
        
        // Add refresh action
        val refreshAction = Action.Builder()
            .setTitle("Refresh")
            .setOnClickListener {
                AutoProxyReceiver.requestData(carContext)
            }
            .build()
        paneBuilder.addAction(refreshAction)
        
        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(createHeaderTitle(data))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
    
    /**
     * Create battery percentage display with color coding
     */
    private fun createBatteryTitle(data: EucData): CarText {
        val batteryText = "${data.batteryPercentage}%"
        
        // Note: CarText color support is limited in Android Auto templates.
        // The color logic is kept here for future use when Android Auto supports
        // colored text in PaneTemplate rows.
        // Color would be: RED (<10%), ORANGE (<20%), YELLOW (<40%), GREEN (>=40%)
        
        return CarText.Builder(batteryText)
            .addVariant(batteryText)
            .build()
    }
    
    /**
     * Create voltage display text
     */
    private fun createVoltageText(data: EucData): CharSequence {
        return String.format("%.1fV", data.voltage)
    }
    
    /**
     * Create header title with connection status
     */
    private fun createHeaderTitle(data: EucData): String {
        val status = if (data.isConnected) "Connected" else "Wheel Disconnected"
        return "EUC World - $status"
    }
    
    /**
     * Create template shown when disconnected from EUC World API
     */
    private fun createDisconnectedTemplate(): Template {
        val messageBuilder = MessageTemplate.Builder(
            "Not connected to EUC World.\n\nMake sure EUC World app is running on your phone and connected to your wheel."
        )
        
        messageBuilder.setTitle("EUC World")
        messageBuilder.setHeaderAction(Action.APP_ICON)
        
        // Retry action
        val retryAction = Action.Builder()
            .setTitle("Retry")
            .setOnClickListener {
                AutoProxyReceiver.subscribe(carContext)
                AutoProxyReceiver.requestData(carContext)
                invalidate()
            }
            .build()
        messageBuilder.addAction(retryAction)
        
        return messageBuilder.build()
    }
    
    /**
     * Create loading template while waiting for data
     */
    private fun createLoadingTemplate(): Template {
        val messageBuilder = MessageTemplate.Builder(
            "Connecting to EUC World..."
        )
        
        messageBuilder.setTitle("EUC World")
        messageBuilder.setHeaderAction(Action.APP_ICON)
        messageBuilder.setLoading(true)
        
        return messageBuilder.build()
    }
}
