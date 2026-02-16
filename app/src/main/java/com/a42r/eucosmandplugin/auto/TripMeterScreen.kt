package com.a42r.eucosmandplugin.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.a42r.eucosmandplugin.util.TripMeterManager

/**
 * Android Auto screen showing list of trip meters.
 * Tapping on a trip meter opens the detail screen.
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
        
        // Build list of trip meter items (browsable rows navigate to detail screen)
        val listBuilder = ItemList.Builder()
        
        // Trip A - browsable row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip A: ${formatTripDistance(tripA)} km")
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.push(
                        TripMeterDetailScreen(
                            carContext,
                            tripMeterManager,
                            TripMeterManager.TripMeter.A,
                            currentOdometer
                        )
                    )
                }
                .build()
        )
        
        // Trip B - browsable row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip B: ${formatTripDistance(tripB)} km")
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.push(
                        TripMeterDetailScreen(
                            carContext,
                            tripMeterManager,
                            TripMeterManager.TripMeter.B,
                            currentOdometer
                        )
                    )
                }
                .build()
        )
        
        // Trip C - browsable row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Trip C: ${formatTripDistance(tripC)} km")
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.push(
                        TripMeterDetailScreen(
                            carContext,
                            tripMeterManager,
                            TripMeterManager.TripMeter.C,
                            currentOdometer
                        )
                    )
                }
                .build()
        )
        
        // Clear All - browsable row leading to confirmation
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Clear All Trips")
                .setBrowsable(true)
                .setOnClickListener {
                    screenManager.push(
                        ClearAllTripsConfirmScreen(
                            carContext,
                            tripMeterManager,
                            currentOdometer
                        )
                    )
                }
                .build()
        )
        
        // Use ListTemplate for the trip meter list
        return ListTemplate.Builder()
            .setTitle("Trip Meters")
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
