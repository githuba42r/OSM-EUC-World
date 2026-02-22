package com.a42r.eucosmandplugin.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.a42r.eucosmandplugin.R
import com.a42r.eucosmandplugin.range.database.WheelDatabase

/**
 * Dialog for selecting a wheel model from the database.
 * 
 * Allows filtering by manufacturer and displays wheel specs.
 */
class WheelModelSelectorDialog : DialogFragment() {
    
    interface WheelSelectionListener {
        fun onWheelSelected(wheelSpec: WheelDatabase.WheelSpec)
    }
    
    private var listener: WheelSelectionListener? = null
    private lateinit var manufacturerSpinner: Spinner
    private lateinit var wheelListView: ListView
    private lateinit var adapter: WheelListAdapter
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_wheel_selector, null)
        
        manufacturerSpinner = view.findViewById(R.id.manufacturer_spinner)
        wheelListView = view.findViewById(R.id.wheel_list)
        
        setupManufacturerSpinner()
        setupWheelList()
        
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_wheel_model_selector)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
    
    private fun setupManufacturerSpinner() {
        val manufacturers = listOf(getString(R.string.all_manufacturers)) + 
                           WheelDatabase.getAllManufacturers()
        
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            manufacturers
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        manufacturerSpinner.adapter = spinnerAdapter
        
        manufacturerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedManufacturer = if (position == 0) null else manufacturers[position]
                updateWheelList(selectedManufacturer)
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupWheelList() {
        adapter = WheelListAdapter(requireContext(), WheelDatabase.getAllWheelSpecs())
        wheelListView.adapter = adapter
        
        wheelListView.setOnItemClickListener { _, _, position, _ ->
            val selectedWheel = adapter.getItem(position)
            selectedWheel?.let {
                listener?.onWheelSelected(it)
                dismiss()
            }
        }
    }
    
    private fun updateWheelList(manufacturer: String?) {
        val wheels = WheelDatabase.getAllWheelSpecs(manufacturer)
        adapter.clear()
        adapter.addAll(wheels)
        adapter.notifyDataSetChanged()
    }
    
    fun setWheelSelectionListener(listener: WheelSelectionListener) {
        this.listener = listener
    }
    
    companion object {
        const val TAG = "WheelModelSelectorDialog"
        
        fun newInstance(): WheelModelSelectorDialog {
            return WheelModelSelectorDialog()
        }
    }
}

/**
 * Custom adapter for displaying wheel specs in a list.
 */
private class WheelListAdapter(
    context: Context,
    wheels: List<WheelDatabase.WheelSpec>
) : ArrayAdapter<WheelDatabase.WheelSpec>(context, 0, wheels) {
    
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        
        val wheel = getItem(position)
        wheel?.let {
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            
            text1.text = it.displayName
            text2.text = String.format(
                "%s - %d Wh (%s)",
                it.manufacturer,
                it.batteryConfig.capacityWh.toInt(),
                it.batteryConfig.getConfigString()
            )
        }
        
        return view
    }
}
