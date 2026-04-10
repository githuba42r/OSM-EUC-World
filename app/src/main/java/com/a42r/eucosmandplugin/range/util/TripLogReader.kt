package com.a42r.eucosmandplugin.range.util

import android.util.Log
import com.a42r.eucosmandplugin.range.model.BatterySample
import com.a42r.eucosmandplugin.range.model.SampleFlag
import com.a42r.eucosmandplugin.range.model.TripSnapshot
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File

/**
 * Reads a JSONL trip log file back into a [TripSnapshot] for offline processing
 * (e.g., rebuilding the rider profile from historical captures).
 *
 * The reader tolerates missing optional fields and silently skips malformed lines.
 */
class TripLogReader {

    companion object {
        private const val TAG = "TripLogReader"
    }

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    /**
     * Parsed output of a single log file.
     *
     * @param tripSnapshot Reconstructed trip snapshot with all valid samples.
     * @param wheelModel Wheel model recorded in the log metadata (raw identifier), if any.
     * @param batteryCapacityWh Battery capacity recorded in the log metadata, if any.
     * @param startTime Trip start time in ms (from metadata), or first sample timestamp.
     * @param totalLines Total JSONL lines read from the file.
     * @param sampleCount Number of sample entries successfully parsed.
     */
    data class ParsedTripLog(
        val tripSnapshot: TripSnapshot,
        val wheelModel: String?,
        val batteryCapacityWh: Double?,
        val startTime: Long,
        val totalLines: Int,
        val sampleCount: Int,
        val fileName: String
    )

    /**
     * Parse a JSONL trip log file.
     *
     * @return Parsed trip data, or null if the file is empty / unparseable.
     */
    fun read(file: File): ParsedTripLog? {
        if (!file.exists() || file.length() == 0L) {
            Log.d(TAG, "Skipping empty or missing log file: ${file.name}")
            return null
        }

        val samples = mutableListOf<BatterySample>()
        var startTime: Long = 0L
        var wheelModel: String? = null
        var batteryCapacityWh: Double? = null
        var totalObjects = 0

        // DataCaptureLogger currently uses GsonBuilder.setPrettyPrinting(), which
        // means each JSON object spans multiple physical lines. We therefore use
        // a streaming reader in LENIENT mode which happily consumes a sequence of
        // pretty-printed (or compact) JSON objects separated by any whitespace.
        try {
            file.bufferedReader().use { br ->
                val reader = JsonReader(br)
                reader.isLenient = true
                while (true) {
                    val token = try {
                        reader.peek()
                    } catch (e: Exception) {
                        break
                    }
                    if (token == JsonToken.END_DOCUMENT) break

                    val obj: Map<String, Any?> = try {
                        gson.fromJson<Map<String, Any?>>(reader, mapType) ?: continue
                    } catch (e: Exception) {
                        // Abort the file on structural parse error — lenient mode
                        // copes with whitespace but not with corrupt objects.
                        Log.w(TAG, "Stopping parse of ${file.name} after ${totalObjects} " +
                                "objects due to: ${e.message}")
                        break
                    }
                    totalObjects++

                    when (obj["type"] as? String) {
                        "metadata" -> {
                            (obj["startTime"] as? Number)?.toLong()?.let { startTime = it }
                            // New-format fields (added by this change).
                            (obj["wheelModel"] as? String)?.takeIf { it.isNotBlank() }
                                ?.let { wheelModel = it }
                            (obj["batteryCapacityWh"] as? Number)?.toDouble()
                                ?.let { batteryCapacityWh = it }
                        }
                        "event" -> {
                            // Fallback: older logs may record wheel model in a wheel_detected event.
                            if (wheelModel == null &&
                                (obj["eventType"] as? String) == "wheel_detected") {
                                (obj["wheelModel"] as? String)?.takeIf { it.isNotBlank() }
                                    ?.let { wheelModel = it }
                                (obj["batteryCapacityWh"] as? Number)?.toDouble()
                                    ?.let { batteryCapacityWh = it }
                            }
                        }
                        "sample" -> {
                            parseSample(obj)?.let { samples.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ${file.name}: ${e.message}", e)
            return null
        }

        if (samples.isEmpty()) {
            Log.d(TAG, "No parseable samples in ${file.name}")
            return null
        }

        // Populate distanceSincePreviousSampleKm for downstream consumers
        // (RiderProfileBuilder relies on this for bucket distance summation).
        var prev: BatterySample? = null
        samples.forEach { current ->
            prev?.let { p ->
                val dDist = (current.tripDistanceKm - p.tripDistanceKm).coerceAtLeast(0.0)
                current.distanceSincePreviousSampleKm = dDist
                current.timeSincePreviousSampleMs = (current.timestamp - p.timestamp).coerceAtLeast(0L)
            }
            prev = current
        }

        val effectiveStart = if (startTime > 0L) startTime else samples.first().timestamp

        return ParsedTripLog(
            tripSnapshot = TripSnapshot(
                startTime = effectiveStart,
                samples = samples,
                segments = emptyList(),
                isCurrentlyCharging = false,
                chargingEvents = emptyList()
            ),
            wheelModel = wheelModel,
            batteryCapacityWh = batteryCapacityWh,
            startTime = effectiveStart,
            totalLines = totalObjects,
            sampleCount = samples.size,
            fileName = file.name
        )
    }

    /**
     * Convert a decoded JSON object into a [BatterySample], or null if essential
     * fields are missing.
     */
    private fun parseSample(obj: Map<String, Any?>): BatterySample? {
        val timestamp = (obj["timestamp"] as? Number)?.toLong() ?: return null
        val voltage = (obj["voltage"] as? Number)?.toDouble() ?: return null
        val compensatedVoltage = (obj["compensatedVoltage"] as? Number)?.toDouble() ?: voltage
        val batteryPercent = (obj["batteryPercent"] as? Number)?.toDouble() ?: 0.0
        val tripDistanceKm = (obj["tripDistanceKm"] as? Number)?.toDouble() ?: 0.0
        val speedKmh = (obj["speedKmh"] as? Number)?.toDouble() ?: 0.0
        val powerWatts = (obj["powerWatts"] as? Number)?.toDouble() ?: 0.0
        val currentAmps = (obj["currentAmps"] as? Number)?.toDouble() ?: 0.0
        val temperatureCelsius = (obj["temperatureCelsius"] as? Number)?.toDouble() ?: -1.0
        val latitude = (obj["latitude"] as? Number)?.toDouble() ?: 0.0
        val longitude = (obj["longitude"] as? Number)?.toDouble() ?: 0.0
        val gpsSpeedKmh = (obj["gpsSpeedKmh"] as? Number)?.toDouble() ?: 0.0

        val flags: Set<SampleFlag> = (obj["flags"] as? List<*>)
            ?.mapNotNull { entry ->
                val name = entry as? String ?: return@mapNotNull null
                try { SampleFlag.valueOf(name) } catch (e: IllegalArgumentException) { null }
            }
            ?.toSet()
            ?: emptySet()

        return BatterySample(
            timestamp = timestamp,
            voltage = voltage,
            compensatedVoltage = compensatedVoltage,
            batteryPercent = batteryPercent,
            tripDistanceKm = tripDistanceKm,
            speedKmh = speedKmh,
            powerWatts = powerWatts,
            currentAmps = currentAmps,
            temperatureCelsius = temperatureCelsius,
            latitude = latitude,
            longitude = longitude,
            gpsSpeedKmh = gpsSpeedKmh,
            flags = flags
        )
    }
}
