package com.example.wifidirectshare

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.wifidirectshare.databinding.ItemTransferBinding

class TransferAdapter : RecyclerView.Adapter<TransferAdapter.VH>() {

    private val items = mutableListOf<TransferView>()

    fun submit(list: List<TransferView>) {
        val old = items.toList()
        items.clear()
        items.addAll(list)
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].id == items[b].id
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == items[b]
        }).dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    class VH(val binding: ItemTransferBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(t: TransferView) {
            val arrow = if (t.direction == TransferDirection.INCOMING) "\u2193" else "\u2191"
            binding.name.text = "$arrow ${t.name}"
            binding.progress.setProgressCompat(t.progress, true)
            val done = t.bytesDone / (1024.0 * 1024.0)
            val total = t.bytesTotal / (1024.0 * 1024.0)
            binding.bytes.text = String.format("%.2f MB / %.2f MB", done, total)
            binding.status.text = when (t.status) {
                TransferStatus.QUEUED -> "Queued"
                TransferStatus.RUNNING -> "${t.progress}%"
                TransferStatus.DONE -> "Done"
                TransferStatus.FAILED -> "Failed: ${t.error ?: ""}"
            }
        }
    }
}
