# AI-Enhanced Range Estimation Implementation Plan

**Last Updated:** March 7, 2026  
**Status:** Ready for implementation

## Executive Summary

Implement an AI-powered range estimation system that learns from historical riding patterns to provide more accurate predictions than traditional algorithmic approaches. The system will build a rider profile over time and use LLM reasoning to predict range based on current conditions, historical patterns, and trip context.

### Key Technology Decision: OpenRouter.ai

**✅ We will use OpenRouter.ai as our unified LLM gateway**

**Why OpenRouter.ai:**
- ✅ **Already implemented in PEVPlugShare** - Complete OAuth flow ready to adapt
- ✅ **Single API for 200+ models** - OpenAI, Anthropic, Google, Meta, Mistral, etc.
- ✅ **OAuth2 PKCE authentication** - More secure than API key entry
- ✅ **User controls cost** - Select model based on budget (free to premium)
- ✅ **Free models available** - Gemini 2.0 Flash Exp, Llama 3.3 70B
- ✅ **No vendor lock-in** - Switch models anytime
- ✅ **Saves development time** - 1-2 weeks vs implementing multiple providers

**Implementation Timeline:** 8-10 weeks (down from 10-12 weeks)

## Overview

Implement an AI-powered range estimation system that learns from historical riding patterns to provide more accurate predictions than traditional algorithmic approaches. The system will build a rider profile over time and use LLM reasoning to predict range based on current conditions, historical patterns, and trip context.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Range Estimation System                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────┐      ┌────────────────────────────────┐  │
│  │   Current Trip   │      │      Historical Rider          │  │
│  │   Data Stream    │──────│      Profile Database          │  │
│  │  (real-time)     │      │    (aggregated patterns)       │  │
│  └──────────────────┘      └────────────────────────────────┘  │
│           │                              │                       │
│           │                              │                       │
│           ▼                              ▼                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │          Traditional Algorithm Layer                        │ │
│  │  (WeightedWindowEstimator - baseline, always works)        │ │
│  └────────────────────────────────────────────────────────────┘ │
│           │                                                       │
│           │                                                       │
│           ▼                                                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │              AI Enhancement Layer                           │ │
│  │   (LLM analyzes patterns + current → enhanced estimate)    │ │
│  └────────────────────────────────────────────────────────────┘ │
│           │                                                       │
│           ▼                                                       │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │              Final Range Estimate                           │ │
│  │  - Base estimate (traditional algorithm)                   │ │
│  │  - AI-enhanced estimate (if available)                     │ │
│  │  - Confidence scores for both                              │ │
│  │  - Reasoning explanation                                   │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

## Phase 1: Rider Profile Data Collection (Foundation)

### 1.1 Design Rider Profile Schema

Create a comprehensive rider profile that captures consumption patterns across different conditions:

```kotlin
data class RiderProfile(
    val profileId: String,
    val wheelModel: String,
    val batteryCapacityWh: Double,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    
    // Speed-based consumption profiles
    val speedProfiles: List<SpeedProfile>,
    
    // Terrain-based consumption profiles
    val terrainProfiles: List<TerrainProfile>,
    
    // Environmental factors
    val environmentalFactors: EnvironmentalFactors,
    
    // Riding behavior patterns
    val behaviorPatterns: BehaviorPatterns,
    
    // Statistical summary
    val statistics: ProfileStatistics
)

data class SpeedProfile(
    val speedRangeKmh: IntRange,        // e.g., 20-25 km/h
    val avgEfficiencyWhPerKm: Double,   // Average consumption in this range
    val stdDeviation: Double,            // Variance
    val sampleCount: Int,                // Number of samples
    val totalDistanceKm: Double,         // Total distance at this speed
    val timePercentage: Double,          // % of time spent at this speed
    val conditions: SpeedConditions      // When this speed typically occurs
)

data class SpeedConditions(
    val typicalDuration: Duration,       // How long rider sustains this speed
    val transitionFrom: List<Int>,       // Speeds typically transitioned from
    val transitionTo: List<Int>,         // Speeds typically transitioned to
    val commonContext: List<String>      // e.g., ["highway", "morning", "flat"]
)

data class TerrainProfile(
    val terrainType: TerrainType,        // FLAT, UPHILL, DOWNHILL, MIXED
    val grade: GradeRange,               // Estimated grade (from GPS elevation)
    val avgEfficiencyWhPerKm: Double,
    val stdDeviation: Double,
    val sampleCount: Int,
    val avgSpeedKmh: Double
)

enum class TerrainType {
    FLAT,           // < 2% grade
    GENTLE_UPHILL,  // 2-5% grade
    STEEP_UPHILL,   // > 5% grade
    GENTLE_DOWNHILL,// -2 to -5% grade
    STEEP_DOWNHILL, // < -5% grade
    MIXED           // Frequent grade changes
}

data class EnvironmentalFactors(
    val temperatureProfiles: Map<TempRange, EfficiencyAdjustment>,
    val windProfiles: Map<WindStrength, EfficiencyAdjustment>,
    val trafficPatterns: Map<TrafficType, EfficiencyAdjustment>
)

data class BehaviorPatterns(
    val accelerationProfile: AccelerationProfile,
    val brakingProfile: BrakingProfile,
    val typicalSpeedDistribution: Map<Int, Double>,  // Speed → % of time
    val stopFrequency: StopPattern,
    val routeTypes: Map<RouteType, RouteStats>       // Highway, city, mixed
)

data class AccelerationProfile(
    val avgAccelerationRate: Double,     // m/s²
    val aggressivenessScore: Double,     // 0.0 (gentle) to 1.0 (aggressive)
    val typicalAccelDuration: Duration,
    val avgPowerDuringAccel: Double      // Watts
)

data class BrakingProfile(
    val avgBrakingRate: Double,
    val regenEfficiency: Double,         // How much energy recovered
    val typicalBrakeDuration: Duration
)

data class StopPattern(
    val avgStopsPerKm: Double,
    val avgStopDuration: Duration,
    val stopTypes: Map<StopType, Int>    // SHORT, MEDIUM, LONG
)

data class ProfileStatistics(
    val totalTrips: Int,
    val totalDistanceKm: Double,
    val totalRidingTimeHours: Double,
    val avgTripDistanceKm: Double,
    val overallAvgEfficiency: Double,
    val overallAvgSpeed: Double,
    val dataQualityScore: Double,        // 0.0-1.0 based on sample count
    val lastRecalculated: Long
)
```

