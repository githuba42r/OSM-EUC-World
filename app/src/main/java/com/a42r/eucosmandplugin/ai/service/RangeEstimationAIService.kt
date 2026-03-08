package com.a42r.eucosmandplugin.ai.service

import android.content.Context
import android.util.Log
import com.a42r.eucosmandplugin.ai.data.RiderProfileDatabase
import com.a42r.eucosmandplugin.ai.data.TokenManager
import com.a42r.eucosmandplugin.ai.model.*
import com.a42r.eucosmandplugin.range.model.RangeEstimate
import com.a42r.eucosmandplugin.range.model.TripSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * AI-enhanced range estimation service using OpenRouter.ai
 * 
 * This service:
 * 1. Fetches rider profile from database
 * 2. Builds comprehensive prompt with:
 *    - Current trip data (battery, speed, efficiency)
 *    - Historical patterns (speed profiles, behavior)
 *    - Baseline algorithm estimate
 * 3. Calls OpenRouter API with selected model
 * 4. Parses and validates AI response
 * 5. Applies sanity checks (AI must be within 50% of baseline)
 */
class RangeEstimationAIService(private val context: Context) {
    
    companion object {
        private const val TAG = "RangeEstimationAIService"
        private const val OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val REQUEST_TIMEOUT_MS = 30000 // 30 seconds
        private const val MAX_DEVIATION_PERCENT = 50.0 // AI can't deviate more than 50% from baseline
        
        // Default model if none selected
        private const val DEFAULT_MODEL = "google/gemini-2.0-flash-exp:free"
    }
    
    private val tokenManager = TokenManager(context)
    private val database = RiderProfileDatabase.getInstance(context)
    
