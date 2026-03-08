package com.a42r.eucosmandplugin.ai.model

/**
 * Information about available AI models on OpenRouter
 */
data class AIModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val description: String,
    val contextLength: Int,
    val costPer1MTokens: String,
    val isFree: Boolean = false,
    val isRecommended: Boolean = false
)

/**
 * Predefined list of recommended models for range estimation
 */
object AIModels {
    
    val RECOMMENDED_MODELS = listOf(
        AIModelInfo(
            id = "google/gemini-2.0-flash-exp:free",
            name = "Gemini 2.0 Flash (Free)",
            provider = "Google",
            description = "Fast, free model with good reasoning capabilities",
            contextLength = 1000000,
            costPer1MTokens = "Free",
            isFree = true,
            isRecommended = true
        ),
        AIModelInfo(
            id = "google/gemini-flash-1.5",
            name = "Gemini 1.5 Flash",
            provider = "Google",
            description = "Fast and efficient with excellent performance",
            contextLength = 1000000,
            costPer1MTokens = "$0.075 / $0.30",
            isFree = false,
            isRecommended = true
        ),
        AIModelInfo(
            id = "openai/gpt-4o-mini",
            name = "GPT-4o Mini",
            provider = "OpenAI",
            description = "Compact version of GPT-4o with good performance",
            contextLength = 128000,
            costPer1MTokens = "$0.15 / $0.60",
            isFree = false,
            isRecommended = true
        ),
        AIModelInfo(
            id = "anthropic/claude-3.5-sonnet",
            name = "Claude 3.5 Sonnet",
            provider = "Anthropic",
            description = "Excellent reasoning and analysis capabilities",
            contextLength = 200000,
            costPer1MTokens = "$3.00 / $15.00",
            isFree = false,
            isRecommended = false
        ),
        AIModelInfo(
            id = "openai/gpt-4o",
            name = "GPT-4o",
            provider = "OpenAI",
            description = "Most capable GPT-4 model with multimodal capabilities",
            contextLength = 128000,
            costPer1MTokens = "$2.50 / $10.00",
            isFree = false,
            isRecommended = false
        ),
        AIModelInfo(
            id = "meta-llama/llama-3.1-70b-instruct",
            name = "Llama 3.1 70B",
            provider = "Meta",
            description = "Open source model with strong reasoning",
            contextLength = 131072,
            costPer1MTokens = "$0.35 / $0.40",
            isFree = false,
            isRecommended = false
        )
    )
    
    fun getDefaultModel(): AIModelInfo {
        return RECOMMENDED_MODELS.first { it.isFree }
    }
    
    fun getModelById(id: String): AIModelInfo? {
        return RECOMMENDED_MODELS.find { it.id == id }
    }
}
