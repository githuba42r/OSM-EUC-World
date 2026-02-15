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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(autoProxyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(autoProxyReceiver, filter)
        }
    }
}