    /**
     * Generate AI-enhanced range estimate
     * 
     * @param trip Current trip snapshot
     * @param baselineEstimate Baseline algorithm estimate
     * @param wheelModel Wheel model name (e.g., "Begode EX30")
     * @param batteryCapacityWh Battery capacity in Wh
     * @return Result with AIRangeEstimate or error
     */
    suspend fun enhanceRangeEstimate(
        trip: TripSnapshot,
        baselineEstimate: RangeEstimate,
        wheelModel: String?,
        batteryCapacityWh: Double
    ): Result<AIRangeEstimate> = withContext(Dispatchers.IO) {
        try {
            // Check if AI is ready
            if (!tokenManager.isAiReady()) {
                return@withContext Result.success(
                    AIRangeEstimate(
                        baselineEstimate = baselineEstimate,
                        aiEnhancedEstimate = null,
                        useAI = false,
                        reason = "AI not configured or disabled"
                    )
                )
            }
            
            // Get API key and model
            val apiKey = tokenManager.getOpenRouterApiKey()
                ?: return@withContext Result.failure(Exception("No API key found"))
            
            val modelId = tokenManager.getSelectedAiModel() ?: DEFAULT_MODEL
            
            // Fetch rider profile
            val riderProfile = if (wheelModel != null) {
                database.riderProfileDao().getProfile(wheelModel)
            } else {
                null
            }
            
            // Check if profile has sufficient data quality
            if (riderProfile == null || riderProfile.dataQualityScore < 0.3) {
                return@withContext Result.success(
                    AIRangeEstimate(
                        baselineEstimate = baselineEstimate,
                        aiEnhancedEstimate = null,
                        useAI = false,
                        reason = if (riderProfile == null) {
                            "No rider profile available yet"
                        } else {
                            "Insufficient profile data quality: ${String.format("%.2f", riderProfile.dataQualityScore)}"
                        }
                    )
                )
            }
            
            // Build prompt
            val prompt = buildPrompt(trip, baselineEstimate, riderProfile, batteryCapacityWh)
            
            // Call OpenRouter API
            val aiResponse = callOpenRouterAPI(apiKey, modelId, prompt)
            
            // Parse response
            val aiEstimate = parseAIResponse(aiResponse, baselineEstimate)
            
            // Sanity check: AI estimate must be within 50% of baseline
            if (!isSanityCheckPassed(aiEstimate, baselineEstimate)) {
                Log.w(TAG, "AI estimate failed sanity check. AI: ${aiEstimate.rangeKm} km, Baseline: ${baselineEstimate.rangeKm} km")
                return@withContext Result.success(
                    AIRangeEstimate(
                        baselineEstimate = baselineEstimate,
                        aiEnhancedEstimate = null,
                        useAI = false,
                        reason = "AI estimate deviated too much from baseline (>50%)"
                    )
                )
            }
            
            // Success!
            Result.success(
                AIRangeEstimate(
                    baselineEstimate = baselineEstimate,
                    aiEnhancedEstimate = aiEstimate,
                    useAI = true,
                    reason = "AI enhancement successful"
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate AI estimate", e)
            Result.success(
                AIRangeEstimate(
                    baselineEstimate = baselineEstimate,
                    aiEnhancedEstimate = null,
                    useAI = false,
                    reason = "AI error: ${e.message}"
                )
            )
        }
    }
    
    /**
     * Build comprehensive prompt for AI
     */
    private suspend fun buildPrompt(
        trip: TripSnapshot,
        baseline: RangeEstimate,
        profile: RiderProfile,
        batteryCapacityWh: Double
    ): String {
        val currentSample = trip.latestSample
        val validSamples = trip.getValidSamplesSinceBaseline()
        
        // Calculate recent average speed (last 2 minutes)
        val recentSpeed = if (validSamples.size > 120) {
            validSamples.takeLast(120)
                .map { it.speedKmh }
                .filter { it > 1.0 }
                .average()
        } else {
            currentSample?.speedKmh ?: 0.0
        }
        
        return buildString {
            appendLine("You are an AI assistant helping to estimate the remaining range for an electric unicycle (EUC). ")
            appendLine("You have access to:")
            appendLine("1. Current trip data (battery, speed, efficiency)")
            appendLine("2. Baseline algorithm estimate from physics-based model")
            appendLine("3. This rider's historical patterns and behavior")
            appendLine()
            appendLine("## Current Trip Data")
            appendLine("- Battery: ${String.format("%.1f", currentSample?.batteryPercent ?: 0.0)}%")
            appendLine("- Remaining Energy: ${String.format("%.1f", baseline.diagnostics?.remainingEnergyWh ?: 0.0)} Wh")
            appendLine("- Current Speed: ${String.format("%.1f", currentSample?.speedKmh ?: 0.0)} km/h")
            appendLine("- Recent Avg Speed: ${String.format("%.1f", recentSpeed)} km/h (last 2 min)")
            appendLine("- Distance Traveled: ${String.format("%.2f", trip.getDistanceKmSinceBaseline())} km")
            appendLine("- Riding Time: ${String.format("%.1f", trip.getRidingTimeMsSinceBaseline() / 60000.0)} min")
            appendLine("- Current Efficiency: ${String.format("%.2f", baseline.efficiencyWhPerKm ?: 0.0)} Wh/km")
            appendLine()
            appendLine("## Baseline Algorithm Estimate")
            appendLine("- Range: ${String.format("%.2f", baseline.rangeKm ?: 0.0)} km")
            appendLine("- Confidence: ${String.format("%.2f", baseline.confidence)}")
            appendLine("- Algorithm: Weighted window with exponential decay")
            appendLine("- Method: Blends recent efficiency (40%) with overall trip efficiency (60%)")
            appendLine()
            appendLine("## Rider's Historical Patterns (${profile.totalTrips} trips, ${String.format("%.1f", profile.totalDistanceKm)} km)")
            appendLine()
            appendLine("### Average Consumption by Speed:")
            
            // Fetch speed profiles
            val speedProfiles = try {
                database.speedProfileDao().getProfilesForRider(profile.wheelModel).sortedBy { it.speedRangeLow }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch speed profiles", e)
                emptyList()
            }
            
            speedProfiles.forEach { sp ->
                appendLine("- ${sp.speedRangeLow.toInt()}-${sp.speedRangeHigh.toInt()} km/h: " +
                        "${String.format("%.2f", sp.avgEfficiencyWhPerKm)} Wh/km " +
                        "(${String.format("%.1f", sp.distancePercentage * 100)}% of riding)")
            }
            
            appendLine()
            appendLine("### Riding Behavior:")
            val behavior = try {
                database.behaviorPatternDao().getLatestPattern(profile.wheelModel)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch behavior pattern", e)
                null
            }
            
            if (behavior != null) {
                appendLine("- Acceleration: ${behavior.accelerationStyle} (${String.format("%.1f", behavior.avgAccelerationMps2)} m/s²)")
                appendLine("- Braking: ${behavior.brakingStyle} (${String.format("%.1f", abs(behavior.avgBrakingMps2))} m/s²)")
                appendLine("- Stops per km: ${String.format("%.2f", behavior.stopsPerKm)}")
                appendLine("- Regen efficiency: ${String.format("%.1f", behavior.regenEfficiency * 100)}%")
            }
            
            appendLine()
            appendLine("### Overall Stats:")
            appendLine("- Average efficiency: ${String.format("%.2f", profile.avgEfficiencyWhPerKm)} Wh/km")
            appendLine("- Average speed: ${String.format("%.1f", profile.avgSpeedKmh)} km/h")
            appendLine("- Average range per charge: ${String.format("%.1f", profile.avgRangePerChargeKm)} km")
            appendLine()
            appendLine("## Your Task:")
            appendLine("Based on the current trip data, baseline estimate, and rider's historical patterns, ")
            appendLine("provide an improved range estimate. Consider:")
            appendLine("- Is current speed/efficiency typical for this rider?")
            appendLine("- Are there patterns suggesting the ride may change (e.g., just started, unusual speed)?")
            appendLine("- How confident should we be given data quality?")
            appendLine()
            appendLine("IMPORTANT: Your estimate MUST be within 50% of the baseline estimate (${String.format("%.2f", (baseline.rangeKm ?: 0.0) * 0.5)} - ${String.format("%.2f", (baseline.rangeKm ?: 0.0) * 1.5)} km).")
            appendLine()
            appendLine("Respond ONLY with valid JSON in this exact format:")
            appendLine("""
                {
                  "rangeKm": <number>,
                  "confidence": <0.0-1.0>,
                  "reasoning": "<brief explanation>",
                  "assumptions": ["<assumption1>", "<assumption2>"],
                  "riskFactors": ["<risk1>", "<risk2>"]
                }
            """.trimIndent())
        }
    }
    
    /**
     * Call OpenRouter API
     */
    private fun callOpenRouterAPI(apiKey: String, modelId: String, prompt: String): String {
        val url = URL(OPENROUTER_API_URL)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("HTTP-Referer", "https://github.com/philg666/OSM-EUC-World")
            connection.setRequestProperty("X-Title", "EUC World - Range Estimation")
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.doOutput = true
            
            // Build request body
            val requestBody = JSONObject().apply {
                put("model", modelId)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.3) // Low temperature for more consistent results
                put("max_tokens", 500)
            }
            
            // Write request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }
            
            // Read response
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorStream = connection.errorStream
                val errorBody = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                } else {
                    "No error body"
                }
                throw Exception("OpenRouter API returned $responseCode: $errorBody")
            }
            
