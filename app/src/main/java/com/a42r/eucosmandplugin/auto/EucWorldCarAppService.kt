package com.a42r.eucosmandplugin.auto

import android.content.Intent
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto Car App Service for EUC World.
 * 
 * This service provides the entry point for the Android Auto experience.
 * It creates a Session that manages the EUC data display screens.
 */
class EucWorldCarAppService : CarAppService() {
    
    companion object {
        private const val TAG = "EucWorldCarAppService"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
    }
    
    override fun createHostValidator(): HostValidator {
        Log.d(TAG, "createHostValidator()")
        // Allow all hosts for development; in production, restrict to known hosts
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }
    
    override fun onCreateSession(): Session {
        Log.d(TAG, "onCreateSession()")
        return EucWorldSession()
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy()")
        super.onDestroy()
    }
}

/**
 * Session for the EUC World Car App.
 * Manages the lifecycle and screens of the Android Auto experience.
 */
class EucWorldSession : Session() {
    
    companion object {
        private const val TAG = "EucWorldSession"
    }
    
    override fun onCreateScreen(intent: Intent): Screen {
        Log.d(TAG, "onCreateScreen() intent=$intent")
        return EucWorldScreen(carContext)
    }
}
