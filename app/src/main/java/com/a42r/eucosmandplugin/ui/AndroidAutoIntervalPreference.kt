package com.a42r.eucosmandplugin.ui

import android.content.Context
import android.content.res.TypedArray
import android.text.InputType
import android.util.AttributeSet
import android.widget.EditText
import android.widget.SeekBar
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.a42r.eucosmandplugin.R

/**
 * Custom preference for Android Auto update interval with variable increments:
 * - 5-60 seconds: 5 second increments
 * - 60-300 seconds: 15 second increments
 */
class AndroidAutoIntervalPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        private const val MIN_VALUE = 5
        private const val MAX_VALUE = 300
        private const val BREAKPOINT = 60 // Switch from 5s to 15s increments at 60s
        
        // Calculate seekbar positions
        private val positions: List<Int> = buildList {
            // 5-60 seconds: 5 second increments = 12 steps (5, 10, 15, ..., 60)
            for (i in MIN_VALUE..BREAKPOINT step 5) {
                add(i)
            }
            // 60-300 seconds: 15 second increments = 16 steps (75, 90, 105, ..., 300)
            for (i in (BREAKPOINT + 15)..MAX_VALUE step 15) {
                add(i)
            }
        }
    }

    private var currentValue: Int = 15
        set(value) {
            val newValue = value.coerceIn(MIN_VALUE, MAX_VALUE)
            if (field != newValue) {
                field = newValue
                persistInt(newValue)
                notifyChanged()
            }
        }

    init {
        layoutResource = R.layout.preference_seekbar_with_edittext
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, 15)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        currentValue = getPersistedInt((defaultValue as? Int) ?: 15)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val seekBar = holder.findViewById(R.id.seekbar) as SeekBar
        val valueEdit = holder.findViewById(R.id.value_edit) as EditText

        seekBar.max = positions.size - 1
        seekBar.progress = findClosestPosition(currentValue)

        valueEdit.setText(currentValue.toString())
        valueEdit.inputType = InputType.TYPE_CLASS_NUMBER

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = positions[progress]
                if (fromUser) {
                    currentValue = value
                    valueEdit.setText(value.toString())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        valueEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = valueEdit.text.toString()
                val value = text.toIntOrNull()?.coerceIn(MIN_VALUE, MAX_VALUE) ?: currentValue
                currentValue = value
                seekBar.progress = findClosestPosition(value)
                valueEdit.setText(value.toString())
            }
        }
    }

    private fun findClosestPosition(value: Int): Int {
        return positions.indexOfFirst { it >= value }.takeIf { it >= 0 } ?: (positions.size - 1)
    }

    fun getValue(): Int = currentValue
}
