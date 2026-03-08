package com.a42r.eucosmandplugin.ai.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.ai.model.AiModel
import com.a42r.eucosmandplugin.ai.model.ModelRecommendations
import com.google.android.material.card.MaterialCardView

/**
 * Adapter for displaying AI models in a RecyclerView for selection
 */
class ModelSelectionAdapter(
    private var models: List<AiModel>,
    private var selectedModelId: String?,
    private val onModelSelected: (AiModel) -> Unit,
    private val onViewModelLink: (String) -> Unit
) : RecyclerView.Adapter<ModelSelectionAdapter.ViewHolder>() {

    private var filteredModels: List<AiModel> = models

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val modelCard: MaterialCardView = view.findViewById(R.id.modelCard)
        val modelName: TextView = view.findViewById(R.id.modelName)
        val recommendationBadge: TextView = view.findViewById(R.id.recommendationBadge)
        val modelProvider: TextView = view.findViewById(R.id.modelProvider)
        val modelCost: TextView = view.findViewById(R.id.modelCost)
        val modelDetails: TextView = view.findViewById(R.id.modelDetails)
        val modelParameters: TextView = view.findViewById(R.id.modelParameters)
        val modelLink: TextView = view.findViewById(R.id.modelLink)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = filteredModels[position]
        val isSelected = model.id == selectedModelId

        holder.modelName.text = model.name
        holder.modelProvider.text = "Provider: ${model.provider}"
        holder.modelCost.text = "Cost: ${model.cost}"

        // Show recommendation badge if model is recommended
        if (model.isRecommended) {
            holder.recommendationBadge.visibility = View.VISIBLE
            val badgeText = when (model.recommendationTier) {
                1 -> "⭐ Best Value"
                2 -> "✓ Recommended"
                3 -> "💎 Premium"
                else -> "✓ Suggested"
            }
            holder.recommendationBadge.text = badgeText
        } else {
            holder.recommendationBadge.visibility = View.GONE
        }

        if (model.contextSize != null) {
            holder.modelDetails.text = "Context: ${model.contextSize}"
            holder.modelDetails.visibility = View.VISIBLE
        } else {
            holder.modelDetails.visibility = View.GONE
        }

        val parameters = extractParameters(model.description)
        if (parameters != null) {
            holder.modelParameters.text = "Parameters: $parameters"
            holder.modelParameters.visibility = View.VISIBLE
        } else {
            holder.modelParameters.visibility = View.GONE
        }

        // Update card appearance based on selection
        if (isSelected) {
            holder.modelCard.strokeWidth = 4
            holder.modelCard.setCardBackgroundColor(
                holder.itemView.context.getColor(R.color.selection_background)
            )
        } else {
            holder.modelCard.strokeWidth = 0
            holder.modelCard.setCardBackgroundColor(
                holder.itemView.context.getColor(android.R.color.white)
            )
        }

        // Link click handler
        holder.modelLink.setOnClickListener {
            onViewModelLink(model.id)
        }

        holder.itemView.setOnClickListener {
            val previousSelectedId = selectedModelId
            selectedModelId = model.id
            onModelSelected(model)

            // Update the UI for both old and new selection
            notifyItemChanged(filteredModels.indexOfFirst { it.id == previousSelectedId })
            notifyItemChanged(position)
        }
    }

    override fun getItemCount() = filteredModels.size

    /**
     * Filter models by search query, free-only flag, and/or recommended-only flag
     */
    fun filter(query: String, freeOnly: Boolean = false, recommendedOnly: Boolean = false) {
        filteredModels = if (query.isEmpty() && !freeOnly && !recommendedOnly) {
            models
        } else {
            val lowerQuery = query.lowercase()
            models.filter { model ->
                val matchesSearch = query.isEmpty() ||
                    model.name.lowercase().contains(lowerQuery) ||
                    model.provider.lowercase().contains(lowerQuery) ||
                    model.id.lowercase().contains(lowerQuery)

                val matchesFree = !freeOnly || model.cost.lowercase() == "free"
                
                val matchesRecommended = !recommendedOnly || model.isRecommended

                matchesSearch && matchesFree && matchesRecommended
            }
        }
        notifyDataSetChanged()
    }

    /**
     * Get the currently selected model
     */
    fun getSelectedModel(): AiModel? {
        return filteredModels.find { it.id == selectedModelId }
    }

    /**
     * Extract parameter count from model description
     * Looks for patterns like "7B", "13B", "70B" parameters
     */
    private fun extractParameters(description: String?): String? {
        if (description.isNullOrBlank()) return null

        // Regex to find patterns like "7B", "7b", "13B", "13b", "70B", "70b" followed by "parameters" or "param"
        // or "7 billion", "7 billion parameters"
        val regex = Regex("(\\d+(\\.\\d+)?[BMb])(?:\\s*parameters?)?|(\\d+)\\s*(?:billion|B)(?:\\s*parameters?)?", RegexOption.IGNORE_CASE)
        val match = regex.find(description)

        return match?.value
    }
}