### 1.2 Create Profile Builder Service

```kotlin
class RiderProfileBuilder(
    private val database: WheelDatabase,
    private val gpsService: GPSService
) {
    /**
     * Process completed trip and update rider profile
     */
    suspend fun processTripForProfile(trip: TripSnapshot) {
        val profile = loadOrCreateProfile(trip.wheelModel)
        
        // Extract speed profiles from trip
        val speedProfiles = extractSpeedProfiles(trip)
        
        // Extract terrain profiles (requires GPS elevation data)
        val terrainProfiles = extractTerrainProfiles(trip)
        
        // Update behavior patterns
        val behaviorUpdates = analyzeBehaviorPatterns(trip)
        
        // Merge with existing profile (incremental learning)
        val updatedProfile = mergeWithExistingProfile(
            profile, 
            speedProfiles, 
            terrainProfiles, 
            behaviorUpdates
        )
        
        // Save updated profile
        database.saveRiderProfile(updatedProfile)
    }
    
    private fun extractSpeedProfiles(trip: TripSnapshot): List<SpeedProfile> {
        val speedBuckets = mapOf(
            0..10 to mutableListOf<BatterySample>(),
            10..15 to mutableListOf<BatterySample>(),
            15..20 to mutableListOf<BatterySample>(),
            20..25 to mutableListOf<BatterySample>(),
            25..30 to mutableListOf<BatterySample>(),
            30..35 to mutableListOf<BatterySample>(),
            35..40 to mutableListOf<BatterySample>(),
            40..50 to mutableListOf<BatterySample>(),
        )
        
        // Bucket samples by speed
        trip.getValidSamplesSinceBaseline().forEach { sample ->
            val speed = sample.speedKmh.toInt()
            speedBuckets.entries.find { speed in it.key }?.value?.add(sample)
        }
        
        // Calculate profile for each bucket
        return speedBuckets.map { (range, samples) ->
            if (samples.isEmpty()) return@map null
            
            val efficiencies = samples.map { it.instantEfficiencyWhPerKm }
                .filter { !it.isNaN() }
            
            SpeedProfile(
                speedRangeKmh = range,
                avgEfficiencyWhPerKm = efficiencies.average(),
                stdDeviation = calculateStdDev(efficiencies),
                sampleCount = samples.size,
                totalDistanceKm = calculateDistance(samples),
                timePercentage = samples.size.toDouble() / trip.samples.size,
                conditions = extractSpeedConditions(samples, trip)
            )
        }.filterNotNull()
    }
    
    private fun extractTerrainProfiles(trip: TripSnapshot): List<TerrainProfile> {
        // Requires GPS elevation data
        if (!gpsService.hasElevationData(trip)) {
            return emptyList()
        }
        
        val segments = segmentByTerrain(trip)
        
        return segments.map { segment ->
            val grade = calculateGrade(segment)
            val terrainType = classifyTerrain(grade)
            
            TerrainProfile(
                terrainType = terrainType,
                grade = grade,
                avgEfficiencyWhPerKm = segment.samples.map { it.instantEfficiencyWhPerKm }
                    .filter { !it.isNaN() }.average(),
                stdDeviation = calculateStdDev(segment.samples.map { it.instantEfficiencyWhPerKm }),
                sampleCount = segment.samples.size,
                avgSpeedKmh = segment.samples.map { it.speedKmh }.average()
            )
        }
    }
    
    private fun mergeWithExistingProfile(
        existing: RiderProfile,
        newSpeedProfiles: List<SpeedProfile>,
        newTerrainProfiles: List<TerrainProfile>,
        behaviorUpdates: BehaviorPatterns
    ): RiderProfile {
        // Incremental weighted average update
        // Give more weight to recent data but don't discard old patterns
        
        val mergedSpeedProfiles = existing.speedProfiles.map { existingProfile ->
            val newProfile = newSpeedProfiles.find { 
                it.speedRangeKmh == existingProfile.speedRangeKmh 
            }
            
            if (newProfile != null) {
                // Weighted merge: 70% existing, 30% new (can be tuned)
                val totalSamples = existingProfile.sampleCount + newProfile.sampleCount
                val existingWeight = existingProfile.sampleCount.toDouble() / totalSamples
                val newWeight = newProfile.sampleCount.toDouble() / totalSamples
                
                existingProfile.copy(
                    avgEfficiencyWhPerKm = 
                        existingProfile.avgEfficiencyWhPerKm * existingWeight +
                        newProfile.avgEfficiencyWhPerKm * newWeight,
                    sampleCount = totalSamples,
                    totalDistanceKm = existingProfile.totalDistanceKm + newProfile.totalDistanceKm
                )
            } else {
                existingProfile
            }
        }
        
        return existing.copy(
            speedProfiles = mergedSpeedProfiles,
            terrainProfiles = mergeTerrainProfiles(existing.terrainProfiles, newTerrainProfiles),
            behaviorPatterns = behaviorUpdates,
            lastUpdatedAt = System.currentTimeMillis()
        )
    }
}
```

