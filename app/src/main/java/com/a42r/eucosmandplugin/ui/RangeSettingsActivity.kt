package com.a42r.eucosmandplugin.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.ai.data.TokenManager
import com.a42r.eucosmandplugin.ai.ui.OAuthActivity
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.databinding.ActivitySettingsBinding
import com.a42r.eucosmandplugin.range.database.WheelDatabase
import com.a42r.eucosmandplugin.service.AutoProxyReceiver

/**
 * Settings activity for range estimation configuration.
 */
class RangeSettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_category_range_estimation)
        }
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, RangeSettingsFragment())
                .commit()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    class RangeSettingsFragment : PreferenceFragmentCompat(), WheelModelSelectorDialog.WheelSelectionListener {
        
        private lateinit var tokenManager: TokenManager
        
        private val eucDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.extras?.let { bundle ->
                    val data = AutoProxyReceiver.parseDataBundle(bundle)
                    handleWheelDetection(data)
                }
            }
        }
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Initialize TokenManager
            tokenManager = TokenManager(requireContext())
            
            // Migrate Int preferences to String for EditTextPreference compatibility
            migrateIntPreferencesToString()
            
            setPreferencesFromResource(R.xml.preferences_range, rootKey)
            
            // Wheel configuration mode
            findPreference<ListPreference>("wheel_config_mode")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    updateWheelConfigUI(newValue as String)
                    true
                }
                updateWheelConfigUI(value ?: "auto")
            }
            
            // Wheel model selector
            findPreference<Preference>("wheel_model_selector")?.apply {
                setOnPreferenceClickListener {
                    showWheelModelSelector()
                    true
                }
            }
            
            // Reset range trip preference
            findPreference<Preference>("reset_range_trip")?.apply {
                setOnPreferenceClickListener {
                    showResetRangeTripDialog()
                    true
                }
            }
            
            // Battery optimization warning preference
            findPreference<Preference>("battery_optimization_warning")?.apply {
                setOnPreferenceClickListener {
                    openBatteryOptimizationSettings()
                    true
                }
                // Update summary based on current battery optimization status
                updateBatteryOptimizationStatus(this)
            }
            
            // Update detected wheel info
            updateDetectedWheelInfo()
            
            // AI Settings
            setupAIPreferences()
        }
        
        override fun onResume() {
            super.onResume()
            // Register for EUC data broadcasts
            LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                eucDataReceiver,
                IntentFilter(AutoProxyReceiver.ACTION_EUC_DATA_UPDATE)
            )
            
            // Refresh battery optimization status when returning to this screen
            findPreference<Preference>("battery_optimization_warning")?.let { pref ->
                updateBatteryOptimizationStatus(pref)
            }
            
            // Update AI status
            updateAIStatus()
        }
        
        override fun onPause() {
            super.onPause()
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(eucDataReceiver)
        }
        
        private fun handleWheelDetection(data: EucData) {
            val configMode = preferenceManager.sharedPreferences?.getString("wheel_config_mode", "auto") ?: "auto"
            
            if (configMode == "auto" && data.wheelModel.isNotBlank()) {
                val wheelSpec = WheelDatabase.findWheelSpec(data.wheelModel)
                
                if (wheelSpec != null) {
                    // Auto-detected wheel found in database
                    saveWheelConfig(wheelSpec)
                    updateDetectedWheelInfo(wheelSpec)
                } else {
                    // Wheel connected but not in database
                    updateDetectedWheelInfo(null, data.wheelModel)
                }
            }
        }
        
        private fun updateWheelConfigUI(mode: String) {
            val detectedWheelPref = findPreference<Preference>("detected_wheel_info")
            val modelSelectorPref = findPreference<Preference>("wheel_model_selector")
            val capacityPref = findPreference<EditTextPreference>("battery_capacity_wh")
            val cellCountPref = findPreference<ListPreference>("battery_cell_count")
            
            when (mode) {
                "auto" -> {
                    detectedWheelPref?.isVisible = true
                    modelSelectorPref?.isVisible = false
                    capacityPref?.isVisible = false
                    cellCountPref?.isVisible = false
                }
                "database" -> {
                    detectedWheelPref?.isVisible = false
                    modelSelectorPref?.isVisible = true
                    capacityPref?.isVisible = false
                    cellCountPref?.isVisible = false
                }
                "manual" -> {
                    detectedWheelPref?.isVisible = false
                    modelSelectorPref?.isVisible = false
                    capacityPref?.isVisible = true
                    cellCountPref?.isVisible = true
                }
            }
        }
        
        private fun updateDetectedWheelInfo(wheelSpec: WheelDatabase.WheelSpec? = null, unknownModel: String? = null) {
            findPreference<Preference>("detected_wheel_info")?.apply {
                // If no wheelSpec provided, try to load from SharedPreferences
                val effectiveWheelSpec = wheelSpec ?: run {
                    val prefs = preferenceManager.sharedPreferences
                    val savedModel = prefs?.getString("selected_wheel_model", null)
                    savedModel?.let { WheelDatabase.findWheelSpec(it) }
                }
                
                if (effectiveWheelSpec != null) {
                    title = effectiveWheelSpec.displayName
                    summary = getString(
                        R.string.settings_detected_wheel_summary,
                        effectiveWheelSpec.manufacturer,
                        effectiveWheelSpec.batteryConfig.capacityWh.toInt(),
                        effectiveWheelSpec.batteryConfig.getConfigString()
                    )
                } else if (unknownModel != null) {
                    title = unknownModel
                    summary = "Unknown wheel model - please select from database or use manual configuration"
                } else {
                    title = getString(R.string.settings_detected_wheel)
                    summary = getString(R.string.settings_detected_wheel_none)
                }
            }
        }
        
        private fun showWheelModelSelector() {
            val dialog = WheelModelSelectorDialog.newInstance()
            dialog.setWheelSelectionListener(this)
            dialog.show(childFragmentManager, WheelModelSelectorDialog.TAG)
        }
        
        override fun onWheelSelected(wheelSpec: WheelDatabase.WheelSpec) {
            saveWheelConfig(wheelSpec)
            
            // Update selector summary
            findPreference<Preference>("wheel_model_selector")?.summary = 
                "${wheelSpec.displayName} - ${wheelSpec.batteryConfig.capacityWh.toInt()} Wh (${wheelSpec.batteryConfig.getConfigString()})"
            
            Toast.makeText(
                requireContext(),
                "Selected: ${wheelSpec.displayName}",
                Toast.LENGTH_SHORT
            ).show()
        }
        
        private fun saveWheelConfig(wheelSpec: WheelDatabase.WheelSpec) {
            preferenceManager.sharedPreferences?.edit()?.apply {
                putString("selected_wheel_model", wheelSpec.displayName)
                putInt("battery_capacity_wh", wheelSpec.batteryConfig.capacityWh.toInt())
                putInt("battery_cell_count", wheelSpec.batteryConfig.cellCount)
                apply()
            }
        }
        
        private fun migrateIntPreferencesToString() {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            val editor = prefs.edit()
            
            // Migrate battery_capacity_wh if it's stored as Int
            try {
                val capacityInt = prefs.getInt("battery_capacity_wh", -1)
                if (capacityInt != -1) {
                    editor.remove("battery_capacity_wh")
                    editor.putString("battery_capacity_wh", capacityInt.toString())
                }
            } catch (e: ClassCastException) {
                // Already a String, no migration needed
            }
            
            // Migrate battery_cell_count if it's stored as Int
            try {
                val cellCountInt = prefs.getInt("battery_cell_count", -1)
                if (cellCountInt != -1) {
                    editor.remove("battery_cell_count")
                    editor.putString("battery_cell_count", cellCountInt.toString())
                }
            } catch (e: ClassCastException) {
                // Already a String, no migration needed
            }
            
            editor.apply()
        }
        
        private fun showResetRangeTripDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.reset_range_trip_title)
                .setMessage(R.string.reset_range_trip_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    resetRangeTripData()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        private fun resetRangeTripData() {
            // Clear range estimation data from SharedPreferences
            val prefs = requireContext().getSharedPreferences("range_estimation_data", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            
            // Show confirmation
            Toast.makeText(
                requireContext(),
                R.string.reset_range_trip_success,
                Toast.LENGTH_SHORT
            ).show()
        }
        
        /**
         * Update battery optimization status in the warning preference
         */
        private fun updateBatteryOptimizationStatus(preference: Preference) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
                val packageName = requireContext().packageName
                
                val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
                
                if (isIgnoring) {
                    preference.summary = "✓ Battery optimization is disabled. Range estimation will work correctly in the background."
                    preference.title = "✓ Background Running Enabled"
                } else {
                    preference.summary = "⚠️ Battery optimization is enabled. Range estimation may stop when app is backgrounded. Tap to open settings."
                    preference.title = "⚠️ Background Running Required"
                }
            } else {
                // Android < M doesn't have battery optimization
                preference.summary = "Battery optimization not applicable on this Android version."
            }
        }
        
        /**
         * Open battery optimization settings for this app
         */
        private fun openBatteryOptimizationSettings() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general battery optimization settings
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (e2: Exception) {
                        // Show toast if settings cannot be opened
                        Toast.makeText(
                            requireContext(),
                            "Unable to open battery optimization settings. Please go to:\nSettings → Apps → OSM EUC World → Battery → Unrestricted",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                Toast.makeText(
                    requireContext(),
                    "Battery optimization not available on this Android version.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        /**
         * Setup AI preferences
         */
        private fun setupAIPreferences() {
            // AI Enabled switch
            findPreference<SwitchPreferenceCompat>("ai_enabled")?.apply {
                // Sync with TokenManager
                isChecked = tokenManager.isAiEnabled()
                
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    
                    if (enabled && !tokenManager.isAiConfigured()) {
                        // Show setup dialog
                        showAISetupDialog()
                        false // Don't change the switch yet
                    } else {
                        // Save to TokenManager
                        tokenManager.setAiEnabled(enabled)
                        updateAIStatus()
                        true
                    }
                }
            }
            
            // AI Status preference
            findPreference<Preference>("ai_status")?.apply {
                setOnPreferenceClickListener {
                    if (!tokenManager.isAiConfigured()) {
                        showAISetupDialog()
                    } else {
                        showAIStatusDialog()
                    }
                    true
                }
            }
            
            // AI Configure preference
            findPreference<Preference>("ai_configure")?.apply {
                setOnPreferenceClickListener {
                    openAIConfiguration()
                    true
                }
            }
            
            // Initial status update
            updateAIStatus()
        }
        
        /**
         * Update AI status display
         */
        private fun updateAIStatus() {
            val aiStatusPref = findPreference<Preference>("ai_status")
            val isConfigured = tokenManager.isAiConfigured()
            val isEnabled = tokenManager.isAiEnabled()
            
            aiStatusPref?.apply {
                when {
                    !isConfigured -> {
                        title = getString(R.string.settings_ai_status)
                        summary = getString(R.string.settings_ai_status_not_configured)
                    }
                    isEnabled -> {
                        title = "✓ " + getString(R.string.settings_ai_status)
                        summary = getString(R.string.settings_ai_status_configured)
                    }
                    else -> {
                        title = getString(R.string.settings_ai_status)
                        summary = getString(R.string.settings_ai_status_configured_disabled)
                    }
                }
            }
            
            // Update configure preference summary
            findPreference<Preference>("ai_configure")?.apply {
                val model = tokenManager.getSelectedAiModel()
                summary = if (model != null) {
                    "Model: $model"
                } else {
                    getString(R.string.settings_ai_configure_summary)
                }
            }
        }
        
        /**
         * Show dialog explaining AI setup
         */
        private fun showAISetupDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.ai_setup_required_title)
                .setMessage(R.string.ai_setup_required_message)
                .setPositiveButton(R.string.ai_setup_button) { _, _ ->
                    openAIConfiguration()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        
        /**
         * Show AI status information dialog
         */
        private fun showAIStatusDialog() {
            val model = tokenManager.getSelectedAiModel() ?: "None"
            val hasKey = tokenManager.hasOpenRouterApiKey()
            val isEnabled = tokenManager.isAiEnabled()
            
            val message = buildString {
                appendLine("AI Configuration Status:")
                appendLine()
                appendLine("Enabled: ${if (isEnabled) "Yes" else "No"}")
                appendLine("API Key: ${if (hasKey) "Configured" else "Not configured"}")
                appendLine("Model: $model")
            }
            
            AlertDialog.Builder(requireContext())
                .setTitle("AI Status")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Reconfigure") { _, _ ->
                    openAIConfiguration()
                }
                .show()
        }
        
        /**
         * Open AI configuration activity (OAuth flow)
         */
        private fun openAIConfiguration() {
            val intent = Intent(requireContext(), OAuthActivity::class.java)
            startActivity(intent)
        }
    }
}
