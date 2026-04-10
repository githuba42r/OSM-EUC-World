package com.a42r.eucosmandplugin.range.util

import android.content.Context
import android.util.Log
import com.a42r.eucosmandplugin.range.model.BatterySample
import com.a42r.eucosmandplugin.range.model.RangeEstimate
import com.a42r.eucosmandplugin.range.model.TripSnapshot
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data capture logger for developer mode.
 * Captures battery samples and trip data for post-trip analysis.
 * 
 * Log file format: JSON Lines (JSONL) with one JSON object per line
 * - Compact storage
 * - Easy to parse
 * - Can be analyzed with standard tools (jq, pandas, etc.)
 */
class DataCaptureLogger(private val context: Context) {
    
    companion object {
        private const val TAG = "DataCaptureLogger"
        private const val LOG_DIR_NAME = "trip_logs"
        private const val PREF_LOGGING_ENABLED = "developer_logging_enabled"
        private const val PREF_CURRENT_LOG_FILE = "developer_current_log_file"
    }
    
    // Use compact (non-pretty) JSON so each entry occupies a single physical line
    // — true JSON Lines format, matching the documented ai-log-analysis schema
    // and enabling line-oriented tooling (head / tail / streaming parse).
    private val gson = GsonBuilder()
        .serializeNulls()
        .create()
    
    private val prefs = context.getSharedPreferences("developer_settings", Context.MODE_PRIVATE)
    private var currentLogFile: File? = null
    private var currentLogWriter: FileWriter? = null
    private var sampleCount = 0

    // Streaming trip-end aggregates. These are accumulated as samples are
    // written and flushed into the footer metadata when the log closes. They
    // are informational — the authoritative values are recomputed during
    // replay by RiderProfileBuilder — but writing them makes the closed log
    // self-describing for inspection tools.
    private var elevationReferenceM: Double = Double.NaN
    private var totalAscentMeters: Double = 0.0
    private var totalDescentMeters: Double = 0.0
    private var speedWeightedTimeSec: Double = 0.0
    private var totalTimeSec: Double = 0.0
    private var previousSampleTsMs: Long = 0L
    private var tripStartTsMs: Long = 0L
    private var tripEndTsMs: Long = 0L

    /** Deadband in metres for ignoring GPS altitude noise. */
    private val ELEVATION_DEADBAND_M = 0.5
    /** Maximum dt (seconds) between samples before the gap is dropped from time-weighted aggregates. */
    private val MAX_SAMPLE_GAP_SEC = 5.0

    // Wheel context for log metadata. Captured via [setWheelContext] when the wheel
    // is detected, and re-emitted as a `wheel_detected` event on the current log so
    // that [TripLogReader] can recover the wheel identity from older logs that
    // started before detection ran.
    private var currentWheelModel: String? = null
    private var currentBatteryCapacityWh: Double? = null
    
    /**
     * Check if logging is enabled.
     */
    fun isLoggingEnabled(): Boolean {
        return prefs.getBoolean(PREF_LOGGING_ENABLED, false)
    }

    /**
     * Record the currently-detected wheel so future log files can embed the wheel
     * identity in their metadata header. If a log is already open, a
     * `wheel_detected` event is appended so offline readers can still recover the
     * wheel model for logs that started before detection completed.
     *
     * Pass null values to clear the stored context.
     */
    fun setWheelContext(wheelModel: String?, batteryCapacityWh: Double?) {
        val modelChanged = currentWheelModel != wheelModel
        currentWheelModel = wheelModel
        currentBatteryCapacityWh = batteryCapacityWh

        if (modelChanged && wheelModel != null && currentLogWriter != null) {
            val details = mutableMapOf<String, Any?>("wheelModel" to wheelModel)
            batteryCapacityWh?.let { details["batteryCapacityWh"] = it }
            logEvent("wheel_detected", details)
        }
    }