### 1.3 Database Schema

Add tables to store rider profiles:

```sql
-- Rider profile summary
CREATE TABLE rider_profiles (
    profile_id TEXT PRIMARY KEY,
    wheel_model TEXT NOT NULL,
    battery_capacity_wh REAL NOT NULL,
    created_at INTEGER NOT NULL,
    last_updated_at INTEGER NOT NULL,
    total_trips INTEGER NOT NULL,
    total_distance_km REAL NOT NULL,
    total_riding_time_hours REAL NOT NULL,
    overall_avg_efficiency REAL NOT NULL,
    overall_avg_speed REAL NOT NULL,
    data_quality_score REAL NOT NULL
);

-- Speed profiles
CREATE TABLE speed_profiles (
    profile_id TEXT NOT NULL,
    speed_range_min INTEGER NOT NULL,
    speed_range_max INTEGER NOT NULL,
    avg_efficiency REAL NOT NULL,
    std_deviation REAL NOT NULL,
    sample_count INTEGER NOT NULL,
    total_distance_km REAL NOT NULL,
    time_percentage REAL NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES rider_profiles(profile_id)
);

-- Terrain profiles
CREATE TABLE terrain_profiles (
    profile_id TEXT NOT NULL,
    terrain_type TEXT NOT NULL,
    grade_min REAL NOT NULL,
    grade_max REAL NOT NULL,
    avg_efficiency REAL NOT NULL,
    std_deviation REAL NOT NULL,
    sample_count INTEGER NOT NULL,
    avg_speed REAL NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES rider_profiles(profile_id)
);

-- Behavior patterns
CREATE TABLE behavior_patterns (
    profile_id TEXT PRIMARY KEY,
    avg_acceleration_rate REAL NOT NULL,
    aggressiveness_score REAL NOT NULL,
    avg_braking_rate REAL NOT NULL,
    regen_efficiency REAL NOT NULL,
    avg_stops_per_km REAL NOT NULL,
    avg_stop_duration_seconds REAL NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES rider_profiles(profile_id)
);
```

## Phase 2: AI Integration Layer

### 2.1 AI Range Estimator Design

