package com.example.replybubble

import android.content.res.ColorStateList
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
        holder.b.tvTag.text = levelLabel(item.level)

        val pair = when (item.level) {
            "稳" -> 0xFFF2F2F7L to 0xFF8E8E93L
            "进" -> 0x1A34C759L to 0xFF248A3DL
            else -> 0x1A007AFFL to 0xFF007AFFL
        }
        holder.b.tvTag.backgroundTintList = ColorStateList.valueOf(pair.first.toInt())
        holder.b.tvTag.setTextColor(pair.second.toInt())

        holder.b.tvText.text = item.text
        holder.b.root.setOnClickListener { onClick(item) }
    }

    private fun levelLabel(level: String): String = when (level) {
        "稳" -> "稳 · 不推进"
        "进" -> "进 · 主动一点"
        else -> "中 · 接住"
    }

    class Holder(val b: ItemReplyBinding) : RecyclerView.ViewHolder(b.root)
}
