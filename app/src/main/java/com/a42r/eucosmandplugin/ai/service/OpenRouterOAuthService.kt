package com.a42r.eucosmandplugin.ai.service

import android.util.Base64
import android.util.Log
import com.a42r.eucosmandplugin.ai.util.NativeSecrets
import com.a42r.eucosmandplugin.ai.util.OAuthSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth2 service for OpenRouter with PKCE flow
 */
class OpenRouterOAuthService {

    companion object {
        private const val TAG = "OpenRouterOAuth"
        private const val PROVIDER_NAME = "openrouter"
        private const val AUTH_BASE_URL = "https://openrouter.ai/auth"
        private const val CODE_CHALLENGE_METHOD = "S256"
    }

    /**
     * PKCE (Proof Key for Code Exchange) state holder
     */
    data class PKCEState(
        val codeVerifier: String,
        val codeChallenge: String,
        val callbackUrl: String,
        val sessionId: String
    )

    /**
     * Generate PKCE code verifier and challenge
     */
    private fun generatePKCE(): Pair<String, String> {
        // Generate code verifier (43-128 characters)
        val codeVerifier = generateRandomString(64)

        // Generate code challenge (SHA-256 hash of verifier, base64url encoded)
        val bytes = codeVerifier.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val codeChallenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        return Pair(codeVerifier, codeChallenge)
    }

    /**
     * Generate cryptographically secure random string
     */
    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = SecureRandom()
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }

    /**
     * Register OAuth session with callback service
     *
     * @return PKCEState containing callback URL and session info
     * @throws Exception if registration fails
     */
    suspend fun registerOAuthSession(): PKCEState = withContext(Dispatchers.IO) {
        val timestamp = OAuthSignature.getCurrentTimestamp()
        val deepLinkUrl = NativeSecrets.getDeepLinkScheme()
        val callbackBaseUrl = NativeSecrets.getOAuthCallbackUrl()
        val secret = NativeSecrets.getOAuthSharedSecret()

        Log.d(TAG, "OAuth Registration - Callback URL: $callbackBaseUrl")
        Log.d(TAG, "OAuth Registration - Deep Link: $deepLinkUrl")
        Log.d(TAG, "OAuth Registration - Secret length: ${secret.length}")

        val signature = OAuthSignature.generateSignature(
            provider = PROVIDER_NAME,
            deepLinkUrl = deepLinkUrl,
            timestamp = timestamp,
            secret = secret
        )

        // Create registration request
        val appId = NativeSecrets.getAppId()
        val requestBody = JSONObject().apply {
            put("app_id", appId)
            put("provider", PROVIDER_NAME)
            put("deep_link_url", deepLinkUrl)
            put("timestamp", timestamp)
            put("signature", signature)
        }

        Log.d(TAG, "Registering session at: $callbackBaseUrl/oauth/register")
        Log.d(TAG, "Request body: $requestBody")

        // Send registration request
        val url = URL("$callbackBaseUrl/oauth/register")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Write request body
            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray())
            }

            // Read response
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Registration failed with code $responseCode")
                Log.e(TAG, "Error response: $errorBody")
                throw Exception("OAuth registration failed: $responseCode - $errorBody")
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val response = JSONObject(responseBody)

            // Generate PKCE parameters
            val (codeVerifier, codeChallenge) = generatePKCE()

            // Return PKCE state
            PKCEState(
                codeVerifier = codeVerifier,
                codeChallenge = codeChallenge,
                callbackUrl = response.getString("callback_url"),
                sessionId = response.getString("session_id")
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Build OpenRouter authorization URL with PKCE
     *
     * @param pkceState PKCE state from registration
     * @return Authorization URL to open in browser
     */
    fun buildAuthorizationUrl(pkceState: PKCEState): String {
        return "$AUTH_BASE_URL?" +
                "callback_url=${pkceState.callbackUrl}" +
                "&code_challenge=${pkceState.codeChallenge}" +
                "&code_challenge_method=$CODE_CHALLENGE_METHOD"
    }

    /**
     * Exchange authorization code for API key
     *
     * @param code Authorization code from callback
     * @param codeVerifier PKCE code verifier
     * @return OpenRouter API key
     * @throws Exception if exchange fails
     */
    suspend fun exchangeCodeForKey(code: String, codeVerifier: String): String = withContext(Dispatchers.IO) {
        // OpenRouter exchange endpoint
        val url = URL("https://openrouter.ai/api/v1/auth/keys")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            // Request body with code, code_verifier, and code_challenge_method
            val requestBody = JSONObject().apply {
                put("code", code)
                put("code_verifier", codeVerifier)
                put("code_challenge_method", CODE_CHALLENGE_METHOD)
            }

            // Write request
            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray())
            }

            // Read response
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                throw Exception("Token exchange failed: $responseCode - $errorBody")
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val response = JSONObject(responseBody)

            // Extract API key
            response.getString("key")
        } finally {
            connection.disconnect()
        }
    }
}
