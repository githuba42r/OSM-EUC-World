package com.a42r.eucosmandplugin.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        
        private var clickCount = 0
        private var lastClickTime = 0L
        private var currentToast: Toast? = null
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_about, rootKey)
            
            // App version (with developer mode activation)
            findPreference<Preference>("app_version")?.apply {
                // Use the same SharedPreferences as SettingsActivity
                val prefs = preferenceManager.sharedPreferences
                
                fun updateSummary() {
                    val isDeveloperMode = prefs?.getBoolean("developer_mode_enabled", false) ?: false
                    summary = if (isDeveloperMode) {
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) - Developer Mode"
                    } else {
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    }
                }
                
                updateSummary()
                
                setOnPreferenceClickListener {
                    val isDeveloperMode = prefs?.getBoolean("developer_mode_enabled", false) ?: false
                    
                    // Check if already in developer mode - if so, show option to disable
                    if (isDeveloperMode) {
                        // Show dialog to disable developer mode
                        AlertDialog.Builder(requireContext())
                            .setTitle("Developer Mode")
                            .setMessage("Developer mode is currently enabled.\n\nDo you want to disable it?")
                            .setPositiveButton("Disable") { _, _ ->
                                prefs?.edit()?.putBoolean("developer_mode_enabled", false)?.apply()
                                updateSummary()
                                currentToast?.cancel()
                                currentToast = Toast.makeText(requireContext(), "Developer mode disabled", Toast.LENGTH_SHORT)
                                currentToast?.show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        return@setOnPreferenceClickListener true
                    }
                    
                    val currentTime = System.currentTimeMillis()
                    
                    // Reset counter if more than 3 seconds between clicks
                    if (currentTime - lastClickTime > 3000) {
                        clickCount = 0
                    }
                    
                    clickCount++
                    lastClickTime = currentTime
                    
                    // Cancel any existing toast to allow rapid clicks
                    currentToast?.cancel()
                    
                    if (clickCount >= 10) {
                        // Activate developer mode
                        prefs?.edit()?.putBoolean("developer_mode_enabled", true)?.apply()
                        
                        // Update the summary to show developer mode is enabled
                        updateSummary()
                        
                        currentToast = Toast.makeText(requireContext(), "Developer mode enabled!\nDeveloper Settings now available in Settings", Toast.LENGTH_LONG)
                        currentToast?.show()
                        clickCount = 0
                    } else if (clickCount >= 6) {
                        // Only show progress feedback after 6 clicks to obfuscate the process
                        val remainingClicks = 10 - clickCount
                        currentToast = Toast.makeText(
                            requireContext(), 
                            "$remainingClicks more clicks to enable Developer mode", 
                            Toast.LENGTH_SHORT
                        )
                        currentToast?.show()
                    }
                    // For clicks 1-5, no feedback is shown
                    
                    true
                }
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
            
            // Developer settings
            setupDeveloperModeSettings()
        }
        
        override fun onResume() {
            super.onResume()
            // Refresh developer mode settings visibility in case it was toggled
            setupDeveloperModeSettings()
        }
        
        private fun setupDeveloperModeSettings() {
            val prefs = preferenceManager.sharedPreferences
            val isDeveloperMode = prefs?.getBoolean("developer_mode_enabled", false) ?: false
            
            findPreference<Preference>("developer_settings")?.apply {
                isVisible = isDeveloperMode
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), DeveloperSettingsActivity::class.java))
                    true
                }
            }
        }
    }
}
