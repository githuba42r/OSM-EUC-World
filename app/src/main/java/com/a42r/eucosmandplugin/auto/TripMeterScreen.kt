package com.a42r.eucosmandplugin.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.a42r.eucosmandplugin.util.TripMeterManager

/**
 * Android Auto screen for managing trip meters.
 * 
 * Due to Android Auto constraints:
 * - PaneTemplate max: 2 actions
 * - ListTemplate rows: 0 actions allowed
 * - ActionStrip: Must have icons
 * 
 * Solution: Show info only, with 2 actions: Reset Selected and Clear All
 */
class TripMeterScreen(
    carContext: CarContext,
    private val tripMeterManager: TripMeterManager,
    private val currentOdometer: Double
) : Screen(carContext) {
    
    companion object {
        private const val TAG = "TripMeterScreen"
    }
    
    override fun onGetTemplate(): Template {
        Log.d(TAG, "onGetTemplate called")
        
        val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
        
        // Build message showing all trip meters
        val message = buildString {
            append("Trip A: ${formatTripDistance(tripA)} km\n")
            append("Trip B: ${formatTripDistance(tripB)} km\n")
            append("Trip C: ${formatTripDistance(tripC)} km")
        }
        
        // Use MessageTemplate with limited actions (max 2)
        return MessageTemplate.Builder(message)
            .setTitle("Trip Meters")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Reset All")
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        tripMeterManager.resetTrip(TripMeterManager.TripMeter.A, currentOdometer)
                        tripMeterManager.resetTrip(TripMeterManager.TripMeter.B, currentOdometer)
                        tripMeterManager.resetTrip(TripMeterManager.TripMeter.C, currentOdometer)
                        invalidate()
                    })
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Clear All")
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        tripMeterManager.clearAllTrips()
                        invalidate()
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
