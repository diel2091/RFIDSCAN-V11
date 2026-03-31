package com.zebra.rfidscanner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zebra.rfidscanner.databinding.ItemEpcBinding
import com.zebra.rfidscanner.utils.SgtinDecoder

class EpcAdapter : ListAdapter<EpcAdapter.Row, EpcAdapter.ViewHolder>(DIFF) {

    sealed class Row {
        data class EpcRow(val epc: String, val count: Int) : Row()
        data class EanRow(val result: SgtinDecoder.SgtinResult, val count: Int) : Row()
    }

    class ViewHolder(private val binding: ItemEpcBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row) {
            when (row) {
                is Row.EpcRow -> {
                    binding.tvEpc.text = row.epc
                    binding.tvSub.text = ""
                    binding.tvCount.text = "x${row.count}"
                }
                is Row.EanRow -> {
                    val r = row.result
                    binding.tvEpc.text = r.epc
                    binding.tvSub.text = if (r.isValid) "EAN: ${r.ean13}  GTIN: ${r.gtin14}" else r.error
                    binding.tvCount.text = "x${row.count}"
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpcBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row): Boolean {
                return when {
                    a is Row.EpcRow && b is Row.EpcRow -> a.epc == b.epc
                    a is Row.EanRow && b is Row.EanRow -> a.result.epc == b.result.epc
                    else -> false
                }
            }
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
