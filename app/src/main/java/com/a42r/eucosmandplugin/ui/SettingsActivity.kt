package com.a42r.eucosmandplugin.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.a42r.eucosmandplugin.BuildConfig
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
        }
    }
}
