package com.nap.safe

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import java.io.IOException

class GeocoderSuggestionsAdapter(private val context: Context) : BaseAdapter(), Filterable {

    private var suggestions: List<Address> = emptyList()
    private val geocoder = Geocoder(context)

    override fun getCount(): Int = suggestions.size

    override fun getItem(position: Int): Address = suggestions[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(
            android.R.layout.simple_dropdown_item_1line, parent, false
        )
        val textView = view.findViewById<TextView>(android.R.id.text1)
        val address = getItem(position)

        // Format the address cleanly
        val addressLine = address.getAddressLine(0) ?: ""
        textView.text = addressLine

        // Ensure proper Material 3 color contrasts / style on dynamic backgrounds
        textView.setTextColor(context.getColor(android.R.color.tab_indicator_text))

        return view
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val results = FilterResults()
                if (constraint.isNullOrEmpty()) {
                    return results
                }

                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(constraint.toString(), 5)
                    if (addresses != null) {
                        results.values = addresses
                        results.count = addresses.size
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }

                return results
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                if (results != null && results.count > 0) {
                    suggestions = results.values as List<Address>
                    notifyDataSetChanged()
                } else {
                    suggestions = emptyList()
                    notifyDataSetInvalidated()
                }
            }

            override fun convertResultToString(resultValue: Any?): CharSequence {
                return if (resultValue is Address) {
                    resultValue.getAddressLine(0) ?: ""
                } else {
                    super.convertResultToString(resultValue)
                }
            }
        }
    }
}
