package com.a42r.eucosmandplugin.testing

import android.util.Log
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.range.algorithm.LiIonDischargeCurve
import com.a42r.eucosmandplugin.range.database.WheelDatabase
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * Generates realistic EUC telemetry data for emulator testing.
 * 
 * Features:
 * - Realistic Li-Ion battery discharge physics
 * - Speed-based power consumption modeling
 * - Terrain/altitude effects on power
 * - Acceleration power modeling
 * - Voltage sag simulation under load
 * - Configurable wheel models from WheelDatabase
 * 
 * Physics Model:
 * - Base efficiency: 15-20 Wh/km at cruise speed
 * - Air resistance: Increases quadratically with speed
 * - Grade factor: 5% power change per 1% grade
 * - Acceleration: Extra power for speed changes
 * - Weight: Linear effect on power consumption
 */
class MockEucDataGenerator(
    private val config: MockEucConfig
) {
    companion object {
        private const val TAG = "MockEucDataGenerator"
        
        // Reference values for power calculations
        private const val REFERENCE_SPEED_KMH = 20.0
        private const val REFERENCE_WEIGHT_KG = 80.0
        
        // Power model parameters
        private const val SPEED_FACTOR_COEFFICIENT = 0.3
        private const val GRADE_FACTOR_PER_PERCENT = 0.05
        private const val ACCEL_FACTOR_COEFFICIENT = 0.2
        private const val VARIABILITY_RANGE = 0.05  // ±5%
        
        // Voltage sag parameters
        private const val VOLTAGE_SAG_PER_KW = 0.1  // 0.1V per 1kW
        
        // Temperature model parameters
        private const val BASE_TEMPERATURE_C = 25.0
        private const val TEMP_INCREASE_PER_KW = 5.0  // °C per kW
        private const val TEMP_COOLING_RATE = 0.1  // °C per second when idle
    }
    
    /**
     * Configuration for mock EUC data generation.
     */
    data class MockEucConfig(
        val wheelSpec: WheelDatabase.WheelSpec,
        val startBatteryPercent: Double = 95.0,
        val riderWeightKg: Double = 80.0,
        val baseEfficiencyWhPerKm: Double = 18.0,
        val useRealisticVariability: Boolean = true
    )
    
    /**
     * Internal battery state.
     */
    private data class BatteryState(
        var remainingEnergyWh: Double,
        var batteryPercent: Double,
        var voltage: Double,
        var compensatedVoltage: Double,
        var currentAmps: Double
    )
    
    /**
     * Internal trip state.
     */
    private data class TripState(
        var totalDistanceKm: Double = 0.0,
        var ridingTimeMs: Long = 0L,
        var previousSpeedKmh: Double = 0.0,
        var previousAltitude: Double = 0.0,
        var temperatureCelsius: Double = BASE_TEMPERATURE_C
    )
    
    // State variables
    private val batteryState: BatteryState
    private val tripState = TripState()
    private var lastUpdateTime: Long = System.currentTimeMillis()
    private val startTime: Long = System.currentTimeMillis()
    
    init {
        // Initialize battery state
        val totalCapacityWh = config.wheelSpec.batteryConfig.capacityWh
        val remainingWh = totalCapacityWh * (config.startBatteryPercent / 100.0)
        val initialVoltage = calculateVoltageFromBattery(config.startBatteryPercent)
        
        batteryState = BatteryState(
            remainingEnergyWh = remainingWh,
            batteryPercent = config.startBatteryPercent,
            voltage = initialVoltage,
            compensatedVoltage = initialVoltage,
            currentAmps = 0.0
        )
        
        Log.d(TAG, "Initialized mock EUC: ${config.wheelSpec.displayName}, " +
                "battery=${config.startBatteryPercent}%, " +
                "capacity=${totalCapacityWh}Wh, " +
                "voltage=${String.format("%.1f", initialVoltage)}V")
    }
    
    /**
     * Generate EUC data based on current GPS location.
     */
    fun generateEucData(locationData: MockLocationProvider.MockLocationData): EucData {
        val currentTime = System.currentTimeMillis()
        val deltaTimeMs = currentTime - lastUpdateTime
        val deltaTimeSeconds = deltaTimeMs / 1000.0
        
        // Extract speed and altitude from GPS
        val speedKmh = locationData.speedKmh
        val altitude = locationData.altitude
        
        // Calculate distance traveled in this interval
        val distanceTraveled = speedKmh * (deltaTimeSeconds / 3600.0)  // km
        
        // Calculate altitude change
        val altitudeChange = altitude - tripState.previousAltitude
        
        // Calculate power consumption
        val powerWatts = calculatePowerConsumption(
            speedKmh = speedKmh,
            previousSpeedKmh = tripState.previousSpeedKmh,
            altitudeChange = altitudeChange,
            distanceTraveled = distanceTraveled,
            deltaTimeSeconds = deltaTimeSeconds
        )
        
        // Update battery state
        updateBattery(powerWatts, deltaTimeMs)
        
        // Update trip state
        tripState.totalDistanceKm += distanceTraveled
        if (speedKmh > 1.0) {
            tripState.ridingTimeMs += deltaTimeMs
        }
        tripState.previousSpeedKmh = speedKmh
        tripState.previousAltitude = altitude
        
        // Update temperature
        updateTemperature(powerWatts, deltaTimeSeconds)
        
        // Calculate PWM/load (percentage of max power)
        val maxPower = config.wheelSpec.batteryConfig.nominalVoltage * 100.0  // Estimate max power
        val pwm = ((powerWatts / maxPower) * 100.0).coerceIn(0.0, 100.0)
        
        // Update timestamp
        lastUpdateTime = currentTime
        
        // Create EucData object
        return EucData(
            batteryPercentage = batteryState.batteryPercent.toInt(),
            voltage = batteryState.voltage,
            current = batteryState.currentAmps,
            power = powerWatts,
            speed = speedKmh,
            topSpeed = tripState.previousSpeedKmh.coerceAtLeast(speedKmh),
            averageSpeed = if (tripState.ridingTimeMs > 0) {
                (tripState.totalDistanceKm / (tripState.ridingTimeMs / 3600000.0))
            } else 0.0,
            wheelTrip = tripState.totalDistanceKm,
            totalDistance = tripState.totalDistanceKm,
            temperature = tripState.temperatureCelsius,
            cpuTemperature = tripState.temperatureCelsius + 5.0,  // CPU slightly warmer
            imuTemperature = tripState.temperatureCelsius - 2.0,  // IMU slightly cooler
            ridingTime = tripState.ridingTimeMs,
            pwm = pwm,
            load = pwm,
            wheelModel = config.wheelSpec.displayName,
            wheelBrand = config.wheelSpec.manufacturer,
            serialNumber = "MOCK-${Random.nextInt(1000, 9999)}",
            firmwareVersion = "1.0.0-mock",
            isConnected = true,
            isCharging = false,
            isFanRunning = tripState.temperatureCelsius > 50.0,
            alarm1Speed = 40.0,
            alarm2Speed = 45.0,
            alarm3Speed = 50.0,
            tiltBackSpeed = 48.0,
            gpsSpeed = speedKmh,
            latitude = locationData.latitude,
            longitude = locationData.longitude,
            altitude = altitude,
            timestamp = currentTime
        )
    }
    
    /**
     * Calculate power consumption based on riding conditions.
     */
    private fun calculatePowerConsumption(
        speedKmh: Double,
        previousSpeedKmh: Double,
        altitudeChange: Double,
        distanceTraveled: Double,
        deltaTimeSeconds: Double
    ): Double {
        // If stopped, return minimal power (idle consumption)
        if (speedKmh < 1.0) {
            return 5.0  // 5W idle consumption
        }
        
        // Base efficiency at reference speed
        val baseWhPerKm = config.baseEfficiencyWhPerKm
        
        // Speed factor (air resistance increases with speed²)
        val speedRatio = speedKmh / REFERENCE_SPEED_KMH
        val speedFactor = 1.0 + (speedRatio - 1.0) * speedRatio * SPEED_FACTOR_COEFFICIENT
        
        // Grade factor (positive = uphill, negative = downhill with regen)
        val gradePercent = if (distanceTraveled > 0.001) {
            (altitudeChange / (distanceTraveled * 1000.0)) * 100.0  // Convert to %
        } else 0.0
        
        val gradeFactor = 1.0 + (gradePercent * GRADE_FACTOR_PER_PERCENT)
        
        // Acceleration factor (speed changes require extra power)
        val speedChange = abs(speedKmh - previousSpeedKmh)
        val accelFactor = if (speedKmh > 0.1) {
            1.0 + (speedChange / speedKmh) * ACCEL_FACTOR_COEFFICIENT
        } else 1.0
        
        // Weight factor (heavier rider = more power)
        val weightFactor = config.riderWeightKg / REFERENCE_WEIGHT_KG
        
        // Calculate efficiency (Wh/km)
        val efficiencyWhPerKm = baseWhPerKm * speedFactor * gradeFactor * weightFactor
        
        // Calculate instantaneous power (W = Wh/km * km/h / 3600)
        var powerWatts = efficiencyWhPerKm * speedKmh * accelFactor
        
        // Add realistic variability (±5%)
        if (config.useRealisticVariability) {
            val variation = Random.nextDouble(-VARIABILITY_RANGE, VARIABILITY_RANGE)
            powerWatts *= (1.0 + variation)
        }
        
        // Ensure non-negative (regenerative braking can give negative values)
        // For simplicity, we don't model regen in this version
        return powerWatts.coerceAtLeast(0.0)
    }
    
    /**
     * Update battery state based on power consumption.
     */
    private fun updateBattery(powerWatts: Double, deltaTimeMs: Long) {
        // Energy consumed in this timestep
        val deltaTimeHours = deltaTimeMs / 3600000.0
        val energyConsumedWh = powerWatts * deltaTimeHours
        
        // Update remaining energy
        batteryState.remainingEnergyWh = (batteryState.remainingEnergyWh - energyConsumedWh)
            .coerceAtLeast(0.0)
        
        // Calculate new battery percentage
        val totalCapacity = config.wheelSpec.batteryConfig.capacityWh
        batteryState.batteryPercent = (batteryState.remainingEnergyWh / totalCapacity * 100.0)
            .coerceIn(0.0, 100.0)
        
        // Calculate compensated voltage (without sag) using Li-Ion discharge curve
        batteryState.compensatedVoltage = calculateVoltageFromBattery(batteryState.batteryPercent)
        
        // Calculate voltage sag under load (realistic behavior)
        val voltageSag = (powerWatts / 1000.0) * VOLTAGE_SAG_PER_KW  // ~0.1V per 1kW
        batteryState.voltage = (batteryState.compensatedVoltage - voltageSag)
            .coerceAtLeast(config.wheelSpec.batteryConfig.cellCount * 3.0)  // Min 3.0V per cell
        
        // Calculate current from power and voltage
        batteryState.currentAmps = if (batteryState.voltage > 0) {
            powerWatts / batteryState.voltage
        } else 0.0
    }
    
    /**
     * Update temperature based on power consumption.
     */
    private fun updateTemperature(powerWatts: Double, deltaTimeSeconds: Double) {
        // Temperature increases with power consumption
        val powerKw = powerWatts / 1000.0
        val tempIncrease = powerKw * TEMP_INCREASE_PER_KW * deltaTimeSeconds
        
        // Temperature cools down when idle
        val tempDecrease = if (powerWatts < 100.0) {
            TEMP_COOLING_RATE * deltaTimeSeconds
        } else 0.0
        
        // Update temperature
        tripState.temperatureCelsius = (tripState.temperatureCelsius + tempIncrease - tempDecrease)
            .coerceIn(BASE_TEMPERATURE_C, 80.0)  // Max 80°C
    }
    
    /**
     * Calculate voltage from battery percentage using Li-Ion discharge curve.
     */
    private fun calculateVoltageFromBattery(batteryPercent: Double): Double {
        val cellCount = config.wheelSpec.batteryConfig.cellCount
        
        // Use inverse of voltage-to-energy conversion
        // This is an approximation - for exact conversion we'd need the inverse function
        // For now, use linear interpolation of common points
        val voltagePerCell = when {
            batteryPercent >= 100.0 -> 4.2
            batteryPercent >= 90.0 -> 4.1 + (batteryPercent - 90.0) / 10.0 * 0.1
            batteryPercent >= 80.0 -> 4.0 + (batteryPercent - 80.0) / 10.0 * 0.1
            batteryPercent >= 20.0 -> 3.5 + (batteryPercent - 20.0) / 60.0 * 0.5
            batteryPercent >= 10.0 -> 3.3 + (batteryPercent - 10.0) / 10.0 * 0.2
            else -> 3.0 + batteryPercent / 10.0 * 0.3
        }
        
        return voltagePerCell * cellCount
    }
    
    /**
     * Get current battery statistics for debugging.
     */
    fun getBatteryStats(): String {
        return "Battery: ${String.format("%.1f", batteryState.batteryPercent)}%, " +
                "${String.format("%.1f", batteryState.voltage)}V, " +
                "${String.format("%.1f", batteryState.remainingEnergyWh)}Wh remaining"
    }
    
    /**
     * Get current trip statistics for debugging.
     */
    fun getTripStats(): String {
        val ridingTimeMinutes = tripState.ridingTimeMs / 60000.0
        val avgSpeed = if (ridingTimeMinutes > 0) {
            tripState.totalDistanceKm / (ridingTimeMinutes / 60.0)
        } else 0.0
        
        return "Trip: ${String.format("%.2f", tripState.totalDistanceKm)}km, " +
                "${String.format("%.1f", ridingTimeMinutes)}min, " +
                "avg ${String.format("%.1f", avgSpeed)}km/h"
    }
    
    /**
     * Reset trip data (battery state is preserved).
     */
    fun resetTrip() {
        tripState.totalDistanceKm = 0.0
        tripState.ridingTimeMs = 0L
        tripState.previousSpeedKmh = 0.0
        tripState.previousAltitude = 0.0
        lastUpdateTime = System.currentTimeMillis()
        
        Log.d(TAG, "Trip reset")
    }
    
    /**
     * Reset battery to starting state.
     */
    fun resetBattery() {
        val totalCapacityWh = config.wheelSpec.batteryConfig.capacityWh
        val remainingWh = totalCapacityWh * (config.startBatteryPercent / 100.0)
        val initialVoltage = calculateVoltageFromBattery(config.startBatteryPercent)
        
        batteryState.remainingEnergyWh = remainingWh
        batteryState.batteryPercent = config.startBatteryPercent
        batteryState.voltage = initialVoltage
        batteryState.compensatedVoltage = initialVoltage
        batteryState.currentAmps = 0.0
        
        tripState.temperatureCelsius = BASE_TEMPERATURE_C
        
        Log.d(TAG, "Battery reset to ${config.startBatteryPercent}%")
    }
}
