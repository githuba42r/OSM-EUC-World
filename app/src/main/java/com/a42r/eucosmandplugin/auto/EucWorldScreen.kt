package com.a42r.eucosmandplugin.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.service.AutoProxyReceiver
import com.a42r.eucosmandplugin.service.ConnectionState

/**
 * Quick-glance screen for Android Auto showing EUC battery status.
 * 
 * Design: Simple, large text optimized for quick glances while driving.
 * 
 * Layout:
 * ┌─────────────────────────────────┐
 * │           EUC World             │
 * │                                 │
 * │             85%                 │  <- Large battery percentage
 * │            84.2V                │  <- Voltage
 * │                                 │
 * │   Trip A: 12.3 km               │  <- Only shown if active
 * │   Trip B: 5.7 km                │  <- Only shown if active
 * └─────────────────────────────────┘
 * 
 * When disconnected:
 * ┌─────────────────────────────────┐
 * │           EUC World             │
 * │                                 │
 * │              NC                 │  <- Not Connected
 * │                                 │
 * └─────────────────────────────────┘
 */
class EucWorldScreen(carContext: CarContext) : Screen(carContext) {
    
    companion object {
        private const val TAG = "EucWorldScreen"
    }
    
    // Current EUC data
    private var eucData: EucData? = null
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    
    // Trip data from service
    private var tripA: Double? = null
    private var tripB: Double? = null
    private var tripC: Double? = null
    private var tripAActive = false
    private var tripBActive = false
    private var tripCActive = false
    
    // Receiver for data from phone
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Received: ${intent.action}")
            
            when (intent.action) {
                AutoProxyReceiver.ACTION_EUC_DATA_UPDATE -> {
                    intent.extras?.let { bundle ->
                        eucData = AutoProxyReceiver.parseDataBundle(bundle)
                        
                        // Extract trip data
                        val tA = bundle.getDouble(AutoProxyReceiver.EXTRA_TRIP_A, -1.0)
                        val tB = bundle.getDouble(AutoProxyReceiver.EXTRA_TRIP_B, -1.0)
                        val tC = bundle.getDouble(AutoProxyReceiver.EXTRA_TRIP_C, -1.0)
                        tripA = if (tA >= 0) tA else null
                        tripB = if (tB >= 0) tB else null
                        tripC = if (tC >= 0) tC else null
                        tripAActive = bundle.getBoolean(AutoProxyReceiver.EXTRA_TRIP_A_ACTIVE, false)
                        tripBActive = bundle.getBoolean(AutoProxyReceiver.EXTRA_TRIP_B_ACTIVE, false)
                        tripCActive = bundle.getBoolean(AutoProxyReceiver.EXTRA_TRIP_C_ACTIVE, false)
                        
                        connectionState = ConnectionState.CONNECTED
                        invalidate()
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
                    invalidate()
                }
            }
        }
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
        val data = eucData
        
        return if (data != null && connectionState == ConnectionState.CONNECTED) {
            createConnectedTemplate(data)
        } else {
            createDisconnectedTemplate()
        }
    }
    
    /**
     * Create the connected template with battery, voltage, and active trips
     */
    private fun createConnectedTemplate(data: EucData): Template {
        val listBuilder = ItemList.Builder()
        
        // Row 1: Large battery percentage
        listBuilder.addItem(
            Row.Builder()
                .setTitle("${data.batteryPercentage}%")
                .addText(String.format("%.1fV", data.voltage))
                .build()
        )
        
        // Add active trips only
        if (tripAActive && tripA != null) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Trip A")
                    .addText(String.format("%.1f km", tripA))
                    .build()
            )
        }
        
        if (tripBActive && tripB != null) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Trip B")
                    .addText(String.format("%.1f km", tripB))
                    .build()
            )
        }
        
        if (tripCActive && tripC != null) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("Trip C")
                    .addText(String.format("%.1f km", tripC))
                    .build()
            )
        }
        
        return ListTemplate.Builder()
            .setTitle("EUC World")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .build()
    }
    
    /**
     * Create the disconnected template showing NC
     */
    private fun createDisconnectedTemplate(): Template {
        val listBuilder = ItemList.Builder()
        
        // Show NC - Not Connected
        listBuilder.addItem(
            Row.Builder()
                .setTitle("NC")
                .addText("Not Connected")
                .build()
        )
        
        return ListTemplate.Builder()
            .setTitle("EUC World")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .build()
    }
}
