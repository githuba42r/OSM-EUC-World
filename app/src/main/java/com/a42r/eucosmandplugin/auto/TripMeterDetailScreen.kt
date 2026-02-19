package com.a42r.eucosmandplugin.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.a42r.eucosmandplugin.util.TripMeterManager

/**
 * Android Auto detail screen for a single trip meter.
 * Shows the trip value and provides Reset/Clear actions.
 * 
 * Reset = Set to current odometer (start tracking from now)
 * Clear = Set to not tracking (no value)
 */
class TripMeterDetailScreen(
    carContext: CarContext,
    private val tripMeterManager: TripMeterManager,
    private val tripMeter: TripMeterManager.TripMeter,
    private val currentOdometer: Double
) : Screen(carContext) {
    
    companion object {
        private const val TAG = "TripMeterDetailScreen"
    }
    
    override fun onGetTemplate(): Template {
        Log.d(TAG, "onGetTemplate called for ${tripMeter.name}")
        
        val tripDistance = tripMeterManager.getTripDistance(tripMeter, currentOdometer)
        val isActive = tripMeterManager.isTripActive(tripMeter)
        
        // Build list with trip value and action items
        val listBuilder = ItemList.Builder()
        
        // Show current trip value
        val distanceText = if (isActive) {
            "${formatTripDistance(tripDistance)} km"
        } else {
            "${formatTripDistance(tripDistance)} km (Not tracking)"
        }
        
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Distance")
                .addText(distanceText)
                .build()
        )
        
        // Reset action - browsable row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Reset Trip ${tripMeter.name}")
                .addText("Start tracking from current odometer")
                .setBrowsable(true)
                .setOnClickListener {
                    tripMeterManager.resetTrip(tripMeter, currentOdometer)
                    invalidate()
                    screenManager.pop()
                }
                .build()
        )
        
        // Clear action - browsable row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Clear Trip ${tripMeter.name}")
                .addText("Stop tracking this trip")
                .setBrowsable(true)
                .setOnClickListener {
                    tripMeterManager.clearTrip(tripMeter)
                    invalidate()
                    screenManager.pop()
                }
                .build()
        )
        
        // Use ListTemplate to allow use while driving
        return ListTemplate.Builder()
            .setTitle("Trip ${tripMeter.name}")
            .setHeaderAction(Action.BACK)
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