```kotlin
class AIRangeEstimator(
    private val llmService: LLMService,
    private val profileDatabase: RiderProfileDatabase,
    private val traditionalEstimator: WeightedWindowEstimator
) {
    
    suspend fun estimateWithAI(trip: TripSnapshot): AIRangeEstimate {
        // Get traditional baseline estimate
        val baselineEstimate = traditionalEstimator.estimate(trip)
        
        // Load rider profile
        val profile = profileDatabase.getRiderProfile(trip.wheelModel)
        
        if (profile == null || profile.dataQualityScore < 0.5) {
            // Not enough historical data, use baseline only
            return AIRangeEstimate(
                baselineEstimate = baselineEstimate,
                aiEnhancedEstimate = null,
                useAI = false,
                reason = "Insufficient historical data"
            )
        }
        
        // Build AI prompt with profile + current trip data
        val prompt = buildAIPrompt(trip, profile, baselineEstimate)
        
        // Get AI prediction
        val aiResponse = try {
            llmService.predict(prompt)
        } catch (e: Exception) {
            Log.w(TAG, "AI prediction failed, falling back to baseline", e)
            return AIRangeEstimate(
                baselineEstimate = baselineEstimate,
                aiEnhancedEstimate = null,
                useAI = false,
                reason = "AI service unavailable: ${e.message}"
            )
        }
        
        // Parse and validate AI response
        val aiEstimate = parseAIResponse(aiResponse)
        
        // Sanity check: AI should be within reasonable bounds of baseline
        val enhancedEstimate = if (isReasonable(aiEstimate, baselineEstimate)) {
            aiEstimate
        } else {
            Log.w(TAG, "AI estimate unreasonable, using baseline")
            null
        }
        
        return AIRangeEstimate(
            baselineEstimate = baselineEstimate,
            aiEnhancedEstimate = enhancedEstimate,
            useAI = enhancedEstimate != null,
            reason = if (enhancedEstimate != null) "AI enhanced" else "AI sanity check failed"
        )
    }
    
    private fun buildAIPrompt(
        trip: TripSnapshot,
        profile: RiderProfile,
        baselineEstimate: RangeEstimate?
    ): String {
        val currentSample = trip.samples.lastOrNull() ?: return ""
        
        // Calculate current trip statistics
        val currentStats = calculateCurrentTripStats(trip)
        
        // Find matching historical patterns
        val matchingPatterns = findMatchingPatterns(currentStats, profile)
        
        return """
You are an expert EUC (Electric Unicycle) range estimation system. Your task is to predict remaining range based on the rider's historical patterns and current trip data.

## Current Trip Status
- Battery: ${currentSample.batteryPercent}%
- Remaining Energy: ${currentSample.batteryPercent * profile.batteryCapacityWh / 100} Wh
- Distance Traveled: ${currentStats.distanceKm} km
- Riding Time: ${currentStats.ridingTimeMinutes} minutes
- Current Speed: ${currentSample.speedKmh} km/h
- Recent Avg Speed: ${currentStats.avgSpeedKmh} km/h
- Recent Efficiency: ${currentStats.recentEfficiencyWhPerKm} Wh/km
- Overall Trip Efficiency: ${currentStats.overallEfficiencyWhPerKm} Wh/km

## Baseline Algorithm Estimate
- Range: ${baselineEstimate?.rangeKm ?: "N/A"} km
- Confidence: ${baselineEstimate?.confidence ?: "N/A"}
- Method: Hybrid blend (40% recent window, 60% overall trip)

## Rider's Historical Profile
${formatHistoricalProfile(profile)}

## Matching Historical Patterns
${formatMatchingPatterns(matchingPatterns)}

## Current Riding Behavior vs Historical
${compareWithHistorical(currentStats, profile)}

## Task
Based on the above information, provide an improved range estimate that considers:
1. The rider's typical consumption patterns at current speeds
2. How long the rider typically sustains high/low speed riding
3. Expected return to baseline patterns after temporary conditions (hills, traffic)
4. Historical accuracy of similar trip patterns

Respond in JSON format:
{
  "estimatedRangeKm": <your estimate>,
  "confidence": <0.0 to 1.0>,
  "reasoning": "<explanation of your estimate>",
  "assumptions": [
    "<key assumption 1>",
    "<key assumption 2>"
  ],
  "riskFactors": [
    "<factor that could reduce range>",
    "<another risk factor>"
  ]
}
""".trimIndent()
    }
    
    private fun formatHistoricalProfile(profile: RiderProfile): String {
        return buildString {
            appendLine("Overall Statistics:")
            appendLine("- Total Trips: ${profile.statistics.totalTrips}")
            appendLine("- Total Distance: ${String.format("%.1f", profile.statistics.totalDistanceKm)} km")
            appendLine("- Average Efficiency: ${String.format("%.2f", profile.statistics.overallAvgEfficiency)} Wh/km")
            appendLine("- Average Speed: ${String.format("%.1f", profile.statistics.overallAvgSpeed)} km/h")
            appendLine()
            appendLine("Speed-Based Consumption Patterns:")
            profile.speedProfiles.forEach { speedProfile ->
                appendLine("- ${speedProfile.speedRangeKmh} km/h: ${String.format("%.2f", speedProfile.avgEfficiencyWhPerKm)} Wh/km " +
                    "(${String.format("%.1f", speedProfile.totalDistanceKm)} km total, " +
                    "${String.format("%.1f", speedProfile.timePercentage * 100)}% of riding time)")
            }
            appendLine()
            appendLine("Terrain Consumption Patterns:")
            profile.terrainProfiles.forEach { terrainProfile ->
                appendLine("- ${terrainProfile.terrainType}: ${String.format("%.2f", terrainProfile.avgEfficiencyWhPerKm)} Wh/km " +
                    "at avg ${String.format("%.1f", terrainProfile.avgSpeedKmh)} km/h")
            }
            appendLine()
            appendLine("Riding Behavior:")
            appendLine("- Acceleration: ${profile.behaviorPatterns.accelerationProfile.aggressivenessScore * 100}% aggressive")
            appendLine("- Regen Efficiency: ${profile.behaviorPatterns.brakingProfile.regenEfficiency * 100}%")
            appendLine("- Stops: ${String.format("%.2f", profile.behaviorPatterns.stopFrequency.avgStopsPerKm)} per km")
        }
    }
    
    private fun findMatchingPatterns(
        currentStats: CurrentTripStats,
        profile: RiderProfile
    ): List<MatchingPattern> {
        // Find historical trips with similar characteristics
        val matchingPatterns = mutableListOf<MatchingPattern>()
        
        // Match by speed
        val currentSpeedRange = profile.speedProfiles.find { 
            currentStats.avgSpeedKmh.toInt() in it.speedRangeKmh 
        }
        if (currentSpeedRange != null) {
            matchingPatterns.add(
                MatchingPattern(
                    type = "Speed Match",
                    description = "Rider typically consumes ${currentSpeedRange.avgEfficiencyWhPerKm} Wh/km " +
                        "at ${currentStats.avgSpeedKmh} km/h speed",
                    expectedEfficiency = currentSpeedRange.avgEfficiencyWhPerKm,
                    confidence = currentSpeedRange.sampleCount / 1000.0 // More samples = higher confidence
                )
            )
        }
        
        // Match by behavior (aggressive vs conservative)
        val currentAggressiveness = calculateAggressiveness(currentStats)
        val historicalAggressiveness = profile.behaviorPatterns.accelerationProfile.aggressivenessScore
        if (abs(currentAggressiveness - historicalAggressiveness) < 0.2) {
            matchingPatterns.add(
                MatchingPattern(
                    type = "Behavior Match",
                    description = "Current riding style matches historical patterns",
                    expectedEfficiency = profile.statistics.overallAvgEfficiency,
                    confidence = 0.8
                )
            )
        }
        
        return matchingPatterns
    }
}

data class AIRangeEstimate(
    val baselineEstimate: RangeEstimate?,
    val aiEnhancedEstimate: AIEstimateResult?,
    val useAI: Boolean,
    val reason: String
)

data class AIEstimateResult(
    val rangeKm: Double,
    val confidence: Double,
    val reasoning: String,
    val assumptions: List<String>,
    val riskFactors: List<String>
)
```

### 2.2 LLM Service Implementation with OpenRouter.ai

