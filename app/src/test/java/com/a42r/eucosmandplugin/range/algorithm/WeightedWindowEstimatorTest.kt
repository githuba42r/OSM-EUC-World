package com.a42r.eucosmandplugin.range.algorithm

import com.a42r.eucosmandplugin.range.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [WeightedWindowEstimator], specifically validating the
 * physically-correct efficiency aggregation (fix for the initial-estimate
 * over-estimation issue).
 *
 * Regression coverage for: the previous implementation computed efficiency as
 * `mean(power_i / speed_i)` which is systematically biased low when speed
 * varies across the window, producing ~30-40% optimistic range estimates on
 * real trip logs. The correct formula is `Σ(P_i · w_i) / Σ(S_i · w_i)`, i.e.
 * total energy divided by total distance.
 */
class WeightedWindowEstimatorTest {

    companion object {
        // Sherman S spec
        private const val BATTERY_WH = 3200.0
        private const val CELLS = 24

        // Full-charge compensated voltage (per cell * 24 → flat-region start)
        private const val NEAR_FULL_VOLTAGE = 24 * 4.10  // ~98.4 V → ~88 %

        private const val SAMPLE_INTERVAL_MS = 500L
    }

    /**
     * Build a trip with a known mix of cruising / hard-acceleration / regen
     * samples and verify the aggregated efficiency matches the physically
     * correct total_energy / total_distance.
     *
     * The trip mixes:
     *   - 60 s cruising  @ 20 km/h, 400 W  → 20 Wh/km
     *   - 20 s climbing  @ 20 km/h, 1600 W → 80 Wh/km  (4× cruise)
     *   - 20 s coasting  @ 30 km/h, 100 W  → 3.33 Wh/km
     *
     * Distance weighted (correct):
     *   energy  = 400·60 + 1600·20 + 100·20 = 24000 + 32000 + 2000 = 58000 J·s
     *           = 58000/3600 = 16.11 Wh
     *   dist    = 20·60 + 20·20 + 30·20 = 1200 + 400 + 600 = 2200 km·s
     *           = 2200/3600 = 0.6111 km
     *   → 16.11 / 0.6111 = 26.36 Wh/km
     *
     * Simple mean of instant efficiencies (biased):
     *   mean(20, 80, 3.33) weighted by sample count 120, 40, 40 =
     *     (20·120 + 80·40 + 3.33·40) / 200 = 2400 + 3200 + 133.3 / 200 = 28.67 Wh/km
     *
     * The specific numbers differ from the ratio-of-means answer, proving the
     * arithmetic mean gives a systematically different result. The fix must
     * return the physically correct ~26.36 Wh/km value.
     */
    @Test
    fun efficiencyMatchesTotalEnergyOverTotalDistance() {
        val samples = buildMixedRidingTrip()
        val estimator = WeightedWindowEstimator(
            batteryCapacityWh = BATTERY_WH,
            cellCount = CELLS,
            // Large window so the exponential decay ≈ uniform weights.
            windowMinutes = 600,
            weightDecayFactor = 0.0,
            minTimeMinutes = 0.5,
            minDistanceKm = 0.5,
        )

        val trip = TripSnapshot(
            startTime = samples.first().timestamp,
            samples = samples,
            segments = listOf(
                TripSegment(
                    startTimestamp = samples.first().timestamp,
                    type = SegmentType.NORMAL_RIDING,
                    samples = samples.toMutableList(),
                    isBaselineSegment = true,
                    baselineReason = "test start"
                )
            ),
            isCurrentlyCharging = false,
            chargingEvents = emptyList(),
        )

        val estimate = estimator.estimate(trip)
        assertNotNull("Estimator returned null", estimate)
        val eff = estimate!!.efficiencyWhPerKm
        assertNotNull("Efficiency was null", eff)

        // Compute the expected "truth" from the synthetic trip.
        val totalEnergyWh = samples.sumOf { it.powerWatts } * SAMPLE_INTERVAL_MS / 1000.0 / 3600.0
        val totalDistanceKm = samples.sumOf { it.speedKmh } * SAMPLE_INTERVAL_MS / 1000.0 / 3600.0
        val truth = totalEnergyWh / totalDistanceKm

        // Must be within 5 % of truth. The previous buggy implementation
        // would return a value ~8-10% different from truth on this dataset.
        assertEquals(
            "Aggregated efficiency must equal total_energy / total_distance (within 5%)",
            truth, eff!!, truth * 0.05
        )
    }

