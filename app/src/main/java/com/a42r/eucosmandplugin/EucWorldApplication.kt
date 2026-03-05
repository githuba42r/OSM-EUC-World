package com.a42r.eucosmandplugin

import android.app.Application
import android.content.IntentFilter
import android.os.Build
import com.a42r.eucosmandplugin.service.AutoProxyReceiver
import com.a42r.eucosmandplugin.service.EucWorldService

/**
 * Application class for EUC World OsmAnd Plugin.
 * Initializes services and receivers on app start.
 */
class EucWorldApplication : Application() {
    
    private val autoProxyReceiver = AutoProxyReceiver()
    
    override fun onCreate() {
        super.onCreate()
        
        // Register the Android Auto proxy receiver
        registerAutoProxyReceiver()
    }
    
    private fun registerAutoProxyReceiver() {
        val filter = IntentFilter().apply {
            addAction(AutoProxyReceiver.ACTION_REQUEST_DATA)
            addAction(AutoProxyReceiver.ACTION_REQUEST_SUBSCRIBE)
            addAction(AutoProxyReceiver.ACTION_REQUEST_UNSUBSCRIBE)
        }
        
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            autoProxyReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
