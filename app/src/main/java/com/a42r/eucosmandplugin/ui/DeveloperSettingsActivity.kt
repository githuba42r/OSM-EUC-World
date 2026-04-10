package com.a42r.eucosmandplugin.ui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.ai.service.RiderProfileBuilder
import com.a42r.eucosmandplugin.databinding.ActivitySettingsBinding
import com.a42r.eucosmandplugin.range.database.WheelDatabase
import com.a42r.eucosmandplugin.range.util.DataCaptureLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            setupRebuildProfileButton()
            setupResetProfileButton()
            setupWipeAllTripDataButton()
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
        
        private fun setupRebuildProfileButton() {
            findPreference<Preference>("rebuild_profile_from_logs")?.apply {
                setOnPreferenceClickListener {
                    confirmAndRebuildProfile()
                    true
                }
            }
        }

        private fun setupResetProfileButton() {
            findPreference<Preference>("reset_rider_profile")?.apply {
                setOnPreferenceClickListener {
                    confirmAndResetProfile()
                    true
                }
            }
        }

        /**
         * Hard wipe: rider profile DB + all trip_*.jsonl files on disk.
         * Intended for clean breaks when the log schema changes (e.g. v2 → v3
         * adds altitude data that older logs don't carry).
         */
        private fun setupWipeAllTripDataButton() {
            findPreference<Preference>("wipe_all_trip_data")?.apply {
                setOnPreferenceClickListener {
                    confirmAndWipeAllTripData()
                    true
                }
            }
        }

        private fun confirmAndWipeAllTripData() {
            val ctx = requireContext()
            val logDir = logger.getLogDirectoryFile()
            val logFiles = logDir.listFiles { f -> f.extension == "jsonl" } ?: emptyArray()

            AlertDialog.Builder(ctx)
                .setTitle(R.string.dev_wipe_all_data_confirm_title)
                .setMessage(ctx.getString(R.string.dev_wipe_all_data_confirm_message, logFiles.size))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val deleted = withContext(Dispatchers.IO) {
                                // Close any currently-open log file BEFORE deletion.
                                if (logger.isCurrentlyLogging()) {
                                    logger.closeCurrentLog()
                                }

                                // Wipe Room DB.
                                RiderProfileBuilder(ctx).resetAllProfiles()

                                // Delete trip log files. Re-list in case the set
                                // changed since the confirmation dialog opened.
                                val files = logger.getLogDirectoryFile()
                                    .listFiles { f -> f.extension == "jsonl" }
                                    ?: emptyArray()
                                var count = 0
                                for (f in files) {
                                    if (f.delete()) count++
                                }
                                count
                            }
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.dev_wipe_all_data_done, deleted),
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                ctx,
                                "Wipe failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun confirmAndResetProfile() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_reset_rider_profile_confirm_title)
                .setMessage(R.string.dev_reset_rider_profile_confirm_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                RiderProfileBuilder(requireContext()).resetAllProfiles()
                            }
                            Toast.makeText(
                                requireContext(),
                                R.string.dev_reset_rider_profile_done,
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                requireContext(),
                                "Reset failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun confirmAndRebuildProfile() {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.dev_rebuild_profile_confirm_title)
                .setMessage(R.string.dev_rebuild_profile_confirm_message)
                .setPositiveButton(android.R.string.ok) { _, _ -> runProfileRebuild() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        @Suppress("DEPRECATION")
        private fun runProfileRebuild() {
            val ctx = requireContext()
            val progress = ProgressDialog(ctx).apply {
                setMessage(ctx.getString(R.string.dev_rebuild_profile_in_progress))
                setCancelable(false)
                show()
            }

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val builder = RiderProfileBuilder(ctx)
                        // Wipe everything first so this is a true rebuild.
                        builder.resetAllProfiles()

                        // Resolve fallback wheel model + battery capacity from
                        // the currently-configured wheel. Logs captured before
                        // v2 metadata will use these values.
                        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                        val fallbackWheel = defaultPrefs.getString("selected_wheel_model", null)
                        val fallbackCapacity = WheelDatabase.findWheelSpec(fallbackWheel ?: "")
                            ?.batteryConfig?.capacityWh
                            ?: try {
                                defaultPrefs.getInt("battery_capacity_wh", -1)
                                    .takeIf { it != -1 }?.toDouble()
                            } catch (e: ClassCastException) {
                                defaultPrefs.getString("battery_capacity_wh", null)?.toDoubleOrNull()
                            }
                            ?: 2000.0

                        val logger = DataCaptureLogger(ctx)
                        val logDir = logger.getLogDirectoryFile()
                        val currentLog = ctx.getSharedPreferences(
                            "developer_settings",
                            android.content.Context.MODE_PRIVATE
                        ).getString("developer_current_log_file", null)

                        builder.replayLogDirectory(
                            logDir = logDir,
                            fallbackWheelModel = fallbackWheel,
                            fallbackBatteryCapacityWh = fallbackCapacity,
                            onlyUnprocessed = false,
                            skipCurrentFile = currentLog
                        )
                    }

                    progress.dismiss()
                    val msg = getString(
                        R.string.dev_rebuild_profile_result,
                        result.filesProcessed,
                        result.filesScanned,
                        result.samplesProcessed,
                        result.totalDistanceKm
                    )
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.dev_rebuild_profile_from_logs_title)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } catch (e: Exception) {
                    progress.dismiss()
                    Toast.makeText(ctx, "Rebuild failed: ${e.message}", Toast.LENGTH_LONG).show()
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
