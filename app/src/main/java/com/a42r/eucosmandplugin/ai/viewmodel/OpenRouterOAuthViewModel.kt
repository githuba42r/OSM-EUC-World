package com.a42r.eucosmandplugin.ai.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.a42r.eucosmandplugin.ai.data.TokenManager
import com.a42r.eucosmandplugin.ai.service.OpenRouterOAuthService
import kotlinx.coroutines.launch

/**
 * ViewModel for OpenRouter OAuth flow
 */
class OpenRouterOAuthViewModel(
    application: Application,
    private val oauthService: OpenRouterOAuthService,
    private val tokenManager: TokenManager
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OAuthViewModel"
    }

    // Current PKCE state (stored in memory during OAuth flow)
    private var currentPKCEState: OpenRouterOAuthService.PKCEState? = null

    // OAuth flow state
    private val _oauthState = MutableLiveData<OAuthState>()
    val oauthState: LiveData<OAuthState> = _oauthState

    // API key result
    private val _apiKey = MutableLiveData<String?>()
    val apiKey: LiveData<String?> = _apiKey

    // Is authenticated
    private val _isAuthenticated = MutableLiveData<Boolean>()
    val isAuthenticated: LiveData<Boolean> = _isAuthenticated

    init {
        checkExistingAuthentication()
    }

    sealed class OAuthState {
        object Idle : OAuthState()
        object Registering : OAuthState()
        data class ReadyToAuthorize(val authUrl: String) : OAuthState()
        object ExchangingCode : OAuthState()
        data class Success(val apiKey: String) : OAuthState()
        data class Error(val message: String) : OAuthState()
        object Authenticated : OAuthState()
    }

    /**
     * Check if user is already authenticated
     */
    private fun checkExistingAuthentication() {
        val existingKey = tokenManager.getOpenRouterApiKey()
        if (existingKey != null) {
            _apiKey.value = existingKey
            _isAuthenticated.value = true
            _oauthState.value = OAuthState.Authenticated
        } else {
            _isAuthenticated.value = false
            _oauthState.value = OAuthState.Idle
        }
    }

    /**
     * Start OAuth flow - register session and prepare authorization URL
     */
    fun startOAuthFlow() {
        _oauthState.value = OAuthState.Registering

        viewModelScope.launch {
            try {
                // Register session with callback service
                val pkceState = oauthService.registerOAuthSession()
                currentPKCEState = pkceState

                // Save code verifier to persistent storage (survives app restart)
                tokenManager.savePkceCodeVerifier(pkceState.codeVerifier)

                // Build authorization URL
                val authUrl = oauthService.buildAuthorizationUrl(pkceState)

                // Ready to open browser
                _oauthState.value = OAuthState.ReadyToAuthorize(authUrl)
            } catch (e: Exception) {
                Log.e(TAG, "OAuth registration failed", e)
                _oauthState.value = OAuthState.Error("Registration failed: ${e.message}")
            }
        }
    }

    /**
     * Open authorization URL in Custom Tab
     */
    fun openAuthorizationUrl(authUrl: String) {
        try {
            val intent = CustomTabsIntent.Builder().build()
            intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.launchUrl(getApplication(), Uri.parse(authUrl))
        } catch (e: Exception) {
            _oauthState.value = OAuthState.Error("Failed to open browser: ${e.message}")
        }
    }

    /**
     * Handle deep link callback with authorization code
     */
    fun handleCallback(code: String, provider: String) {
        // Try to get PKCE state from memory first, then from persistent storage
        var pkceState = currentPKCEState
        if (pkceState == null) {
            // Restore from persistent storage (app may have been restarted)
            val savedVerifier = tokenManager.getPkceCodeVerifier()
            if (savedVerifier == null) {
                _oauthState.value = OAuthState.Error("No active OAuth session - code verifier not found")
                return
            }
            // Reconstruct PKCE state with saved verifier
            // Note: We only need the verifier for token exchange, other fields aren't used
            pkceState = OpenRouterOAuthService.PKCEState(
                codeVerifier = savedVerifier,
                codeChallenge = "", // Not needed for exchange
                sessionId = "", // Not needed for exchange
                callbackUrl = "" // Not needed for exchange
            )
        }

        if (provider != "openrouter") {
            _oauthState.value = OAuthState.Error("Unexpected provider: $provider")
            return
        }

        _oauthState.value = OAuthState.ExchangingCode

        viewModelScope.launch {
            try {
                // Exchange code for API key
                val apiKey = oauthService.exchangeCodeForKey(code, pkceState.codeVerifier)

                // Save API key
                tokenManager.saveOpenRouterApiKey(apiKey)

                // Clear PKCE state from memory and storage
                currentPKCEState = null
                tokenManager.clearPkceCodeVerifier()

                // Success!
                _apiKey.value = apiKey
                _isAuthenticated.value = true
                _oauthState.value = OAuthState.Success(apiKey)
            } catch (e: Exception) {
                currentPKCEState = null
                tokenManager.clearPkceCodeVerifier()
                _oauthState.value = OAuthState.Error("Token exchange failed: ${e.message}")
            }
        }
    }

    /**
     * Handle OAuth error callback
     */
    fun handleError(error: String, errorDescription: String?) {
        currentPKCEState = null
        tokenManager.clearPkceCodeVerifier()
        val message = errorDescription ?: error
        _oauthState.value = OAuthState.Error("OAuth error: $message")
    }

    /**
     * Reset OAuth state
     */
    fun reset() {
        currentPKCEState = null
        tokenManager.clearPkceCodeVerifier()
        _oauthState.value = OAuthState.Idle
        _apiKey.value = null
    }

    /**
     * Disconnect - clear saved API key
     */
    fun disconnect() {
        tokenManager.clearOpenRouterApiKey()
        tokenManager.clearPkceCodeVerifier()
        _apiKey.value = null
        _isAuthenticated.value = false
        _oauthState.value = OAuthState.Idle
    }
    
    /**
     * Open browser for authentication (called from Activity)
     */
    fun openBrowserForAuth(context: android.content.Context, authUrl: String) {
        try {
            val intent = CustomTabsIntent.Builder().build()
            intent.launchUrl(context, Uri.parse(authUrl))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open browser", e)
            _oauthState.value = OAuthState.Error("Failed to open browser: ${e.message}")
        }
    }
    
    /**
     * Handle OAuth callback from deep link
     */
    fun handleOAuthCallback(code: String, state: String?) {
        handleCallback(code, "openrouter")
    }
    
    /**
     * Get selected AI model
     */
    fun getSelectedModel(): String? {
        return tokenManager.getSelectedAiModel()
    }
    
    /**
     * Set selected AI model
     */
    fun setSelectedModel(modelId: String) {
        tokenManager.saveSelectedAiModel(modelId)
    }
}
