package com.a42r.eucosmandplugin.ai.model

import com.a42r.eucosmandplugin.range.model.RangeEstimate

/**
 * Combined result with baseline and AI-enhanced estimates
 */
data class AIRangeEstimate(
    val baselineEstimate: RangeEstimate?,
    val aiEnhancedEstimate: AIEstimateResult?,
    val useAI: Boolean,
    val reason: String
)

/**
 * AI-enhanced range estimate result
 */
data class AIEstimateResult(
    val rangeKm: Double,
    val confidence: Double,              // 0.0-1.0
    val reasoning: String,
    val assumptions: List<String>,
    val riskFactors: List<String>
)

/**
 * Request to AI service for range estimation
 */
data class AIRangeRequest(
    val currentBatteryPercent: Double,
    val remainingEnergyWh: Double,
    val distanceTraveled: Double,
    val ridingTimeMinutes: Double,
    val currentSpeedKmh: Double,
    val recentAvgSpeedKmh: Double,
    val recentEfficiencyWhPerKm: Double,
    val overallTripEfficiencyWhPerKm: Double,
    val baselineRangeKm: Double?,
    val baselineConfidence: Double?
)
