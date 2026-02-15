package com.a42r.eucosmandplugin.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages internal trip meters (A, B, C) that calculate distance
 * based on the wheel's total odometer (vdt).
 * 
 * Each trip meter stores the odometer value at the time it was reset.
 * Current trip distance = current odometer - stored odometer value
 */
class TripMeterManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "trip_meters"
        private const val KEY_TRIP_A_START = "trip_a_start"
        private const val KEY_TRIP_B_START = "trip_b_start"
        private const val KEY_TRIP_C_START = "trip_c_start"
        
        // Special value indicating trip meter is not set
        private const val NOT_SET = -1.0
    }
    
    enum class TripMeter {
        A, B, C
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Get the starting odometer value for a trip meter
     */
    private fun getTripStart(trip: TripMeter): Double {
        val key = when (trip) {
            TripMeter.A -> KEY_TRIP_A_START
            TripMeter.B -> KEY_TRIP_B_START
            TripMeter.C -> KEY_TRIP_C_START
        }
        return Double.fromBits(prefs.getLong(key, NOT_SET.toBits()))
    }
    
    /**
     * Set/reset a trip meter to start from the current odometer value
     */
    fun resetTrip(trip: TripMeter, currentOdometer: Double) {
        val key = when (trip) {
            TripMeter.A -> KEY_TRIP_A_START
            TripMeter.B -> KEY_TRIP_B_START
            TripMeter.C -> KEY_TRIP_C_START
        }
        prefs.edit().putLong(key, currentOdometer.toBits()).apply()
    }
    
    /**
     * Clear a trip meter (set to not active)
     */
    fun clearTrip(trip: TripMeter) {
        val key = when (trip) {
            TripMeter.A -> KEY_TRIP_A_START
            TripMeter.B -> KEY_TRIP_B_START
            TripMeter.C -> KEY_TRIP_C_START
        }
        prefs.edit().putLong(key, NOT_SET.toBits()).apply()
    }
    
    /**
     * Clear all trip meters
     */
    fun clearAllTrips() {
        prefs.edit()
            .putLong(KEY_TRIP_A_START, NOT_SET.toBits())
            .putLong(KEY_TRIP_B_START, NOT_SET.toBits())
            .putLong(KEY_TRIP_C_START, NOT_SET.toBits())
            .apply()
    }
    
    /**
     * Check if a trip meter is active (has been set)
     */
    fun isTripActive(trip: TripMeter): Boolean {
        return getTripStart(trip) != NOT_SET
    }
    
    /**
     * Calculate the current trip distance
     * @param trip The trip meter to calculate
     * @param currentOdometer The current total odometer value from the wheel
     * @return The trip distance, or null if trip meter is not set
     */
    fun getTripDistance(trip: TripMeter, currentOdometer: Double): Double? {
        val startOdometer = getTripStart(trip)
        if (startOdometer == NOT_SET || currentOdometer < 0) {
            return null
        }
        return (currentOdometer - startOdometer).coerceAtLeast(0.0)
    }
    
    /**
     * Get all trip distances at once
     * @param currentOdometer The current total odometer value from the wheel
     * @return Triple of (Trip A, Trip B, Trip C) distances, null if not set
     */
    fun getAllTripDistances(currentOdometer: Double): Triple<Double?, Double?, Double?> {
        return Triple(
            getTripDistance(TripMeter.A, currentOdometer),
            getTripDistance(TripMeter.B, currentOdometer),
            getTripDistance(TripMeter.C, currentOdometer)
        )
    }
    
    /**
     * Format trip distance for display
     */
    fun formatDistance(distance: Double?): String {
        return if (distance != null) {
            String.format("%.1f km", distance)
        } else {
            "-- km"
        }
    }
}
