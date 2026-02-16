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
        
        // Build message showing trip meter value
        val message = buildString {
            append("${formatTripDistance(tripDistance)} km")
            if (!isActive) {
                append("\n\n(Not tracking)")
            }
        }
        
        // Use MessageTemplate with Reset and Clear actions
        return MessageTemplate.Builder(message)
            .setTitle("Trip ${tripMeter.name}")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Reset")
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        tripMeterManager.resetTrip(tripMeter, currentOdometer)
                        invalidate()
                    })
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Clear")
                    .setOnClickListener(ParkedOnlyOnClickListener.create {
                        tripMeterManager.clearTrip(tripMeter)
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
