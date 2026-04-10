package com.a42r.eucosmandplugin.ai.model

import com.a42r.eucosmandplugin.range.model.RangeEstimate

/**
 * Combined result with baseline, AI-enhanced and practical range estimates.
 *
 * - [baselineEstimate] is the physics-based algorithmic estimate from
 *   SimpleLinearEstimator / WeightedWindowEstimator. It answers the question
 *   *"how far could this rider theoretically go if they rode to 0 %?"*.
 *
 * - [practicalRangeKm] is a deterministic real-world estimate from the
 *   rider's historical trip endings: `current_batt_pct × median(km/%)`. It
 *   answers the question *"how far does this rider actually go between
 *   charges, given how they typically ride and when they stop?"*. This is
 *   usually lower than the theoretical estimate and is the more useful
 *   number for ride planning. Null when no rider profile data is available.
 *
 * - [aiEnhancedEstimate] / [useAI] is the legacy OpenRouter path, disabled
 *   at build time via BuildConfig.AI_RANGE_ESTIMATION_ENABLED.
 */
data class AIRangeEstimate(
    val baselineEstimate: RangeEstimate?,
    val aiEnhancedEstimate: AIEstimateResult?,
    val useAI: Boolean,
    val reason: String,
    val practicalRangeKm: Double? = null,
    val practicalKmPerPct: Double? = null
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
