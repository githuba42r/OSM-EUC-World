package com.a42r.eucosmandplugin.range.algorithm

import com.a42r.eucosmandplugin.range.model.RangeEstimate
import com.a42r.eucosmandplugin.range.model.TripSnapshot

/**
 * Interface for range estimation algorithms.
 * 
 * Different algorithms can be implemented:
 * - SimpleLinearEstimator: Basic linear calculation
 * - WeightedWindowEstimator: Adaptive with exponential weighting (recommended)
 * - MLLiteEstimator: Machine learning-based (advanced)
 * 
 * All estimators must:
 * - Check for insufficient data (10 min + 10 km)
 * - Use compensatedVoltage for energy calculations
 * - Handle charging state
 * - Return null if unable to estimate
 */
interface RangeEstimator {
    /**
     * Calculate range estimate for the current trip.
     * 
     * @param trip Current trip snapshot with all samples
     * @return Range estimate, or null if unable to calculate
     */
    fun estimate(trip: TripSnapshot): RangeEstimate?
    
    /**
     * Get the name of this estimator.
     * Used for display in settings.
     */
    fun getName(): String
    
    /**
     * Get a short description of this estimator.
     * Used for display in settings.
     */
    fun getDescription(): String
}
