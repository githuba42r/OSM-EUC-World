package com.a42r.eucosmandplugin.ui

import android.content.Context
import android.content.res.TypedArray
import android.text.InputType
import android.util.AttributeSet
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.a42r.eucosmandplugin.R
import android.content.Intent

/**
 * Custom preference for minimum sampling time with combined SeekBar and EditText.
 * Range: 0.5 to 120 minutes
 * - 0.5-10 min: 0.5 minute increments
 * - 10-30 min: 1 minute increments
 * - 30-60 min: 5 minute increments
 * - 60-120 min: 10 minute increments
 */
class SamplingTimePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        private const val MIN_VALUE = 0.5f
        private const val MAX_VALUE = 120f
        
        // Calculate seekbar positions with variable increments
        private val positions: List<Float> = buildList {
            // 0.5-10 min: 0.5 minute increments (19 steps)
            var value = MIN_VALUE
            while (value <= 10f) {
                add(value)
                value += 0.5f
            }
            // 10-30 min: 1 minute increments (20 steps)
            value = 11f
            while (value <= 30f) {
                add(value)
                value += 1f
            }
            // 30-60 min: 5 minute increments (6 steps)
            value = 35f
            while (value <= 60f) {
                add(value)
                value += 5f
            }
            // 60-120 min: 10 minute increments (6 steps)
            value = 70f
            while (value <= MAX_VALUE) {
                add(value)
                value += 10f
            }
        }
    }

    private var currentValue: Float = 10f
        set(value) {
            val newValue = value.coerceIn(MIN_VALUE, MAX_VALUE)
            if (field != newValue) {
                field = newValue
                persistFloat(newValue)
                notifyChanged()
                broadcastUpdate()
            }
        }

    init {
        layoutResource = R.layout.preference_seekbar_with_edittext
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getFloat(index, 10f)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        currentValue = getPersistedFloat((defaultValue as? Float) ?: 10f)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val seekBar = holder.findViewById(R.id.seekbar) as SeekBar
        val valueEdit = holder.findViewById(R.id.value_edit) as EditText
        val suffixLabel = holder.findViewById(R.id.suffix_label) as TextView

        seekBar.max = positions.size - 1
        seekBar.progress = findClosestPosition(currentValue)

        valueEdit.setText(formatValue(currentValue))
        valueEdit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        
        suffixLabel.text = "min"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = positions[progress]
                if (fromUser) {
                    currentValue = value
                    valueEdit.setText(formatValue(value))
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        valueEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = valueEdit.text.toString()
                val value = text.toFloatOrNull()?.coerceIn(MIN_VALUE, MAX_VALUE) ?: currentValue
                currentValue = value
                seekBar.progress = findClosestPosition(value)
                valueEdit.setText(formatValue(value))
            }
        }
    }

    private fun findClosestPosition(value: Float): Int {
        return positions.indexOfFirst { it >= value }.takeIf { it >= 0 } ?: (positions.size - 1)
    }

    private fun formatValue(value: Float): String {
        return if (value == value.toInt().toFloat()) {
            value.toInt().toString()
        } else {
            String.format("%.1f", value)
        }
    }

    private fun broadcastUpdate() {
        val intent = Intent("com.a42r.eucosmandplugin.UPDATE_RANGE_PARAMS")
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    fun getValue(): Float = currentValue
}
