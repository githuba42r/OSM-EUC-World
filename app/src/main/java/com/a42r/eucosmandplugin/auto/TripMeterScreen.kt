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
        
        // Build list of trip meter items (without actions - they crash)
        val listBuilder = ItemList.Builder()
        
        // Trip A
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip A")
                .addText("${formatTripDistance(tripA)} km")
                .build()
        )
        
        // Trip B
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip B")
                .addText("${formatTripDistance(tripB)} km")
                .build()
        )
        
        // Trip C
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip C")
                .addText("${formatTripDistance(tripC)} km")
                .build()
        )
        
        // Build action strip with reset buttons
        val actionStripBuilder = ActionStrip.Builder()
        
        // Add individual reset actions (these CAN have FLAG_PRIMARY in action strip)
        actionStripBuilder.addAction(
            Action.Builder()
                .setTitle("Reset A")
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    tripMeterManager.resetTrip(TripMeterManager.TripMeter.A, currentOdometer)
                    invalidate()
                })
                .build()
        )
        
        actionStripBuilder.addAction(
            Action.Builder()
                .setTitle("Reset B")
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    tripMeterManager.resetTrip(TripMeterManager.TripMeter.B, currentOdometer)
                    invalidate()
                })
                .build()
        )
        
        actionStripBuilder.addAction(
            Action.Builder()
                .setTitle("Reset C")
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener(ParkedOnlyOnClickListener.create {
                    tripMeterManager.resetTrip(TripMeterManager.TripMeter.C, currentOdometer)
                    invalidate()
                })
                .build()
        )
        
        actionStripBuilder.addAction(
            Action.Builder()
                .setTitle("Clear All")
                .setFlags(Action.FLAG_PRIMARY)
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
