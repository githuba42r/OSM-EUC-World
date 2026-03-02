package com.a42r.eucosmandplugin.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.api.EucWorldApiClient
import com.a42r.eucosmandplugin.databinding.ActivitySettingsBinding

/**
 * Settings activity for API configuration.
 */
class ApiSettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_category_api)
        }
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, ApiSettingsFragment())
                .commit()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    class ApiSettingsFragment : PreferenceFragmentCompat() {
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_api, rootKey)
            
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
        }
    }
}
