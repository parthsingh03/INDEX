package com.parth.index.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.parth.index.data.model.DeadlineEntity
import com.parth.index.databinding.ItemDeadlineBinding
import com.parth.index.util.Format
import kotlin.math.abs

/** Home deadline cards. The big number is the whole days left, worked out
 *  fresh at bind time — never stored, so it can never go stale. Edit/Delete
 *  only exist for Owner/Admin; the screen controls that flag. */
class DeadlineAdapter(
    private val canManage: Boolean,
    private val onEdit: (DeadlineEntity) -> Unit,
    private val onDelete: (DeadlineEntity) -> Unit
) : RecyclerView.Adapter<DeadlineAdapter.Holder>() {

    private var items: List<DeadlineEntity> = emptyList()

    fun submit(list: List<DeadlineEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDeadlineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val deadline = items[position]
        with(holder.binding) {
            tvDeadlineTitle.text = deadline.title
            val subjectPart = if (deadline.subjectName.isNotEmpty()) " · ${deadline.subjectName}" else ""
            tvDeadlineDue.text = "Due ${Format.date(deadline.dueDateMillis)}$subjectPart"
            val days = Format.daysLeft(deadline.dueDateMillis)
            tvDeadlineDays.text = abs(days).toString()
            tvDeadlineDaysLabel.text = when {
                days < 0 -> "days\noverdue"
                days == 0L -> "due\ntoday"
                days == 1L -> "day\nleft"
                else -> "days\nleft"
            }
            layoutDeadlineActions.visibility = if (canManage) View.VISIBLE else View.GONE
            btnEditDeadline.setOnClickListener { onEdit(deadline) }
            btnDeleteDeadline.setOnClickListener { onDelete(deadline) }
        }
    }

    class Holder(val binding: ItemDeadlineBinding) : RecyclerView.ViewHolder(binding.root)
}