**Key Decision: Use OpenRouter.ai as unified LLM gateway**

Instead of implementing multiple individual LLM provider integrations, we'll use **OpenRouter.ai** which provides:
- Single API endpoint for 200+ models (OpenAI, Anthropic, Google, Meta, Mistral, etc.)
- OAuth2 PKCE authentication flow (more secure than API key entry)
- User-controlled model selection and cost management
- Unified pricing across providers
- Free models available (Llama, some Gemini variants)

**Advantage:** PEVPlugShare already has a complete OpenRouter.ai OAuth implementation that can be adapted!

#### OpenRouter.ai Service Architecture

```kotlin
/**
 * Simplified LLM service using OpenRouter.ai unified API
 * Based on PEVPlugShare's LocationDescriptionGenerator
 */
@Singleton
class RangeEstimationAIService @Inject constructor(
    private val tokenManager: TokenManager,
    private val profileDatabase: RiderProfileDatabase
) {
    private val okHttpClient = OkHttpClient()

    companion object {
        private const val TAG = "RangeEstimationAI"
        private const val OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val DEFAULT_MODEL = "google/gemini-2.0-flash-exp" // Free, fast, good quality
    }

    /**
     * Generate AI-enhanced range estimate
     */
    suspend fun enhanceRangeEstimate(
        trip: TripSnapshot,
        baselineEstimate: RangeEstimate,
        riderProfile: RiderProfile
    ): Result<AIEstimateResult> = withContext(Dispatchers.IO) {
        try {
            // Check if AI is configured
            val apiKey = tokenManager.getOpenRouterApiKey()
            if (apiKey.isNullOrEmpty()) {
                return@withContext Result.failure(
                    Exception("OpenRouter API key not configured")
                )
            }

            val modelId = tokenManager.getSelectedAiModel() ?: DEFAULT_MODEL

            // Build prompt with rider profile + current trip data
            val prompt = buildRangeEstimationPrompt(trip, baselineEstimate, riderProfile)

            // Create request
            val requestBody = buildOpenRouterRequest(modelId, prompt)

            val request = Request.Builder()
                .url(OPENROUTER_API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://osmeucworld.app")  // For OpenRouter analytics
                .header("X-Title", "OSM EUC World")
                .post(requestBody)
                .build()

            Log.d(TAG, "Requesting AI range estimate using model: $modelId")

            // Execute request
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                return@withContext Result.failure(
                    Exception("OpenRouter API failed: ${response.code} - $errorBody")
                )
            }

            val responseBody = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response"))

            // Parse AI response
            val aiEstimate = parseOpenRouterResponse(responseBody)

            Log.d(TAG, "AI enhanced estimate: ${aiEstimate.rangeKm} km (confidence: ${aiEstimate.confidence})")

            Result.success(aiEstimate)
        } catch (e: Exception) {
            Log.w(TAG, "AI enhancement failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun buildOpenRouterRequest(modelId: String, prompt: String): RequestBody {
        val json = JSONObject().apply {
            put("model", modelId)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert EUC range estimation system that analyzes " +
                        "rider patterns and current conditions to predict remaining range accurately.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.3)  // Lower temperature for more consistent predictions
            put("max_tokens", 800)
            put("response_format", JSONObject().apply {
                put("type", "json_object")  // Request structured JSON response
            })
        }

        return json.toString().toRequestBody("application/json".toMediaType())
    }

    private fun parseOpenRouterResponse(responseBody: String): AIEstimateResult {
        val json = JSONObject(responseBody)
        val choices = json.getJSONArray("choices")
        val message = choices.getJSONObject(0).getJSONObject("message")
        val content = message.getString("content")

        // Parse JSON response from AI
        val resultJson = JSONObject(content)

        return AIEstimateResult(
            rangeKm = resultJson.getDouble("estimatedRangeKm"),
            confidence = resultJson.getDouble("confidence"),
            reasoning = resultJson.getString("reasoning"),
            assumptions = resultJson.getJSONArray("assumptions")
                .let { arr -> (0 until arr.length()).map { arr.getString(it) } },
            riskFactors = resultJson.getJSONArray("riskFactors")
                .let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        )
    }

    /**
     * Check if AI service is available
     */
    fun isAvailable(): Boolean {
        return !tokenManager.getOpenRouterApiKey().isNullOrEmpty()
    }

    /**
     * Get currently selected model
     */
    fun getSelectedModel(): String {
        return tokenManager.getSelectedAiModel() ?: DEFAULT_MODEL
    }
}
```

#### Files to Copy/Adapt from PEVPlugShare

The following files contain the complete OAuth implementation and can be adapted for OSM-EUC-World:

1. **Service Layer:**
   - `service/OpenRouterOAuthService.kt` → Copy directly (minimal changes)
     - Registers OAuth session with callback service
     - Generates PKCE code verifier/challenge (SHA-256)
     - Exchanges authorization code for API key
     - No client secret needed (secure)

2. **UI Layer:**
   - `ui/oauth/OAuthActivity.kt` → Adapt for range estimation context
     - Complete OAuth flow UI
     - Model selection dialog with search/filter
     - Fetches available models from OpenRouter API
     - Displays model pricing and context windows
     - Account balance display

3. **ViewModel:**
   - `viewmodel/OpenRouterOAuthViewModel.kt` → Copy directly
     - Manages OAuth state machine
     - Handles deep link callbacks
     - Persists PKCE state across app restarts
     - Chrome Custom Tabs integration

