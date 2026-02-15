package com.a42r.eucosmandplugin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.api.EucData
import com.a42r.eucosmandplugin.api.EucWidgetData
import com.a42r.eucosmandplugin.api.EucWorldApiClient
import com.a42r.eucosmandplugin.ui.MainActivity

/**
 * Foreground service that continuously polls the EUC World Internal Webservice API
 * and broadcasts updates to widgets, OsmAnd, and Android Auto.
 */
class EucWorldService : LifecycleService() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "euc_world_channel"
        const val NOTIFICATION_CHANNEL_ID_MINIMAL = "euc_world_channel_minimal"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "com.a42r.eucosmandplugin.START_SERVICE"
        const val ACTION_STOP = "com.a42r.eucosmandplugin.STOP_SERVICE"
        const val ACTION_UPDATE = "com.a42r.eucosmandplugin.DATA_UPDATE"
        
        const val EXTRA_EUC_DATA = "euc_data"
        
        private const val DEFAULT_POLL_INTERVAL_MS = 500L
        private const val PREF_HIDE_NOTIFICATION = "hide_notification"
        
        // Static reference to latest data for widgets
        @Volatile
        private var _latestData: EucData? = null
        val latestData: EucData? get() = _latestData
        
        fun start(context: Context) {
            val intent = Intent(context, EucWorldService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, EucWorldService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    private val binder = LocalBinder()
    
    private lateinit var apiClient: EucWorldApiClient
    private var pollingJob: Job? = null
    
    private val _eucDataState = MutableStateFlow<EucData?>(null)
    val eucDataState: StateFlow<EucData?> = _eucDataState.asStateFlow()
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private var pollIntervalMs = DEFAULT_POLL_INTERVAL_MS
    
    // OsmAnd AIDL helper for widget integration
    private lateinit var osmAndHelper: OsmAndAidlHelper
    
    inner class LocalBinder : Binder() {
        fun getService(): EucWorldService = this@EucWorldService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeApiClient()
        
        // Initialize OsmAnd AIDL helper
        osmAndHelper = OsmAndAidlHelper(this)
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startPolling()
                
                // Connect to OsmAnd AIDL service
                osmAndHelper.connect()
            }
            ACTION_STOP -> {
                stopPolling()
                osmAndHelper.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        stopPolling()
        osmAndHelper.disconnect()
        super.onDestroy()
    }
    
    private fun initializeApiClient() {
        val prefs = getSharedPreferences("euc_world_prefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("api_base_url", EucWorldApiClient.DEFAULT_BASE_URL) 
            ?: EucWorldApiClient.DEFAULT_BASE_URL
        val port = prefs.getInt("api_port", EucWorldApiClient.DEFAULT_PORT)
        pollIntervalMs = prefs.getLong("poll_interval", DEFAULT_POLL_INTERVAL_MS)
        
        apiClient = EucWorldApiClient(baseUrl, port)
    }
    
    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        
        _connectionState.value = ConnectionState.CONNECTING
        
        pollingJob = lifecycleScope.launch {
            // Use full data filter to get all metrics including wheel model
            apiClient.eucDataFlow(pollIntervalMs, batteryOnly = false)
                .catch { e ->
                    _connectionState.value = ConnectionState.ERROR
                    _eucDataState.value = null
                    _latestData = null
                }
                .collectLatest { result ->
                    result.fold(
                        onSuccess = { data ->
                            _eucDataState.value = data
                            _latestData = data
                            _connectionState.value = if (data.isConnected) {
                                ConnectionState.CONNECTED
                            } else {
                                ConnectionState.WHEEL_DISCONNECTED
                            }
                            
                            // Update notification with current data
                            updateNotification(data)
                            
                            // Broadcast update to widgets and OsmAnd
                            broadcastUpdate(data)
                        },
                        onFailure = { error ->
                            _connectionState.value = ConnectionState.ERROR
                            _eucDataState.value = null
                            _latestData = null
                        }
                    )
                }
        }
    }
    
    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    private fun broadcastUpdate(data: EucData) {
        // Broadcast for widgets
        val widgetIntent = Intent(ACTION_UPDATE).apply {
            putExtra(EXTRA_EUC_DATA, data.batteryPercentage)
            putExtra("voltage", data.voltage)
            putExtra("speed", data.speed)
            putExtra("temperature", data.temperature)
            putExtra("connected", data.isConnected)
        }
        sendBroadcast(widgetIntent)
        
        // Update OsmAnd widget via AIDL
        if (osmAndHelper.isConnected()) {
            osmAndHelper.updateWidgetData(data)
        }
        
        // Broadcast to Android Auto if subscribed
        broadcastToAndroidAuto(data)
    }
    
    /**
     * Broadcast data to Android Auto via the proxy receiver.
     * Android Auto runs on the car head unit and cannot access localhost,
     * so we use broadcast IPC to send data from phone to car.
     */
    private fun broadcastToAndroidAuto(data: EucData) {
        val autoPrefs = getSharedPreferences("auto_proxy", Context.MODE_PRIVATE)
        if (autoPrefs.getBoolean("auto_subscribed", false)) {
            val intent = Intent(AutoProxyReceiver.ACTION_EUC_DATA_UPDATE).apply {
                setPackage(packageName)
                putExtras(AutoProxyReceiver.createDataBundle(data))
            }
            sendBroadcast(intent)
        }
    }
    
    /**
     * Broadcast connection state change to Android Auto
     */
    private fun broadcastConnectionStateToAuto(state: ConnectionState) {
        val autoPrefs = getSharedPreferences("auto_proxy", Context.MODE_PRIVATE)
        if (autoPrefs.getBoolean("auto_subscribed", false)) {
            val intent = Intent(AutoProxyReceiver.ACTION_CONNECTION_STATE).apply {
                setPackage(packageName)
                putExtra(AutoProxyReceiver.EXTRA_CONNECTION_STATE, state.name)
            }
            sendBroadcast(intent)
        }
    }
    
    /**
     * Get the OsmAnd AIDL helper for external access
     */
    fun getOsmAndHelper(): OsmAndAidlHelper = osmAndHelper
    
    fun updateApiConfig(baseUrl: String, port: Int, pollInterval: Long) {
        getSharedPreferences("euc_world_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("api_base_url", baseUrl)
            putInt("api_port", port)
            putLong("poll_interval", pollInterval)
            apply()
        }
        
        // Restart with new config
        stopPolling()
        initializeApiClient()
        startPolling()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Standard channel with low importance
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
            
            // Minimal channel with minimum importance (hidden from status bar)
            val minimalChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID_MINIMAL,
                getString(R.string.notification_channel_name_minimal),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_description_minimal)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(minimalChannel)
        }
    }
    
    private fun createNotification(data: EucData? = null): Notification {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val hideNotification = prefs.getBoolean(PREF_HIDE_NOTIFICATION, false)
        
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, EucWorldService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Choose channel based on preference
        val channelId = if (hideNotification) NOTIFICATION_CHANNEL_ID_MINIMAL else NOTIFICATION_CHANNEL_ID
        
        return if (hideNotification) {
            // Minimal notification - just shows in notification shade, not status bar
            NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_running))
                .setSmallIcon(R.drawable.ic_battery)
                .setContentIntent(contentIntent)
                .addAction(R.drawable.ic_stop, getString(R.string.stop), stopIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        } else {
            // Standard notification with EUC data
            val contentText = if (data != null && data.isConnected) {
                data.getBatteryDisplayString()
            } else {
                getString(R.string.waiting_for_connection)
            }
            
            NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_battery)
                .setContentIntent(contentIntent)
                .addAction(R.drawable.ic_stop, getString(R.string.stop), stopIntent)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
    }
    
    private fun updateNotification(data: EucData) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(data))
    }
    
    /**
     * Get current EUC data as widget-friendly format
     */
    fun getWidgetData(): EucWidgetData {
        return _eucDataState.value?.let { EucWidgetData.fromEucData(it) }
            ?: EucWidgetData.disconnected()
    }
}
