package com.a42r.eucosmandplugin.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.api.EucWorldApiClient
import com.a42r.eucosmandplugin.databinding.ActivitySettingsBinding

/**
 * Settings activity for configuring the EUC World plugin.
 */
class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
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
    
    class SettingsFragment : PreferenceFragmentCompat() {
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
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
            
            // Poll interval preference
            findPreference<SeekBarPreference>("poll_interval")?.apply {
                min = 100
                max = 2000
                setDefaultValue(500)
                summary = "Update interval: ${value}ms"
                setOnPreferenceChangeListener { preference, newValue ->
                    preference.summary = "Update interval: ${newValue}ms"
                    true
                }
            }
            
            // Hide notification preference
            findPreference<SwitchPreferenceCompat>("hide_notification")?.apply {
                setDefaultValue(false)
                setOnPreferenceChangeListener { _, newValue ->
                    // The service will check this preference when creating/updating notifications
                    true
                }
            }
            
            // Auto-start on boot preference
            findPreference<SwitchPreferenceCompat>("auto_start_on_boot")?.apply {
                setDefaultValue(false)
            }
        }
    }
}
