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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.databinding.ActivityTripLogsBinding
import com.a42r.eucosmandplugin.range.util.DataCaptureLogger

/**
 * Activity for viewing and managing trip log files.
 */
class TripLogsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityTripLogsBinding
    private lateinit var logger: DataCaptureLogger
    private lateinit var logAdapter: LogFileAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTripLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Trip Logs"
        }
        
        logger = DataCaptureLogger(this)
        
        setupUI()
        loadLogFiles()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    private fun setupUI() {
        // Clear all logs button
        binding.btnClearAllLogs.setOnClickListener {
            showClearAllLogsDialog()
        }
        
        // Refresh button
        binding.btnRefreshLogs.setOnClickListener {
            loadLogFiles()
        }
        
        // RecyclerView for log files
        logAdapter = LogFileAdapter(
            onShareClick = { logInfo ->
                shareLogFile(logInfo)
            },
            onDeleteClick = { logInfo ->
                showDeleteLogDialog(logInfo)
            }
        )
        
        binding.recyclerLogFiles.apply {
            layoutManager = LinearLayoutManager(this@TripLogsActivity)
            adapter = logAdapter
        }
    }
    
    private fun loadLogFiles() {
        val logs = logger.getLogFiles()
        logAdapter.submitList(logs)
        
        // Update empty state
        if (logs.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.recyclerLogFiles.visibility = View.GONE
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.recyclerLogFiles.visibility = View.VISIBLE
        }
        
        // Update summary
        val totalSize = logs.sumOf { it.sizeBytes }
        val sizeFormatted = when {
            totalSize < 1024 -> "$totalSize B"
            totalSize < 1024 * 1024 -> "${totalSize / 1024} KB"
            else -> "${totalSize / (1024 * 1024)} MB"
        }
        binding.tvLogSummary.text = "${logs.size} log files ($sizeFormatted)"
    }
    
    private fun shareLogFile(logInfo: DataCaptureLogger.LogFileInfo) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                logInfo.file
            )
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "EUC Trip Log: ${logInfo.name}")
                putExtra(Intent.EXTRA_TEXT, "Trip data log captured on ${logInfo.getDateFormatted()}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, "Share Trip Log"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share log: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showDeleteLogDialog(logInfo: DataCaptureLogger.LogFileInfo) {
        AlertDialog.Builder(this)
            .setTitle("Delete Log")
            .setMessage("Delete ${logInfo.name}?\n\nSize: ${logInfo.getSizeFormatted()}\nDate: ${logInfo.getDateFormatted()}")
            .setPositiveButton("Delete") { _, _ ->
                if (logger.deleteLogFile(logInfo.file)) {
                    Toast.makeText(this, "Log deleted", Toast.LENGTH_SHORT).show()
                    loadLogFiles()
                } else {
                    Toast.makeText(this, "Failed to delete log", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showClearAllLogsDialog() {
        val logs = logger.getLogFiles()
        if (logs.isEmpty()) {
            Toast.makeText(this, "No logs to delete", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("Clear All Logs")
            .setMessage("Delete all ${logs.size} log files?\n\nThis cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                val deletedCount = logger.deleteAllLogs()
                Toast.makeText(this, "Deleted $deletedCount log files", Toast.LENGTH_SHORT).show()
                loadLogFiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Adapter for log file list.
     */
    class LogFileAdapter(
        private val onShareClick: (DataCaptureLogger.LogFileInfo) -> Unit,
        private val onDeleteClick: (DataCaptureLogger.LogFileInfo) -> Unit
    ) : RecyclerView.Adapter<LogFileAdapter.ViewHolder>() {
        
        private var logs: List<DataCaptureLogger.LogFileInfo> = emptyList()
        
        fun submitList(newLogs: List<DataCaptureLogger.LogFileInfo>) {
            logs = newLogs
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_file, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(logs[position], onShareClick, onDeleteClick)
        }
        
        override fun getItemCount() = logs.size
        
        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvFileName: TextView = itemView.findViewById(R.id.tv_file_name)
            private val tvFileDate: TextView = itemView.findViewById(R.id.tv_file_date)
            private val tvFileSize: TextView = itemView.findViewById(R.id.tv_file_size)
            private val btnShare: View = itemView.findViewById(R.id.btn_share)
            private val btnDelete: View = itemView.findViewById(R.id.btn_delete)
            
            fun bind(
                logInfo: DataCaptureLogger.LogFileInfo,
                onShareClick: (DataCaptureLogger.LogFileInfo) -> Unit,
                onDeleteClick: (DataCaptureLogger.LogFileInfo) -> Unit
            ) {
                tvFileName.text = logInfo.name
                tvFileDate.text = logInfo.getDateFormatted()
                tvFileSize.text = logInfo.getSizeFormatted()
                
                btnShare.setOnClickListener { onShareClick(logInfo) }
                btnDelete.setOnClickListener { onDeleteClick(logInfo) }
            }
        }
    }
}
