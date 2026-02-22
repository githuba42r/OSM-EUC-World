package com.a42r.eucosmandplugin.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.a42r.eucosmandplugin.BuildConfig
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.api.EucWorldApiClient
import com.a42r.eucosmandplugin.databinding.ActivitySettingsBinding
import com.a42r.eucosmandplugin.range.database.WheelDatabase
import com.a42r.eucosmandplugin.service.AutoProxyReceiver

/**
 * Settings activity for configuring the EUC World plugin.
 */
class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Use default ActionBar from theme
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings)
        }
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    class SettingsFragment : PreferenceFragmentCompat(), WheelModelSelectorDialog.WheelSelectionListener {
        
        private val eucDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.extras?.let { bundle ->
                    val data = AutoProxyReceiver.parseDataBundle(bundle)
                    handleWheelDetection(data)
                }
            }
        }
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Migrate Int preferences to String for EditTextPreference compatibility
            // This is needed because auto-detection saves as Int, but EditTextPreference requires String
            migrateIntPreferencesToString()
            
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            // API Host preference
            findPreference<EditTextPreference>("api_host")?.apply {
                setDefaultValue(EucWorldApiClient.DEFAULT_BASE_URL)
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            
            // API Port preference
            findPreference<EditTextPreference>("api_port")?.apply {
                setDefaultValue(EucWorldApiClient.DEFAULT_PORT.toString())
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            
            // Hide notification preference
            findPreference<SwitchPreferenceCompat>("hide_notification")?.apply {
                setDefaultValue(false)
            }
            
            // Auto-start on boot preference
            findPreference<SwitchPreferenceCompat>("auto_start_on_boot")?.apply {
                setDefaultValue(false)
            }
            
            // App version
            findPreference<Preference>("app_version")?.apply {
                summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            }
            
            // Build date
            findPreference<Preference>("build_date")?.apply {
                summary = BuildConfig.BUILD_DATE
            }
            
            // GitHub project link
            findPreference<Preference>("github_project")?.apply {
                setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/githuba42r/OSM-EUC-World"))
                    startActivity(intent)
                    true
                }
            }
            
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
            
            // Update detected wheel info
            updateDetectedWheelInfo()
        }
        
        override fun onResume() {
            super.onResume()
            // Register for EUC data broadcasts
            LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                eucDataReceiver,
                IntentFilter(AutoProxyReceiver.ACTION_EUC_DATA_UPDATE)
            )
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
        
        /**
         * Migrate Int preferences to String for EditTextPreference compatibility.
         * Auto-detection saves battery_capacity_wh and battery_cell_count as Int,
         * but EditTextPreference requires String values.
         */
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
    }
}
