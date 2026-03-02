package com.a42r.eucosmandplugin.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.databinding.ActivitySettingsBinding

/**
 * Settings activity for general settings.
 */
class GeneralSettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_category_general)
        }
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, GeneralSettingsFragment())
                .commit()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    class GeneralSettingsFragment : PreferenceFragmentCompat() {
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_general, rootKey)
            
            // Hide notification preference
            findPreference<SwitchPreferenceCompat>("hide_notification")?.apply {
                setDefaultValue(false)
            }
            
            // Auto-start on boot preference
            findPreference<SwitchPreferenceCompat>("auto_start_on_boot")?.apply {
                setDefaultValue(false)
            }
        }
    }
}
