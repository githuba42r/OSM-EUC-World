package com.a42r.eucosmandplugin.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.a42r.eucosmandplugin.api.EucData

/**
 * Service that connects to OsmAnd and provides EUC data for display.
 * 
 * OsmAnd provides two integration methods:
 * 1. OsmAnd API via Intents - for triggering actions
 * 2. Custom widgets via broadcasts - for displaying data
 * 
 * This service uses broadcasts to update a custom info widget in OsmAnd
 * that displays EUC battery percentage and voltage, similar to the
 * speed display on a Motoeye E6 HUD.
 */
class OsmAndConnectionService : Service() {
    
    companion object {
        private const val TAG = "OsmAndConnection"
        
        // OsmAnd package names
        const val OSMAND_PACKAGE = "net.osmand"
        const val OSMAND_PLUS_PACKAGE = "net.osmand.plus"
        const val OSMAND_DEV_PACKAGE = "net.osmand.dev"
        
        // OsmAnd API actions
        const val OSMAND_SHOW_GPX = "net.osmand.SHOW_GPX"
        const val OSMAND_NAVIGATE = "net.osmand.NAVIGATE"
        const val OSMAND_NAVIGATE_GPX = "net.osmand.NAVIGATE_GPX"
        const val OSMAND_RECORD_GPX = "net.osmand.RECORD_GPX"
        const val OSMAND_ADD_FAVORITE = "net.osmand.ADD_FAVORITE"
        const val OSMAND_ADD_MAP_MARKER = "net.osmand.ADD_MAP_MARKER"
        
        // Custom broadcast for EUC data widget update
        const val ACTION_UPDATE_EUC_WIDGET = "com.a42r.eucosmandplugin.UPDATE_WIDGET"
        
        // Extras for widget data
        const val EXTRA_BATTERY_TEXT = "battery_text"
        const val EXTRA_VOLTAGE_TEXT = "voltage_text"
        const val EXTRA_SPEED_TEXT = "speed_text"
        const val EXTRA_TEMPERATURE_TEXT = "temperature_text"
        const val EXTRA_POWER_TEXT = "power_text"
        const val EXTRA_CONNECTED = "connected"
        
        /**
         * Get the installed OsmAnd package name
         */
        fun getOsmAndPackage(context: Context): String? {
            val pm = context.packageManager
            return listOf(OSMAND_PLUS_PACKAGE, OSMAND_PACKAGE, OSMAND_DEV_PACKAGE)
                .firstOrNull { pkg ->
                    try {
                        pm.getPackageInfo(pkg, 0)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
        }
        
        /**
         * Check if OsmAnd is installed
         */
        fun isOsmAndInstalled(context: Context): Boolean {
            return getOsmAndPackage(context) != null
        }
    }
    
    private val binder = LocalBinder()
    private var latestEucData: EucData? = null
    private var isConnected = false
    
    inner class LocalBinder : Binder() {
        fun getService(): OsmAndConnectionService = this@OsmAndConnectionService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OsmAnd Connection Service created")
    }
    
    override fun onDestroy() {
        Log.d(TAG, "OsmAnd Connection Service destroyed")
        super.onDestroy()
    }
    
    /**
     * Update EUC data and broadcast to OsmAnd widget
     */
    fun updateEucData(data: EucData) {
        latestEucData = data
        broadcastWidgetUpdate(data)
    }
    
    /**
     * Broadcast EUC data update for OsmAnd widget display
     */
    private fun broadcastWidgetUpdate(data: EucData) {
        val intent = Intent(ACTION_UPDATE_EUC_WIDGET).apply {
            // Format like Motoeye E6 HUD: "85%" and "84.2V"
            putExtra(EXTRA_BATTERY_TEXT, "${data.batteryPercentage}%")
            putExtra(EXTRA_VOLTAGE_TEXT, String.format("%.1fV", data.voltage))
            putExtra(EXTRA_SPEED_TEXT, String.format("%.0f", data.speed))
            putExtra(EXTRA_TEMPERATURE_TEXT, String.format("%.0f°C", data.temperature))
            putExtra(EXTRA_POWER_TEXT, String.format("%.0fW", data.power))
            putExtra(EXTRA_CONNECTED, data.isConnected)
        }
        sendBroadcast(intent)
        
        Log.d(TAG, "Broadcast widget update: ${data.batteryPercentage}% ${data.voltage}V")
    }
    
    /**
     * Launch OsmAnd app
     */
    fun launchOsmAnd(): Boolean {
        val osmandPackage = getOsmAndPackage(this) ?: return false
        
        return try {
            val intent = packageManager.getLaunchIntentForPackage(osmandPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch OsmAnd: ${e.message}")
            false
        }
    }
    
    /**
     * Add a map marker at the specified location
     */
    fun addMapMarker(lat: Double, lon: Double, name: String): Boolean {
        val osmandPackage = getOsmAndPackage(this) ?: return false
        
        return try {
            val intent = Intent(OSMAND_ADD_MAP_MARKER).apply {
                setPackage(osmandPackage)
                putExtra("lat", lat)
                putExtra("lon", lon)
                putExtra("name", name)
            }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add map marker: ${e.message}")
            false
        }
    }
    
    /**
     * Start GPX track recording in OsmAnd
     */
    fun startGpxRecording(): Boolean {
        val osmandPackage = getOsmAndPackage(this) ?: return false
        
        return try {
            val intent = Intent(OSMAND_RECORD_GPX).apply {
                setPackage(osmandPackage)
                putExtra("start", true)
            }
            sendBroadcast(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPX recording: ${e.message}")
            false
        }
    }
    
    /**
     * Stop GPX track recording in OsmAnd
     */
    fun stopGpxRecording(): Boolean {
        val osmandPackage = getOsmAndPackage(this) ?: return false
        
        return try {
            val intent = Intent(OSMAND_RECORD_GPX).apply {
                setPackage(osmandPackage)
                putExtra("stop", true)
            }
            sendBroadcast(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop GPX recording: ${e.message}")
            false
        }
    }
    
    /**
     * Navigate to a destination
     */
    fun navigateTo(lat: Double, lon: Double, name: String? = null): Boolean {
        val osmandPackage = getOsmAndPackage(this) ?: return false
        
        return try {
            val intent = Intent(OSMAND_NAVIGATE).apply {
                setPackage(osmandPackage)
                putExtra("dest_lat", lat)
                putExtra("dest_lon", lon)
                name?.let { putExtra("dest_name", it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate: ${e.message}")
            false
        }
    }
    
    /**
     * Get current EUC data
     */
    fun getCurrentData(): EucData? = latestEucData
}
