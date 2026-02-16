package com.a42r.eucosmandplugin.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.databinding.ActivityMainBinding
import com.a42r.eucosmandplugin.service.ConnectionState
import com.a42r.eucosmandplugin.service.EucWorldService
import com.a42r.eucosmandplugin.service.OsmAndConnectionService
import com.a42r.eucosmandplugin.util.TripMeterManager

/**
 * Main activity for the EUC World OsmAnd Plugin.
 * 
 * Displays current EUC status in a HUD-style format and provides
 * configuration options for the plugin.
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var tripMeterManager: TripMeterManager
    
    private var eucService: EucWorldService? = null
    private var serviceBound = false
    private var currentOdometer: Double = 0.0
    private var selectedTripMeter: TripMeterManager.TripMeter = TripMeterManager.TripMeter.A
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EucWorldService.LocalBinder
            eucService = binder.getService()
            serviceBound = true
            observeServiceData()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            eucService = null
            serviceBound = false
        }
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startEucService()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        tripMeterManager = TripMeterManager(this)
        
        setupUI()
        setupTripMeterControls()
        checkPermissionsAndStart()
    }
    
    override fun onStart() {
        super.onStart()
        bindToService()
    }
    
    override fun onStop() {
        super.onStop()
        unbindFromService()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_launch_osmand -> {
                launchOsmAnd()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupUI() {
        // Initial state
        updateUIDisconnected()
    }
    
    private fun setupTripMeterControls() {
        // Setup spinner for trip selection
        val tripOptions = arrayOf("Trip A", "Trip B", "Trip C")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tripOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTripSelect.adapter = adapter
        
        binding.spinnerTripSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedTripMeter = when (position) {
                    0 -> TripMeterManager.TripMeter.A
                    1 -> TripMeterManager.TripMeter.B
                    2 -> TripMeterManager.TripMeter.C
                    else -> TripMeterManager.TripMeter.A
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedTripMeter = TripMeterManager.TripMeter.A
            }
        }
        
        // Reset selected trip button
        binding.btnResetTrip.setOnClickListener {
            if (currentOdometer > 0) {
                tripMeterManager.resetTrip(selectedTripMeter, currentOdometer)
                updateTripMeters()
                eucService?.triggerImmediateBroadcast()
            }
        }
        
        // Clear all trips button
        binding.btnClearAllTrips.setOnClickListener {
            tripMeterManager.clearAllTrips()
            updateTripMeters()
            eucService?.triggerImmediateBroadcast()
        }
        
        // Long click on Trip A to reset it
        binding.tvTripA.setOnLongClickListener {
            if (currentOdometer > 0) {
                tripMeterManager.resetTrip(TripMeterManager.TripMeter.A, currentOdometer)
                updateTripMeters()
                eucService?.triggerImmediateBroadcast()
            }
            true
        }
        
        // Long click on Trip B to reset it
        binding.tvTripB.setOnLongClickListener {
            if (currentOdometer > 0) {
                tripMeterManager.resetTrip(TripMeterManager.TripMeter.B, currentOdometer)
                updateTripMeters()
                eucService?.triggerImmediateBroadcast()
            }
            true
        }
        
        // Long click on Trip C to reset it
        binding.tvTripC.setOnLongClickListener {
            if (currentOdometer > 0) {
                tripMeterManager.resetTrip(TripMeterManager.TripMeter.C, currentOdometer)
                updateTripMeters()
                eucService?.triggerImmediateBroadcast()
            }
            true
        }
        
        // Initial trip meter display
        updateTripMeters()
    }
    
    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startEucService()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show explanation and then request
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startEucService()
        }
    }
    
    private fun startEucService() {
        EucWorldService.start(this)
    }
    
    private fun bindToService() {
        Intent(this, EucWorldService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    private fun unbindFromService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    private fun observeServiceData() {
        lifecycleScope.launch {
            eucService?.eucDataState?.collectLatest { data ->
                if (data != null) {
                    updateUI(data)
                } else {
                    updateUIDisconnected()
                }
            }
        }
        
        lifecycleScope.launch {
            eucService?.connectionState?.collectLatest { state ->
                updateConnectionStatus(state)
            }
        }
    }
    
    private fun updateUI(data: EucData) {
        // Main battery display - HUD style
        binding.tvBatteryPercent.text = "${data.batteryPercentage}%"
        binding.tvVoltage.text = String.format("%.1fV", data.voltage)
        
        // Color based on battery level
        val color = when {
            data.batteryPercentage <= 10 -> getColor(R.color.battery_critical)
            data.batteryPercentage <= 20 -> getColor(R.color.battery_low)
            data.batteryPercentage <= 40 -> getColor(R.color.battery_medium)
            else -> getColor(R.color.battery_good)
        }
        binding.tvBatteryPercent.setTextColor(color)
        
        // Additional metrics
        binding.tvSpeed.text = String.format("%.0f", data.speed)
        binding.tvTemperature.text = String.format("%.0f", data.temperature)
        binding.tvPower.text = String.format("%.0f", data.power)
        binding.tvLoad.text = String.format("%.0f", data.pwm)
        
        // Trip info
        binding.tvTripDistance.text = String.format("%.1f", data.wheelTrip)
        binding.tvTotalDistance.text = String.format("%.0f", data.totalDistance)
        
        // Store current odometer for trip meter calculations
        currentOdometer = data.totalDistance
        
        // Update app trip meters
        updateTripMeters()
        
        // Wheel info - Line 1 of main HUD display
        if (data.wheelModel.isNotEmpty()) {
            binding.tvWheelName.text = data.wheelModel
        } else {
            binding.tvWheelName.text = getString(R.string.wheel_unknown)
        }
    }
    
    private fun updateTripMeters() {
        val (tripA, tripB, tripC) = tripMeterManager.getAllTripDistances(currentOdometer)
        
        binding.tvTripA.text = formatTripDistance(tripA)
        binding.tvTripB.text = formatTripDistance(tripB)
        binding.tvTripC.text = formatTripDistance(tripC)
    }
    
    private fun formatTripDistance(distance: Double?): String {
        return if (distance != null) {
            String.format("%.1f", distance)
        } else {
            "--"
        }
    }
    
    private fun updateUIDisconnected() {
        binding.tvBatteryPercent.text = "--"
        binding.tvVoltage.text = "---"
        binding.tvBatteryPercent.setTextColor(getColor(R.color.text_disabled))
        
        binding.tvSpeed.text = "--"
        binding.tvTemperature.text = "--"
        binding.tvPower.text = "--"
        binding.tvLoad.text = "--"
        
        binding.tvTripDistance.text = "--"
        binding.tvTotalDistance.text = "--"
        
        binding.tvWheelName.text = getString(R.string.not_connected)
        
        // Keep trip meters showing their values (they persist)
        updateTripMeters()
    }
    
    private fun updateConnectionStatus(state: ConnectionState) {
        val statusText = when (state) {
            ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
            ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            ConnectionState.CONNECTED -> getString(R.string.status_connected)
            ConnectionState.WHEEL_DISCONNECTED -> getString(R.string.status_wheel_disconnected)
            ConnectionState.ERROR -> getString(R.string.status_error)
        }
        binding.tvConnectionStatus.text = statusText
        
        val statusColor = when (state) {
            ConnectionState.CONNECTED -> getColor(R.color.status_connected)
            ConnectionState.CONNECTING -> getColor(R.color.status_connecting)
            else -> getColor(R.color.status_disconnected)
        }
        binding.tvConnectionStatus.setTextColor(statusColor)
    }
    
    private fun launchOsmAnd() {
        if (OsmAndConnectionService.isOsmAndInstalled(this)) {
            val osmandPackage = OsmAndConnectionService.getOsmAndPackage(this)
            val intent = packageManager.getLaunchIntentForPackage(osmandPackage!!)
            if (intent != null) {
                startActivity(intent)
            }
        } else {
            // Show message that OsmAnd is not installed
            binding.tvConnectionStatus.text = getString(R.string.osmand_not_installed)
        }
    }
}
