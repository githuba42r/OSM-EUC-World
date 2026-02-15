package com.a42r.eucosmandplugin.auto

import android.content.Intent
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
    
    override fun createHostValidator(): HostValidator {
        // Allow all hosts for development; in production, restrict to known hosts
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }
    
    override fun onCreateSession(): Session {
        return EucWorldSession()
    }
}

/**
 * Session for the EUC World Car App.
 * Manages the lifecycle and screens of the Android Auto experience.
 */
class EucWorldSession : Session() {
    
    override fun onCreateScreen(intent: Intent): Screen {
        return EucWorldScreen(carContext)
    }
}
