package com.a42r.eucosmandplugin.ai.model

/**
 * Data class representing a model from the OpenRouter API
 */
data class OpenRouterModel(
    val id: String,
    val context_length: Int?,
    val description: String?,
    val pricing: ModelPricing?
)

/**
 * Pricing information for a model
 */
data class ModelPricing(
    val prompt: String,  // Cost per million prompt tokens
    val completion: String  // Cost per million completion tokens
)

/**
 * Data class for displaying AI models in the selection dialog
 */
data class AiModel(
    val id: String,
    val name: String,
    val provider: String,
    val cost: String,
    val contextSize: String? = null,
    val description: String? = null,
    val pricing: ModelPricing? = null,
    val isRecommended: Boolean = false,
    val recommendationTier: Int = 0  // 1 = Free/Best Value, 2 = Low Cost, 3 = Premium
)

/**
 * API key information from OpenRouter /api/v1/key endpoint
 */
data class KeyInfo(
    val label: String,
    val limit: Double?,
    val usage: Double,
    val usage_daily: Double,
    val usage_weekly: Double,
    val usage_monthly: Double,
    val is_free_tier: Boolean,
    val limit_remaining: Double?,
    val limit_reset: String?,
    val expires_at: String?
)

/**
 * Response wrapper for key info
 */
data class KeyInfoResponse(
    val data: KeyInfo
)

/**
 * Determines if a model is recommended for range estimation
 */
object ModelRecommendations {
    
    // Recommended model IDs organized by tier
    private val TIER_1_FREE = setOf(
        "google/gemini-2.0-flash-exp:free",
        "google/gemini-flash-1.5-exp:free",
        "google/gemini-2.0-flash-thinking-exp:free",
        "meta-llama/llama-3.2-90b-vision-instruct:free"
    )
    
    private val TIER_2_LOW_COST = setOf(
        "google/gemini-flash-1.5",
        "google/gemini-pro-1.5",
        "openai/gpt-4o-mini",
        "anthropic/claude-3-haiku",
        "meta-llama/llama-3.1-70b-instruct",
        "meta-llama/llama-3.3-70b-instruct"
    )
    
    private val TIER_3_PREMIUM = setOf(
        "anthropic/claude-3.5-sonnet",
        "anthropic/claude-3-opus",
        "openai/gpt-4o",
        "google/gemini-pro-1.5-exp"
    )
    
    /**
     * Check if a model is recommended for range estimation
     */
    fun isRecommended(modelId: String): Boolean {
        return TIER_1_FREE.contains(modelId) ||
               TIER_2_LOW_COST.contains(modelId) ||
               TIER_3_PREMIUM.contains(modelId)
    }
    
    /**
     * Get the recommendation tier for a model
     * @return 1 (Free/Best), 2 (Low Cost), 3 (Premium), or 0 (Not recommended)
     */
    fun getRecommendationTier(modelId: String): Int {
        return when {
            TIER_1_FREE.contains(modelId) -> 1
            TIER_2_LOW_COST.contains(modelId) -> 2
            TIER_3_PREMIUM.contains(modelId) -> 3
            else -> 0
        }
    }
    
    /**
     * Get recommendation label for display
     */
    fun getRecommendationLabel(tier: Int): String {
        return when (tier) {
            1 -> "Best Value"
            2 -> "Recommended"
            3 -> "Premium"
            else -> ""
        }
    }
}