4. **Data Storage:**
   - `data/remote/TokenManager.kt` → Merge with existing TokenManager
     - Encrypted SharedPreferences storage
     - Stores API key securely
     - Stores selected model ID
     - PKCE code verifier persistence

5. **Utilities:**
   - `util/OAuthSignature.kt` → Copy (needed for OAuth flow)
   - `util/NativeSecrets.kt` → Adapt (configure deep link for OSM-EUC-World)

#### Deep Link Configuration

OSM-EUC-World will need its own deep link scheme:

```xml
<!-- AndroidManifest.xml -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="osmeucworld"
          android:host="oauth"
          android:pathPrefix="/callback" />
</intent-filter>
```

**Deep Link URL:** `osmeucworld://oauth/callback`

#### Cost Comparison

| Provider (via OpenRouter.ai) | Model | Cost per Request | Quality |
|------------------------------|-------|------------------|---------|
| Google Gemini | gemini-2.0-flash-exp | **FREE** | High |
| Meta | llama-3.3-70b | **FREE** | High |
| Google Gemini | gemini-1.5-flash | $0.00005 | High |
| Anthropic | claude-3-haiku | $0.00025 | Very High |
| OpenAI | gpt-4o-mini | $0.0003 | Very High |

**Recommendation:** Default to `google/gemini-2.0-flash-exp` (free, fast, good quality)

**Cost Estimate for Paid Models:**
- ~50 requests per trip = $0.0025 - $0.015 per trip
- Much cheaper than direct API usage (OpenRouter provides bulk pricing)

## Phase 3: User Interface & Settings

### 3.1 Settings UI (OpenRouter OAuth Integration)

Add AI options in developer settings that launch the OAuth activity:

```xml
<!-- settings_developer.xml -->
<PreferenceScreen>
    <PreferenceCategory
        android:title="AI Range Estimation">
        
        <SwitchPreference
            android:key="ai_range_estimation_enabled"
            android:title="Enable AI Range Estimation"
            android:summary="Use AI to enhance range predictions based on your riding patterns"
            android:defaultValue="false"/>
        
        <Preference
            android:key="ai_configure_openrouter"
            android:title="Configure OpenRouter AI"
            android:summary="Connect to OpenRouter and select AI model"
            android:dependency="ai_range_estimation_enabled">
            <!-- Launches OAuthActivity for OpenRouter configuration -->
        </Preference>
        
        <Preference
            android:key="ai_connection_status"
            android:title="Connection Status"
            android:summary="Not connected"
            android:enabled="false"
            android:dependency="ai_range_estimation_enabled"/>
        
        <Preference
            android:key="ai_selected_model"
            android:title="Selected AI Model"
            android:summary="No model selected"
            android:enabled="false"
            android:dependency="ai_range_estimation_enabled"/>
        
        <SwitchPreference
            android:key="ai_show_reasoning"
            android:title="Show AI Reasoning"
            android:summary="Display AI's reasoning for range estimates"
            android:defaultValue="true"
            android:dependency="ai_range_estimation_enabled"/>
        
        <Preference
            android:key="ai_profile_stats"
            android:title="View Rider Profile"
            android:summary="See your historical consumption patterns"
            android:dependency="ai_range_estimation_enabled"/>
        
        <Preference
            android:key="ai_reset_profile"
            android:title="Reset Rider Profile"
            android:summary="Clear all historical riding data"
            android:dependency="ai_range_estimation_enabled"/>
    </PreferenceCategory>
</PreferenceScreen>
```

**Settings Handler:**

```kotlin
class DeveloperSettingsFragment : PreferenceFragmentCompat() {
    
    @Inject
    lateinit var tokenManager: TokenManager
    
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_developer, rootKey)
        
        // Configure OpenRouter button
        findPreference<Preference>("ai_configure_openrouter")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), OAuthActivity::class.java))
            true
        }
        
        // View rider profile button
        findPreference<Preference>("ai_profile_stats")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), RiderProfileActivity::class.java))
            true
        }
        
        // Reset profile button
        findPreference<Preference>("ai_reset_profile")?.setOnPreferenceClickListener {
            showResetProfileConfirmation()
            true
        }
        
        // Update connection status
        updateConnectionStatus()
    }
    
    override fun onResume() {
        super.onResume()
        updateConnectionStatus()  // Refresh after returning from OAuth
    }
    
    private fun updateConnectionStatus() {
        val isConnected = !tokenManager.getOpenRouterApiKey().isNullOrEmpty()
        val selectedModel = tokenManager.getSelectedAiModel()
        
        findPreference<Preference>("ai_connection_status")?.apply {
            summary = if (isConnected) "✅ Connected to OpenRouter" else "Not connected"
        }
        
        findPreference<Preference>("ai_selected_model")?.apply {
            summary = selectedModel ?: "No model selected"
        }
    }
}
```

### 3.2 Range Display UI Enhancement

Show both baseline and AI estimates:

