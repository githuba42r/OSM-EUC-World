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
        
        val message = "${formatTripDistance(tripA)} km / ${formatTripDistance(tripB)} km / ${formatTripDistance(tripC)} km"
        
        return MessageTemplate.Builder(message)
            .setTitle("Clear All?")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Cancel")
                    .setOnClickListener {
                        screenManager.pop()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Clear All")
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        tripMeterManager.clearAllTrips()
                        // Pop back to list screen
                        screenManager.pop()
                    })
                    .build()
            )
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