    /**
     * Get the log directory (public accessor for offline replay).
     */
    fun getLogDirectoryFile(): File = getLogDirectory()
    
    /**
     * Check if currently logging (log file is open).
     */
    fun isCurrentlyLogging(): Boolean {
        return currentLogFile != null && currentLogWriter != null
    }
    
    /**
     * Enable or disable logging.
     */
    fun setLoggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_LOGGING_ENABLED, enabled).apply()
        
        if (enabled && currentLogFile == null) {
            startNewLog()
        } else if (!enabled) {
            closeCurrentLog()
        }
    }
    
    /**
     * Start a new log file with current timestamp.
     */
    fun startNewLog() {
        closeCurrentLog()
        
        if (!isLoggingEnabled()) {
            Log.d(TAG, "Logging disabled, not starting new log")
            return
        }
        
        val logDir = getLogDirectory()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "trip_$timestamp.jsonl"
        
        currentLogFile = File(logDir, fileName)
        
        try {
            currentLogWriter = FileWriter(currentLogFile, true)
            sampleCount = 0
            // Reset streaming trip-end aggregates for the new log.
            elevationReferenceM = Double.NaN
            totalAscentMeters = 0.0
            totalDescentMeters = 0.0
            speedWeightedTimeSec = 0.0
            totalTimeSec = 0.0
            previousSampleTsMs = 0L
            tripStartTsMs = 0L
            tripEndTsMs = 0L

            // Write header metadata
            val metadata = mutableMapOf<String, Any?>(
                "type" to "metadata",
                "version" to 3,
                "startTime" to System.currentTimeMillis(),
                "startTimestamp" to timestamp,
                "deviceInfo" to mapOf(
                    "manufacturer" to android.os.Build.MANUFACTURER,
                    "model" to android.os.Build.MODEL,
                    "androidVersion" to android.os.Build.VERSION.SDK_INT
                )
            )
            // Include wheel context if already known (used by offline replay to
            // build rider profiles without relying on shared preferences).
            currentWheelModel?.let { metadata["wheelModel"] = it }
            currentBatteryCapacityWh?.let { metadata["batteryCapacityWh"] = it }
            writeJsonLine(metadata)
            
            prefs.edit().putString(PREF_CURRENT_LOG_FILE, currentLogFile?.absolutePath).apply()
            
            Log.d(TAG, "Started new log: ${currentLogFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start new log", e)
            currentLogFile = null
            currentLogWriter = null
        }
    }
    
    /**
     * Log a battery sample.
     */
    fun logSample(sample: BatterySample) {
        if (!isLoggingEnabled() || currentLogWriter == null) {
            return
        }
        
        try {
            val altitudeJson: Double? = if (sample.altitudeMeters.isNaN()) null else sample.altitudeMeters

            val sampleData = mapOf(
                "type" to "sample",
                "timestamp" to sample.timestamp,
                "voltage" to sample.voltage,
                "compensatedVoltage" to sample.compensatedVoltage,
                "batteryPercent" to sample.batteryPercent,
                "tripDistanceKm" to sample.tripDistanceKm,
                "speedKmh" to sample.speedKmh,
                "powerWatts" to sample.powerWatts,
                "currentAmps" to sample.currentAmps,
                "temperatureCelsius" to sample.temperatureCelsius,
                "latitude" to sample.latitude,
                "longitude" to sample.longitude,
                "gpsSpeedKmh" to sample.gpsSpeedKmh,
                "altitudeMeters" to altitudeJson,
                "hasGpsLocation" to sample.hasGpsLocation,
                "hasAltitude" to sample.hasAltitude,
                "voltageSag" to sample.voltageSag,
                "hasSignificantSag" to sample.hasSignificantSag,
                "instantEfficiencyWhPerKm" to if (sample.instantEfficiencyWhPerKm.isNaN()) null else sample.instantEfficiencyWhPerKm,
                "isValidForEstimation" to sample.isValidForEstimation,
                "flags" to sample.flags.map { it.name }
            )

            writeJsonLine(sampleData)
            sampleCount++

            // ---- Streaming trip-end aggregates ----
            if (tripStartTsMs == 0L) tripStartTsMs = sample.timestamp
            tripEndTsMs = sample.timestamp

            // Time-weighted average speed — accumulate Σ(speed·dt) and Σdt,
            // dropping pathological gaps (connection dropouts).
            if (previousSampleTsMs != 0L) {
                val dtSec = (sample.timestamp - previousSampleTsMs) / 1000.0
                if (dtSec > 0.0 && dtSec <= MAX_SAMPLE_GAP_SEC && sample.speedKmh >= 0.0) {
                    totalTimeSec += dtSec
                    speedWeightedTimeSec += sample.speedKmh * dtSec
                }
            }
            previousSampleTsMs = sample.timestamp

            // Ascent / descent with deadband — a climb of < 0.5 m doesn't
            // count (GPS altitude jitter). The reference only advances when
            // the delta exceeds the deadband, otherwise small oscillations
            // around a plateau don't silently accumulate.
            if (!sample.altitudeMeters.isNaN()) {
                val cur = sample.altitudeMeters
                if (elevationReferenceM.isNaN()) {
                    elevationReferenceM = cur
                } else {
                    val delta = cur - elevationReferenceM
                    if (delta > ELEVATION_DEADBAND_M) {
                        totalAscentMeters += delta
                        elevationReferenceM = cur
                    } else if (delta < -ELEVATION_DEADBAND_M) {
                        totalDescentMeters += -delta
                        elevationReferenceM = cur
                    }
                    // else: within deadband, hold reference
                }
            }
            
            // Flush every 100 samples to ensure data is written
            if (sampleCount % 100 == 0) {
                currentLogWriter?.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log sample", e)
        }
    }
    
    /**
     * Log a range estimate.
     */
    fun logEstimate(estimate: RangeEstimate) {
        if (!isLoggingEnabled() || currentLogWriter == null) {
            return
        }
        
        try {
            val estimateData = mapOf(
                "type" to "estimate",
                "timestamp" to System.currentTimeMillis(),
                "rangeKm" to estimate.rangeKm,
                "confidence" to estimate.confidence,
                "status" to estimate.status.name,
                "efficiencyWhPerKm" to estimate.efficiencyWhPerKm,
                "estimatedTimeMinutes" to estimate.estimatedTimeMinutes,
                "dataQuality" to mapOf(
                    "totalSamples" to estimate.dataQuality.totalSamples,
                    "validSamples" to estimate.dataQuality.validSamples,
                    "interpolatedSamples" to estimate.dataQuality.interpolatedSamples,
                    "chargingEvents" to estimate.dataQuality.chargingEvents,
                    "baselineReason" to estimate.dataQuality.baselineReason,
                    "travelTimeMinutes" to estimate.dataQuality.travelTimeMinutes,
                    "travelDistanceKm" to estimate.dataQuality.travelDistanceKm,
                    "meetsMinimumTime" to estimate.dataQuality.meetsMinimumTime,
                    "meetsMinimumDistance" to estimate.dataQuality.meetsMinimumDistance
                )
            )
            
            writeJsonLine(estimateData)
            currentLogWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log estimate", e)
        }
    }
    
    /**
     * Log a range estimate with detailed reasoning and calculation details.
     * This version includes diagnostic information about why the estimate has a particular status
     * and details about the calculations performed.
     */
    fun logEstimateWithReasoning(
        estimate: RangeEstimate,
        algorithm: String,
        reasoning: Map<String, Any?>
    ) {
        if (!isLoggingEnabled() || currentLogWriter == null) {
            return
        }
        
        try {
            val estimateData = mapOf(
                "type" to "estimate_detailed",
                "timestamp" to System.currentTimeMillis(),
                "algorithm" to algorithm,
                "rangeKm" to estimate.rangeKm,
                "confidence" to estimate.confidence,
                "status" to estimate.status.name,
                "efficiencyWhPerKm" to estimate.efficiencyWhPerKm,
                "estimatedTimeMinutes" to estimate.estimatedTimeMinutes,
                "dataQuality" to mapOf(
                    "totalSamples" to estimate.dataQuality.totalSamples,
                    "validSamples" to estimate.dataQuality.validSamples,
                    "interpolatedSamples" to estimate.dataQuality.interpolatedSamples,
                    "chargingEvents" to estimate.dataQuality.chargingEvents,
                    "baselineReason" to estimate.dataQuality.baselineReason,
                    "travelTimeMinutes" to estimate.dataQuality.travelTimeMinutes,
                    "travelDistanceKm" to estimate.dataQuality.travelDistanceKm,
                    "meetsMinimumTime" to estimate.dataQuality.meetsMinimumTime,
                    "meetsMinimumDistance" to estimate.dataQuality.meetsMinimumDistance,
                    "timeProgress" to estimate.dataQuality.timeProgress,
                    "distanceProgress" to estimate.dataQuality.distanceProgress,
                    "validPercentage" to estimate.dataQuality.validPercentage,
                    "interpolatedPercentage" to estimate.dataQuality.interpolatedPercentage
                ),
                "reasoning" to reasoning
            )
            
            writeJsonLine(estimateData)
            currentLogWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log detailed estimate", e)
        }
    }
    
    /**
     * Log a status transition (e.g., INSUFFICIENT_DATA -> COLLECTING -> VALID).
     * This helps diagnose when and why the range estimation status changes.
     */
    fun logStatusTransition(
        fromStatus: String?,
        toStatus: String,
        reason: String,
        details: Map<String, Any?>
    ) {
        if (!isLoggingEnabled() || currentLogWriter == null) {
            return
        }
        
        try {
            val transitionData = mutableMapOf<String, Any?>(
                "type" to "status_transition",
                "timestamp" to System.currentTimeMillis(),
                "fromStatus" to fromStatus,
                "toStatus" to toStatus,
                "reason" to reason
            )
            transitionData.putAll(details)
            
            writeJsonLine(transitionData)
            currentLogWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log status transition", e)
        }
    }
    
    /**
     * Log a trip event (charging start/end, baseline change, etc.)
     */
    fun logEvent(eventType: String, details: Map<String, Any?>) {
        if (!isLoggingEnabled() || currentLogWriter == null) {
            return
        }
        
        try {
            val eventData = mutableMapOf<String, Any?>(
                "type" to "event",
                "timestamp" to System.currentTimeMillis(),
                "eventType" to eventType
            )
            eventData.putAll(details)
            
            writeJsonLine(eventData)
            currentLogWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log event", e)
        }
    }
    
    /**
     * Log trip snapshot summary.
     */
    fun logTripSnapshot(trip: TripSnapshot) {
        if (!isLoggingEnabled() || currentLogWriter == null) {
            return
        }
        
        try {
            val snapshotData = mapOf(
                "type" to "snapshot",
                "timestamp" to System.currentTimeMillis(),
                "totalSamples" to trip.samples.size,
                "totalSegments" to trip.segments.size,
                "chargingEvents" to trip.chargingEvents.size,
                "isCurrentlyCharging" to trip.isCurrentlyCharging,
                "validSampleCount" to trip.validSampleCount,
                "interpolatedSampleCount" to trip.interpolatedSampleCount,
                "ridingTimeMsSinceBaseline" to trip.getRidingTimeMsSinceBaseline(),
                "distanceKmSinceBaseline" to trip.getDistanceKmSinceBaseline()
            )
            
            writeJsonLine(snapshotData)
            currentLogWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log snapshot", e)
        }
    }
    
    /**
     * Close current log file.
     */
    fun closeCurrentLog() {
        try {
            if (currentLogWriter != null) {
                // Compute trip-end summary from streaming aggregates.
                val timeWeightedAvgSpeed = if (totalTimeSec > 0.0) {
                    speedWeightedTimeSec / totalTimeSec
                } else 0.0
                val durationSec = if (tripStartTsMs != 0L && tripEndTsMs >= tripStartTsMs) {
                    (tripEndTsMs - tripStartTsMs) / 1000.0
                } else 0.0

                // Write footer metadata — informational totals mirrored from the
                // streaming aggregates. Replay still recomputes these from raw
                // samples, but writing them makes logs self-describing.
                val metadata = mapOf(
                    "type" to "metadata",
                    "endTime" to System.currentTimeMillis(),
                    "totalSamples" to sampleCount,
                    "totalAscentMeters" to totalAscentMeters,
                    "totalDescentMeters" to totalDescentMeters,
                    "timeWeightedAvgSpeedKmh" to timeWeightedAvgSpeed,
                    "durationSeconds" to durationSec
                )
                writeJsonLine(metadata)

                currentLogWriter?.flush()
                currentLogWriter?.close()

                Log.d(TAG, "Closed log with $sampleCount samples, ascent=${"%.1f".format(totalAscentMeters)}m descent=${"%.1f".format(totalDescentMeters)}m avgSpd=${"%.1f".format(timeWeightedAvgSpeed)}km/h: ${currentLogFile?.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close log", e)
        } finally {
            currentLogWriter = null
            currentLogFile = null
            sampleCount = 0
            // Reset streaming aggregates so a later startNewLog() sees a clean slate.
            elevationReferenceM = Double.NaN
            totalAscentMeters = 0.0
            totalDescentMeters = 0.0
            speedWeightedTimeSec = 0.0
            totalTimeSec = 0.0
            previousSampleTsMs = 0L
            tripStartTsMs = 0L
            tripEndTsMs = 0L
            prefs.edit().remove(PREF_CURRENT_LOG_FILE).apply()
        }
    }
    
    /**
     * Get the log directory, creating it if necessary.
     */
    private fun getLogDirectory(): File {
        val logDir = File(context.getExternalFilesDir(null), LOG_DIR_NAME)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return logDir
    }
    
    /**
     * Write a JSON line to the log file.
     */
    private fun writeJsonLine(data: Map<String, Any?>) {
        currentLogWriter?.apply {
            write(gson.toJson(data))
            write("\n")
        }
    }
    
    /**
     * Get list of all log files.
     */
    fun getLogFiles(): List<LogFileInfo> {
        val logDir = getLogDirectory()
        return logDir.listFiles { file -> file.extension == "jsonl" }
            ?.map { file ->
                LogFileInfo(
                    file = file,
                    name = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified()
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }
    
    /**
     * Delete a log file.
     */
    fun deleteLogFile(file: File): Boolean {
        return try {
            // Don't delete the current active log
            if (file.absolutePath == currentLogFile?.absolutePath) {
                closeCurrentLog()
            }
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete log file", e)
            false
        }
    }
    
    /**
     * Delete all log files.
     */
    fun deleteAllLogs(): Int {
        closeCurrentLog()
        
        val logDir = getLogDirectory()
        val files = logDir.listFiles { file -> file.extension == "jsonl" } ?: emptyArray()
        
        var deletedCount = 0
        files.forEach { file ->
            if (file.delete()) {
                deletedCount++
            }
        }
        
        Log.d(TAG, "Deleted $deletedCount log files")
        return deletedCount
    }
    
    /**
     * Information about a log file.
     */
    data class LogFileInfo(
        val file: File,
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long
    ) {
        fun getSizeFormatted(): String {
            return when {
                sizeBytes < 1024 -> "$sizeBytes B"
                sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
                else -> "${sizeBytes / (1024 * 1024)} MB"
            }
        }
        
        fun getDateFormatted(): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(lastModified))
        }
    }
}
