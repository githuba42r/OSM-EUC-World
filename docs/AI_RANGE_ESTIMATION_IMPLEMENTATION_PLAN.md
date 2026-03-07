# AI-Enhanced Range Estimation Implementation Plan

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

### 2.2 LLM Service Implementation

Support multiple LLM providers:

```kotlin
interface LLMService {
    suspend fun predict(prompt: String): String
    fun isAvailable(): Boolean
    fun getProviderName(): String
}

class GeminiLLMService(
    private val apiKey: String
) : LLMService {
    override suspend fun predict(prompt: String): String {
        // Call Gemini API
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=$apiKey")
            .post(buildRequestBody(prompt))
            .build()
        
        val response = client.newCall(request).execute()
        return parseGeminiResponse(response.body?.string() ?: "")
    }
    
    override fun isAvailable(): Boolean = apiKey.isNotEmpty()
    override fun getProviderName(): String = "Google Gemini"
}

class OpenAILLMService(
    private val apiKey: String
) : LLMService {
    override suspend fun predict(prompt: String): String {
        // Call OpenAI API
        // Similar implementation
    }
    
    override fun isAvailable(): Boolean = apiKey.isNotEmpty()
    override fun getProviderName(): String = "OpenAI GPT-4"
}

class OnDeviceLLMService : LLMService {
    // Use TensorFlow Lite or ML Kit for on-device inference
    override suspend fun predict(prompt: String): String {
        // Implement on-device LLM inference
        // May use quantized Llama 2 or similar small model
    }
    
    override fun isAvailable(): Boolean = true // Always available
    override fun getProviderName(): String = "On-Device Model"
}
```

## Phase 3: User Interface & Settings

### 3.1 Settings UI

Add AI options in developer settings:

```xml
<!-- settings_developer.xml -->
<PreferenceScreen>
    <PreferenceCategory
        android:title="AI Range Estimation">
        
        <SwitchPreference
            android:key="ai_range_estimation_enabled"
            android:title="Enable AI Range Estimation"
            android:summary="Use AI to enhance range predictions"
            android:defaultValue="false"/>
        
        <ListPreference
            android:key="ai_provider"
            android:title="AI Provider"
            android:entries="@array/ai_providers"
            android:entryValues="@array/ai_provider_values"
            android:defaultValue="gemini"
            android:dependency="ai_range_estimation_enabled"/>
        
        <EditTextPreference
            android:key="ai_api_key"
            android:title="API Key"
            android:summary="Enter your API key for cloud AI"
            android:dependency="ai_range_estimation_enabled"/>
        
        <SwitchPreference
            android:key="ai_show_reasoning"
            android:title="Show AI Reasoning"
            android:summary="Display AI's reasoning for estimates"
            android:defaultValue="true"
            android:dependency="ai_range_estimation_enabled"/>
        
        <Preference
            android:key="ai_profile_stats"
            android:title="View Rider Profile"
            android:summary="See your consumption patterns"/>
        
        <Preference
            android:key="ai_reset_profile"
            android:title="Reset Rider Profile"
            android:summary="Clear all historical data"/>
    </PreferenceCategory>
</PreferenceScreen>
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

## Phase 4: Implementation Roadmap

### Milestone 1: Data Collection (2-3 weeks)
- [ ] Implement `RiderProfile` data structures
- [ ] Create database schema and migrations
- [ ] Build `RiderProfileBuilder` service
- [ ] Add profile building to trip completion flow
- [ ] Test profile building with existing trip logs
- [ ] Validate data quality and accuracy

**Deliverable:** App collects and stores rider profiles from completed trips

### Milestone 2: Profile Analysis UI (1 week)
- [ ] Create rider profile viewing screen
- [ ] Show speed consumption charts
- [ ] Show terrain consumption charts
- [ ] Display behavior patterns
- [ ] Add profile export feature (for debugging)

**Deliverable:** Users can view their riding patterns

### Milestone 3: LLM Integration (2 weeks)
- [ ] Implement LLM service interfaces
- [ ] Create Gemini API integration
- [ ] Create OpenAI API integration (optional)
- [ ] Build prompt templates
- [ ] Implement response parsing
- [ ] Add error handling and fallbacks
- [ ] Test with real trip data

**Deliverable:** System can query LLM for range predictions

### Milestone 4: AI Estimator (2 weeks)
- [ ] Implement `AIRangeEstimator`
- [ ] Build prompt generation logic
- [ ] Implement pattern matching
- [ ] Add sanity checking for AI responses
- [ ] Integrate with existing range estimation pipeline
- [ ] Add comprehensive logging

**Deliverable:** AI-enhanced estimates available alongside baseline

### Milestone 5: UI Integration (1 week)
- [ ] Update range display to show both estimates
- [ ] Add AI reasoning display
- [ ] Create settings UI for AI features
- [ ] Add API key management
- [ ] Implement profile stats viewer

**Deliverable:** Complete user-facing AI range estimation

### Milestone 6: Testing & Refinement (2-3 weeks)
- [ ] Real-world testing with multiple riders
- [ ] Collect accuracy metrics
- [ ] Tune weighting between baseline and AI
- [ ] Optimize prompts for better predictions
- [ ] Performance optimization (caching, async)
- [ ] Battery impact assessment

**Deliverable:** Production-ready AI range estimation

### Total Timeline: 10-12 weeks

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

### API Costs (Cloud LLM)
- **Gemini Pro**: ~$0.00025 per request
- **GPT-4**: ~$0.03 per request
- **Estimate**: ~50 requests per trip = $0.0125 - $1.50 per trip
- **Mitigation**: Cache results, use on-device model as primary

### Privacy
- Rider profiles stored locally only
- API calls include anonymized data (no GPS coordinates in prompt)
- Option to disable cloud AI (use on-device only)
- Profile export for user transparency

### Battery Impact
- LLM API calls: ~5-10 seconds per request
- Limit to once per minute during trip
- Cache results for 30 seconds
- Estimated impact: < 1% battery per hour

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
