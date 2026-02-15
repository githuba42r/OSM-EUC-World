package com.a42r.eucosmandplugin.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.mapwidget.AMapWidget
import net.osmand.aidlapi.mapwidget.AddMapWidgetParams
import net.osmand.aidlapi.mapwidget.RemoveMapWidgetParams
import net.osmand.aidlapi.mapwidget.UpdateMapWidgetParams
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.util.TripMeterManager

/**
 * Helper class for managing OsmAnd AIDL connection and widget updates.
 * 
 * This class handles:
 * - Binding to OsmAnd's AIDL service
 * - Registering custom widgets for EUC data display
 * - Updating widget content when EUC data changes
 * 
 * OsmAnd supports external widgets via AIDL that display custom text
 * on the map view. We use this to show battery %, voltage, speed, etc.
 */
class OsmAndAidlHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "OsmAndAidlHelper"
        
        // OsmAnd package names
        const val OSMAND_PACKAGE = "net.osmand"
        const val OSMAND_PLUS_PACKAGE = "net.osmand.plus"
        const val OSMAND_DEV_PACKAGE = "net.osmand.dev"
        
        // AIDL service action
        const val OSMAND_AIDL_SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"
        
        // Widget IDs - unique identifiers for our widgets
        const val WIDGET_ID_BATTERY = "euc_world_battery"
        const val WIDGET_ID_BATTERY_PERCENT = "euc_world_battery_percent"
        const val WIDGET_ID_VOLTAGE = "euc_world_voltage"
        const val WIDGET_ID_TRIP_A = "euc_world_trip_a"
        const val WIDGET_ID_TRIP_B = "euc_world_trip_b"
        const val WIDGET_ID_TRIP_C = "euc_world_trip_c"
        
        // Widget display order (lower = higher on screen)
        const val WIDGET_ORDER_BATTERY = 100
        const val WIDGET_ORDER_BATTERY_PERCENT = 101
        const val WIDGET_ORDER_VOLTAGE = 102
        const val WIDGET_ORDER_TRIP_A = 110
        const val WIDGET_ORDER_TRIP_B = 111
        const val WIDGET_ORDER_TRIP_C = 112
    }
    
    private var osmAndInterface: IOsmAndAidlInterface? = null
    private var isBound = false
    private var targetPackage: String? = null
    
    private val _connectionState = MutableStateFlow(OsmAndConnectionState.DISCONNECTED)
    val connectionState: StateFlow<OsmAndConnectionState> = _connectionState
    
    // Trip meter manager for calculating trip distances
    private val tripMeterManager = TripMeterManager(context)
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Connected to OsmAnd AIDL service: $name")
            osmAndInterface = IOsmAndAidlInterface.Stub.asInterface(service)
            isBound = true
            _connectionState.value = OsmAndConnectionState.CONNECTED
            
            // First remove any stale widget, then register fresh
            removeWidgets()
            registerWidgets()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Disconnected from OsmAnd AIDL service")
            osmAndInterface = null
            isBound = false
            _connectionState.value = OsmAndConnectionState.DISCONNECTED
        }
    }
    
    /**
     * Connect to OsmAnd's AIDL service.
     * @return true if binding was initiated
     */
    fun connect(): Boolean {
        if (isBound) {
            Log.d(TAG, "Already connected to OsmAnd")
            return true
        }
        
        targetPackage = findOsmAndPackage()
        if (targetPackage == null) {
            Log.w(TAG, "OsmAnd is not installed")
            _connectionState.value = OsmAndConnectionState.NOT_INSTALLED
            return false
        }
        
        _connectionState.value = OsmAndConnectionState.CONNECTING
        
        val intent = Intent(OSMAND_AIDL_SERVICE_ACTION).apply {
            setPackage(targetPackage)
        }
        
        return try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to OsmAnd service: ${e.message}")
            _connectionState.value = OsmAndConnectionState.ERROR
            false
        }
    }
    
    /**
     * Disconnect from OsmAnd's AIDL service.
     */
    fun disconnect() {
        if (isBound) {
            // Remove widgets before disconnecting
            removeWidgets()
            
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding from OsmAnd: ${e.message}")
            }
            
            osmAndInterface = null
            isBound = false
            _connectionState.value = OsmAndConnectionState.DISCONNECTED
        }
    }
    
    /**
     * Check if connected to OsmAnd.
     */
    fun isConnected(): Boolean = isBound && osmAndInterface != null
    
    /**
     * Create intent to launch our app when widget is clicked.
     */
    private fun createClickIntent(): Intent {
        return Intent().apply {
            setClassName(context.packageName, "com.a42r.eucosmandplugin.ui.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    
    /**
     * Register EUC widgets with OsmAnd.
     */
    private fun registerWidgets() {
        val aidl = osmAndInterface ?: return
        
        try {
            // Widget 1: Combined battery + voltage (main display)
            registerWidget(aidl, 
                WIDGET_ID_BATTERY, 
                "EUC Battery & Voltage", 
                "--%", 
                "--V", 
                WIDGET_ORDER_BATTERY
            )
            
            // Widget 2: Battery percentage only
            registerWidget(aidl,
                WIDGET_ID_BATTERY_PERCENT,
                "EUC Battery %",
                "--%",
                "EUC",
                WIDGET_ORDER_BATTERY_PERCENT
            )
            
            // Widget 3: Voltage only
            registerWidget(aidl,
                WIDGET_ID_VOLTAGE,
                "EUC Voltage",
                "--V",
                "EUC",
                WIDGET_ORDER_VOLTAGE
            )
            
            // Widget 4: Trip A
            registerWidget(aidl,
                WIDGET_ID_TRIP_A,
                "EUC Trip A",
                "--",
                "Trip A",
                WIDGET_ORDER_TRIP_A,
                "ic_action_distance"
            )
            
            // Widget 5: Trip B
            registerWidget(aidl,
                WIDGET_ID_TRIP_B,
                "EUC Trip B",
                "--",
                "Trip B",
                WIDGET_ORDER_TRIP_B,
                "ic_action_distance"
            )
            
            // Widget 6: Trip C
            registerWidget(aidl,
                WIDGET_ID_TRIP_C,
                "EUC Trip C",
                "--",
                "Trip C",
                WIDGET_ORDER_TRIP_C,
                "ic_action_distance"
            )
            
            Log.d(TAG, "Registered all EUC widgets")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register widgets: ${e.message}", e)
        }
    }
    
    /**
     * Helper to register a single widget
     */
    private fun registerWidget(
        aidl: IOsmAndAidlInterface,
        widgetId: String,
        menuTitle: String,
        text: String,
        description: String,
        order: Int,
        iconName: String = "ic_action_battery"
    ) {
        val widget = AMapWidget(
            widgetId,
            iconName,
            menuTitle,
            iconName,
            iconName,
            text,
            description,
            order,
            null
        )
        
        val params = AddMapWidgetParams(widget)
        val success = aidl.addMapWidget(params)
        Log.d(TAG, "Registered widget $widgetId: $success")
    }
    
    /**
     * Remove EUC widgets from OsmAnd.
     */
    private fun removeWidgets() {
        val aidl = osmAndInterface ?: return
        
        try {
            // Remove all widgets
            listOf(
                WIDGET_ID_BATTERY,
                WIDGET_ID_BATTERY_PERCENT,
                WIDGET_ID_VOLTAGE,
                WIDGET_ID_TRIP_A,
                WIDGET_ID_TRIP_B,
                WIDGET_ID_TRIP_C
            ).forEach { widgetId ->
                val params = RemoveMapWidgetParams(widgetId)
                aidl.removeMapWidget(params)
            }
            Log.d(TAG, "Removed all EUC widgets")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove widgets: ${e.message}")
        }
    }
    
    /**
     * Update widgets with latest EUC data.
     */
    fun updateWidgetData(data: EucData) {
        val aidl = osmAndInterface ?: return
        
        try {
            // Get trip distances
            val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(data.totalDistance)
            
            // Widget 1: Combined battery + voltage
            updateWidget(aidl,
                WIDGET_ID_BATTERY,
                "EUC Battery & Voltage",
                "${data.batteryPercentage}%",
                String.format("%.1fV", data.voltage),
                WIDGET_ORDER_BATTERY
            )
            
            // Widget 2: Battery percentage only
            updateWidget(aidl,
                WIDGET_ID_BATTERY_PERCENT,
                "EUC Battery %",
                "${data.batteryPercentage}%",
                "EUC",
                WIDGET_ORDER_BATTERY_PERCENT
            )
            
            // Widget 3: Voltage only
            updateWidget(aidl,
                WIDGET_ID_VOLTAGE,
                "EUC Voltage",
                String.format("%.1fV", data.voltage),
                "EUC",
                WIDGET_ORDER_VOLTAGE
            )
            
            // Widget 4: Trip A
            updateWidget(aidl,
                WIDGET_ID_TRIP_A,
                "EUC Trip A",
                formatTripDistance(tripA),
                "Trip A",
                WIDGET_ORDER_TRIP_A,
                "ic_action_distance"
            )
            
            // Widget 5: Trip B
            updateWidget(aidl,
                WIDGET_ID_TRIP_B,
                "EUC Trip B",
                formatTripDistance(tripB),
                "Trip B",
                WIDGET_ORDER_TRIP_B,
                "ic_action_distance"
            )
            
            // Widget 6: Trip C
            updateWidget(aidl,
                WIDGET_ID_TRIP_C,
                "EUC Trip C",
                formatTripDistance(tripC),
                "Trip C",
                WIDGET_ORDER_TRIP_C,
                "ic_action_distance"
            )
            
            Log.v(TAG, "Updated all widgets: ${data.batteryPercentage}% / ${data.voltage}V")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widgets: ${e.message}")
        }
    }
    
    /**
     * Helper to update a single widget
     */
    private fun updateWidget(
        aidl: IOsmAndAidlInterface,
        widgetId: String,
        menuTitle: String,
        text: String,
        description: String,
        order: Int,
        iconName: String = "ic_action_battery"
    ) {
        val widget = AMapWidget(
            widgetId,
            iconName,
            menuTitle,
            iconName,
            iconName,
            text,
            description,
            order,
            null
        )
        
        val params = UpdateMapWidgetParams(widget)
        aidl.updateMapWidget(params)
    }
    
    /**
     * Format trip distance for widget display
     */
    private fun formatTripDistance(distance: Double?): String {
        return if (distance != null) {
            String.format("%.1f km", distance)
        } else {
            "-- km"
        }
    }
    
    /**
     * Clear all widget content when EUC World is not available.
     * Widgets will show no text/output.
     */
    fun clearWidgets() {
        val aidl = osmAndInterface ?: return
        
        try {
            // Clear all widgets by setting empty text
            updateWidget(aidl,
                WIDGET_ID_BATTERY,
                "EUC Battery & Voltage",
                "",
                "",
                WIDGET_ORDER_BATTERY
            )
            
            updateWidget(aidl,
                WIDGET_ID_BATTERY_PERCENT,
                "EUC Battery %",
                "",
                "",
                WIDGET_ORDER_BATTERY_PERCENT
            )
            
            updateWidget(aidl,
                WIDGET_ID_VOLTAGE,
                "EUC Voltage",
                "",
                "",
                WIDGET_ORDER_VOLTAGE
            )
            
            updateWidget(aidl,
                WIDGET_ID_TRIP_A,
                "EUC Trip A",
                "",
                "",
                WIDGET_ORDER_TRIP_A,
                "ic_action_distance"
            )
            
            updateWidget(aidl,
                WIDGET_ID_TRIP_B,
                "EUC Trip B",
                "",
                "",
                WIDGET_ORDER_TRIP_B,
                "ic_action_distance"
            )
            
            updateWidget(aidl,
                WIDGET_ID_TRIP_C,
                "EUC Trip C",
                "",
                "",
                WIDGET_ORDER_TRIP_C,
                "ic_action_distance"
            )
            
            Log.d(TAG, "Cleared all widget content")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear widgets: ${e.message}")
        }
    }
    
    /**
     * Find installed OsmAnd package.
     */
    private fun findOsmAndPackage(): String? {
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
     * Check if OsmAnd is installed.
     */
    fun isOsmAndInstalled(): Boolean = findOsmAndPackage() != null
    
    /**
     * Get the installed OsmAnd package name.
     */
    fun getOsmAndPackage(): String? = findOsmAndPackage()
}

/**
 * Connection states for OsmAnd AIDL service.
 */
enum class OsmAndConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    NOT_INSTALLED,
    ERROR
}