```kotlin
// RangeDisplayFragment.kt
class RangeDisplayFragment : Fragment() {
    
    private fun updateRangeDisplay(estimate: AIRangeEstimate) {
        // Show baseline estimate
        binding.baselineRangeText.text = String.format("%.1f km", estimate.baselineEstimate?.rangeKm ?: 0.0)
        binding.baselineConfidenceText.text = String.format("%.0f%%", (estimate.baselineEstimate?.confidence ?: 0.0) * 100)
        
        // Show AI estimate if available
        if (estimate.useAI && estimate.aiEnhancedEstimate != null) {
            binding.aiRangeLayout.visibility = View.VISIBLE
            binding.aiRangeText.text = String.format("%.1f km", estimate.aiEnhancedEstimate.rangeKm)
            binding.aiConfidenceText.text = String.format("%.0f%%", estimate.aiEnhancedEstimate.confidence * 100)
            
            // Show AI reasoning in expandable section
            binding.aiReasoningText.text = estimate.aiEnhancedEstimate.reasoning
            
            // Highlight which estimate is shown to user
            val displayedRange = if (estimate.aiEnhancedEstimate.confidence > estimate.baselineEstimate.confidence) {
                estimate.aiEnhancedEstimate.rangeKm
            } else {
                estimate.baselineEstimate.rangeKm
            }
            
            binding.mainRangeText.text = String.format("%.1f km", displayedRange)
        } else {
            binding.aiRangeLayout.visibility = View.GONE
            binding.mainRangeText.text = String.format("%.1f km", estimate.baselineEstimate?.rangeKm ?: 0.0)
        }
    }
}
```

## Phase 4: Implementation Roadmap (Updated with OpenRouter.ai)

### Milestone 1: OpenRouter OAuth Integration (1-2 weeks)

**Goal:** Integrate OpenRouter.ai OAuth flow from PEVPlugShare

**Tasks:**
- [ ] Copy OAuth files from PEVPlugShare to OSM-EUC-World:
  - `service/OpenRouterOAuthService.kt` → `app/src/main/java/com/a42r/eucosmandplugin/ai/service/`
  - `viewmodel/OpenRouterOAuthViewModel.kt` → `app/src/main/java/com/a42r/eucosmandplugin/ai/viewmodel/`
  - `ui/oauth/OAuthActivity.kt` → `app/src/main/java/com/a42r/eucosmandplugin/ai/ui/`
  - `util/OAuthSignature.kt` → `app/src/main/java/com/a42r/eucosmandplugin/ai/util/`
- [ ] Configure NativeSecrets for OSM-EUC-World:
  - Deep link scheme: `osmeucworld://oauth/callback`
  - OAuth callback URL (reuse or create new endpoint)
  - Shared secret for signature generation
- [ ] Update AndroidManifest.xml with deep link intent-filter
- [ ] Extend TokenManager to store OpenRouter credentials:
  - API key (encrypted)
  - Selected model ID
  - PKCE code verifier (temporary)
- [ ] Add OAuth launch button to developer settings
- [ ] Test complete OAuth flow end-to-end
- [ ] Test model selection and account balance display

**Deliverable:** Users can authenticate with OpenRouter and select AI model

### Milestone 2: Rider Profile Data Collection (2-3 weeks)

**Goal:** Collect and aggregate historical riding patterns

**Tasks:**
- [ ] Implement `RiderProfile` data structures (see Phase 1 section)
- [ ] Create database schema and migrations for profiles
- [ ] Build `RiderProfileBuilder` service
- [ ] Add profile building to trip completion flow
- [ ] Implement speed-based profile extraction
- [ ] Implement terrain-based profile extraction (requires GPS elevation)
- [ ] Implement behavior pattern analysis
- [ ] Test profile building with existing trip logs
- [ ] Validate data quality and accuracy metrics
- [ ] Add profile update on each completed trip

**Deliverable:** App collects and stores rider profiles from completed trips

### Milestone 3: Rider Profile Analysis UI (1 week)

**Goal:** Allow users to view their riding patterns

**Tasks:**
- [ ] Create `RiderProfileActivity` to display profile stats
- [ ] Show speed consumption charts (using MPAndroidChart or similar)
- [ ] Show terrain consumption charts
- [ ] Display behavior patterns (aggressiveness, regen efficiency, etc.)
- [ ] Add profile export feature (JSON export for debugging)
- [ ] Add profile reset functionality with confirmation dialog
- [ ] Link profile viewer from developer settings

**Deliverable:** Users can view and understand their riding patterns

### Milestone 4: AI Range Estimator Service (2 weeks)

**Goal:** Implement AI-enhanced range estimation using OpenRouter.ai

**Tasks:**
- [ ] Create `RangeEstimationAIService` (see Phase 2.2 section)
- [ ] Build prompt generation logic:
  - Format current trip data
  - Format rider profile patterns
  - Format baseline estimate context
- [ ] Implement OpenRouter.ai API integration:
  - Request builder with proper headers
  - Response parser (JSON structured output)
  - Error handling and fallback to baseline
- [ ] Add sanity checking for AI responses:
  - Range must be > 0
  - Range should be within 50% of baseline (configurable)
  - Confidence must be 0.0-1.0
- [ ] Implement pattern matching logic
- [ ] Add comprehensive logging for debugging
- [ ] Test with real trip data and various scenarios

**Deliverable:** AI service can generate enhanced range estimates

### Milestone 5: UI Integration & Display (1-2 weeks)

**Goal:** Display both baseline and AI estimates to users

**Tasks:**
- [ ] Update range display fragment to show dual estimates:
  - Baseline estimate (always shown)
  - AI-enhanced estimate (when available)
  - Confidence scores for both
- [ ] Add AI reasoning expandable section
- [ ] Add AI assumptions and risk factors display
- [ ] Implement estimate selection logic (higher confidence wins)
- [ ] Update developer settings UI (see Phase 3.1)
- [ ] Add connection status indicator
- [ ] Add AI enable/disable toggle
- [ ] Handle AI unavailable gracefully (show baseline only)

