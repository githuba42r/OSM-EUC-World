package com.a42r.eucosmandplugin.api

import com.google.gson.annotations.SerializedName

/**
 * Data class representing the complete EUC (Electric Unicycle) status
 * received from the EUC World Internal Webservice API.
 * 
 * The EUC World app exposes a local HTTP webservice that provides
 * real-time wheel data. Default endpoint: http://localhost:8080/data
 */
data class EucData(
    // Battery Information
    @SerializedName("battery")
    val batteryPercentage: Int = 0,
    
    @SerializedName("voltage")
    val voltage: Double = 0.0,
    
    @SerializedName("current")
    val current: Double = 0.0,
    
    @SerializedName("power")
    val power: Double = 0.0,
    
    // Speed and Distance
    @SerializedName("speed")
    val speed: Double = 0.0,
    
    @SerializedName("topSpeed")
    val topSpeed: Double = 0.0,
    
    @SerializedName("averageSpeed")
    val averageSpeed: Double = 0.0,
    
    @SerializedName("wheelTrip")
    val wheelTrip: Double = 0.0,
    
    @SerializedName("totalDistance")
    val totalDistance: Double = 0.0,
    
    // Temperature
    @SerializedName("temperature")
    val temperature: Double = 0.0,
    
    @SerializedName("cpuTemperature")
    val cpuTemperature: Double = 0.0,
    
    @SerializedName("imuTemperature")
    val imuTemperature: Double = 0.0,
    
    // Riding Metrics
    @SerializedName("ridingTime")
    val ridingTime: Long = 0,
    
    @SerializedName("pwm")
    val pwm: Double = 0.0,
    
    @SerializedName("load")
    val load: Double = 0.0,
    
    // Wheel Information
    @SerializedName("wheelModel")
    val wheelModel: String = "",
    
    @SerializedName("wheelBrand")
    val wheelBrand: String = "",
    
    @SerializedName("serialNumber")
    val serialNumber: String = "",
    
    @SerializedName("firmwareVersion")
    val firmwareVersion: String = "",
    
    // Status Flags
    @SerializedName("connected")
    val isConnected: Boolean = false,
    
    @SerializedName("charging")
    val isCharging: Boolean = false,
    
    @SerializedName("fanRunning")
    val isFanRunning: Boolean = false,
    
    // Alarms
    @SerializedName("alarm1")
    val alarm1Speed: Double = 0.0,
    
    @SerializedName("alarm2")
    val alarm2Speed: Double = 0.0,
    
    @SerializedName("alarm3")
    val alarm3Speed: Double = 0.0,
    
    @SerializedName("tiltBackSpeed")
    val tiltBackSpeed: Double = 0.0,
    
    // GPS Data (if available)
    @SerializedName("gpsSpeed")
    val gpsSpeed: Double = 0.0,
    
    @SerializedName("latitude")
    val latitude: Double = 0.0,
    
    @SerializedName("longitude")
    val longitude: Double = 0.0,
    
    @SerializedName("altitude")
    val altitude: Double = 0.0,
    
    // Timestamp
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Returns a formatted battery display string
     * Format: "85% 84.2V" similar to Motoeye E6 HUD display
     */
    fun getBatteryDisplayString(): String {
        return "${batteryPercentage}% ${String.format("%.1f", voltage)}V"
    }
    
    /**
     * Returns a compact battery display for widgets
     */
    fun getBatteryCompactString(): String {
        return "$batteryPercentage%"
    }
    
    /**
     * Returns voltage formatted string
     */
    fun getVoltageString(): String {
        return String.format("%.1fV", voltage)
    }
    
    /**
     * Returns speed formatted string with unit
     */
    fun getSpeedString(useKmh: Boolean = true): String {
        val unit = if (useKmh) "km/h" else "mph"
        val displaySpeed = if (useKmh) speed else speed * 0.621371
        return String.format("%.1f %s", displaySpeed, unit)
    }
    
    /**
     * Returns temperature formatted string
     */
    fun getTemperatureString(useCelsius: Boolean = true): String {
        val temp = if (useCelsius) temperature else (temperature * 9/5) + 32
        val unit = if (useCelsius) "°C" else "°F"
        return String.format("%.0f%s", temp, unit)
    }
    
    /**
     * Returns power consumption string
     */
    fun getPowerString(): String {
        return String.format("%.0fW", power)
    }
    
    /**
     * Returns PWM/Load percentage
     */
    fun getLoadString(): String {
        return String.format("%.0f%%", pwm)
    }
    
    /**
     * Check if battery is low (below threshold)
     */
    fun isBatteryLow(threshold: Int = 20): Boolean {
        return batteryPercentage <= threshold
    }
    
    /**
     * Check if battery is critical (below threshold)
     */
    fun isBatteryCritical(threshold: Int = 10): Boolean {
        return batteryPercentage <= threshold
    }
    
    /**
     * Get estimated range based on consumption
     */
    fun getEstimatedRange(whPerKm: Double = 20.0): Double {
        if (whPerKm <= 0) return 0.0
        // Rough estimation based on battery percentage and typical wheel capacity
        val estimatedWh = batteryPercentage / 100.0 * 2000 // Assume 2000Wh wheel
        return estimatedWh / whPerKm
    }
}

/**
 * Simplified data class for widget display
 */
data class EucWidgetData(
    val batteryPercentage: Int,
    val voltage: Double,
    val speed: Double,
    val temperature: Double,
    val isConnected: Boolean,
    val timestamp: Long
) {
    companion object {
        fun fromEucData(data: EucData): EucWidgetData {
            return EucWidgetData(
                batteryPercentage = data.batteryPercentage,
                voltage = data.voltage,
                speed = data.speed,
                temperature = data.temperature,
                isConnected = data.isConnected,
                timestamp = data.timestamp
            )
        }
        
        fun disconnected(): EucWidgetData {
            return EucWidgetData(
                batteryPercentage = 0,
                voltage = 0.0,
                speed = 0.0,
                temperature = 0.0,
                isConnected = false,
                timestamp = System.currentTimeMillis()
            )
        }
    }
}
