package com.a42r.eucosmandplugin.ai.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.ai.data.TokenManager
import com.a42r.eucosmandplugin.ai.model.AIModels
import com.a42r.eucosmandplugin.ai.model.AiModel
import com.a42r.eucosmandplugin.ai.model.KeyInfo
import com.a42r.eucosmandplugin.ai.model.KeyInfoResponse
import com.a42r.eucosmandplugin.ai.model.ModelPricing
import com.a42r.eucosmandplugin.ai.model.ModelRecommendations
import com.a42r.eucosmandplugin.ai.model.OpenRouterModel
import com.a42r.eucosmandplugin.ai.service.OpenRouterOAuthService
import com.a42r.eucosmandplugin.ai.viewmodel.OpenRouterOAuthViewModel
import com.a42r.eucosmandplugin.ai.viewmodel.OpenRouterOAuthViewModelFactory
import com.a42r.eucosmandplugin.databinding.ActivityOauthBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Activity for OpenRouter OAuth2 authentication with PKCE
 *
 * This activity demonstrates the complete OAuth flow:
 * 1. Register session with callback service
 * 2. Open OpenRouter authorization page
 * 3. Handle callback with authorization code
 * 4. Exchange code for API key
 * 5. Store API key securely
 */
class OAuthActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OAuthActivity"
    }

    private lateinit var binding: ActivityOauthBinding
    private lateinit var viewModel: OpenRouterOAuthViewModel
    private lateinit var tokenManager: TokenManager
    
    // For API calls
    private val okHttpClient = OkHttpClient()
    private val modelDataMap = mutableMapOf<String, OpenRouterModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOauthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize ViewModel with factory (no Hilt)
        tokenManager = TokenManager(applicationContext)
        val oauthService = OpenRouterOAuthService()
        val factory = OpenRouterOAuthViewModelFactory(application, oauthService, tokenManager)
        viewModel = ViewModelProvider(this, factory)[OpenRouterOAuthViewModel::class.java]

        setupToolbar()
        setupViews()
        observeViewModel()
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupViews() {
        // Learn more link
        binding.tvLearnMore.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai"))
            startActivity(intent)
        }

        // Connect button
        binding.btnStartOauth.setOnClickListener {
            viewModel.startOAuthFlow()
        }

        // Disconnect button
        binding.btnDisconnect.setOnClickListener {
            showDisconnectConfirmation()
        }

        // Model selection
        binding.selectedModelCard.setOnClickListener {
            showModelSelectionDialog()
        }
    }

    private fun observeViewModel() {
        // Observe OAuth state
        viewModel.oauthState.observe(this) { state ->
            when (state) {
                is OpenRouterOAuthViewModel.OAuthState.Idle -> {
                    showConnectButton()
                }
                is OpenRouterOAuthViewModel.OAuthState.Registering -> {
                    showLoading("Registering session...")
                }
                is OpenRouterOAuthViewModel.OAuthState.ReadyToAuthorize -> {
                    showLoading("Opening browser...")
                    viewModel.openBrowserForAuth(this, state.authUrl)
                }
                is OpenRouterOAuthViewModel.OAuthState.ExchangingCode -> {
                    showLoading("Exchanging authorization code...")
                }
                is OpenRouterOAuthViewModel.OAuthState.Success -> {
                    showSuccess("Connected successfully!")
                    showAuthenticatedUI()
                }
                is OpenRouterOAuthViewModel.OAuthState.Error -> {
                    showError(state.message)
                    showConnectButton()
                }
                is OpenRouterOAuthViewModel.OAuthState.Authenticated -> {
                    showAuthenticatedUI()
                }
            }
        }

        // Observe authentication status
        viewModel.isAuthenticated.observe(this) { isAuth ->
            if (isAuth) {
                showAuthenticatedUI()
            } else {
                showConnectButton()
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data
        if (data != null && data.scheme == "osmeucworld" && data.host == "oauth") {
            Log.d(TAG, "Received OAuth callback: $data")
            val code = data.getQueryParameter("code")
            val state = data.getQueryParameter("state")

            if (code != null) {
                viewModel.handleOAuthCallback(code, state)
            } else {
                showError("No authorization code received")
            }
        }
    }

    private fun showLoading(message: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.btnStartOauth.visibility = View.GONE
        binding.accountInfoSection.visibility = View.GONE
        binding.modelSelectionSection.visibility = View.GONE
        binding.btnDisconnect.visibility = View.GONE
    }

    private fun showConnectButton() {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Not connected"
        binding.btnStartOauth.visibility = View.VISIBLE
        binding.accountInfoSection.visibility = View.GONE
        binding.modelSelectionSection.visibility = View.GONE
        binding.btnDisconnect.visibility = View.GONE
    }

    private fun showAuthenticatedUI() {
        Log.d(TAG, "showAuthenticatedUI() called")
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "Connected"
        binding.btnStartOauth.visibility = View.GONE
        binding.accountInfoSection.visibility = View.VISIBLE
        binding.modelSelectionSection.visibility = View.VISIBLE
        binding.btnDisconnect.visibility = View.VISIBLE

        // Fetch and display account info
        lifecycleScope.launch {
            try {
                val apiKey = tokenManager.getOpenRouterApiKey()
                Log.d(TAG, "API Key present: ${!apiKey.isNullOrEmpty()}")
                if (!apiKey.isNullOrEmpty()) {
                    // Fetch key info
                    Log.d(TAG, "Fetching key info...")
                    val keyInfo = fetchKeyInfo(apiKey)
                    if (keyInfo != null) {
                        Log.d(TAG, "Key info received: label=${keyInfo.label}, limit=${keyInfo.limit}, usage=${keyInfo.usage}")
                        updateAccountInfo(keyInfo)
                    } else {
                        Log.w(TAG, "Key info fetch returned null")
                        // Default to N/A if fetch fails
                        binding.tvAccountBalance.text = "N/A"
                        binding.tvCreditLimit.text = "N/A"
                        binding.tvKeyExpiry.text = "Never"
                    }
                    
                    // Fetch models and update display
                    Log.d(TAG, "Fetching models...")
                    val models = fetchAvailableModels(apiKey)
                    models.forEach { modelDataMap[it.id] = it }
                    Log.d(TAG, "Fetched ${models.size} models")
                } else {
                    Log.w(TAG, "API key is null or empty")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch account/model info", e)
                binding.tvAccountBalance.text = "N/A"
                binding.tvCreditLimit.text = "N/A"
                binding.tvKeyExpiry.text = "Never"
            }
            updateSelectedModelDisplay()
        }
    }

    private suspend fun updateAccountInfo(keyInfo: KeyInfo) {
        withContext(Dispatchers.Main) {
            Log.d(TAG, "updateAccountInfo: is_free_tier=${keyInfo.is_free_tier}, limit=${keyInfo.limit}, limit_remaining=${keyInfo.limit_remaining}")
            
            // Update key allowance remaining
            val balance = if (keyInfo.limit != null && keyInfo.limit_remaining != null) {
                String.format("$%.2f", keyInfo.limit_remaining)
            } else if (keyInfo.is_free_tier) {
                "Free Tier"
            } else {
                "N/A"
            }
            Log.d(TAG, "Setting balance to: $balance")
            binding.tvAccountBalance.text = balance

            // Update credit limit
            val limit = if (keyInfo.limit != null) {
                String.format("$%.2f / month", keyInfo.limit)
            } else if (keyInfo.is_free_tier) {
                "Free Tier"
            } else {
                "No limit"
            }
            Log.d(TAG, "Setting limit to: $limit")
            binding.tvCreditLimit.text = limit

            // Update expiry
            val expiry = if (keyInfo.expires_at != null) {
                try {
                    // Parse ISO 8601 date and format it nicely
                    val instant = java.time.Instant.parse(keyInfo.expires_at)
                    val formatter = java.time.format.DateTimeFormatter
                        .ofPattern("MMM dd, yyyy")
                        .withZone(java.time.ZoneId.systemDefault())
                    formatter.format(instant)
                } catch (e: Exception) {
                    keyInfo.expires_at
                }
            } else {
                "Never"
            }
            Log.d(TAG, "Setting expiry to: $expiry")
            binding.tvKeyExpiry.text = expiry
            
            Log.d(TAG, "Account info UI updated successfully")
        }
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        Toast.makeText(this, "Error: $message", Toast.LENGTH_LONG).show()
        Log.e(TAG, "OAuth error: $message")
    }

    private fun showDisconnectConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Disconnect")
            .setMessage("Are you sure you want to disconnect? This will remove your API key.")
            .setPositiveButton("Disconnect") { _, _ ->
                viewModel.disconnect()
                showConnectButton()
                Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModelSelectionDialog() {
        val dialog = AlertDialog.Builder(this).create()
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_selection, null)
        dialog.setView(dialogView)

        val searchInput = dialogView.findViewById<EditText>(R.id.searchInput)
        val clearSearchButton = dialogView.findViewById<ImageButton>(R.id.clearSearchButton)
        val freeOnlyCheckbox = dialogView.findViewById<CheckBox>(R.id.freeOnlyCheckbox)
        val recommendedOnlyCheckbox = dialogView.findViewById<CheckBox>(R.id.recommendedOnlyCheckbox)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.modelsRecyclerView)
        val selectButton = dialogView.findViewById<MaterialButton>(R.id.selectButton)
        val cancelButton = dialogView.findViewById<MaterialButton>(R.id.cancelButton)

        val currentSelectedModel = viewModel.getSelectedModel()

        // Fetch models from API
        lifecycleScope.launch {
            try {
                val apiKey = tokenManager.getOpenRouterApiKey()
                if (apiKey.isNullOrEmpty()) {
                    Toast.makeText(this@OAuthActivity, "API key not found", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@launch
                }

                val openRouterModels = fetchAvailableModels(apiKey)
                // Store models in map for later use
                openRouterModels.forEach { modelDataMap[it.id] = it }
                val aiModels = openRouterModels.map { createAiModelFromOpenRouterModel(it) }

                setupModelDialog(
                    dialog,
                    searchInput,
                    clearSearchButton,
                    freeOnlyCheckbox,
                    recommendedOnlyCheckbox,
                    recyclerView,
                    selectButton,
                    cancelButton,
                    aiModels,
                    currentSelectedModel
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch models", e)
                Toast.makeText(this@OAuthActivity, "Failed to load models: ${e.message}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()

        // Make dialog full screen
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            (resources.displayMetrics.heightPixels * 0.85).toInt()
        )
    }
    
    private fun updateSelectedModelDisplay() {
        val selectedModelId = viewModel.getSelectedModel()

        if (selectedModelId != null) {
            // Parse model ID to extract provider and name
            val parts = selectedModelId.split("/")
            val provider = if (parts.size >= 2) parts[0] else ""
            val modelName = if (parts.size >= 2) parts[1] else selectedModelId

            // Format display names
            val displayName = modelName.replace("-", " ").split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            val displayProvider = provider.replaceFirstChar { it.uppercase() }

            binding.selectedModelName.text = displayName
            binding.selectedModelProvider.text = "Provider: $displayProvider"
        } else {
            // If no model selected, set default free model
            val defaultModel = AIModels.getDefaultModel()
            viewModel.setSelectedModel(defaultModel.id)
            binding.selectedModelName.text = defaultModel.name
            binding.selectedModelProvider.text = "Provider: ${defaultModel.provider}"
        }
    }

    private fun setupModelDialog(
        dialog: AlertDialog,
        searchInput: EditText,
        clearSearchButton: ImageButton,
        freeOnlyCheckbox: CheckBox,
        recommendedOnlyCheckbox: CheckBox,
        recyclerView: RecyclerView,
        selectButton: MaterialButton,
        cancelButton: MaterialButton,
        aiModels: List<AiModel>,
        currentSelectedModel: String?
    ) {

        val adapter = ModelSelectionAdapter(
            aiModels,
            currentSelectedModel,
            { selectedModel ->
                // Model clicked callback
                Log.d(TAG, "Model clicked: ${selectedModel.name}")
            },
            { modelId ->
                // Open model page on OpenRouter.ai
                val url = "https://openrouter.ai/models/${modelId.replace("/", "--")}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
                }
            }
        )

        val layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        // Scroll to selected model if available
        currentSelectedModel?.let { selectedId ->
            val selectedPosition = aiModels.indexOfFirst { it.id == selectedId }
            if (selectedPosition != -1) {
                layoutManager.scrollToPositionWithOffset(selectedPosition, 200)
            }
        }

        // Filter function
        val applyFilter = {
            val query = searchInput.text?.toString() ?: ""
            val freeOnly = freeOnlyCheckbox.isChecked
            val recommendedOnly = recommendedOnlyCheckbox.isChecked
            adapter.filter(query, freeOnly, recommendedOnly)

            // Show/hide clear button
            clearSearchButton.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        }
        
        // Apply initial filter (recommended only is checked by default)
        applyFilter()

        // Clear search button
        clearSearchButton.setOnClickListener {
            searchInput.setText("")
        }

        // Search functionality
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Free only checkbox
        freeOnlyCheckbox.setOnCheckedChangeListener { _, _ ->
            applyFilter()
        }
        
        // Recommended only checkbox
        recommendedOnlyCheckbox.setOnCheckedChangeListener { _, _ ->
            applyFilter()
        }

        // Select button
        selectButton.setOnClickListener {
            val selected = adapter.getSelectedModel()
            if (selected != null) {
                viewModel.setSelectedModel(selected.id)
                updateSelectedModelDisplay()
                Toast.makeText(this, "Model selected: ${selected.name}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Please select a model", Toast.LENGTH_SHORT).show()
            }
        }

        // Cancel button
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
    }

    private suspend fun fetchAvailableModels(apiKey: String): List<OpenRouterModel> {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }

            val jsonResponse = response.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(jsonResponse)
            val dataArray = json.getJSONArray("data")

            val models = mutableListOf<OpenRouterModel>()

            for (i in 0 until dataArray.length()) {
                val modelJson = dataArray.getJSONObject(i)
                val modelId = modelJson.getString("id")
                val contextLength = modelJson.optInt("context_length", -1).takeIf { it != -1 }
                val description = modelJson.optString("description", "").takeIf { it.isNotEmpty() }

                var pricing: ModelPricing? = null
                if (modelJson.has("pricing")) {
                    val pricingJson = modelJson.getJSONObject("pricing")
                    val promptCost = pricingJson.optString("prompt", "0")
                    val completionCost = pricingJson.optString("completion", "0")

                    val promptPerMillion = (promptCost.toDoubleOrNull() ?: 0.0) * 1000000
                    val completionPerMillion = (completionCost.toDoubleOrNull() ?: 0.0) * 1000000

                    pricing = ModelPricing(
                        prompt = formatCost(promptPerMillion),
                        completion = formatCost(completionPerMillion)
                    )
                }

                models.add(
                    OpenRouterModel(
                        id = modelId,
                        context_length = contextLength,
                        description = description,
                        pricing = pricing
                    )
                )
            }

            models
        }
    }

    private suspend fun fetchKeyInfo(apiKey: String): KeyInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/key")
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch key info: HTTP ${response.code}")
                    return@withContext null
                }

                val jsonResponse = response.body?.string() ?: return@withContext null
                Log.d(TAG, "Key info response: $jsonResponse")
                
                val json = JSONObject(jsonResponse)
                val data = json.getJSONObject("data")
                
                val keyInfo = KeyInfo(
                    label = data.getString("label"),
                    limit = if (data.isNull("limit")) null else data.getDouble("limit"),
                    usage = data.getDouble("usage"),
                    usage_daily = data.getDouble("usage_daily"),
                    usage_weekly = data.getDouble("usage_weekly"),
                    usage_monthly = data.getDouble("usage_monthly"),
                    is_free_tier = data.getBoolean("is_free_tier"),
                    limit_remaining = if (data.isNull("limit_remaining")) null else data.getDouble("limit_remaining"),
                    limit_reset = if (data.isNull("limit_reset")) null else data.getString("limit_reset"),
                    expires_at = if (data.isNull("expires_at")) null else data.getString("expires_at")
                )
                
                keyInfo
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching key info", e)
                null
            }
        }
    }

    private fun formatCost(costPerMillion: Double): String {
        return when {
            costPerMillion == 0.0 -> "Free"
            costPerMillion < 0.01 -> String.format("$%.4f", costPerMillion)
            costPerMillion < 1.0 -> String.format("$%.2f", costPerMillion)
            else -> String.format("$%.0f", costPerMillion)
        }
    }

    private fun createAiModelFromOpenRouterModel(openRouterModel: OpenRouterModel): AiModel {
        val modelId = openRouterModel.id
        val parts = modelId.split("/")
        val provider = if (parts.size >= 2) parts[0] else ""
        val modelName = if (parts.size >= 2) parts[1] else modelId

        val pricing = openRouterModel.pricing
        val pricingDisplay = if (pricing?.prompt == "Free" && pricing.completion == "Free") {
            "Free"
        } else if (pricing != null) {
            "${pricing.prompt}-${pricing.completion}/M tokens"
        } else {
            "Pricing unavailable"
        }

        val contextSizeDisplay = openRouterModel.context_length?.let { "${it / 1000}K tokens" }
            ?: "Unknown context"

        val displayName = modelName.replace("-", " ").split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        val displayProvider = provider.replaceFirstChar { it.uppercase() }

        // Check if this model is recommended
        val isRecommended = ModelRecommendations.isRecommended(modelId)
        val recommendationTier = ModelRecommendations.getRecommendationTier(modelId)

        return AiModel(
            id = modelId,
            name = displayName,
            provider = displayProvider,
            cost = pricingDisplay,
            contextSize = contextSizeDisplay,
            description = openRouterModel.description,
            pricing = pricing,
            isRecommended = isRecommended,
            recommendationTier = recommendationTier
        )
    }
}
