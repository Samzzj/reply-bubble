package com.example.replybubble

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.replybubble.databinding.ItemReplyBinding

class ReplyAdapter(
    private val onClick: (Reply) -> Unit
) : RecyclerView.Adapter<ReplyAdapter.Holder>() {

    private val items = ArrayList<Reply>()

    fun submit(list: List<Reply>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemReplyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val prefix = if (item.group == "B") "🧠 军师" else "💬 直球"
        holder.b.tvTag.text = "$prefix · ${item.tag}"
        holder.b.tvText.text = item.text
        holder.b.root.setOnClickListener { onClick(item) }
    }

    class Holder(val b: ItemReplyBinding) : RecyclerView.ViewHolder(b.root)
}
