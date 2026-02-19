package com.a42r.eucosmandplugin.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.a42r.eucosmandplugin.util.TripMeterManager

/**
 * Confirmation screen for clearing all trip meters.
 */
class ClearAllTripsConfirmScreen(
    carContext: CarContext,
    private val tripMeterManager: TripMeterManager,
    private val currentOdometer: Double
) : Screen(carContext) {
    
    companion object {
        private const val TAG = "ClearAllTripsConfirmScreen"
    }
    
    override fun onGetTemplate(): Template {
        Log.d(TAG, "onGetTemplate called")
        
        val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
        
        // Build list showing current values and actions
        val listBuilder = ItemList.Builder()
        
        // Show current trip values
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Current Trip Meters")
                .addText("A: ${formatTripDistance(tripA)} km  •  B: ${formatTripDistance(tripB)} km  •  C: ${formatTripDistance(tripC)} km")
                .build()
        )
        
        // Clear All action - browsable row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Clear All Trips")
                .addText("Stop tracking all trip meters")
                .setBrowsable(true)
                .setOnClickListener {
                    tripMeterManager.clearAllTrips()
                    screenManager.popToRoot()
                }
                .build()
        )
        
        // Cancel action - browsable row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Cancel")
                .addText("Go back without clearing")
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.pop()
                }
                .build()
        )
        
        // Use ListTemplate to allow use while driving
        return ListTemplate.Builder()
            .setTitle("Clear All?")
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
