package com.a42r.eucosmandplugin.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.databinding.ActivitySettingsBinding
import com.a42r.eucosmandplugin.range.util.DataCaptureLogger

/**
 * Developer settings activity for advanced diagnostics and data capture.
 * 
 * Features:
 * - Enable/disable developer mode
 * - Adjust sampling parameters (min time/distance)
 * - Enable/disable data capture logging
 * - View list of captured trip logs
 * - Share/export log files
 * - Delete individual or all logs
 */
class DeveloperSettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Developer Settings"
        }
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, DeveloperSettingsFragment())
                .commit()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    class DeveloperSettingsFragment : PreferenceFragmentCompat() {
        
        private lateinit var logger: DataCaptureLogger
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_developer, rootKey)
            
            logger = DataCaptureLogger(requireContext())
            
            setupDeveloperModeToggle()
            setupLoggingToggle()
            setupViewLogsButton()
        }
        
        private fun setupDeveloperModeToggle() {
            findPreference<SwitchPreference>("developer_mode_enabled")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == false) {
                        showDisableDeveloperModeDialog()
                        false // Don't change value yet, dialog will handle it
                    } else {
                        true
                    }
                }
            }
        }
        
        private fun setupLoggingToggle() {
            findPreference<SwitchPreference>("developer_logging_enabled")?.apply {
                // Sync with logger state
                isChecked = logger.isLoggingEnabled()
                
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    logger.setLoggingEnabled(enabled)
                    if (enabled) {
                        Toast.makeText(requireContext(), "Data logging enabled", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Data logging disabled", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }
        }
        
        private fun setupViewLogsButton() {
            findPreference<Preference>("view_logs")?.apply {
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), TripLogsActivity::class.java))
                    true
                }
            }
        }
        
        private fun showDisableDeveloperModeDialog() {
            AlertDialog.Builder(requireContext())
                .setTitle("Disable Developer Mode")
                .setMessage("This will hide developer settings from the menu.\n\nYou can re-enable developer mode by clicking the version number 10 times in the About screen.")
                .setPositiveButton("Disable") { _, _ ->
                    preferenceManager.sharedPreferences?.edit()
                        ?.putBoolean("developer_mode_enabled", false)
                        ?.apply()
                    Toast.makeText(requireContext(), "Developer mode disabled", Toast.LENGTH_SHORT).show()
                    requireActivity().finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
