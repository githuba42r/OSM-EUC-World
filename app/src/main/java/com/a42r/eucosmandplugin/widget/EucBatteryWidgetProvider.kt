package com.a42r.eucosmandplugin.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.RemoteViews
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.service.EucWorldService
import com.a42r.eucosmandplugin.ui.MainActivity

/**
 * Widget provider for the EUC battery status home screen widget.
 * 
 * Displays battery percentage and voltage in a compact format,
 * similar to the Motoeye E6 HUD display style.
 * 
 * Widget Layout:
 * ┌─────────────┐
 * │    85%     │  <- Battery percentage (large)
 * │   84.2V    │  <- Voltage (smaller)
 * │  25 km/h   │  <- Speed (optional)
 * └─────────────┘
 */
class EucBatteryWidgetProvider : AppWidgetProvider() {
    
    companion object {
        const val ACTION_WIDGET_UPDATE = "com.a42r.eucosmandplugin.WIDGET_UPDATE"
        
        /**
         * Update all widgets with new data
         */
        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, EucBatteryWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val widgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = widgetManager.getAppWidgetIds(
                ComponentName(context, EucBatteryWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            context.sendBroadcast(intent)
        }
        
        /**
         * Update widgets with specific EUC data
         */
        fun updateWidgets(
            context: Context,
            batteryPercent: Int,
            voltage: Double,
            speed: Double,
            isConnected: Boolean
        ) {
            val widgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = widgetManager.getAppWidgetIds(
                ComponentName(context, EucBatteryWidgetProvider::class.java)
            )
            
            for (widgetId in widgetIds) {
                updateWidget(context, widgetManager, widgetId, batteryPercent, voltage, speed, isConnected)
            }
        }
        
        private fun updateWidget(
            context: Context,
            widgetManager: AppWidgetManager,
            widgetId: Int,
            batteryPercent: Int,
            voltage: Double,
            speed: Double,
            isConnected: Boolean
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_euc_battery)
            
            if (isConnected) {
                // Battery percentage - main display
                views.setTextViewText(R.id.widget_battery_percent, "$batteryPercent%")
                
                // Voltage
                views.setTextViewText(R.id.widget_voltage, String.format("%.1fV", voltage))
                
                // Speed
                views.setTextViewText(R.id.widget_speed, String.format("%.0f km/h", speed))
                
                // Set battery color based on level
                val batteryColor = when {
                    batteryPercent <= 10 -> android.graphics.Color.RED
                    batteryPercent <= 20 -> android.graphics.Color.rgb(255, 165, 0) // Orange
                    batteryPercent <= 40 -> android.graphics.Color.YELLOW
                    else -> android.graphics.Color.GREEN
                }
                views.setTextColor(R.id.widget_battery_percent, batteryColor)
            } else {
                views.setTextViewText(R.id.widget_battery_percent, "--")
                views.setTextViewText(R.id.widget_voltage, "---")
                views.setTextViewText(R.id.widget_speed, "Disconnected")
                views.setTextColor(R.id.widget_battery_percent, android.graphics.Color.GRAY)
            }
            
            // Click to open app
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 
                0, 
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)
            
            widgetManager.updateAppWidget(widgetId, views)
        }
    }
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Get current data from service
        val data = EucWorldService.latestData
        
        for (widgetId in appWidgetIds) {
            if (data != null) {
                updateWidget(
                    context, 
                    appWidgetManager, 
                    widgetId,
                    data.batteryPercentage,
                    data.voltage,
                    data.speed,
                    data.isConnected
                )
            } else {
                // Show disconnected state
                updateWidget(context, appWidgetManager, widgetId, 0, 0.0, 0.0, false)
            }
        }
    }
    
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Start service when first widget is added
        EucWorldService.start(context)
    }
    
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Optionally stop service when last widget is removed
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        // Handle custom update broadcasts from the service
        if (intent.action == EucWorldService.ACTION_UPDATE) {
            val batteryPercent = intent.getIntExtra(EucWorldService.EXTRA_EUC_DATA, 0)
            val voltage = intent.getDoubleExtra("voltage", 0.0)
            val speed = intent.getDoubleExtra("speed", 0.0)
            val isConnected = intent.getBooleanExtra("connected", false)
            
            updateWidgets(context, batteryPercent, voltage, speed, isConnected)
        }
    }
}
