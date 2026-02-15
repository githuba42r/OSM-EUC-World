package com.a42r.eucosmandplugin.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for communicating with the EUC World Internal Webservice API.
 * 
 * EUC World exposes a local HTTP server that provides real-time data
 * about the connected electric unicycle.
 * 
 * API Details:
 * - Endpoint: http://127.0.0.1:8080/api/values
 * - Response: {"values": [{"v": value, "w": "key", "l": bool, ...}, ...]}
 * 
 * Key field codes (w):
 * - "vba" -> Battery % (v field)
 * - "vvo" -> Voltage (v field)
 * - "vsp" -> Speed (v field)
 * - "vte" -> Temperature (v field)
 * - "vpo" -> Power (v field)
 * - "vcu" -> Current (v field)
 * - "vdv" -> Wheel trip distance (v field)
 * - "vdt" -> Total distance/odometer (v field)
 */
class EucWorldApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val port: Int = DEFAULT_PORT
) {
    companion object {
        private const val TAG = "EucWorldApiClient"
        
        const val DEFAULT_BASE_URL = "http://127.0.0.1"
        const val DEFAULT_PORT = 8080
        
        private const val API_PATH = "/api/values"
        
        // Filter for battery/widget display (minimal data)
        private const val BATTERY_FILTER = "filter=(vba|vbf|vvo)"
        
        // Filter for full display (battery, speed, temp, wheel model, etc)
        private const val FULL_FILTER = "filter=(vba|vbf|vvo|vsp|vte|vtec|vpo|vcu|vdv|vdt|vmmo)"
        
        private const val CONNECT_TIMEOUT_MS = 5000L
        private const val READ_TIMEOUT_MS = 5000L
        private const val DEFAULT_POLL_INTERVAL_MS = 1000L
        
        // EUC World API key codes
        const val KEY_BATTERY_PERCENT = "vba"      // Battery %
        const val KEY_BATTERY_FULL = "vbf"         // Battery % (full)
        const val KEY_VOLTAGE = "vvo"              // Voltage
        const val KEY_SPEED = "vsp"                // Speed
        const val KEY_SPEED_AVG = "vsa"            // Average speed
        const val KEY_SPEED_MAX = "vsx"            // Max speed
        const val KEY_TEMPERATURE = "vte"          // Temperature (board)
        const val KEY_TEMP_CONTROLLER = "vtec"     // Controller temperature
        const val KEY_TEMP_INTERNAL = "vtei"       // Internal temperature
        const val KEY_POWER = "vpo"                // Power
        const val KEY_CURRENT = "vcu"              // Current
        const val KEY_WHEEL_TRIP = "vdv"           // Wheel trip distance
        const val KEY_TOTAL_DISTANCE = "vdt"       // Total distance (odometer)
        const val KEY_MODEL = "vmmo"               // Wheel model name
    }
    
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    
    private val gson = Gson()
    
    /**
     * Get the API URL for battery data only (minimal payload)
     */
    fun getBatteryApiUrl(): String = "$baseUrl:$port$API_PATH?$BATTERY_FILTER"
    
    /**
     * Get the API URL for full data
     */
    fun getFullApiUrl(): String = "$baseUrl:$port$API_PATH?$FULL_FILTER"
    
    /**
     * Fetch battery data only (minimal - for widget updates)
     * @return Result containing EucData or error
     */
    suspend fun fetchBatteryData(): Result<EucData> = withContext(Dispatchers.IO) {
        fetchDataFromUrl(getBatteryApiUrl())
    }
    
    /**
     * Fetch full EUC data from the webservice
     * @return Result containing EucData or error
     */
    suspend fun fetchFullData(): Result<EucData> = withContext(Dispatchers.IO) {
        fetchDataFromUrl(getFullApiUrl())
    }
    
    /**
     * Fetch and parse data from the given URL
     */
    private fun fetchDataFromUrl(url: String): Result<EucData> {
        return try {
            Log.d(TAG, "Fetching from: $url")
            
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return Result.failure(
                    IOException("HTTP ${response.code}: ${response.message}")
                )
            }
            
            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                return Result.failure(
                    IOException("Empty response from EUC World API")
                )
            }
            
            Log.v(TAG, "Response length: ${body.length} bytes")
            
            // Parse JSON response
            val apiResponse = parseResponse(body)
            val values = apiResponse?.values
            if (values.isNullOrEmpty()) {
                return Result.failure(
                    IOException("No data in response")
                )
            }
            
            // Convert to EucData
            val eucData = buildEucDataFromValues(values)
            Result.success(eucData)
            
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            Result.failure(IOException("Invalid JSON response: ${e.message}"))
        } catch (e: IOException) {
            Log.e(TAG, "IO error: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}")
            Result.failure(IOException("Unexpected error: ${e.message}"))
        }
    }
    
    /**
     * Parse the JSON response from EUC World API
     * Response format: {"values": [{"v": 100.0, "w": "vba", "l": false, ...}, ...]}
     */
    private fun parseResponse(json: String): EucWorldApiResponse? {
        return try {
            gson.fromJson(json, EucWorldApiResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}")
            null
        }
    }
    
    /**
     * Build EucData from the list of value items
     */
    private fun buildEucDataFromValues(values: List<EucValueItem>): EucData {
        // Create a map for quick lookup by key
        val valueMap = values.associateBy { it.w }
        
        fun getDouble(key: String): Double = valueMap[key]?.v ?: 0.0
        fun getInt(key: String): Int = valueMap[key]?.v?.toInt() ?: 0
        fun getString(key: String): String = valueMap[key]?.s ?: ""
        
        val batteryPercent = getInt(KEY_BATTERY_PERCENT).takeIf { it > 0 }
            ?: getInt(KEY_BATTERY_FULL)
        val voltage = getDouble(KEY_VOLTAGE)
        val speed = getDouble(KEY_SPEED)
        val temperature = getDouble(KEY_TEMPERATURE).takeIf { it != 0.0 }
            ?: getDouble(KEY_TEMP_CONTROLLER)
        val power = getDouble(KEY_POWER)
        val current = getDouble(KEY_CURRENT)
        val wheelTrip = getDouble(KEY_WHEEL_TRIP)
        val totalDistance = getDouble(KEY_TOTAL_DISTANCE)
        val wheelModel = getString(KEY_MODEL)
        
        // Consider connected if we have valid battery data
        val isConnected = batteryPercent > 0 || voltage > 0
        
        Log.d(TAG, "Parsed: battery=$batteryPercent%, voltage=${voltage}V, speed=$speed, model=$wheelModel, connected=$isConnected")
        
        return EucData(
            batteryPercentage = batteryPercent,
            voltage = voltage,
            speed = speed,
            temperature = temperature,
            power = power,
            current = current,
            wheelTrip = wheelTrip,
            totalDistance = totalDistance,
            wheelModel = wheelModel,
            isConnected = isConnected,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Check if the EUC World API is available
     * @return true if the API is reachable
     */
    suspend fun isApiAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(getBatteryApiUrl())
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "API not available: ${e.message}")
            false
        }
    }
    
    /**
     * Create a Flow that continuously polls EUC data at the specified interval
     * @param intervalMs Polling interval in milliseconds (default 1 second)
     * @param batteryOnly If true, only fetch battery data (more efficient)
     * @return Flow emitting EucData results
     */
    fun eucDataFlow(
        intervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        batteryOnly: Boolean = false
    ): Flow<Result<EucData>> = flow {
        while (true) {
            val result = if (batteryOnly) {
                fetchBatteryData()
            } else {
                fetchFullData()
            }
            emit(result)
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Create a new client with different configuration
     */
    fun withConfig(baseUrl: String, port: Int): EucWorldApiClient {
        return EucWorldApiClient(baseUrl, port)
    }
}

/**
 * Root response from EUC World API
 */
data class EucWorldApiResponse(
    val values: List<EucValueItem>? = null
)

/**
 * Represents a single value item from the EUC World API response.
 * 
 * Example JSON: {"v": 100.0, "w": "vba", "l": false, "t": 5, "a": 20}
 * 
 * @param v Numeric value
 * @param w Key/code identifying the value type (e.g., "vba" for battery %)
 * @param l Lock flag (unclear purpose)
 * @param t Type indicator
 * @param a Additional info
 * @param s String value (for model name, firmware, etc.)
 */
data class EucValueItem(
    val v: Double? = null,
    val w: String? = null,
    val l: Boolean? = null,
    val t: Int? = null,
    val a: Int? = null,
    val s: String? = null
)
