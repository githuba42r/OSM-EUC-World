package com.a42r.eucosmandplugin.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.a42r.eucosmandplugin.BuildConfig
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.databinding.ActivitySettingsBinding

/**
 * Settings activity for about information.
 */
class AboutActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.settings_category_about)
        }
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, AboutFragment())
                .commit()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    class AboutFragment : PreferenceFragmentCompat() {
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_about, rootKey)
            
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
