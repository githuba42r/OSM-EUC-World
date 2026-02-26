package com.a42r.eucosmandplugin.testing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Provides GPS location data from Android emulator for mock EUC testing.
 * 
 * In the emulator, location can be simulated by:
 * 1. Loading a GPX file via Extended Controls > Location
 * 2. Manually setting location coordinates
 * 3. Using location playback at various speeds
 * 
 * This provider extracts speed, altitude, and coordinates for realistic
 * EUC data simulation.
 */
class MockLocationProvider(
    private val context: Context,
    private val callback: LocationCallback
) {
    companion object {
        private const val TAG = "MockLocationProvider"
        private const val LOCATION_UPDATE_INTERVAL_MS = 500L
        private const val MIN_DISTANCE_METERS = 0f
    }
    
    /**
     * Callback interface for location updates.
     */
    interface LocationCallback {
        fun onLocationUpdate(data: MockLocationData)
        fun onLocationError(error: String)
    }
    
    /**
     * Location data extracted from GPS.
     */
    data class MockLocationData(
        val speedKmh: Double,
        val altitude: Double,
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val hasSpeed: Boolean,
        val hasAltitude: Boolean
    )
    
    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    
    private var lastLocation: Location? = null
    private var isStarted = false
    
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocationUpdate(location)
        }
        
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            Log.d(TAG, "Location provider status changed: $provider = $status")
        }
        
        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "Location provider enabled: $provider")
        }
        
        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "Location provider disabled: $provider")
            callback.onLocationError("GPS provider disabled")
        }
    }
    
    /**
     * Start receiving location updates.
     * Requires location permissions to be granted.
     */
    fun start() {
        if (isStarted) {
            Log.w(TAG, "Location provider already started")
            return
        }
        
        // Check permissions
        if (!hasLocationPermissions()) {
            val error = "Location permissions not granted"
            Log.e(TAG, error)
            callback.onLocationError(error)
            return
        }
        
        // Check if GPS provider is available
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "GPS provider not enabled, trying network provider")
            
            if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val error = "No location providers available"
                Log.e(TAG, error)
                callback.onLocationError(error)
                return
            }
        }
        
        try {
            // Request location updates from GPS provider (primary)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MS,
                    MIN_DISTANCE_METERS,
                    locationListener
                )
                Log.d(TAG, "Started GPS location updates")
            }
            
            // Also request from network provider (fallback)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_UPDATE_INTERVAL_MS,
                    MIN_DISTANCE_METERS,
                    locationListener
                )
                Log.d(TAG, "Started network location updates")
            }
            
            isStarted = true
            
            // Try to get last known location immediately
            getLastKnownLocation()?.let { location ->
                handleLocationUpdate(location)
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception requesting location updates", e)
            callback.onLocationError("Location permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
            callback.onLocationError("Failed to start location: ${e.message}")
        }
    }
    
    /**
     * Stop receiving location updates.
     */
    fun stop() {
        if (!isStarted) return
        
        try {
            locationManager.removeUpdates(locationListener)
            Log.d(TAG, "Stopped location updates")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates", e)
        }
        
        isStarted = false
        lastLocation = null
    }
    
    /**
     * Get the last known location for immediate feedback.
     */
    private fun getLastKnownLocation(): Location? {
        if (!hasLocationPermissions()) return null
        
        return try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting last location", e)
            null
        }
    }
    
    /**
     * Handle location update from GPS.
     */
    private fun handleLocationUpdate(location: Location) {
        val currentTime = System.currentTimeMillis()
        
        // Calculate speed from GPS or position change
        val speedKmh = if (location.hasSpeed()) {
            // GPS provides speed directly (in m/s)
            location.speed * 3.6  // Convert m/s to km/h
        } else {
            // Calculate from position change
            calculateSpeedFromMovement(location, currentTime)
        }
        
        // Extract altitude
        val altitude = if (location.hasAltitude()) {
            location.altitude
        } else {
            0.0  // Default to sea level if not available
        }
        
        // Create location data
        val locationData = MockLocationData(
            speedKmh = speedKmh.coerceAtLeast(0.0),  // Ensure non-negative
            altitude = altitude,
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = currentTime,
            hasSpeed = location.hasSpeed(),
            hasAltitude = location.hasAltitude()
        )
        
        // Update last location
        lastLocation = location
        
        // Notify callback
        callback.onLocationUpdate(locationData)
        
        Log.d(TAG, "Location update: speed=${String.format("%.1f", speedKmh)} km/h, " +
                "alt=${String.format("%.1f", altitude)}m, " +
                "lat=${String.format("%.6f", location.latitude)}, " +
                "lon=${String.format("%.6f", location.longitude)}")
    }
    
    /**
     * Calculate speed from position change when GPS doesn't provide speed directly.
     */
    private fun calculateSpeedFromMovement(currentLocation: Location, currentTime: Long): Double {
        val lastLoc = lastLocation ?: return 0.0
        
        // Calculate distance between points (in meters)
        val distance = lastLoc.distanceTo(currentLocation)
        
        // Calculate time delta (in seconds)
        val timeDelta = (currentTime - (lastLoc.time ?: currentTime)) / 1000.0
        
        if (timeDelta <= 0.0) return 0.0
        
        // Calculate speed (m/s) and convert to km/h
        val speedMs = distance / timeDelta
        return speedMs * 3.6
    }
    
    /**
     * Check if location permissions are granted.
     */
    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Get current status for debugging.
     */
    fun getStatus(): String {
        return when {
            !hasLocationPermissions() -> "No location permissions"
            !isStarted -> "Not started"
            lastLocation == null -> "Waiting for GPS signal"
            else -> "Active (last update: ${lastLocation?.time}ms ago)"
        }
    }
}
