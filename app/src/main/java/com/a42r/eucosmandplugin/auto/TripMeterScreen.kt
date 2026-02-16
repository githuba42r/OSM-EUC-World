package com.a42r.eucosmandplugin.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.a42r.eucosmandplugin.util.TripMeterManager

/**
 * Android Auto screen for managing trip meters.
 * Allows resetting individual trip meters or clearing all.
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
        
        // Build list of trip meter items
        val listBuilder = ItemList.Builder()
        
        // Trip A
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip A: ${formatTripDistance(tripA)} km")
                .addAction(
                    Action.Builder()
                        .setTitle("Reset")
                        .setOnClickListener(ParkedOnlyOnClickListener.create {
                            tripMeterManager.resetTrip(TripMeterManager.TripMeter.A, currentOdometer)
                            invalidate()
                        })
                        .build()
                )
                .build()
        )
        
        // Trip B
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip B: ${formatTripDistance(tripB)} km")
                .addAction(
                    Action.Builder()
                        .setTitle("Reset")
                        .setOnClickListener(ParkedOnlyOnClickListener.create {
                            tripMeterManager.resetTrip(TripMeterManager.TripMeter.B, currentOdometer)
                            invalidate()
                        })
                        .build()
                )
                .build()
        )
        
        // Trip C
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip C: ${formatTripDistance(tripC)} km")
                .addAction(
                    Action.Builder()
                        .setTitle("Reset")
                        .setOnClickListener(ParkedOnlyOnClickListener.create {
                            tripMeterManager.resetTrip(TripMeterManager.TripMeter.C, currentOdometer)
                            invalidate()
                        })
                        .build()
                )
                .build()
        )
        
        // Build action strip with Clear All button
        val actionStripBuilder = ActionStrip.Builder()
        actionStripBuilder.addAction(
            Action.Builder()
                .setTitle("Clear All")
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    tripMeterManager.clearAllTrips()
                    invalidate()
                })
                .build()
        )
        
        // Use ListTemplate for the trip meter management screen
        return ListTemplate.Builder()
            .setTitle("Trip Meters")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .setActionStrip(actionStripBuilder.build())
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