**Deliverable:** Complete user-facing AI range estimation UI

### Milestone 6: Testing & Refinement (2-3 weeks)

**Goal:** Validate accuracy and optimize performance

**Tasks:**
- [ ] Real-world testing with multiple riders and wheel types
- [ ] Collect accuracy metrics (AI vs baseline vs actual)
- [ ] Tune weighting between baseline and AI (confidence threshold)
- [ ] Optimize prompts for better predictions:
  - Iterate on prompt structure
  - Add more context if needed
  - Test with different models
- [ ] Performance optimization:
  - Cache AI responses (30-60 seconds)
  - Throttle requests (max 1 per minute during ride)
  - Background processing
- [ ] Battery impact assessment
- [ ] Cost analysis (for paid models)
- [ ] Documentation and user guide

**Deliverable:** Production-ready AI range estimation

### Total Timeline: 8-10 weeks

**Time Savings:** OpenRouter.ai integration saves 1-2 weeks compared to implementing multiple LLM provider integrations

## Phase 5: Advanced Features (Future)

### 5.1 Route-Aware Predictions
- Integrate with Google Maps / navigation
- Predict consumption for planned route
- Factor in elevation profile, traffic patterns

### 5.2 Weather Integration
- API for weather data (wind, temperature)
- Adjust predictions based on weather
- Historical weather correlation

### 5.3 Community Learning
- Anonymous aggregation of rider profiles
- Learn from similar riders/wheels
- Improve cold-start predictions for new users

### 5.4 On-Device LLM
- Train custom lightweight model
- Run entirely on-device (privacy, offline)
- Use TensorFlow Lite or ONNX

## Cost & Privacy Considerations

### API Costs (OpenRouter.ai)

**Free Models (Recommended for Most Users):**
- **Google Gemini 2.0 Flash Exp**: FREE, excellent quality, fast
- **Meta Llama 3.3 70B**: FREE, very good quality
- Cost per trip: **$0.00** ✅

**Paid Models (Power Users):**
- **Google Gemini 1.5 Flash**: ~$0.00005 per request
- **Anthropic Claude 3 Haiku**: ~$0.00025 per request
- **OpenAI GPT-4o Mini**: ~$0.0003 per request

**Estimate for Paid Models:**
- ~50 requests per trip (1 per minute for typical ride)
- Cost per trip: $0.0025 - $0.015
- Monthly cost (20 trips): $0.05 - $0.30

**Cost Advantage of OpenRouter.ai:**
- Bulk pricing (cheaper than direct API access)
- No need for separate subscriptions to multiple providers
- User controls cost by choosing model
- Free tier available (no credit card needed for free models)

**Mitigation Strategies:**
- Default to free models (Gemini 2.0 Flash Exp)
- Cache AI responses for 30-60 seconds
- Throttle requests (max 1 per minute)
- Show estimated cost in settings before enabling

### Privacy

**Data Minimization:**
- Rider profiles stored **locally only** (never uploaded)
- API calls include anonymized trip statistics (no GPS coordinates)
- No personally identifiable information sent to OpenRouter
- Prompts contain: battery %, speed, efficiency, distance (generic riding data)

**User Control:**
- OAuth flow gives user full control over API key
- Can disconnect anytime (revoke API key)
- Profile data can be exported/deleted locally
- AI can be disabled without losing profile data

**OpenRouter Privacy:**
- OAuth2 PKCE (no client secret stored in app)
- API key stored in encrypted SharedPreferences
- User owns the API key (not controlled by app developer)

**Compliance:**
- No PII transmitted
- GDPR compliant (local data, user control)
- No tracking or analytics in AI prompts

### Battery Impact

**Network Overhead:**
- API request/response: ~5-10 seconds
- Data transfer: ~5-10 KB per request
- Minimal battery impact from network

**Request Frequency:**
- Throttled to once per minute during trip
- Cached for 30-60 seconds
- Only when battery changes significantly

**Estimated Impact:**
- < 0.5% battery per hour (network only)
- Negligible compared to Bluetooth, GPS, screen

**Optimization:**
- Batch profile updates (end of trip only)
- Background thread processing
- Request coalescing

## Success Metrics

### Accuracy Improvement
- Measure prediction error vs baseline algorithm
- Target: 15-20% improvement in accuracy
- Track over different trip types

### User Engagement
- Track feature adoption rate
- Collect user feedback on AI reasoning
- Monitor confidence scores

### Performance
- API response time < 3 seconds
- Profile building < 1 second per trip
- No perceptible UI lag

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| LLM hallucination (unrealistic estimates) | High | Sanity checking, bounded outputs, fallback to baseline |
| API costs too high | Medium | Use on-device model, cache aggressively, limit requests |
| Privacy concerns | High | Local-first design, anonymize data, transparent controls |
| Insufficient historical data | Medium | Hybrid approach, gradual transition from baseline to AI |
| LLM service outage | Low | Graceful fallback to baseline algorithm |
| Battery drain | Medium | Request throttling, caching, background processing |

## Conclusion

This implementation plan provides a comprehensive path to AI-enhanced range estimation that:
- ✅ Learns from rider's historical patterns
- ✅ Provides more accurate predictions than pure algorithms
- ✅ Maintains privacy and works offline
- ✅ Fails gracefully to baseline algorithm
- ✅ Gives users transparency into AI reasoning

The hybrid approach (traditional baseline + AI enhancement) ensures the system always works, even when AI is unavailable or untrained, while providing meaningful accuracy improvements when sufficient data exists.
