package com.a42r.eucosmandplugin.ai.util

/**
 * Native secrets for OAuth configuration
 * 
 * TODO: Move these to native C++ implementation for better security
 * For now, using Kotlin constants
 */
object NativeSecrets {
    
    /**
     * App ID for OAuth redirector
     * This is used by the OAuth callback service to identify this app
     */
    fun getAppId(): String {
        return "com.a42r.eucosmandplugin"
    }
    
    /**
     * Deep link scheme for OAuth callback
     * Format: osmeucworld://oauth/callback
     */
    fun getDeepLinkScheme(): String {
        return "osmeucworld://oauth/callback"
    }
    
    /**
     * OAuth callback service URL (hosted at a42r.com)
     * This service handles the OAuth flow and redirects back to the app
     */
    fun getOAuthCallbackUrl(): String {
        return "https://oauth.a42r.com"
    }
    
    /**
     * Shared secret for OAuth signature verification
     * This should match the secret configured on the callback service
     */
    fun getOAuthSharedSecret(): String {
        return "ibEVy3hSw-T1XEMMbjpCSdctthm-5TcC2lnnQr9-sd8"
    }
}
