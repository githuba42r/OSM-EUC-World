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
 * A preference that shows a SeekBar with an editable text field for manual input.
 */
class SeekBarWithEditTextPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    var min: Int = 0
        set(value) {
            field = value
            if (currentValue < value) {
                currentValue = value
            }
        }

    var max: Int = 100
        set(value) {
            field = value
            if (currentValue > value) {
                currentValue = value
            }
        }

    private var currentValue: Int = 0
        set(value) {
            val newValue = value.coerceIn(min, max)
            if (field != newValue) {
                field = newValue
                persistInt(newValue)
                notifyChanged()
            }
        }

    var suffix: String = ""
    var updateInterval: Int = 1

    init {
        layoutResource = R.layout.preference_seekbar_with_edittext

        context.obtainStyledAttributes(attrs, R.styleable.SeekBarWithEditTextPreference).apply {
            min = getInt(R.styleable.SeekBarWithEditTextPreference_min, 0)
            max = getInt(R.styleable.SeekBarWithEditTextPreference_max, 100)
            suffix = getString(R.styleable.SeekBarWithEditTextPreference_suffix) ?: ""
            updateInterval = getInt(R.styleable.SeekBarWithEditTextPreference_updateInterval, 1)
            recycle()
        }
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, 0)
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        currentValue = getPersistedInt((defaultValue as? Int) ?: 0)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val seekBar = holder.findViewById(R.id.seekbar) as SeekBar
        val valueEdit = holder.findViewById(R.id.value_edit) as EditText

        seekBar.max = (max - min) / updateInterval
        seekBar.progress = (currentValue - min) / updateInterval

        valueEdit.setText(currentValue.toString())
        valueEdit.inputType = InputType.TYPE_CLASS_NUMBER

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = min + (progress * updateInterval)
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
                val value = text.toIntOrNull()?.coerceIn(min, max) ?: currentValue
                currentValue = value
                seekBar.progress = (value - min) / updateInterval
                valueEdit.setText(value.toString())
            }
        }
    }

    fun getValue(): Int = currentValue
}
