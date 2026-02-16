package com.a42r.eucosmandplugin.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.a42r.eucosmandplugin.util.TripMeterManager

/**
 * Android Auto screen for managing trip meters.
 * Allows resetting individual trip meters or clearing all.
 * 
 * Uses PaneTemplate which allows actions with ParkedOnlyOnClickListener
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
        
        // Build pane with trip meter info
        val paneBuilder = Pane.Builder()
        
        // Add trip meter rows
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Trip A")
                .addText("${formatTripDistance(tripA)} km")
                .build()
        )
        
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Trip B")
                .addText("${formatTripDistance(tripB)} km")
                .build()
        )
        
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Trip C")
                .addText("${formatTripDistance(tripC)} km")
                .build()
        )
        
        // Add action buttons for resetting trips
        paneBuilder.addAction(
            Action.Builder()
                .setTitle("Reset Trip A")
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    tripMeterManager.resetTrip(TripMeterManager.TripMeter.A, currentOdometer)
                    invalidate()
                })
                .build()
        )
        
        paneBuilder.addAction(
            Action.Builder()
                .setTitle("Reset Trip B")
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    tripMeterManager.resetTrip(TripMeterManager.TripMeter.B, currentOdometer)
                    invalidate()
                })
                .build()
        )
        
        paneBuilder.addAction(
            Action.Builder()
                .setTitle("Reset Trip C")
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    tripMeterManager.resetTrip(TripMeterManager.TripMeter.C, currentOdometer)
                    invalidate()
                })
                .build()
        )
        
        paneBuilder.addAction(
            Action.Builder()
                .setTitle("Clear All")
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    tripMeterManager.clearAllTrips()
                    invalidate()
                })
                .build()
        )
        
        // Use PaneTemplate for the trip meter management screen
        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("Trip Meters")
            .setHeaderAction(Action.BACK)
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
