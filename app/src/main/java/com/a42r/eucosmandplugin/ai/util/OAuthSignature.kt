package com.a42r.eucosmandplugin.ai.util

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 signature utilities for OAuth callback service
 */
object OAuthSignature {

    /**
     * Generate HMAC-SHA256 signature for OAuth callback registration
     *
     * Message format: {provider}|{deep_link_url}|{timestamp}
     *
     * @param provider OAuth provider name (e.g., "openrouter")
     * @param deepLinkUrl Deep link URL to redirect back to app
     * @param timestamp Unix timestamp in seconds
     * @param secret Shared secret key
     * @return Hex-encoded HMAC-SHA256 signature
     */
    fun generateSignature(
        provider: String,
        deepLinkUrl: String,
        timestamp: Long,
        secret: String
    ): String {
        // Create message to sign: provider|deep_link_url|timestamp
        val message = "${provider.lowercase()}|$deepLinkUrl|$timestamp"
        
        android.util.Log.d("OAuthSignature", "Signing message: $message")
        android.util.Log.d("OAuthSignature", "Secret length: ${secret.length}")

        // Generate HMAC-SHA256
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val bytes = mac.doFinal(message.toByteArray())

        // Convert to hex string
        val signature = bytes.joinToString("") { "%02x".format(it) }
        android.util.Log.d("OAuthSignature", "Generated signature: $signature")
        return signature
    }

    /**
     * Get current Unix timestamp in seconds
     */
    fun getCurrentTimestamp(): Long {
        return System.currentTimeMillis() / 1000
    }
}
