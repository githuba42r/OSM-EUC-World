package com.a42r.eucosmandplugin.ai.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.a42r.eucosmandplugin.ai.data.TokenManager
import com.a42r.eucosmandplugin.ai.service.OpenRouterOAuthService

/**
 * Factory for creating OpenRouterOAuthViewModel with dependencies
 */
class OpenRouterOAuthViewModelFactory(
    private val application: Application,
    private val oauthService: OpenRouterOAuthService,
    private val tokenManager: TokenManager
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OpenRouterOAuthViewModel::class.java)) {
            return OpenRouterOAuthViewModel(application, oauthService, tokenManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
