package com.a42r.eucosmandplugin.ai.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * Manages secure storage of AI/OpenRouter authentication tokens and preferences
 */
class TokenManager(private val context: Context) {
    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "euc_world_ai_prefs"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        
        // OpenRouter AI keys
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_SELECTED_AI_MODEL = "selected_ai_model"
        private const val KEY_PKCE_CODE_VERIFIER = "pkce_code_verifier"
        private const val KEY_AI_ENABLED = "ai_enabled"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = createEncryptedPreferences(context)
    
    // Also need access to default SharedPreferences to sync the AI enabled setting
    // for RangeEstimationManager which reads from default prefs
    private val defaultPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * Creates EncryptedSharedPreferences with error handling for reinstall scenarios.
     * If decryption fails (e.g., after app reinstall), clears corrupted data and recreates.
     */
    private fun createEncryptedPreferences(context: Context): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, likely due to app reinstall. Clearing corrupted data.", e)

            // Delete the corrupted SharedPreferences file
            val prefsFile = File(
                "${context.applicationInfo.dataDir}/shared_prefs/$PREFS_NAME.xml"
            )
            if (prefsFile.exists()) {
                prefsFile.delete()
                Log.d(TAG, "Deleted corrupted preferences file")
            }

            // Delete the corrupted master key from Android Keystore
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                    keyStore.deleteEntry(MASTER_KEY_ALIAS)
                    Log.d(TAG, "Deleted corrupted master key from keystore")
                }
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to delete master key from keystore", e2)
            }

            // Recreate the master key and EncryptedSharedPreferences
            val newMasterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                newMasterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    // OpenRouter API Key Management
    
    fun saveOpenRouterApiKey(apiKey: String) {
        encryptedPrefs.edit().putString(KEY_OPENROUTER_API_KEY, apiKey).apply()
        Log.d(TAG, "Saved OpenRouter API key (length: ${apiKey.length})")
    }

    fun getOpenRouterApiKey(): String? {
        return encryptedPrefs.getString(KEY_OPENROUTER_API_KEY, null)
    }

    fun clearOpenRouterApiKey() {
        encryptedPrefs.edit().remove(KEY_OPENROUTER_API_KEY).apply()
        Log.d(TAG, "Cleared OpenRouter API key")
    }

    fun hasOpenRouterApiKey(): Boolean {
        return !getOpenRouterApiKey().isNullOrEmpty()
    }

    // AI Model Selection
    
    fun saveSelectedAiModel(modelId: String) {
        encryptedPrefs.edit().putString(KEY_SELECTED_AI_MODEL, modelId).apply()
        Log.d(TAG, "Selected AI model: $modelId")
    }

    fun getSelectedAiModel(): String? {
        return encryptedPrefs.getString(KEY_SELECTED_AI_MODEL, null)
    }

    fun hasSelectedAiModel(): Boolean {
        return !getSelectedAiModel().isNullOrEmpty()
    }

    // PKCE OAuth Flow State Management
    
    fun savePkceCodeVerifier(codeVerifier: String) {
        encryptedPrefs.edit().putString(KEY_PKCE_CODE_VERIFIER, codeVerifier).apply()
        Log.d(TAG, "Saved PKCE code verifier")
    }

    fun getPkceCodeVerifier(): String? {
        return encryptedPrefs.getString(KEY_PKCE_CODE_VERIFIER, null)
    }

    fun clearPkceCodeVerifier() {
        encryptedPrefs.edit().remove(KEY_PKCE_CODE_VERIFIER).apply()
        Log.d(TAG, "Cleared PKCE code verifier")
    }

    // AI Feature Enable/Disable
    
    fun setAiEnabled(enabled: Boolean) {
        // Save to encrypted prefs
        encryptedPrefs.edit().putBoolean(KEY_AI_ENABLED, enabled).apply()
        
        // Also save to default prefs for RangeEstimationManager to read
        // (RangeEstimationManager uses PreferenceManager.getDefaultSharedPreferences)
        defaultPrefs.edit().putBoolean("ai_range_estimation_enabled", enabled).apply()
        
        Log.d(TAG, "AI range estimation ${if (enabled) "enabled" else "disabled"}")
    }

    fun isAiEnabled(): Boolean {
        return encryptedPrefs.getBoolean(KEY_AI_ENABLED, false)
    }

    // Combined AI Readiness Check
    
    fun isAiConfigured(): Boolean {
        return hasOpenRouterApiKey() && hasSelectedAiModel()
    }

    fun isAiReady(): Boolean {
        return isAiEnabled() && isAiConfigured()
    }

    // Clear All AI Data
    
    fun clearAllAiData() {
        encryptedPrefs.edit().apply {
            remove(KEY_OPENROUTER_API_KEY)
            remove(KEY_SELECTED_AI_MODEL)
            remove(KEY_PKCE_CODE_VERIFIER)
            remove(KEY_AI_ENABLED)
            apply()
        }
        
        // Also clear from default prefs
        defaultPrefs.edit().remove("ai_range_estimation_enabled").apply()
        
        Log.d(TAG, "Cleared all AI data")
    }
}