    /**
     * Verify the direction of the old bug: on a trip that alternates
     * low-speed low-power and high-speed high-power samples, the algorithm's
     * efficiency should reflect the distance-weighted value rather than the
     * arithmetic mean of instant efficiencies.
     */
    @Test
    fun highSpeedHighPowerSamplesContributeProportionallyToDistance() {
        val start = System.currentTimeMillis()
        val samples = mutableListOf<BatterySample>()
        var ts = start
        var dist = 0.0

        // 100 samples at 10 km/h, 100 W → instant eff = 10 Wh/km
        repeat(100) {
            samples += buildSample(ts, NEAR_FULL_VOLTAGE, 99.0, dist, speed = 10.0, power = 100.0)
            dist += 10.0 / 3600.0 * 0.5  // km travelled in 500 ms
            ts += SAMPLE_INTERVAL_MS
        }
        // 100 samples at 30 km/h, 900 W → instant eff = 30 Wh/km
        repeat(100) {
            samples += buildSample(ts, NEAR_FULL_VOLTAGE, 99.0, dist, speed = 30.0, power = 900.0)
            dist += 30.0 / 3600.0 * 0.5
            ts += SAMPLE_INTERVAL_MS
        }

        val estimator = WeightedWindowEstimator(
            batteryCapacityWh = BATTERY_WH, cellCount = CELLS,
            windowMinutes = 600, weightDecayFactor = 0.0,
            minTimeMinutes = 0.5, minDistanceKm = 0.5,
        )
        val trip = TripSnapshot(
            startTime = start,
            samples = samples,
            segments = listOf(
                TripSegment(
                    startTimestamp = start,
                    type = SegmentType.NORMAL_RIDING,
                    samples = samples.toMutableList(),
                    isBaselineSegment = true,
                    baselineReason = "test"
                )
            )
        )
        val estimate = estimator.estimate(trip)
        val eff = estimate?.efficiencyWhPerKm
        assertNotNull(eff)

        // Correct distance-weighted answer:
        //   Σ power = 100*100 + 100*900 = 100000 W·sample
        //   Σ speed = 100*10  + 100*30  = 4000   km·sample
        //   efficiency = 100000 / 4000 = 25 Wh/km
        // BUGGY arithmetic mean of instant effs: (10+30)/2 = 20 Wh/km.
        assertEquals(
            "Aggregate efficiency should be 25 Wh/km (distance-weighted), NOT 20 Wh/km (arithmetic mean)",
            25.0, eff!!, 0.5
        )
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun buildMixedRidingTrip(): List<BatterySample> {
        val start = System.currentTimeMillis()
        val samples = mutableListOf<BatterySample>()
        var ts = start
        var dist = 0.0

        fun push(durationSeconds: Int, speedKmh: Double, powerWatts: Double) {
            val count = durationSeconds * 2  // 2 Hz
            repeat(count) {
                samples += buildSample(ts, NEAR_FULL_VOLTAGE, 99.0, dist, speedKmh, powerWatts)
                dist += speedKmh / 3600.0 * 0.5  // km in 500 ms
                ts += SAMPLE_INTERVAL_MS
            }
        }

        push(60, 20.0, 400.0)    // cruise
        push(20, 20.0, 1600.0)   // climb
        push(20, 30.0, 100.0)    // coast
        return samples
    }

    private fun buildSample(
        ts: Long, voltage: Double, batteryPct: Double, distanceKm: Double,
        speed: Double, power: Double,
    ): BatterySample = BatterySample(
        timestamp = ts,
        voltage = voltage,
        compensatedVoltage = voltage,
        batteryPercent = batteryPct,
        tripDistanceKm = distanceKm,
        speedKmh = speed,
        powerWatts = power,
        currentAmps = if (speed > 0) power / voltage else 0.0,
        temperatureCelsius = 25.0,
        flags = emptySet(),
    )
}
