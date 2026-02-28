package com.a42r.eucosmandplugin.range.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.a42r.eucosmandplugin.range.model.HistoricalSegment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages historical trip data for adaptive range estimation calibration.
 * 
 * Stores segments from past trips to build a real-world efficiency profile
 * that accounts for:
 * - Li-Ion discharge characteristics
 * - Individual riding patterns
 * - Wheel-specific efficiency
 * - Terrain and conditions (averaged over many trips)
 * 
 * Historical data is used to:
 * 1. Calibrate the discharge curve to match real-world behavior
 * 2. Adjust efficiency estimates based on battery level
 * 3. Improve accuracy as more trips are recorded
 */
class HistoricalDataManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "HistoricalDataManager"
        
        // Preference keys
        private const val PREF_HISTORICAL_SEGMENTS = "range_historical_segments"
        private const val PREF_CALIBRATION_ENABLED = "range_historical_calibration_enabled"
        
        // Limits
        private const val MAX_SEGMENTS = 100 // Keep last 100 segments
        private const val MIN_SEGMENT_DISTANCE_KM = 2.0 // Min 2 km to be valid
        private const val MIN_SEGMENT_PERCENT = 5 // Min 5% battery consumed
        
        // Efficiency bounds
        private const val MIN_EFFICIENCY = 5.0
        private const val MAX_EFFICIENCY = 200.0
    }
    
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val gson = Gson()
    
    /**
     * Check if historical calibration is enabled.
     */
    fun isCalibrationEnabled(): Boolean {
        return prefs.getBoolean(PREF_CALIBRATION_ENABLED, true)
    }
    
    /**
     * Add a historical segment from a completed trip portion.
     * 
     * @param segment The segment to add
     */
    fun addSegment(segment: HistoricalSegment) {
        // Validate segment
        if (!isSegmentValid(segment)) {
            Log.w(TAG, "Ignoring invalid segment: $segment")
            return
        }
        
        val segments = getAllSegments().toMutableList()
        segments.add(segment)
        
        // Keep only most recent MAX_SEGMENTS
        val trimmedSegments = if (segments.size > MAX_SEGMENTS) {
            segments.sortedByDescending { it.timestamp }
                .take(MAX_SEGMENTS)
        } else {
            segments
        }
        
        saveSegments(trimmedSegments)
        Log.d(TAG, "Added segment: ${segment.startPercent}% -> ${segment.endPercent}%, distance=${segment.distanceKm}km, efficiency=${segment.efficiencyWhPerKm}Wh/km")
    }
    
    /**
     * Get all stored historical segments.
     */
    fun getAllSegments(): List<HistoricalSegment> {
        val json = prefs.getString(PREF_HISTORICAL_SEGMENTS, null) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<HistoricalSegment>>() {}.type
            gson.fromJson<List<HistoricalSegment>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse historical segments: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Get segments for a specific wheel model.
     */
    fun getSegmentsForWheel(wheelModel: String): List<HistoricalSegment> {
        return getAllSegments().filter { 
            it.wheelModel.equals(wheelModel, ignoreCase = true) 
        }
    }
    
    /**
     * Get average efficiency for a battery range across all historical data.
     * 
     * Example: getAverageEfficiency(80, 50) returns average Wh/km for 80% -> 50% range
     * 
     * @param startPercent Starting battery percentage
     * @param endPercent Ending battery percentage
     * @param wheelModel Optional wheel model filter
     * @return Average efficiency in Wh/km, or null if insufficient data
     */
    fun getAverageEfficiency(
        startPercent: Int,
        endPercent: Int,
        wheelModel: String? = null
    ): Double? {
        val segments = if (wheelModel != null) {
            getSegmentsForWheel(wheelModel)
        } else {
            getAllSegments()
        }
        
        // Find segments that overlap with requested range
        val relevantSegments = segments.filter { segment ->
            // Segment overlaps if: start is within range OR end is within range
            (segment.startPercent <= startPercent && segment.endPercent >= endPercent) ||
            (segment.startPercent in endPercent..startPercent) ||
            (segment.endPercent in endPercent..startPercent)
        }
        
        if (relevantSegments.isEmpty()) {
            return null
        }
        
        // Calculate weighted average by distance
        val totalDistance = relevantSegments.sumOf { it.distanceKm }
        val weightedEfficiency = relevantSegments.sumOf { 
            it.efficiencyWhPerKm * it.distanceKm 
        } / totalDistance
        
        Log.d(TAG, "Average efficiency for $startPercent% -> $endPercent%: $weightedEfficiency Wh/km (from ${relevantSegments.size} segments)")
        return weightedEfficiency
    }
    
    /**
     * Get calibration factor for current battery level.
     * 
     * Returns a multiplier to apply to the estimated range based on
     * historical data showing actual vs. predicted efficiency.
     * 
     * Example: If historical data shows we consistently get 20% more
     * range than predicted, returns 1.2
     * 
     * @param currentPercent Current battery percentage
     * @param predictedEfficiency Predicted efficiency from current algorithm
     * @param wheelModel Optional wheel model filter
     * @return Calibration multiplier (typically 0.8 to 1.2), or 1.0 if no data
     */
    fun getCalibrationFactor(
        currentPercent: Int,
        predictedEfficiency: Double,
        wheelModel: String? = null
    ): Double {
        if (!isCalibrationEnabled()) {
            return 1.0
        }
        
        // Get average efficiency for this battery range from historical data
        val historicalEfficiency = getAverageEfficiency(
            startPercent = 100,
            endPercent = currentPercent,
            wheelModel = wheelModel
        ) ?: return 1.0
        
        // If historical efficiency is lower than predicted, we'll go further than predicted
        // If historical efficiency is higher than predicted, we won't go as far
        // Calibration factor = predicted / historical
        val calibrationFactor = (predictedEfficiency / historicalEfficiency)
            .coerceIn(0.8, 1.2) // Limit adjustment to ±20%
        
        Log.d(TAG, "Calibration factor at $currentPercent%: $calibrationFactor (historical=$historicalEfficiency, predicted=$predictedEfficiency)")
        return calibrationFactor
    }
    
    /**
     * Clear all historical data.
     */
    fun clearAllData() {
        prefs.edit()
            .remove(PREF_HISTORICAL_SEGMENTS)
            .apply()
        Log.d(TAG, "Cleared all historical data")
    }
    
    /**
     * Get statistics about historical data.
     */
    fun getStatistics(): HistoricalStatistics {
        val segments = getAllSegments()
        
        return HistoricalStatistics(
            totalSegments = segments.size,
            totalDistanceKm = segments.sumOf { it.distanceKm },
            averageEfficiency = if (segments.isNotEmpty()) {
                segments.map { it.efficiencyWhPerKm }.average()
            } else 0.0,
            oldestTimestamp = segments.minOfOrNull { it.timestamp } ?: 0L,
            newestTimestamp = segments.maxOfOrNull { it.timestamp } ?: 0L,
            uniqueWheels = segments.map { it.wheelModel }.distinct().size
        )
    }
    
    /**
     * Validate a segment before adding to history.
     */
    private fun isSegmentValid(segment: HistoricalSegment): Boolean {
        // Check distance minimum
        if (segment.distanceKm < MIN_SEGMENT_DISTANCE_KM) {
            return false
        }
        
        // Check battery consumption minimum
        if (segment.energyConsumedPercent < MIN_SEGMENT_PERCENT) {
            return false
        }
        
        // Check efficiency bounds
        if (segment.efficiencyWhPerKm < MIN_EFFICIENCY || 
            segment.efficiencyWhPerKm > MAX_EFFICIENCY) {
            return false
        }
        
        // Check logical order
        if (segment.startPercent <= segment.endPercent) {
            return false
        }
        
        return true
    }
    
    /**
     * Save segments to SharedPreferences.
     */
    private fun saveSegments(segments: List<HistoricalSegment>) {
        val json = gson.toJson(segments)
        prefs.edit()
            .putString(PREF_HISTORICAL_SEGMENTS, json)
            .apply()
    }
}

/**
 * Statistics about historical calibration data.
 */
data class HistoricalStatistics(
    val totalSegments: Int,
    val totalDistanceKm: Double,
    val averageEfficiency: Double,
    val oldestTimestamp: Long,
    val newestTimestamp: Long,
    val uniqueWheels: Int
)