            val responseBody = BufferedReader(InputStreamReader(connection.inputStream)).use {
                it.readText()
            }
            
            // Parse response to extract message content
            val responseJson = JSONObject(responseBody)
            val choices = responseJson.getJSONArray("choices")
            if (choices.length() == 0) {
                throw Exception("No choices in OpenRouter response")
            }
            
            val message = choices.getJSONObject(0).getJSONObject("message")
            val content = message.getString("content")
            
            Log.d(TAG, "OpenRouter API response: $content")
            return content
            
        } finally {
            connection.disconnect()
        }
    }
    
    /**
     * Parse AI response JSON
     */
    private fun parseAIResponse(response: String, baseline: RangeEstimate): AIEstimateResult {
        // Try to extract JSON from response (in case AI adds markdown formatting)
        val jsonContent = if (response.contains("```json")) {
            response.substringAfter("```json").substringBefore("```").trim()
        } else if (response.contains("```")) {
            response.substringAfter("```").substringBefore("```").trim()
        } else {
            response.trim()
        }
        
        val json = JSONObject(jsonContent)
        
        return AIEstimateResult(
            rangeKm = json.getDouble("rangeKm"),
            confidence = json.getDouble("confidence"),
            reasoning = json.getString("reasoning"),
            assumptions = json.getJSONArray("assumptions").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            riskFactors = json.getJSONArray("riskFactors").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
        )
    }
    
    /**
     * Sanity check: AI estimate must be within 50% of baseline
     */
    private fun isSanityCheckPassed(aiEstimate: AIEstimateResult, baseline: RangeEstimate): Boolean {
        val baselineRange = baseline.rangeKm ?: return false
        
        if (baselineRange <= 0) return false
        
        val deviation = abs(aiEstimate.rangeKm - baselineRange) / baselineRange * 100.0
        
        return deviation <= MAX_DEVIATION_PERCENT
    }
}
